package com.example.myapplication.media.download

import com.example.myapplication.network.AppJson
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.myapplication.media.source.SanitizeHeaders
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.source.PlaybackSourceCredentialsStore
import com.example.myapplication.media.source.rehydrateWebDavCredentials
import com.example.myapplication.media.source.PersonalMediaServerProvider
import com.example.myapplication.media.source.rehydratePersonalServerCredentials
import com.example.myapplication.media.source.forPlaybackCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Downloads a ranked list of resolved media candidates.
 *
 * Every candidate is written to a temporary file and validated as an actual MP4 before it can
 * replace an existing episode. This prevents CDN error pages and 1x1 PNG placeholders from being
 * treated as downloaded episodes.
 */
class MediaDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val okHttpClient: OkHttpClient by inject()
    private val playbackCredentials: PlaybackSourceCredentialsStore by inject()
    private val json = AppJson

    /**
     * The shared client is tuned for short API calls; an episode is a multi-minute transfer over
     * hundreds of requests. Same connection pool, download-shaped timeouts.
     */
    private val downloadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()
        val jobId = inputData.getString(KEY_JOB_ID).orEmpty()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Vetro")
            .setContentText("Загрузка эпизода…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()
        val notificationId = stableNotificationId(jobId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val label = inputData.getString(KEY_LABEL) ?: "Auto"
        val outDir = inputData.getString(KEY_OUT_DIR) ?: return@withContext Result.failure()
        val episode = inputData.getInt(KEY_EPISODE, 0).takeIf { it > 0 }
            ?: return@withContext Result.failure()
        val legacyVideo = VetroVideo(
            url = url,
            label = label,
            resolution = resolutionFromLabel(label),
            headers = parseHeaders(inputData.getString(KEY_HEADERS_JSON).orEmpty()),
            isPreferred = true,
        )
        val candidates = parseCandidates(inputData.getString(KEY_CANDIDATES_JSON).orEmpty())
            .ifEmpty { listOf(legacyVideo) }
            .map(::rehydrateCredentials)
            .distinctBy { it.url }

        val outputDirectory = File(outDir)
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            return@withContext fail(jobId, "Не удалось создать папку загрузки")
        }

        try {
            setForeground(getForegroundInfo())
        } catch (error: Exception) {
            Log.w(TAG, "Foreground promotion rejected, continuing safely", error)
        }

        val outFile = File(outputDirectory, "E${episode.toString().padStart(3, '0')}.mp4")
        if (outFile.exists() && !MediaFileValidator.isPlayableMp4(outFile)) {
            runCatching { outFile.delete() }
            DownloadedSkipStore.delete(outFile)
        }
        MediaJobBus.update(MediaJobProgress(jobId, "downloading", 0))

        try {
            val failures = mutableListOf<String>()
            var downloaded = false
            candidates.forEachIndexed { index, video ->
                if (downloaded) return@forEachIndexed
                checkCancelled(jobId)
                val candidateFile = candidateFile(outFile)
                cleanupCandidate(candidateFile)
                try {
                    Log.i(
                        TAG,
                        "Trying ${index + 1}/${candidates.size}: ${video.label} " +
                            "${if (video.isHlsOrDash) "HLS/DASH" else "progressive"} " +
                            "host=${safeHost(video.url)}",
                    )
                    if (video.isHlsOrDash) {
                        if (!video.url.contains(".m3u8", ignoreCase = true)) {
                            throw IOException("Компонент HLS-загрузки недоступен")
                        }
                        downloadSeekableHls(video, candidateFile) { percent ->
                            if (MediaJobBus.isCancelRequested(jobId)) {
                                throw DownloadCancelledException()
                            } else {
                                MediaJobBus.update(
                                    MediaJobProgress(jobId, "downloading", percent)
                                )
                            }
                        }
                    } else {
                        downloadProgressive(video, candidateFile, jobId)
                    }
                    if (!MediaFileValidator.isPlayableVideo(candidateFile)) {
                        throw IOException("Источник вернул не видео")
                    }
                    installCandidate(candidateFile, outFile)
                    downloaded = true
                    persistSkipTimings(outFile, video, candidates)
                    Log.i(TAG, "Download completed with host=${safeHost(video.url)}")
                } catch (cancelled: DownloadCancelledException) {
                    cleanupCandidate(candidateFile)
                    throw cancelled
                } catch (error: Exception) {
                    cleanupCandidate(candidateFile)
                    val reason = error.message ?: error.javaClass.simpleName
                    failures += "${safeHost(video.url)}: $reason"
                    Log.w(TAG, "Candidate failed: host=${safeHost(video.url)} reason=$reason")
                }
            }

            checkCancelled(jobId)
            if (!downloaded || !MediaFileValidator.isPlayableMp4(outFile)) {
                throw IOException(
                    failures.take(3).joinToString(
                        prefix = "Ни один источник не отдал видео: ",
                        separator = "; ",
                    )
                )
            }
            MediaJobBus.clearCancel(jobId)
            MediaJobBus.update(MediaJobProgress(jobId, "success", 100, outFile.absolutePath))
            Result.success(workDataOf(KEY_FILE_PATH to outFile.absolutePath))
        } catch (_: DownloadCancelledException) {
            cleanupCandidate(candidateFile(outFile))
            MediaJobBus.clearCancel(jobId)
            MediaJobBus.update(MediaJobProgress(jobId, "cancelled"))
            Result.failure()
        } catch (error: Exception) {
            Log.e(TAG, "Download failed for episode $episode", error)
            cleanupCandidate(candidateFile(outFile))
            if (!MediaFileValidator.isPlayableMp4(outFile)) {
                runCatching { outFile.delete() }
                DownloadedSkipStore.delete(outFile)
            }
            fail(jobId, error.message ?: "Ошибка загрузки")
        }
    }

    private fun rehydrateCredentials(video: VetroVideo): VetroVideo =
        PersonalMediaServerProvider.entries.fold(
            video.rehydrateWebDavCredentials(playbackCredentials.webDav())
        ) { hydrated, provider ->
            hydrated.rehydratePersonalServerCredentials(
                provider,
                playbackCredentials.personalServer(provider),
            )
        }

    private fun downloadSeekableHls(
        video: VetroVideo,
        destination: File,
        onProgress: (Int) -> Unit,
    ) {
        val transport = File(
            destination.parentFile,
            destination.nameWithoutExtension + ".transport.ts",
        )
        runCatching { transport.delete() }
        val cancelled = {
            val requested =
                MediaJobBus.isCancelRequested(inputData.getString(KEY_JOB_ID).orEmpty()) || isStopped
            if (requested) throw DownloadCancelledException()
            false
        }
        try {
            HlsSegmentDownloader(downloadClient.forPlaybackCandidate(video)).download(
                video = video,
                destination = transport,
                onProgress = { onProgress((it * 9) / 10) },
                isCancelled = cancelled,
            )
            onProgress(92)
            NativeMediaRemuxer.remuxToMp4(
                source = transport,
                destination = destination,
                isCancelled = cancelled,
            )
            onProgress(100)
        } finally {
            runCatching { transport.delete() }
        }
    }

    /**
     * A dropped connection resumes with `Range` instead of restarting the episode. Servers that
     * ignore the header answer 200 and the partial file is discarded, so a resume can never splice
     * two different byte offsets together.
     */
    private fun downloadProgressive(
        video: VetroVideo,
        destination: File,
        jobId: String,
    ) {
        val tmp = File(destination.parentFile, destination.nameWithoutExtension + ".part.mp4")
        runCatching { tmp.delete() }

        try {
            var completed = 0L
            var lastError: IOException? = null
            var succeeded = false
            repeat(PROGRESSIVE_ATTEMPTS) { attempt ->
                if (succeeded) return@repeat
                checkCancelled(jobId)
                try {
                    completed = fetchProgressive(video, tmp, jobId, resumeFrom = completed)
                    succeeded = true
                } catch (cancelled: DownloadCancelledException) {
                    throw cancelled
                } catch (error: IOException) {
                    lastError = error
                    completed = tmp.length()
                    Log.w(
                        TAG,
                        "progressive attempt ${attempt + 1}/$PROGRESSIVE_ATTEMPTS failed at " +
                            "$completed bytes: ${error.message ?: error.javaClass.simpleName}",
                    )
                    if (attempt < PROGRESSIVE_ATTEMPTS - 1) Thread.sleep(RETRY_BACKOFF_MS)
                }
            }
            if (!succeeded) {
                throw lastError ?: IOException("Загрузка не удалась")
            }
            if (!MediaFileValidator.isPlayableMp4(tmp)) {
                throw IOException("Загруженный ответ не является MP4 (${tmp.length()} байт)")
            }
            if (!tmp.renameTo(destination)) {
                tmp.copyTo(destination, overwrite = true)
                if (!tmp.delete()) Log.w(TAG, "Could not delete ${tmp.name}")
            }
        } catch (error: Exception) {
            runCatching { tmp.delete() }
            throw error
        }
    }

    /** Streams the body into [tmp] and returns how many bytes the file holds afterwards. */
    private fun fetchProgressive(
        video: VetroVideo,
        tmp: File,
        jobId: String,
        resumeFrom: Long,
    ): Long {
        val request = Request.Builder().url(video.url).apply {
            SanitizeHeaders.sanitize(video.headers).forEach { (name, value) ->
                header(name, value)
            }
            if (resumeFrom > 0L) {
                header("Range", "bytes=$resumeFrom-")
            } else if (safeHost(video.url).endsWith("jut.su")) {
                header("Range", "bytes=0-")
            }
        }.build()

        downloadClient.forPlaybackCandidate(video).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} при загрузке")
            }
            val body = response.body ?: throw IOException("Пустой ответ сервера")
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
                throw IOException("Сервер вернул $contentType вместо видео")
            }
            val resuming = resumeFrom > 0L && response.code == 206
            if (!resuming && tmp.exists() && !tmp.delete()) {
                throw IOException("Не удалось очистить частичную загрузку")
            }
            val alreadyOnDisk = if (resuming) resumeFrom else 0L
            val remaining = body.contentLength().takeIf { it > 0L }
            val total = remaining?.plus(alreadyOnDisk)
            if (total != null && total < MIN_PLAUSIBLE_VIDEO_BYTES) {
                throw IOException("Ответ слишком мал для видео: $total байт")
            }
            var copied = alreadyOnDisk
            var lastPercent = -1
            body.byteStream().use { input ->
                java.io.FileOutputStream(tmp, resuming).buffered(COPY_BUFFER_BYTES).use { output ->
                    // 64 КБ вместо 8: в восемь раз меньше системных вызовов на серию в сотни МБ.
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        checkCancelled(jobId)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        total?.let {
                            val percent = ((copied * 100L) / it).toInt().coerceIn(0, 99)
                            // Раньше публиковалось на каждом чтении — десятки тысяч событий на
                            // серию, каждое будило подписчиков шины; процент меняется ≤100 раз.
                            if (percent != lastPercent) {
                                lastPercent = percent
                                MediaJobBus.update(
                                    MediaJobProgress(jobId, "downloading", percent)
                                )
                            }
                        }
                    }
                }
            }
            if (total != null && copied < total) {
                throw IOException("Загрузка оборвана: $copied из $total байт")
            }
            return copied
        }
    }

    /**
     * Opening/ending timings travel with the candidates the resolver produced — AniLiberty measures
     * them per episode, jut.su contributes a reference for every other studio (see
     * `withPropagatedSkipReference`). Storing them beside the file is what lets autoskip work on a
     * downloaded episode without network.
     *
     * The downloaded candidate speaks first; a sibling rendition of the same episode is an equally
     * valid source when the winner carries nothing.
     */
    private fun persistSkipTimings(
        outFile: File,
        downloaded: VetroVideo,
        candidates: List<VetroVideo>,
    ) {
        val ordered = listOf(downloaded) + candidates
        val measured = ordered.firstOrNull { it.timestamps.isNotEmpty() }
        val skip = DownloadedEpisodeSkip(
            timestamps = measured?.timestamps.orEmpty(),
            origin = measured?.sourceName,
            reference = ordered.firstNotNullOfOrNull { it.skipReference },
        )
        DownloadedSkipStore.save(outFile, skip)
        Log.i(
            TAG,
            "Skip timings for ${outFile.name}: exact=${skip.timestamps.size} " +
                "origin=${skip.origin ?: "none"} reference=${skip.reference?.origin ?: "none"}",
        )
    }

    private fun installCandidate(candidate: File, outFile: File) {
        if (outFile.exists() && !outFile.delete()) {
            throw IOException("Не удалось заменить старый файл")
        }
        if (!candidate.renameTo(outFile)) {
            candidate.copyTo(outFile, overwrite = true)
            if (!candidate.delete()) Log.w(TAG, "Could not delete ${candidate.name}")
        }
    }

    private fun candidateFile(outFile: File): File =
        File(outFile.parentFile, outFile.nameWithoutExtension + ".candidate.mp4")

    private fun cleanupCandidate(candidate: File) {
        runCatching { candidate.delete() }
        runCatching {
            File(candidate.parentFile, candidate.nameWithoutExtension + ".part.mp4").delete()
        }
    }

    private fun checkCancelled(jobId: String) {
        if (MediaJobBus.isCancelRequested(jobId) || isStopped) {
            throw DownloadCancelledException()
        }
    }

    private fun fail(jobId: String, message: String): Result {
        MediaJobBus.update(MediaJobProgress(jobId, "failed", error = message))
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Загрузки видео",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Прогресс загрузки эпизодов"
                setShowBadge(false)
            }
        )
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, String>>(raw)
        }.getOrElse { emptyMap() }
    }

    private fun parseCandidates(raw: String): List<VetroVideo> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<VetroVideo>>(raw)
        }.onFailure {
            Log.w(TAG, "Could not decode media candidates: ${it.message}")
        }.getOrElse { emptyList() }
    }

    private fun resolutionFromLabel(label: String): Int? =
        Regex("""(?i)\b(\d{3,4})p\b""")
            .find(label)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun stableNotificationId(jobId: String): Int =
        (jobId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)

    private fun safeHost(url: String): String =
        runCatching { URI(url).host.orEmpty() }.getOrDefault("unknown")

    private class DownloadCancelledException : Exception()

    companion object {
        private const val TAG = "MediaDownloadWorker"
        private const val CHANNEL_ID = "vetro_media_download"
        private const val MIN_PLAUSIBLE_VIDEO_BYTES = 128 * 1024L
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val PROGRESSIVE_ATTEMPTS = 4
        private const val RETRY_BACKOFF_MS = 1_000L

        const val KEY_JOB_ID = "job_id"
        const val KEY_URL = "url"
        const val KEY_LABEL = "label"
        const val KEY_OUT_DIR = "out_dir"
        const val KEY_EPISODE = "episode"
        const val KEY_ANIME_ID = "anime_id"
        const val KEY_QUALITY = "quality"
        const val KEY_DURATION_SEC = "duration_sec"
        const val KEY_HEADERS_JSON = "headers_json"
        const val KEY_CANDIDATES_JSON = "candidates_json"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_ERROR = "error"
        const val WORK_NAME = "vetro_media_download"
    }
}
