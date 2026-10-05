package com.example.myapplication.manga.translate

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Модели автоперевода. В APK их нет: ~128 МБ скачиваются один раз, когда пользователь включает
 * функцию, и лежат во внутренней памяти приложения.
 *
 * Все четыре файла - под Apache-2.0 и лежат у авторов на Hugging Face; Vetro их не хостит и не
 * переупаковывает. Ссылки закреплены на конкретный коммит репозитория, а содержимое сверяется по
 * SHA-256: обновление весов у автора не сломает конвейер молча.
 *  - детектор: ogkalu/comic-text-and-bubble-detector (RT-DETRv2, int8);
 *  - OCR: onnx-community/manga-ocr-base-ONNX (kha-white/manga-ocr-base, int8) + его словарь.
 */
object TranslationModels {

    data class Spec(
        val fileName: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    val SPECS: List<Spec> = listOf(
        Spec(
            fileName = "detector.onnx",
            url = "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/" +
                "16e8a622f91fabc6b5b65c96d32d1183f8843546/detector-v4-s_int8.onnx",
            sizeBytes = 11_120_765L,
            sha256 = "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79",
        ),
        Spec(
            fileName = "ocr-encoder.onnx",
            url = "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/" +
                "f9023406bb2f6b17df67bc4a327c56ecd20611f0/onnx/encoder_model_int8.onnx",
            sizeBytes = 86_967_767L,
            sha256 = "ddd1af56963093795705fa38da6ce7e6567d1658e7c7359db7e13fcd37dbf279",
        ),
        Spec(
            fileName = "ocr-decoder.onnx",
            url = "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/" +
                "f9023406bb2f6b17df67bc4a327c56ecd20611f0/onnx/decoder_model_int8.onnx",
            sizeBytes = 29_627_936L,
            sha256 = "2e7177d2b0a59f1c612b694ed70c13971bee765cc2b2bc7bc9376e4753652f27",
        ),
        Spec(
            fileName = "ocr-vocab.txt",
            url = "https://huggingface.co/kha-white/manga-ocr-base/resolve/" +
                "aa6573bd10b0d446cbf622e29c3e084914df9741/vocab.txt",
            sizeBytes = 24_072L,
            sha256 = "344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d",
        ),
    )

    val TOTAL_BYTES: Long = SPECS.sumOf { it.sizeBytes }

    /** Детектор качается первым и один: по нему шторка загрузки делит общий прогресс на два шага. */
    val DETECTOR_BYTES: Long = SPECS.first { it.fileName == "detector.onnx" }.sizeBytes

    /** Прогресс шагов "детектор" и "распознавание" (0..1) по числу скачанных байт всего. */
    fun stepFractions(doneBytes: Long): Pair<Float, Float> {
        val detector = (doneBytes.toFloat() / DETECTOR_BYTES).coerceIn(0f, 1f)
        val ocrBytes = TOTAL_BYTES - DETECTOR_BYTES
        val ocr = ((doneBytes - DETECTOR_BYTES).toFloat() / ocrBytes).coerceIn(0f, 1f)
        return detector to ocr
    }

    fun detectorFile(dir: File) = File(dir, "detector.onnx")
    fun encoderFile(dir: File) = File(dir, "ocr-encoder.onnx")
    fun decoderFile(dir: File) = File(dir, "ocr-decoder.onnx")
    fun vocabFile(dir: File) = File(dir, "ocr-vocab.txt")
}

sealed interface ModelsState {
    data object NotInstalled : ModelsState

    /**
     * Скачано [doneBytes] из [totalBytes] по всем файлам вместе; [bytesPerSecond] - сглаженная
     * скорость (0, пока замер не набрался).
     */
    data class Downloading(
        val doneBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long = 0L,
    ) : ModelsState {
        val fraction: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

        /** Оставшееся время в секундах или null, пока скорость неизвестна. */
        val etaSeconds: Long?
            get() = if (bytesPerSecond > 0) ((totalBytes - doneBytes) / bytesPerSecond).coerceAtLeast(0) else null
    }

    data object Ready : ModelsState

