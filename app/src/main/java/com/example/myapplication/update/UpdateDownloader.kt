package com.example.myapplication.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Скачанный файл не совпал с тем, что заявлено в релизе (размер или SHA-256). */
class UpdateIntegrityException(message: String) : IOException(message)

/** Сервер на запрос куска (Range) ответил целым файлом: параллельная загрузка невозможна. */
internal class RangeIgnoredException(message: String) : IOException(message)

/**
 * Скачивает APK обновления. Заменяет системный `DownloadManager`, который жил внутри экрана настроек
 * и зависел от его жизни: тут загрузка идёт в приложении, прогресс считается сам, оборванное
 * докачивается с того места, где остановились (HTTP Range), а готовый файл проверяется по размеру и
 * контрольной сумме до того, как его покажут пользователю.
 *
 * Крупный файл с известным размером качается [segments] соединениями сразу: у CDN GitHub скорость на
 * одно соединение по мобильной сети часто в разы ниже, чем у канала в целом (см. [downloadParallel]).
 *
 * Файл появляется под своим именем только целым и проверенным: до этого он лежит как `.part`.
 *
 * @param client должен ходить по HTTP/1.1, иначе параллельные запросы к одному хосту уйдут в одно
 *  HTTP/2-соединение и выигрыша не будет.
 */
