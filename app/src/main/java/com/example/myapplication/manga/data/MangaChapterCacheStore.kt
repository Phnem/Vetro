package com.example.myapplication.manga.data

import com.example.myapplication.network.AppJson
import android.content.Context
import android.util.Log
import com.example.myapplication.manga.domain.MangaChapter
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

@Serializable
data class CachedChapters(
    val sourceId: String,
    val mangaKey: String,
    val chapters: List<MangaChapter>,
    val resolvedAt: Long,
)

/**
 * Файловый кэш оглавлений: открытие вкладки «Главы» не должно каждый раз ждать сеть.
 *
 * Работает по stale-while-revalidate — список отдаётся сразу, даже протухший, а обновление идёт
 * фоном. TTL короткий: онгоинги выкладывают главы часто, и увидеть новую спустя сутки хуже, чем
 * лишний запрос.
 *
 * Кэш привязан к паре (источник, ключ тайтла), а не к animeId: сменив привязку, пользователь
 * должен увидеть главы нового источника, а не остатки прежнего.
 */
class MangaChapterCacheStore(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val json = AppJson
    private val mutex = Mutex()
    @Volatile private var loaded = false

    private val _flow = MutableStateFlow<Map<String, CachedChapters>>(emptyMap())

    /**
     * Оглавления реактивно: карточка главного экрана считает по ним прогресс чтения и не должна
     * узнавать об изменении списка глав только при перезапуске.
     */
    val flow: StateFlow<Map<String, CachedChapters>> = _flow.asStateFlow()

    private var cache: Map<String, CachedChapters>
        get() = _flow.value
        set(value) { _flow.value = value }

    private val serializer = MapSerializer(String.serializer(), CachedChapters.serializer())

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            cache = withContext(Dispatchers.IO) {
                runCatching {
                    if (!file.exists()) emptyMap() else json.decodeFromString(serializer, file.readText())
                }.getOrElse {
                    Log.w(TAG, "Failed to read chapter cache", it)
                    emptyMap()
                }
            }
            loaded = true
        }
    }

    fun entry(sourceId: String, mangaKey: String): CachedChapters? = cache[key(sourceId, mangaKey)]

    /** Ключ записи в [flow] — чтобы подписчик искал оглавление по привязке, а не перебором. */
    fun entryKey(sourceId: String, mangaKey: String): String = key(sourceId, mangaKey)

    fun isFresh(
        sourceId: String,
        mangaKey: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val entry = entry(sourceId, mangaKey) ?: return false
        return nowMillis - entry.resolvedAt <= TTL_MILLIS
    }

    suspend fun put(sourceId: String, mangaKey: String, chapters: List<MangaChapter>) {
        if (chapters.isEmpty()) return
        ensureLoaded()
        mutex.withLock {
            val map = cache.toMutableMap().apply {
                put(
                    key(sourceId, mangaKey),
                    CachedChapters(
                        sourceId = sourceId,
                        mangaKey = mangaKey,
                        chapters = chapters,
                        resolvedAt = System.currentTimeMillis(),
                    ),
                )
            }
            cache = map.pruneToLimit()
            persist(cache)
        }
    }

    suspend fun remove(sourceId: String, mangaKey: String) {
        ensureLoaded()
        mutex.withLock {
            val entryKey = key(sourceId, mangaKey)
            if (!cache.containsKey(entryKey)) return
            cache = cache - entryKey
            persist(cache)
        }
    }

    /** Убрать оглавления тайтлов, привязки к которым больше нет. */
    suspend fun retainOnly(activeKeys: Set<Pair<String, String>>) {
        ensureLoaded()
        mutex.withLock {
            val allowed = activeKeys.map { (source, manga) -> key(source, manga) }.toSet()
            val map = cache.filterKeys { it in allowed }
            if (map.size != cache.size) {
                cache = map
                persist(map)
            }
        }
    }

    /**
     * Оглавление длинного тайтла — это сотни записей; без потолка файл растёт с каждой
     * просмотренной мангой. Выбрасываем самые давно обновлявшиеся.
     */
    private fun Map<String, CachedChapters>.pruneToLimit(): Map<String, CachedChapters> =
        if (size <= MAX_ENTRIES) {
            this
        } else {
            entries.sortedByDescending { it.value.resolvedAt }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        }

    private suspend fun persist(map: Map<String, CachedChapters>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(json.encodeToString(serializer, map))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Failed to write chapter cache", it) }
    }

    private fun key(sourceId: String, mangaKey: String) = "$sourceId::$mangaKey"

    private companion object {
        const val TAG = "MangaChapterCache"
        const val CACHE_FILE = "manga_chapters_v1.json"
        /** Онгоинги обновляются часто — держим оглавление свежим в пределах часа. */
        const val TTL_MILLIS = 60L * 60 * 1000
        const val MAX_ENTRIES = 60
    }
}
