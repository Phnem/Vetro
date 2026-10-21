package com.example.myapplication.media.source.vftmodule

import android.content.Context
import com.example.myapplication.audiobooks.torrent.TorrentEngine
import com.example.myapplication.audiobooks.torrent.TorrentLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException

/**
 * Расширение движка [TorrentEngine] для видео (VFTmodule).
 * Анализирует структуру торрент-раздачи, находит видеофайлы, сопоставляет серии/сезоны
 * и организует последовательное скачивание кусков для потокового воспроизведения.
 */
class VftTorrentEngine(
    private val engine: TorrentEngine,
    private val client: OkHttpClient,
    val rootDir: File,
) {
    constructor(context: Context, client: OkHttpClient) : this(
        engine = TorrentEngine(File(context.filesDir, "video/torrents").apply { mkdirs() }),
        client = client,
        rootDir = File(context.filesDir, "video/torrents").apply { mkdirs() },
    )

    /**
     * Загружает метаданные торрента по magnet или ссылке на .torrent.
     */
    suspend fun fetchMetadata(magnetOrUrl: String): TorrentInfo = withContext(Dispatchers.IO) {
        val link = if (magnetOrUrl.startsWith("magnet:", ignoreCase = true)) {
            TorrentLink.Magnet(magnetOrUrl)
        } else {
            TorrentLink.File(magnetOrUrl)
        }

        engine.metadata(link) { fileLink ->
            fetchTorrentFileBytes(fileLink.url)
        }
    }

    /**
     * Получает список всех файлов раздачи с определением видеофайлов и номеров серий.
     */
    fun listFiles(info: TorrentInfo): List<VftTorrentFile> {
        val files = info.files()
        val numFiles = info.numFiles()
        val result = mutableListOf<VftTorrentFile>()

        for (i in 0 until numFiles) {
            val path = files.filePath(i)
            val name = files.fileName(i)
            val size = files.fileSize(i)
            val isVideo = isVideoFile(name)

            result.add(
                VftTorrentFile(
                    index = i,
                    path = path,
                    name = name,
                    sizeBytes = size,
                    isVideo = isVideo,
                    season = if (isVideo) extractSeason(path) else null,
                    episode = if (isVideo) extractEpisode(path) else null,
                )
            )
        }

        return result
    }

    /**
     * Находит подходящий видеофайл внутри раздачи.
     * Для фильма — наибольший по размеру видеофайл.
     * Для сериала — файл, соответствующий сезону и номеру серии.
     */
    fun findTargetFile(files: List<VftTorrentFile>, season: Int?, episode: Int?): VftTorrentFile? {
        val videoFiles = files.filter { it.isVideo }
        if (videoFiles.isEmpty()) return null

        if (season != null && episode != null) {
            // 1. Точное совпадение по сезону и серии
            val exact = videoFiles.find { it.season == season && it.episode == episode }
            if (exact != null) return exact

            // 2. Совпадение по серии (если в раздаче один сезон и он не указан в названии файла)
            val byEp = videoFiles.find { it.episode == episode }
            if (byEp != null) return byEp
        } else if (episode != null) {
            val byEp = videoFiles.find { it.episode == episode }
            if (byEp != null) return byEp
        }

        // Если фильм или сериал из 1 файла — берём самый большой видеофайл
        return videoFiles.maxByOrNull { it.sizeBytes }
    }

    /**
     * Запускает последовательную загрузку выбранного видеофайла [fileIndex].
     * Все остальные файлы раздачи игнорируются, экономя трафик и место на диске.
     */
    fun startStreaming(info: TorrentInfo, fileIndex: Int): TorrentHandle {
        val handle = engine.ensure(info, setOf(fileIndex))
        engine.focus(handle, fileIndex)
        return handle
    }

    /**
     * Возвращает путь к скачиваемому файлу на диске.
     */
    fun fileOnDisk(handle: TorrentHandle, fileIndex: Int): File = engine.fileOnDisk(handle, fileIndex)

    /**
     * Возвращает закэшированный торрент по хэшу, если он уже известен.
     */
    fun getCachedInfo(infoHash: String): TorrentInfo? = engine.cached(infoHash)

    /**
     * Проверяет, поддерживается ли архитектура устройства libtorrent.
     */
    val isSupported: Boolean get() = TorrentEngine.isSupported

    private suspend fun fetchTorrentFileBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
    }

    companion object {
        private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "webm", "ts", "m4v", "flv")

        fun isVideoFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXT
        }

        fun extractSeason(path: String): Int? {
            val m = Regex("""(?:[sS](\d{1,2})[eE]|(?:\b|^)(?:s|season|сезон)[\s._-]*(\d{1,2}))""", RegexOption.IGNORE_CASE).find(path)
            return m?.groupValues?.get(1)?.toIntOrNull() ?: m?.groupValues?.get(2)?.toIntOrNull()
        }

        fun extractEpisode(path: String): Int? {
            val m = Regex("""(?:[sS]\d{1,2}[eE]|(?:\b|^)(?:e|ep|episode|серия)[\s._-]*)(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(path)
                ?: Regex("""\b\d{1,2}x(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(path)
                ?: Regex("""\b(?:-\s*|\b)(\d{1,3})\s*(?:\[|\.mkv|\.mp4|\.avi)""", RegexOption.IGNORE_CASE).find(path)
            return m?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}