class UpdateDownloader(
    private val client: OkHttpClient,
    private val dir: File,
    private val bufferSize: Int = 64 * 1024,
    private val segments: Int = SEGMENTS,
    private val parallelMinBytes: Long = PARALLEL_MIN_BYTES,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {

    /** Куда ложится готовый APK этого релиза. */
    fun fileFor(tag: String): File = File(dir, "vetro-${safe(tag)}.apk")

    private fun partFor(tag: String): File = File(dir, "vetro-${safe(tag)}.apk.part")

    private fun segmentFor(tag: String, index: Int): File = File(dir, "vetro-${safe(tag)}.apk.part.$index")

    /** Скачан ли уже этот релиз целиком (файл появляется под своим именем только после проверки). */
    fun downloaded(tag: String): File? = fileFor(tag).takeIf { it.isFile && it.length() > 0 }

    /** Убрать всё, кроме файла [keepTag]: старые версии и недокачанное не должны копиться. */
    fun cleanExcept(keepTag: String?) {
        val keep = keepTag?.let { fileFor(it).name }
        dir.listFiles()?.forEach { file -> if (file.name != keep) file.delete() }
    }

    /**
     * @param onProgress вызывается по ходу загрузки: сколько байт уже есть и сколько всего; не чаще
     *  чем раз в [PROGRESS_STEP_BYTES] (одним потоком) или [PROGRESS_TICK_MS] (несколькими) и не из
     *  нескольких потоков сразу.
     * @throws UpdateIntegrityException если скачанное не совпало с релизом; недокачанный файл при этом удалён.
     */
    suspend fun download(release: UpdateRelease, onProgress: (done: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            downloaded(release.tag)?.let { return@withContext it }
            val part = partFor(release.tag)
            val parallel = segments > 1 && release.sizeBytes >= parallelMinBytes &&
                try {
                    downloadParallel(release, part, onProgress)
                    true
                } catch (e: RangeIgnoredException) {
                    // Сервер Range не понимает: куски бесполезны, качаем целиком одним потоком.
                    deleteSegments(release.tag, from = 0)
                    false
                }
            if (!parallel) downloadSequential(release, part, onProgress)
            verify(part, release)
            val target = fileFor(release.tag)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                throw IOException("cannot move the downloaded file")
            }
            target
        }

    /**
     * Файл режется на [segments] равных кусков; каждый тянется своим соединением и лежит в своём
     * `.part.N`, поэтому оборванная загрузка докачивается по каждому куску отдельно. Готовые куски
     * склеиваются в [target].
     * @throws RangeIgnoredException если сервер на запрос куска ответил целым файлом.
     */
    private suspend fun downloadParallel(
        release: UpdateRelease,
        target: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) = coroutineScope {
        val total = release.sizeBytes
        val chunk = (total + segments - 1) / segments
        val ranges = (0 until segments)
            .map { i -> (i * chunk) to (minOf(total, (i + 1) * chunk) - 1) }
            .filter { (start, end) -> start <= end }
        val files = ranges.indices.map { segmentFor(release.tag, it) }
        // Куски другой раскладки и одиночный .part прежней версии докачивать не к чему.
        deleteSegments(release.tag, from = ranges.size)
        target.delete()
        val done = AtomicLong(files.sumOf { it.length() })

        // Прогресс отдаёт один сборщик, а не рабочие потоки: потребитель не рассчитан на параллельные вызовы.
        val ticker = launch {
            while (true) {
                onProgress(done.get(), total)
                delay(PROGRESS_TICK_MS)
            }
        }
        try {
            ranges.mapIndexed { i, (start, end) ->
                async { fetchSegment(release, start, end, files[i], done) }
            }.awaitAll()
        } finally {
            ticker.cancel()
        }
        onProgress(done.get(), total)

        FileOutputStream(target).use { out ->
            files.forEach { f -> f.inputStream().use { it.copyTo(out, bufferSize) } }
        }
        files.forEach { it.delete() }
    }

    /** Один кусок [start]..[end] (включительно) в [file]; обрыв докачивается с того же места. */
    private suspend fun fetchSegment(release: UpdateRelease, start: Long, end: Long, file: File, done: AtomicLong) {
        val wanted = end - start + 1
        var failures = 0
        while (true) {
            val have = file.length()
            if (have == wanted) return
            if (have > wanted) {
                file.delete()
                throw IOException("segment overrun")
            }
            try {
                val request = Request.Builder().url(release.downloadUrl)
                    .header("Accept", "application/octet-stream")
                    .header("Range", "bytes=${start + have}-$end")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 200) throw RangeIgnoredException("HTTP 200 on a range request")
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("empty body")
                    FileOutputStream(file, true).use { out ->
                        val buffer = ByteArray(bufferSize)
                        body.byteStream().use { input ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done.addAndGet(n.toLong())
                            }
                        }
                    }
                }
                if (file.length() == wanted) return
                throw IOException("segment cut short")
            } catch (e: RangeIgnoredException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Слабая мобильная сеть рвёт соединения: пара повторов с паузой дешевле, чем начинать сначала.
                if (++failures >= SEGMENT_ATTEMPTS) throw e
                delay(retryDelayMs * failures)
            }
        }
    }

    /** Убрать куски `.part.N` с номером не меньше [from]. */
    private fun deleteSegments(tag: String, from: Int) {
        val prefix = "${partFor(tag).name}."
        dir.listFiles()?.forEach { f ->
            val index = f.name.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()
            if (index != null && index >= from) f.delete()
        }
    }

    /** Обычная загрузка одним потоком с докачкой единого `.part`. */
    private suspend fun downloadSequential(
        release: UpdateRelease,
        part: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        var have = if (part.isFile) part.length() else 0L
        // Недокачанный файл больше заявленного размера - мусор от прежней попытки.
        if (release.sizeBytes > 0 && have > release.sizeBytes) {
            part.delete()
            have = 0L
        }

        val request = Request.Builder().url(release.downloadUrl)
            .header("Accept", "application/octet-stream")
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 416 && have > 0) {
                // Сервер говорит, что докачивать нечего или файл другой: начинаем заново.
                part.delete()
                throw IOException("range not satisfiable")
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty body")
            val resumed = response.code == 206
            if (!resumed) have = 0L
            val total = when {
                release.sizeBytes > 0 -> release.sizeBytes
                body.contentLength() > 0 -> have + body.contentLength()
                else -> 0L
            }
            RandomAccessFile(part, "rw").use { out ->
                if (resumed) out.seek(have) else out.setLength(0)
                val buffer = ByteArray(bufferSize)
                var done = have
                var reported = done
                onProgress(done, total)
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - reported >= PROGRESS_STEP_BYTES) {
                            reported = done
                            onProgress(done, total)
                        }
                    }
                }
                onProgress(done, total)
            }
        }
    }

    private fun verify(file: File, release: UpdateRelease) {
        if (release.sizeBytes > 0 && file.length() != release.sizeBytes) {
            file.delete()
            throw UpdateIntegrityException("size ${file.length()} != ${release.sizeBytes}")
        }
        val expected = release.sha256 ?: return
        val actual = sha256(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw UpdateIntegrityException("sha256 mismatch")
        }
    }

    companion object {
        const val PROGRESS_STEP_BYTES = 128L * 1024

        /** Как часто сборщик отдаёт прогресс при загрузке несколькими соединениями. */
        const val PROGRESS_TICK_MS = 250L

        /** Сколько соединений тянут файл одновременно. */
        const val SEGMENTS = 4

        /** Меньше этого размера делить файл смысла нет: рукопожатия съедят выигрыш. */
        const val PARALLEL_MIN_BYTES = 4L * 1024 * 1024

        private const val SEGMENT_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 1_000L

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Тег идёт в имя файла: всё, что не буква, цифра, точка и дефис, заменяется. */
        private fun safe(tag: String): String = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
