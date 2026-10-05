package com.example.myapplication.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.myapplication.data.ai.AiLlmFallbackRouter
import com.example.myapplication.data.ai.AiRoutedResult
import com.example.myapplication.data.ai.AiRateLimitException
import com.example.myapplication.data.ai.NoAiProviderException
import com.example.myapplication.manga.domain.MangaPage
import com.example.myapplication.manga.translate.pipeline.AnalyzedPage
import com.example.myapplication.manga.ui.DecodedPageLoader
import com.example.myapplication.manga.ui.DecodedPageRequest
import com.example.myapplication.manga.translate.pipeline.OpenedAnalyzer
import com.example.myapplication.manga.translate.pipeline.PageAnalyzerFactory
import com.example.myapplication.manga.translate.pipeline.RgbImage
import com.example.myapplication.manga.translate.render.PageRenderer
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Для какой главы и на какой язык переводим. Остальное сервис берёт со страницы. */
data class ChapterTranslationContext(
    val sourceId: String,
    val chapterKey: String,
    val target: AppLanguage,
    /**
     * Название и описание произведения. Функция, а не значение: описание берётся у источника по
     * сети, и ждать его при открытии главы незачем - оно нужно только когда страница дошла до ИИ.
     */
    val work: suspend () -> TranslationPrompt.WorkContext,
    /** Манга читается справа налево, вебтун - слева направо. */
    val rightToLeft: Boolean,
    /** Язык оригинала главы: японский читает manga-ocr, остальные - модель с картинками. */
    val sourceLanguage: String = "ja",
)

/** Что экран ридера держит на время главы: сервис и контекст, по которому он переводит её страницы. */
class ChapterTranslationHost(
    val service: MangaPageTranslationService,
    val context: ChapterTranslationContext,
)

enum class FailReason { NO_AI_KEY, MODELS_MISSING, RATE_LIMITED, NETWORK, PAGE_UNREADABLE, INTERNAL }

sealed interface PageTranslationResult {
    /** Готовая картинка вместо оригинала. */
    data class Ready(val file: File) : PageTranslationResult

    /** На странице нет текста - показываем оригинал и больше не пытаемся. */
    data object NothingToTranslate : PageTranslationResult

    data class Failed(val reason: FailReason, val detail: String? = null) : PageTranslationResult
}

/**
 * Перевод одной страницы: оригинал -> детектор -> OCR -> перевод ИИ -> стирание и набор.
 *
 * Три слоя кэша, чтобы повторное открытие страницы ничего не стоило:
 *  1. готовая картинка (cacheDir; её могут вытеснить, жалеть нечего);
 *  2. разбор с переводом (filesDir, JSON): перерисовать страницу можно без детектора, OCR и
 *     токенов - например, после смены шрифта;
 *  3. файл-метка "текста нет".
 *
 * Параллелизм устроен по узкому месту: модели на устройстве работают по одной странице (они сами
 * разгоняют ядра), запросы к ИИ - по две сразу, так что перевод страницы N идёт, пока для N+1
 * уже читается текст.
 */