    data class Failed(val message: String) : ModelsState
}

/** Папка с моделями и их загрузка. Одновременно идёт не больше одной загрузки. */
class TranslationModelStore(
    context: Context,
    private val client: OkHttpClient,
) {
    val directory: File = File(context.filesDir, "manga_translate_models")

    private val mutex = Mutex()
    private val _state = MutableStateFlow(if (isInstalled()) ModelsState.Ready else ModelsState.NotInstalled)
    val state: StateFlow<ModelsState> = _state.asStateFlow()

    /** Файлы на месте и нужного размера. Полная проверка хэшей - при загрузке, не на каждый запуск. */
    fun isInstalled(): Boolean = TranslationModels.SPECS.all { spec ->
        File(directory, spec.fileName).let { it.isFile && it.length() == spec.sizeBytes }
    }

    suspend fun ensureInstalled(): Result<Unit> = mutex.withLock {
        if (isInstalled()) {
            _state.value = ModelsState.Ready
            return@withLock Result.success(Unit)
        }
        withContext(Dispatchers.IO) {
            val total = TranslationModels.TOTAL_BYTES
            val speed = SpeedMeter()
            runCatching {
                directory.mkdirs()
                var doneBytes = 0L
                for (spec in TranslationModels.SPECS) {
                    val target = File(directory, spec.fileName)
                    if (target.isFile && target.length() == spec.sizeBytes) {
                        doneBytes += spec.sizeBytes
                        continue
                    }
                    _state.value = ModelsState.Downloading(doneBytes, total, speed.bytesPerSecond)
                    download(spec, target) { fileBytes ->
                        val done = doneBytes + fileBytes
                        _state.value = ModelsState.Downloading(done, total, speed.update(done))
                    }
                    doneBytes += spec.sizeBytes
                }
                _state.value = ModelsState.Ready
            }.onFailure {
                if (it is CancellationException) {
                    // Пользователь отменил: недокачанный кусок не нужен, а состояние - как до включения.
                    directory.listFiles { f -> f.name.endsWith(".part") }?.forEach { part -> part.delete() }
                    _state.value = ModelsState.NotInstalled
                    throw it
                }
                Log.w(TAG, "model download failed", it)
                _state.value = ModelsState.Failed(it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /** Освободить место: модели можно скачать заново. */
    suspend fun delete() = mutex.withLock {
        withContext(Dispatchers.IO) { directory.deleteRecursively() }
        _state.value = ModelsState.NotInstalled
    }

    private suspend fun download(spec: TranslationModels.Spec, target: File, onProgress: (Long) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        val request = Request.Builder().url(spec.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${spec.fileName}")
            val body = response.body ?: throw IOException("empty body for ${spec.fileName}")
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastReport = 0L
            part.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        // Чтение блокирующее: проверяем отмену на каждом куске, иначе "Отменить" не сработает.
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        written += n
                        if (written - lastReport >= REPORT_EVERY_BYTES) {
                            lastReport = written
                            onProgress(written)
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (written != spec.sizeBytes || actual != spec.sha256) {
                part.delete()
                throw IOException("checksum mismatch for ${spec.fileName}")
            }
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("cannot move ${spec.fileName} into place")
        }
    }

    private companion object {
        const val TAG = "TranslationModelStore"
        const val REPORT_EVERY_BYTES = 256 * 1024L
    }
}

/**
 * Скорость загрузки: сглаженная (EWMA) по окнам не короче [windowMillis], чтобы цифра на экране не
 * прыгала от каждого пакета. [clock] подменяется в тестах.
 */
class SpeedMeter(
    private val windowMillis: Long = 600L,
    private val smoothing: Double = 0.25,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var windowStartMs = -1L
    private var windowStartBytes = 0L
    private var smoothed = 0.0

    val bytesPerSecond: Long get() = smoothed.toLong()

    /** @param totalDone сколько байт скачано всего на сейчас; возвращает текущую скорость. */
    fun update(totalDone: Long): Long {
        val now = clock()
        if (windowStartMs < 0) {
            windowStartMs = now
            windowStartBytes = totalDone
            return bytesPerSecond
        }
        val elapsed = now - windowStartMs
        if (elapsed >= windowMillis) {
            val instant = (totalDone - windowStartBytes) * 1000.0 / elapsed
            smoothed = if (smoothed == 0.0) instant else smoothed * (1 - smoothing) + instant * smoothing
            windowStartMs = now
            windowStartBytes = totalDone
        }
        return bytesPerSecond
    }
}
