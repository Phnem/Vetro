package com.example.myapplication.media.download

import android.util.Log
import com.example.myapplication.media.source.SanitizeHeaders
import com.example.myapplication.media.source.VetroVideo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Android-native VOD HLS downloader.
 *
 * AniLiberty exposes media playlists made of MPEG-TS segments. Concatenated TS packets remain a
 * valid stream which Media3 can sniff and play locally; no external executable or shell process is
 * required. fMP4 playlists are also supported when they provide EXT-X-MAP.
 *
 * A single dropped connection must not cost the whole episode: CDNs close long-lived keep-alive
 * sessions mid-body (OkHttp surfaces that as `EOFException`), and an episode is hundreds of
 * segments. Each segment is therefore fetched whole into memory and retried on transport errors;
 * only a complete segment is appended to the output, so a half-read body can never corrupt it.
 *
 * Up to [PARALLEL_SEGMENTS] segments are in flight at once and appended strictly in playlist order:
 * on a slow link one connection rarely fills the pipe, and hundreds of sequential round trips were
 * most of an episode's download time. The first transport failure drops the window to one — a CDN
 * that is dropping connections is not helped by more of them. Segments go straight from memory to
 * the output; the old staging file wrote every byte twice.
 */
class HlsSegmentDownloader(
    private val client: OkHttpClient,
) {
    fun download(
        video: VetroVideo,
        destination: File,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        parallelism: Int = PARALLEL_SEGMENTS,
    ): File {
        val playlist = resolveMediaPlaylist(video.url, video, depth = 0)
        val urls = playlist.segmentUrls
        if (urls.isEmpty()) throw IOException("HLS-плейлист не содержит сегментов")

        destination.parentFile?.mkdirs()
        val tmp = File(destination.parentFile, destination.nameWithoutExtension + ".part.mp4")
        runCatching { tmp.delete() }
        // A staging file left by a download interrupted before segments moved to memory.
        runCatching { File(destination.parentFile, destination.nameWithoutExtension + ".seg.tmp").delete() }

        val window = parallelism.coerceIn(1, urls.size)
        val degraded = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(window)
        try {
            tmp.outputStream().buffered(WRITE_BUFFER_BYTES).use { output ->
                val inFlight = ArrayDeque<Future<ByteArray>>()
                var nextToFetch = 0
                var lastPercent = -1
                for (index in urls.indices) {
                    if (isCancelled()) throw HlsDownloadCancelledException()
                    val limit = if (degraded.get()) 1 else window
                    while (nextToFetch < urls.size && (inFlight.isEmpty() || inFlight.size < limit)) {
                        val segment = nextToFetch++
                        inFlight.addLast(
                            executor.submit(
                                Callable {
                                    fetchSegmentWithRetry(urls[segment], video, segment, isCancelled, degraded)
                                },
                            ),
                        )
                    }
                    output.write(inFlight.removeFirst().awaitSegment())
                    val percent = (((index + 1L) * 100L) / urls.size).toInt().coerceIn(0, 99)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            }
            if (!MediaFileValidator.isPlayableVideo(tmp)) {
                throw IOException("Сегменты HLS не образовали поддерживаемый видеофайл")
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("Не удалось заменить временный файл HLS")
            }
            if (!tmp.renameTo(destination)) {
                tmp.copyTo(destination, overwrite = true)
                if (!tmp.delete()) throw IOException("Не удалось завершить HLS-загрузку")
            }
            onProgress(100)
            return destination
        } catch (error: Exception) {
            runCatching { tmp.delete() }
            throw error
        } finally {
            // Interrupts segments still in flight after a failure or cancellation.
            executor.shutdownNow()
        }
    }

    private fun Future<ByteArray>.awaitSegment(): ByteArray = try {
        get()
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    }

    /**
     * Retries transport failures (dropped connection, read timeout, truncated body). A refusal the
     * server means — bad status, non-video payload — is permanent and fails on the first answer.
     */
    private fun fetchSegmentWithRetry(
        segmentUrl: String,
        video: VetroVideo,
        index: Int,
        isCancelled: () -> Boolean,
        degraded: AtomicBoolean,
    ): ByteArray {
        var lastError: IOException? = null
        repeat(SEGMENT_ATTEMPTS) { attempt ->
            if (isCancelled()) throw HlsDownloadCancelledException()
            try {
                return fetchSegment(segmentUrl, video, isCancelled)
            } catch (cancelled: HlsDownloadCancelledException) {
                throw cancelled
            } catch (permanent: PermanentSegmentException) {
                throw permanent
            } catch (error: IOException) {
                lastError = error
                degraded.set(true)
                runCatching {
                    Log.w(
                        TAG,
                        "segment $index attempt ${attempt + 1}/$SEGMENT_ATTEMPTS failed: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                }
                if (attempt < SEGMENT_ATTEMPTS - 1) {
                    Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1))
                }
            }
        }
        val reason = lastError?.message ?: lastError?.javaClass?.simpleName.orEmpty()
        throw IOException("Сегмент $index не скачался за $SEGMENT_ATTEMPTS попыток: $reason", lastError)
    }

    private fun fetchSegment(
        segmentUrl: String,
        video: VetroVideo,
        isCancelled: () -> Boolean,
    ): ByteArray {
        client.newCall(request(segmentUrl, video)).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code in RETRYABLE_STATUSES) {
                    throw IOException("HTTP ${response.code} для HLS-сегмента")
                }
                throw PermanentSegmentException("HTTP ${response.code} для HLS-сегмента")
            }
            val contentType = response.header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                .orEmpty()
            if (
                contentType.startsWith("image/") ||
                contentType.startsWith("text/") ||
                contentType.contains("json")
            ) {
                throw PermanentSegmentException("HLS-сегмент вернул $contentType")
            }
            val body = response.body ?: throw IOException("Пустой HLS-сегмент")
            val declaredLength = body.contentLength().takeIf { it > 0L }
            var written = 0L
            val output = ByteArrayOutputStream(
                declaredLength?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: READ_BUFFER_BYTES,
            )
            body.byteStream().use { input ->
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (true) {
                    if (isCancelled()) throw HlsDownloadCancelledException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    written += count
                }
            }
            // A short body is the silent form of the dropped-connection failure: OkHttp only raises
            // EOFException for chunked/gzip streams, a truncated Content-Length response just ends.
            if (declaredLength != null && written < declaredLength) {
                throw IOException("Сегмент оборван: $written из $declaredLength байт")
            }
            return output.toByteArray()
        }
    }

    private fun resolveMediaPlaylist(
        url: String,
        video: VetroVideo,
        depth: Int,
    ): MediaPlaylist {
        if (depth > MAX_PLAYLIST_DEPTH) throw IOException("Слишком глубокий HLS-плейлист")
        val lines = fetchPlaylistWithRetry(url, video)
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
            val variants = parseVariants(lines, url)
            val selected = variants.minWithOrNull(
                compareBy<Variant>(
                    { variant ->
                        val requested = video.resolution
                        val height = variant.height
                        if (requested == null || height == null) 0 else abs(requested - height)
                    },
                    { -it.bandwidth },
                )
            ) ?: throw IOException("Master HLS-плейлист не содержит вариантов")
            return resolveMediaPlaylist(selected.url, video, depth + 1)
        }

        if (lines.any { it.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) }) {
            throw IOException("HLS byte-range пока не поддерживается")
        }
        val encrypted = lines.firstOrNull {
            it.startsWith("#EXT-X-KEY", ignoreCase = true) &&
                !it.contains("METHOD=NONE", ignoreCase = true)
        }
        if (encrypted != null) throw IOException("Зашифрованный HLS пока не поддерживается")

        val result = mutableListOf<String>()
        lines.firstOrNull { it.startsWith("#EXT-X-MAP", ignoreCase = true) }
            ?.let(::extractMapUri)
            ?.let { result += resolveUrl(url, it) }
        lines.asSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { resolveUrl(url, it) }
            .forEach(result::add)
        return MediaPlaylist(result)
    }

    private fun fetchPlaylistWithRetry(url: String, video: VetroVideo): List<String> {
        var lastError: IOException? = null
        repeat(PLAYLIST_ATTEMPTS) { attempt ->
            try {
                return fetchPlaylist(url, video)
            } catch (permanent: PermanentSegmentException) {
                throw IOException(permanent.message)
            } catch (error: IOException) {
                lastError = error
                if (attempt < PLAYLIST_ATTEMPTS - 1) {
                    Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1))
                }
            }
        }
        throw IOException(
            "HLS-плейлист не открылся: ${lastError?.message.orEmpty()}",
            lastError,
        )
    }

    private fun fetchPlaylist(url: String, video: VetroVideo): List<String> {
        client.newCall(request(url, video)).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code in RETRYABLE_STATUSES) {
                    throw IOException("HTTP ${response.code} для HLS-плейлиста")
                }
                throw PermanentSegmentException("HTTP ${response.code} для HLS-плейлиста")
            }
            val text = response.body?.string() ?: throw IOException("Пустой HLS-плейлист")
            if (!text.trimStart().startsWith("#EXTM3U")) {
                throw PermanentSegmentException("Источник вернул не HLS-плейлист")
            }
            return text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        }
    }

    private fun parseVariants(lines: List<String>, baseUrl: String): List<Variant> =
        buildList {
            lines.forEachIndexed { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) return@forEachIndexed
                val next = lines.drop(index + 1).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
                val height = RESOLUTION.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val bandwidth = BANDWIDTH.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                add(Variant(resolveUrl(baseUrl, next), height, bandwidth))
            }
        }

    private fun request(url: String, video: VetroVideo): Request =
        Request.Builder().url(url).apply {
            SanitizeHeaders.sanitize(video.headers).forEach { (name, value) -> header(name, value) }
        }.build()

    private fun resolveUrl(base: String, child: String): String =
        runCatching { URI(base).resolve(child).toString() }
            .getOrElse { throw IOException("Некорректный URL HLS-сегмента", it) }

    private fun extractMapUri(line: String): String? =
        MAP_URI.find(line)?.groupValues?.getOrNull(1)

    private data class MediaPlaylist(val segmentUrls: List<String>)
    private data class Variant(val url: String, val height: Int?, val bandwidth: Long)

    /** The server answered and said no — retrying the same request cannot change the answer. */
    private class PermanentSegmentException(message: String) : IOException(message)

    class HlsDownloadCancelledException : IOException("HLS download cancelled")

    companion object {
        private const val TAG = "HlsSegmentDownloader"
        private const val MAX_PLAYLIST_DEPTH = 2
        private const val SEGMENT_ATTEMPTS = 4
        private const val PLAYLIST_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 800L
        const val PARALLEL_SEGMENTS = 3
        private const val READ_BUFFER_BYTES = 64 * 1024
        private const val WRITE_BUFFER_BYTES = 256 * 1024
        private val RETRYABLE_STATUSES = setOf(408, 425, 429, 500, 502, 503, 504)
        private val MAP_URI = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
        private val BANDWIDTH = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
    }
}
