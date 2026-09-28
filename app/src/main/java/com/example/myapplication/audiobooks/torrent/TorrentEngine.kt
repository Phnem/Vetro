package com.example.myapplication.audiobooks.torrent

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo

/**
 * Торрент-движок аудиокниг (libtorrent). Раздача качается в папку приложения по порядку файлов,
 * слушать можно сразу: [TorrentDataSource] просит нужные куски с дедлайном и ждёт их. Скачанное
 * остаётся на устройстве — книга потом играет без сети.
 *
 * Отдача ограничена: протокол требует делиться, но трафик пользователя не должен уходить на чужие
 * загрузки.
 */
class TorrentEngine(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "audiobooks/torrents"))

    private val metaDir = File(root, ".meta").apply { mkdirs() }
    private val infos = ConcurrentHashMap<String, TorrentInfo>()

    private val session: SessionManager by lazy {
        SessionManager(false).apply {
            val settings = SettingsPack()
                .uploadRateLimit(UPLOAD_LIMIT_BYTES)
                .activeDownloads(3)
                .activeSeeds(2)
                .connectionsLimit(200)
            start(SessionParams(settings))
        }
    }

    /** Файл торрента — из кэша, по magnet (DHT и трекеры) или готовые байты `.torrent`. */
    suspend fun metadata(link: TorrentLink, fetchFile: suspend (TorrentLink.File) -> ByteArray?): TorrentInfo =
        withContext(Dispatchers.IO) {
            link.hash?.let { cached(it) }?.let { return@withContext it }
            val bytes = when (link) {
                is TorrentLink.Magnet -> session.fetchMagnet(link.uri, MAGNET_TIMEOUT_SEC, metaDir)
                is TorrentLink.File -> fetchFile(link)
            } ?: throw IOException("torrent metadata unavailable")
            val info = TorrentInfo(bytes)
            val hash = info.infoHash().toHex()
            File(metaDir, "$hash.torrent").writeBytes(bytes)
            infos[hash] = info
            info
        }

    /** Уже известный торрент по хешу (кэш в памяти или на диске). */
    fun cached(hash: String): TorrentInfo? {
        val key = hash.lowercase()
        infos[key]?.let { return it }
        val file = File(metaDir, "$key.torrent").takeIf { it.isFile } ?: return null
        return runCatching { TorrentInfo(file) }.getOrNull()?.also { infos[key] = it }
    }

    /**
     * Раздача запущена: аудиофайлы качаются по порядку ([audio] — их индексы), остальное (картинки,
     * тексты) — нет. Повторный вызов возвращает ту же раздачу.
     */
    @Synchronized
    fun ensure(info: TorrentInfo, audio: Set<Int>): TorrentHandle {
        val hash = info.infoHash()
        session.find(hash)?.takeIf { it.isValid }?.let { return it }
        val priorities = Array(info.numFiles()) { if (it in audio) Priority.DEFAULT else Priority.IGNORE }
        val dir = File(root, hash.toHex()).apply { mkdirs() }
        session.download(info, dir, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        return session.find(hash) ?: throw IOException("torrent did not start")
    }

    /** Файл [index] сейчас слушают: он важнее остальных. */
    fun focus(handle: TorrentHandle, index: Int) {
        if (handle.filePriority(index) != Priority.TOP_PRIORITY) handle.filePriority(index, Priority.TOP_PRIORITY)
    }

    /** Где на диске лежит файл раздачи (куски пишутся на свои места по мере загрузки). */
    fun fileOnDisk(handle: TorrentHandle, index: Int): File =
        File(handle.savePath(), handle.torrentFile().files().filePath(index))

    /** Раздача скачана целиком — её можно слушать без сети. */
    fun isComplete(hash: String): Boolean =
        runCatching { session.find(Sha1Hash.parseHex(hash))?.status()?.isFinished == true }.getOrDefault(false)

    companion object {
        private const val UPLOAD_LIMIT_BYTES = 64 * 1024
        private const val MAGNET_TIMEOUT_SEC = 45

        /**
         * Есть ли libtorrent под этот телефон. В APK движок только для arm64-v8a (и x86_64 в debug
         * для эмулятора): 32-битная сборка весила 13 МБ ради почти исчезнувших устройств.
         */
        val isSupported: Boolean by lazy {
            runCatching { System.loadLibrary("torrent4j") }.isSuccess
        }
    }
}

/** Где взять торрент: magnet (с хешем) или файл `.torrent` по ссылке. */
sealed interface TorrentLink {
    val hash: String?

    data class Magnet(val uri: String) : TorrentLink {
        override val hash: String? =
            Regex("""urn:btih:([0-9a-fA-F]{40})""").find(uri)?.groupValues?.get(1)?.lowercase()
    }

    data class File(val url: String, val headers: Map<String, String> = emptyMap(), override val hash: String? = null) : TorrentLink

    companion object {
        /** Magnet из хеша и трекеров со страницы (как у AudioBookBay). */
        fun magnet(hash: String, name: String?, trackers: List<String>): Magnet {
            val dn = name?.let { "&dn=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val tr = trackers.joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
            return Magnet("magnet:?xt=urn:btih:${hash.lowercase()}$dn$tr")
        }
    }
}
