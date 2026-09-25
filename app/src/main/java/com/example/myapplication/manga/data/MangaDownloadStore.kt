package com.example.myapplication.manga.data

import com.example.myapplication.network.AppJson
import android.content.Context
import android.util.Log
import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.security.MessageDigest

@Serializable
data class DownloadedChapter(
    val sourceId: String,
    val mangaKey: String,
    val chapterKey: String,
    /** Имена файлов страниц по порядку чтения, относительно каталога главы. */
    val pageFiles: List<String>,
    val bytes: Long,
    val downloadedAt: Long,
)

/** Прогресс активной загрузки: сколько страниц уже на диске из скольких. */
data class DownloadProgress(val downloaded: Int, val total: Int) {
    val fraction: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
}

/**
 * Скачанные главы: раскладка файлов на диске + индекс.
 *
 * Каталог главы — `filesDir/manga_downloads/{источник}/{хэш тайтла}/{хэш главы}`. Хэши потому,
 * что ключи источников — это slug'и и uuid'ы вперемешку, и складывать их в имена файлов напрямую
 * нельзя (слэши, юникод, длина).
 *
 * Индекс — единственный источник правды о том, что скачано: каталог с недокачанными страницами
 * в индекс не попадает и при следующей загрузке просто перезаписывается.
 */
class MangaDownloadStore(context: Context) {

    private val root = File(context.filesDir, ROOT_DIR)
    private val file = File(context.filesDir, INDEX_FILE)
    private val json = AppJson
    private val mutex = Mutex()
    @Volatile private var loaded = false

    private val _downloaded = MutableStateFlow<Map<String, DownloadedChapter>>(emptyMap())
    val downloaded: StateFlow<Map<String, DownloadedChapter>> = _downloaded.asStateFlow()

    private val _active = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val active: StateFlow<Map<String, DownloadProgress>> = _active.asStateFlow()

    private val serializer = MapSerializer(String.serializer(), DownloadedChapter.serializer())

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val map = withContext(Dispatchers.IO) {
                runCatching {
                    if (!file.exists()) emptyMap() else json.decodeFromString(serializer, file.readText())
                }.getOrElse {
                    Log.w(TAG, "Failed to read download index", it)
                    emptyMap()
                }
            }
            _downloaded.value = map
            loaded = true
        }
    }

    fun isDownloaded(chapter: MangaChapter): Boolean =
        _downloaded.value.containsKey(key(chapter.sourceId.value, chapter.key))

    /** Локальные страницы главы или null, если скачана не она. Пропавшие файлы = кэша нет. */
    fun localPages(chapter: MangaChapter): List<MangaPage>? {
        val entry = _downloaded.value[key(chapter.sourceId.value, chapter.key)] ?: return null
        val dir = chapterDir(chapter.sourceId.value, chapter.mangaKey, chapter.key)
        val pages = entry.pageFiles.mapIndexedNotNull { index, name ->
            File(dir, name).takeIf { it.exists() }?.let { page ->
                MangaPage(index = index, url = "file://${page.absolutePath}")
            }
        }
        return pages.takeIf { it.size == entry.pageFiles.size }
    }

    fun chapterDir(sourceId: String, mangaKey: String, chapterKey: String): File =
        File(File(File(root, sourceId.sanitized()), mangaKey.hashed()), chapterKey.hashed())

    fun progressOf(chapter: MangaChapter): DownloadProgress? =
        _active.value[key(chapter.sourceId.value, chapter.key)]

    fun onProgress(chapter: MangaChapter, downloaded: Int, total: Int) {
        _active.value = _active.value + (
            key(chapter.sourceId.value, chapter.key) to DownloadProgress(downloaded, total)
            )
    }

    fun onFinished(chapter: MangaChapter) {
        _active.value = _active.value - key(chapter.sourceId.value, chapter.key)
    }

    suspend fun put(entry: DownloadedChapter) {
        ensureLoaded()
        mutex.withLock {
            val map = _downloaded.value.toMutableMap().apply {
                put(key(entry.sourceId, entry.chapterKey), entry)
            }
            _downloaded.value = map
            persist(map)
        }
    }

    /**
     * Удаление скачанной главы: сначала индекс, потом файлы. Обратный порядок оставил бы после
     * краша запись о главе, файлов которой уже нет, — ридер открыл бы пустоту.
     */
    suspend fun delete(chapter: MangaChapter) {
        ensureLoaded()
        mutex.withLock {
            val entryKey = key(chapter.sourceId.value, chapter.key)
            if (!_downloaded.value.containsKey(entryKey)) return
            val map = _downloaded.value - entryKey
            _downloaded.value = map
            persist(map)
        }
        withContext(Dispatchers.IO) {
            chapterDir(chapter.sourceId.value, chapter.mangaKey, chapter.key).deleteRecursively()
        }
    }

    /** Снести все главы тайтла у источника — например, когда пользователь сменил привязку. */
    suspend fun deleteAll(sourceId: String, mangaKey: String) {
        ensureLoaded()
        val victims = _downloaded.value.values
            .filter { it.sourceId == sourceId && it.mangaKey == mangaKey }
        if (victims.isEmpty()) return
        mutex.withLock {
            val victimKeys = victims.map { key(it.sourceId, it.chapterKey) }.toSet()
            val map = _downloaded.value - victimKeys
            _downloaded.value = map
            persist(map)
        }
        withContext(Dispatchers.IO) {
            victims.forEach { chapterDir(it.sourceId, it.mangaKey, it.chapterKey).deleteRecursively() }
        }
    }

    private suspend fun persist(map: Map<String, DownloadedChapter>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, "$INDEX_FILE.tmp")
            tmp.writeText(json.encodeToString(serializer, map))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Failed to write download index", it) }
    }

    private fun key(sourceId: String, chapterKey: String) = "$sourceId::$chapterKey"

    private fun String.sanitized(): String = replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun String.hashed(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "MangaDownloadStore"
        const val ROOT_DIR = "manga_downloads"
        const val INDEX_FILE = "manga_downloads_v1.json"
    }
}
