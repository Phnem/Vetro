package com.example.myapplication.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Скачанный файл не совпал с тем, что заявлено в релизе (размер или SHA-256). */
class UpdateIntegrityException(message: String) : IOException(message)

/** Сервер на запрос куска (Range) ответил целым файлом: параллельная загрузка невозможна. */
internal class RangeIgnoredException(message: String) : IOException(message)

/** Кусок тянется слишком долго одним соединением: его надо продолжить новым, а не ждать. */
private class SlowChunkException : IOException("chunk is too slow")

/**
 * Скачивает APK обновления. Заменяет системный `DownloadManager`, который жил внутри экрана настроек
 * и зависел от его жизни: тут загрузка идёт в приложении, прогресс считается сам, оборванное
 * докачивается с того места, где остановились (HTTP Range), а готовый файл проверяется по размеру и
 * контрольной сумме до того, как его покажут пользователю.
 *
 * Крупный файл с известным размером режется на мелкие куски ([chunkBytes]), которые [connections]
 * соединений разбирают из общей очереди ([downloadParallel]). Раньше файл делился на N равных долей по
 * числу соединений, и хвост тянуло самое медленное из них: быстрые давно закончили, а последние
 * проценты шли в одно соединение. Теперь, освободившись, соединение берёт следующий кусок, и
 * медленному достаётся не доля файла, а один кусок.
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
    private val connections: Int = CONNECTIONS,
    private val parallelMinBytes: Long = PARALLEL_MIN_BYTES,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val chunkBytes: Long = CHUNK_BYTES,
    /** Сколько миллисекунд один заход на кусок считается нормальным, дальше соединение меняется. */
    private val slowChunkMs: Long = SLOW_CHUNK_MS,
) {

    /** Куда ложится готовый APK этого релиза. */
    fun fileFor(tag: String): File = File(dir, "vetro-${safe(tag)}.apk")

    private fun partFor(tag: String): File = File(dir, "vetro-${safe(tag)}.apk.part")

    /** Какие куски `.part` уже целиком на диске (см. [downloadParallel]). */
    private fun mapFor(tag: String): File = File(dir, "vetro-${safe(tag)}.apk.part.map")

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
            val parallel = connections > 1 && release.sizeBytes >= parallelMinBytes &&
                try {
                    downloadParallel(release, part, onProgress)
                    true
                } catch (e: RangeIgnoredException) {
                    // Сервер Range не понимает: куски бесполезны, качаем целиком одним потоком.
                    part.delete()
                    mapFor(release.tag).delete()
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
     * `.part` создаётся сразу во всю длину, куски пишутся по своим смещениям, а готовые отмечаются в
     * `.part.map` (заголовок `v1 <размер> <кусок>`, дальше по строке на готовый кусок). Оборванная
     * загрузка продолжается с первого не отмеченного куска; недописанный кусок начнётся заново.
     * Файл больше не склеивается из частей — отдельного прохода по всему файлу в конце нет.
     *
     * Ссылка релиза редиректит на подписанный адрес CDN; на каждый кусок идти через редирект дорого,
     * поэтому адрес узнаётся один раз пробным запросом и дальше используется напрямую. Подпись живёт
     * недолго: на отказ в доступе адрес узнаётся заново.
     * @throws RangeIgnoredException если сервер на запрос куска ответил целым файлом.
     */
    private suspend fun downloadParallel(
        release: UpdateRelease,
        part: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) = coroutineScope {
        val total = release.sizeBytes
        val chunkCount = ((total + chunkBytes - 1) / chunkBytes).toInt()
        val map = mapFor(release.tag)
        val header = "v1 $total $chunkBytes"

        // Куски прежней раскладки (`.part.N`) и чужая запись не докачиваются: чистим и начинаем заново.
        deleteLegacySegments(release.tag)
        val finished = BooleanArray(chunkCount)
        val resumable = part.isFile && part.length() == total && map.isFile &&
            map.readLines().firstOrNull() == header
        if (resumable) {
            map.readLines().drop(1).forEach { line ->
                line.trim().toIntOrNull()?.takeIf { it in 0 until chunkCount }?.let { finished[it] = true }
            }
        } else {
            part.delete()
            RandomAccessFile(part, "rw").use { it.setLength(total) }
            map.writeText("$header\n")
        }
        fun chunkSize(i: Int) = minOf(chunkBytes, total - i * chunkBytes)
        val done = AtomicLong((0 until chunkCount).filter { finished[it] }.sumOf { chunkSize(it) })

        val resolver = UrlResolver(release.downloadUrl)
        val queue = Channel<Int>(Channel.UNLIMITED)
        val pending = (0 until chunkCount).filter { !finished[it] }
        pending.forEach { queue.trySend(it) }
        queue.close()
        val mapLock = Any()

        // Прогресс отдаёт один сборщик, а не рабочие потоки: потребитель не рассчитан на параллельные вызовы.
        val ticker = launch {
            while (true) {
                onProgress(done.get(), total)
                delay(PROGRESS_TICK_MS)
            }
        }
        try {
            if (pending.isNotEmpty()) resolver.probe()
            RandomAccessFile(part, "rw").use { raf ->
                val channel = raf.channel
                (0 until minOf(connections, maxOf(pending.size, 1))).map {
                    async {
                        for (index in queue) {
                            val start = index * chunkBytes
                            fetchChunk(resolver, start, start + chunkSize(index) - 1, channel, done)
                            synchronized(mapLock) { map.appendText("$index\n") }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            ticker.cancel()
        }
        onProgress(done.get(), total)
        map.delete()
    }

    /** Адрес для запросов кусков: окончательный (после редиректов), с возможностью узнать заново. */
    private inner class UrlResolver(private val original: String) {
        @Volatile
        var url: HttpUrl = original.toHttpUrl()
            private set
        private val lock = Mutex()

        /** Пробный запрос первого байта: узнаёт конечный адрес и проверяет, что сервер понимает Range. */
        suspend fun probe() = lock.withLock {
            var failures = 0
            while (true) {
                try {
                    val request = Request.Builder().url(original)
                        .header("Accept", "application/octet-stream")
                        .header("Range", "bytes=0-0")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.code == 200) throw RangeIgnoredException("HTTP 200 on a range request")
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        url = response.request.url
                    }
                    return@withLock
                } catch (e: RangeIgnoredException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (++failures >= CHUNK_ATTEMPTS) throw e
                    delay(retryDelayMs * failures)
                }
            }
        }
    }

    /**
     * Один кусок [start]..[end] (включительно) пишется в [channel] по своему смещению; обрыв
     * продолжается с того же байта. Заход дольше [slowChunkMs] прерывается и продолжается новым
     * соединением: у CDN скорость отдельного соединения сильно гуляет, и медленное не должно держать
     * последние проценты загрузки.
     */
    private suspend fun fetchChunk(
        resolver: UrlResolver,
        start: Long,
        end: Long,
        channel: FileChannel,
        done: AtomicLong,
    ) {
        val wanted = end - start + 1
        var have = 0L
        var failures = 0
        var slowRestarts = 0
        while (have < wanted) {
            val attemptStartedAt = System.nanoTime()
            try {
                val request = Request.Builder().url(resolver.url)
                    .header("Accept", "application/octet-stream")
                    .header("Range", "bytes=${start + have}-$end")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 200) throw RangeIgnoredException("HTTP 200 on a range request")
                    if (response.code in EXPIRED_LINK_CODES) {
                        // Подпись адреса CDN истекла: узнаём адрес заново, а этот заход считается неудачным.
                        resolver.probe()
                        throw IOException("HTTP ${response.code}")
                    }
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("empty body")
                    val buffer = ByteArray(bufferSize)
                    body.byteStream().use { input ->
                        while (have < wanted) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), wanted - have).toInt())
                            if (n < 0) break
                            writeFully(channel, ByteBuffer.wrap(buffer, 0, n), start + have)
                            have += n
                            done.addAndGet(n.toLong())
                            val elapsedMs = (System.nanoTime() - attemptStartedAt) / 1_000_000
                            if (have < wanted && elapsedMs > slowChunkMs) throw SlowChunkException()
                        }
                    }
                }
                if (have == wanted) return
                throw IOException("chunk cut short")
            } catch (e: RangeIgnoredException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: SlowChunkException) {
                if (++slowRestarts > MAX_SLOW_RESTARTS) throw IOException("chunk is too slow")
            } catch (e: IOException) {
                // Слабая мобильная сеть рвёт соединения: пара повторов с паузой дешевле, чем начинать сначала.
                if (++failures >= CHUNK_ATTEMPTS) throw e
                delay(retryDelayMs * failures)
            }
        }
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer, position: Long) {
        var at = position
        while (buffer.hasRemaining()) at += channel.write(buffer, at)
    }

    /** Убрать куски `.part.N` прежней раскладки (равные доли по числу соединений). */
    private fun deleteLegacySegments(tag: String) {
        val prefix = "${partFor(tag).name}."
        dir.listFiles()?.forEach { f ->
            val suffix = f.name.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
            if (suffix?.toIntOrNull() != null) f.delete()
        }
    }

    /** Обычная загрузка одним потоком с докачкой единого `.part`. */
    private suspend fun downloadSequential(
        release: UpdateRelease,
        part: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        // Единый `.part` от параллельной загрузки (во всю длину, с картой кусков) для докачки с конца не годится.
        if (mapFor(release.tag).exists()) {
            part.delete()
            mapFor(release.tag).delete()
        }
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
        const val CONNECTIONS = 6

        /**
         * Размер куска из общей очереди. Меньше — хвост короче (медленному соединению достаётся
         * мало), но больше запросов; на 33 МБ мегабайтные куски дают ~30 запросов на всё.
         */
        const val CHUNK_BYTES = 1L * 1024 * 1024

        /** Меньше этого размера делить файл смысла нет: рукопожатия съедят выигрыш. */
        const val PARALLEL_MIN_BYTES = 4L * 1024 * 1024

        /** Заход на один кусок дольше этого считается медленным соединением: его меняют на новое. */
        const val SLOW_CHUNK_MS = 15_000L

        private const val MAX_SLOW_RESTARTS = 8
        private const val CHUNK_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 1_000L

        /** Ответы, которыми подписанный адрес CDN говорит, что устарел. */
        private val EXPIRED_LINK_CODES = setOf(400, 401, 403, 410)

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