class MangaPageTranslationService(
    context: Context,
    private val client: OkHttpClient,
    private val models: TranslationModelStore,
    private val router: AiLlmFallbackRouter,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val analysisDir = File(appContext.filesDir, "manga_translate/analysis")
    private val renderDir = File(appContext.cacheDir, "manga_translate")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val inFlight = ConcurrentHashMap<String, Deferred<PageTranslationResult>>()
    private val compute = Semaphore(1)
    private val network = Semaphore(2)
    private val prefetchJobs = ConcurrentHashMap<String, Job>()

    private val analyzerLock = Mutex()
    private var opened: OpenedAnalyzer? = null
    private var idleJob: Job? = null

    /** Картинка уже готова на диске - без запуска чего-либо. Нужна, чтобы не мигать оригиналом. */
    fun cached(ctx: ChapterTranslationContext, page: MangaPage): PageTranslationResult? {
        val key = cacheKey(ctx, page)
        val rendered = renderedFile(key)
        return when {
            rendered.isFile -> PageTranslationResult.Ready(rendered)
            emptyMarker(key).isFile -> PageTranslationResult.NothingToTranslate
            else -> null
        }
    }

    suspend fun translate(ctx: ChapterTranslationContext, page: MangaPage): PageTranslationResult {
        cached(ctx, page)?.let { return it }
        val key = cacheKey(ctx, page)
        val created = scope.async(start = CoroutineStart.LAZY) { run(ctx, page, key) }
        val existing = inFlight.putIfAbsent(key, created)
        val job = if (existing != null) {
            created.cancel()
            existing
        } else {
            created.invokeOnCompletion { inFlight.remove(key, created) }
            created.also { it.start() }
        }
        return job.await()
    }

    /** Заранее переводит ближайшие страницы; новый вызов для главы отменяет прежний. */
    fun prefetch(ctx: ChapterTranslationContext, pages: List<MangaPage>, from: Int, count: Int = PREFETCH_PAGES) {
        prefetchJobs.remove(ctx.chapterKey)?.cancel()
        prefetchJobs[ctx.chapterKey] = scope.launch {
            for (index in from until minOf(pages.size, from + count)) {
                if (translate(ctx, pages[index]) is PageTranslationResult.Failed) break
            }
        }
    }

    // ---- конвейер одной страницы ----

    private suspend fun run(ctx: ChapterTranslationContext, page: MangaPage, key: String): PageTranslationResult {
        try {
            // Модели обычно скачаны при включении функции; если нет (обрыв, очистка памяти) - докачиваем
            // здесь же, страница подождёт, а не упадёт.
            if (!models.isInstalled()) {
                val installed = models.ensureInstalled()
                if (installed.isFailure) {
                    return PageTranslationResult.Failed(FailReason.MODELS_MISSING, installed.exceptionOrNull()?.message)
                }
            }
            val bitmap = loadBitmap(page) ?: return PageTranslationResult.Failed(FailReason.PAGE_UNREADABLE)
            try {
                val pixels = withContext(Dispatchers.Default) { bitmap.toRgbImage() }
                var analyzed = readAnalysis(key)
                if (analyzed == null) {
                    analyzed = compute.withPermit { analyze(pixels, ctx.rightToLeft, readText = ctx.sourceLanguage == JAPANESE) }
                    if (analyzed.regions.isEmpty()) {
                        emptyMarker(key).apply { parentFile?.mkdirs() }.writeText("")
                        return PageTranslationResult.NothingToTranslate
                    }
                }
                if (analyzed.regions.any { it.translation == null }) {
                    analyzed = translateRegions(ctx, analyzed, bitmap)
                }
                writeAnalysis(key, analyzed)
                val file = compute.withPermit { renderToFile(bitmap, pixels, analyzed, key) }
                return PageTranslationResult.Ready(file)
            } finally {
                bitmap.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationFailure) {
            return PageTranslationResult.Failed(e.reason, e.message)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "out of memory while translating a page", e)
            return PageTranslationResult.Failed(FailReason.INTERNAL, "out of memory")
        } catch (e: Throwable) {
            Log.w(TAG, "page translation failed", e)
            return PageTranslationResult.Failed(FailReason.INTERNAL, e.message)
        }
    }

    private class TranslationFailure(val reason: FailReason, message: String? = null) : Exception(message)

    /** [readText] = false: только найти реплики, текст прочитает модель с картинками (английский). */
    private suspend fun analyze(pixels: RgbImage, rightToLeft: Boolean, readText: Boolean): AnalyzedPage = withContext(Dispatchers.Default) {
        val analyzer = analyzerLock.withLock {
            opened ?: PageAnalyzerFactory.open(
                TranslationModels.detectorFile(models.directory),
                TranslationModels.encoderFile(models.directory),
                TranslationModels.decoderFile(models.directory),
                TranslationModels.vocabFile(models.directory),
            ).also { opened = it }
        }
        try {
            if (readText) analyzer.analyzer.analyze(pixels, rightToLeft) else analyzer.analyzer.detectRegions(pixels, rightToLeft)
        } finally {
            scheduleIdleClose()
        }
    }

    /** Три сессии ONNX держат под 200 МБ: пока страницы не идут, отпускаем. */
    private fun scheduleIdleClose() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            compute.withPermit {
                analyzerLock.withLock {
                    opened?.close()
                    opened = null
                }
            }
        }
    }

    private suspend fun translateRegions(ctx: ChapterTranslationContext, page: AnalyzedPage, bitmap: Bitmap): AnalyzedPage {
        var remaining = page.regions.withIndex()
            .filter { it.value.translation == null }
            .map { (it.index + 1) to it.value.source }
        val done = HashMap<Int, String>()
        // Английский читает модель по картинке: рисуем на копии страницы номера реплик и шлём её вместе с запросом.
        val numberedPage = if (ctx.sourceLanguage == JAPANESE) null else withContext(Dispatchers.Default) { numberedJpeg(bitmap, page) }
        repeat(MODEL_ATTEMPTS) {
            if (remaining.isEmpty()) return@repeat
            val work = runCatching { ctx.work() }.getOrDefault(TranslationPrompt.WorkContext("", null))
            val reply = if (numberedPage == null) {
                askModel { router.completeText(userPrompt = TranslationPrompt.userMessage(work, ctx.target, remaining), systemPrompt = TranslationPrompt.SYSTEM, jsonMode = false) }
            } else {
                askModel {
                    router.completeWithImage(
                        userPrompt = TranslationPrompt.visionMessage(work, ctx.target, remaining.map { it.first }),
                        imageBase64 = numberedPage,
                        mimeType = "image/jpeg",
                        systemPrompt = TranslationPrompt.VISION_SYSTEM,
                        jsonMode = false,
                    )
                }
            }
            val parsed = TranslationResponse.parse(reply, remaining.map { it.first })
            done += parsed.translations
            remaining = remaining.filter { it.first !in done }
        }
        // Пустая строка - "модель так и не ответила": страницу не мучаем бесконечными запросами,
        // а реплика остаётся на оригинале.
        return page.copy(
            regions = page.regions.mapIndexed { index, region ->
                if (region.translation != null) region else region.copy(translation = done[index + 1].orEmpty())
            },
        )
    }

    /** Страница с пронумерованными красными рамками реплик, JPEG в base64. Большие страницы уменьшаются. */
    private fun numberedJpeg(source: Bitmap, page: AnalyzedPage): String {
        val scale = minOf(1f, VISION_MAX_SIDE.toFloat() / maxOf(source.width, source.height))
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val canvasBitmap = Bitmap.createScaledBitmap(source, width, height, true)
            .let { if (it.isMutable && it !== source) it else it.copy(Bitmap.Config.ARGB_8888, true) }
        try {
            val canvas = android.graphics.Canvas(canvasBitmap)
            val stroke = (maxOf(width, height) / 600f).coerceAtLeast(2f)
            val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.RED
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = stroke
            }
            val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = (maxOf(width, height) / 55f).coerceAtLeast(16f)
                isFakeBoldText = true
            }
            val badge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.RED }
            page.regions.forEachIndexed { index, region ->
                val box = region.region.textBounds
                val rect = android.graphics.RectF(box.left * scale, box.top * scale, box.right * scale, box.bottom * scale)
                canvas.drawRect(rect, line)
                val text = (index + 1).toString()
                val textWidth = label.measureText(text)
                val pad = label.textSize * 0.25f
                val badgeRect = android.graphics.RectF(
                    rect.left, rect.top - label.textSize - pad * 2, rect.left + textWidth + pad * 2, rect.top,
                )
                // Метка над рамкой; у верхнего края страницы переносим её внутрь.
                if (badgeRect.top < 0) badgeRect.offset(0f, rect.top - badgeRect.top)
                canvas.drawRect(badgeRect, badge)
                canvas.drawText(text, badgeRect.left + pad, badgeRect.bottom - pad, label)
            }
            val out = java.io.ByteArrayOutputStream()
            canvasBitmap.compress(Bitmap.CompressFormat.JPEG, VISION_JPEG_QUALITY, out)
            return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } finally {
            if (canvasBitmap !== source) canvasBitmap.recycle()
        }
    }

    private suspend fun askModel(call: suspend () -> Result<AiRoutedResult>): String {
        var rateLimitedOnce = false
        while (true) {
            val outcome = network.withPermit { call() }
            val failure = outcome.exceptionOrNull() ?: return outcome.getOrThrow().text
            when {
                failure is NoAiProviderException -> throw TranslationFailure(FailReason.NO_AI_KEY)
                failure is AiRateLimitException -> {
                    // Короткую паузу переждём сами и попробуем ещё раз; длинную отдаём пользователю.
                    if (rateLimitedOnce || failure.retryAfterMs > MAX_AUTO_WAIT_MS) {
                        throw TranslationFailure(FailReason.RATE_LIMITED, failure.message)
                    }
                    rateLimitedOnce = true
                    delay(failure.retryAfterMs.coerceAtLeast(MIN_RETRY_WAIT_MS))
                }
                else -> throw TranslationFailure(FailReason.NETWORK, failure.message)
            }
        }
    }

    // ---- картинка ----

    private suspend fun loadBitmap(page: MangaPage): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            if (page.isLocal) File(requireNotNull(Uri.parse(page.url).path)).readBytes() else download(page)
        }.getOrNull() ?: return@withContext null
        decodeCapped(bytes)
    }

    private suspend fun download(page: MangaPage): ByteArray {
        // Страницы японских источников приходят перемешанными/зашифрованными: берём уже восстановленные.
        page.decode?.let { return DecodedPageLoader.bytes(DecodedPageRequest(page.url, page.headers, it)) }
        val request = Request.Builder().url(page.url)
            .apply { page.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.bytes() ?: throw IOException("empty page")
        }
    }

    /** Полосы длиннее лимита уменьшаются при декодировании: целиком они не поместились бы в память. */
    private fun decodeCapped(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > MAX_PIXELS) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun Bitmap.toRgbImage(): RgbImage {
        val data = IntArray(width * height)
        getPixels(data, 0, width, 0, 0, width, height)
        for (i in data.indices) data[i] = data[i] and 0xFFFFFF
        return RgbImage(width, height, data)
    }

    private fun renderToFile(source: Bitmap, pixels: RgbImage, page: AnalyzedPage, key: String): File {
        renderDir.mkdirs()
        trimRenderCache()
        val rendered = PageRenderer.render(source, pixels, page)
        val target = renderedFile(key)
        val part = File(renderDir, "$key.part")
        try {
            part.outputStream().use { out ->
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                }
                rendered.compress(format, WEBP_QUALITY, out)
            }
            if (!part.renameTo(target)) throw IOException("cannot move rendered page")
        } finally {
            rendered.recycle()
            part.delete()
        }
        return target
    }

    /** Кэш картинок - временный: держим не больше лимита, вытесняем самые старые. */
    private fun trimRenderCache() {
        val files = renderDir.listFiles { f -> f.extension == "webp" }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= RENDER_CACHE_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    // ---- кэш разбора ----

    private fun readAnalysis(key: String): AnalyzedPage? {
        val file = File(analysisDir, "$key.json")
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(AnalyzedPage.serializer(), file.readText()) }.getOrNull()
    }

    private fun writeAnalysis(key: String, page: AnalyzedPage) {
        analysisDir.mkdirs()
        val target = File(analysisDir, "$key.json")
        val part = File(analysisDir, "$key.part")
        part.writeText(json.encodeToString(AnalyzedPage.serializer(), page))
        if (!part.renameTo(target)) part.delete()
    }

    private fun renderedFile(key: String) = File(renderDir, "$key.webp")
    private fun emptyMarker(key: String) = File(renderDir, "$key.none")

    /** Страницы главы не меняются, а адрес картинки у многих источников подписан и живёт недолго - в ключ его не кладём. */
    private fun cacheKey(ctx: ChapterTranslationContext, page: MangaPage): String {
        val raw = "$PIPELINE_VERSION|${ctx.sourceId}|${ctx.chapterKey}|${page.index}|${ctx.target.name}"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "MangaPageTranslation"

        /** Менять при изменении разбора или набора: старые результаты тогда перестанут подходить. */
        const val PIPELINE_VERSION = 1

        const val JAPANESE = "ja"

        /** Длинная сторона страницы, уходящей модели с картинками: дальше токены растут, а читаемость - нет. */
        const val VISION_MAX_SIDE = 1600
        const val VISION_JPEG_QUALITY = 82

        const val PREFETCH_PAGES = 2
        const val MODEL_ATTEMPTS = 2
        const val MAX_PIXELS = 24_000_000L
        const val WEBP_QUALITY = 90
        const val RENDER_CACHE_BYTES = 300L * 1024 * 1024
        const val IDLE_CLOSE_MS = 90_000L
        const val MAX_AUTO_WAIT_MS = 20_000L
        const val MIN_RETRY_WAIT_MS = 1_500L
    }
}
