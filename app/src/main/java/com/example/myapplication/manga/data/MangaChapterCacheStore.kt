package com.example.myapplication.manga.data

import android.content.Context
import com.example.myapplication.data.local.JsonMapFileStore
import com.example.myapplication.manga.domain.MangaChapter
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
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

    private val store = JsonMapFileStore(File(context.filesDir, CACHE_FILE), CachedChapters.serializer(), TAG)

    /**
     * Оглавления реактивно: карточка главного экрана считает по ним прогресс чтения и не должна
     * узнавать об изменении списка глав только при перезапуске.
     */
    val flow: StateFlow<Map<String, CachedChapters>> = store.flow

    suspend fun ensureLoaded() = store.ensureLoaded()

    fun entry(sourceId: String, mangaKey: String): CachedChapters? = store[key(sourceId, mangaKey)]

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
        store.update { map ->
            val entry = CachedChapters(
                sourceId = sourceId,
                mangaKey = mangaKey,
                chapters = chapters,
                resolvedAt = System.currentTimeMillis(),
            )
            (map + (key(sourceId, mangaKey) to entry)).pruneToLimit()
        }
    }

    suspend fun remove(sourceId: String, mangaKey: String) {
        store.update { it - key(sourceId, mangaKey) }
    }

    /** Убрать оглавления тайтлов, привязки к которым больше нет. */
    suspend fun retainOnly(activeKeys: Set<Pair<String, String>>) {
        val allowed = activeKeys.map { (source, manga) -> key(source, manga) }.toSet()
        store.update { map -> map.filterKeys { it in allowed } }
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

    private fun key(sourceId: String, mangaKey: String) = "$sourceId::$mangaKey"

    private companion object {
        const val TAG = "MangaChapterCache"
        const val CACHE_FILE = "manga_chapters_v1.json"
        /** Онгоинги обновляются часто — держим оглавление свежим в пределах часа. */
        const val TTL_MILLIS = 60L * 60 * 1000
        const val MAX_ENTRIES = 60
    }
}
