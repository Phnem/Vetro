package com.example.myapplication.data.local

import android.content.Context
import com.example.myapplication.domain.seasons.SeasonEpisodesEntry
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Файловый кэш «серии по сезонам» (filesDir, atomic rename) — по образцу [WebLinksStore],
 * без миграции схемы SQLDelight. Ключ — animeId. UI (Details) подписывается на [flow].
 *
 * TTL двухуровневый: полная запись (все сезоны с числом серий, без онгоингов) живёт
 * [COMPLETE_TTL_MILLIS]; неполная или с онгоингом — [INCOMPLETE_TTL_MILLIS], т.е. фоновые
 * проходы будут долбить источники, пока не соберут всё («гарантированный» резолв).
 */
class SeasonEpisodesStore(context: Context) {

    private val store = JsonMapFileStore(
        File(context.filesDir, CACHE_FILE),
        SeasonEpisodesEntry.serializer(),
        TAG,
    )

    val flow: StateFlow<Map<String, SeasonEpisodesEntry>> = store.flow

    suspend fun ensureLoaded() = store.ensureLoaded()

    fun entryFor(animeId: String): SeasonEpisodesEntry? = store[animeId]

    /** Свежа ли запись с учётом двухуровневого TTL (см. kdoc класса). */
    fun isFresh(animeId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val e = store[animeId] ?: return false
        if (e.resolvedAt <= 0) return false
        // Запись, собранная прежней версией резолвера, показывает неверный расклад — перерезолвить
        // сразу, не дожидаясь месячного TTL «полных» записей.
        if (e.schema < SeasonEpisodesEntry.CURRENT_SCHEMA) return false
        val ttl = if (e.complete) COMPLETE_TTL_MILLIS else INCOMPLETE_TTL_MILLIS
        return nowMillis - e.resolvedAt <= ttl
    }

    suspend fun put(entry: SeasonEpisodesEntry) {
        store.update { map ->
            // Отметка о догоне принадлежит тайтлу, а не конкретному резолву: резолвер и
            // discovery собирают запись с нуля и о ней не знают, а затереть её нулём значит
            // снять паузу и пустить каскад по кругу.
            val kept = map[entry.animeId]?.lastCatchUpAt ?: 0L
            val merged = if (entry.lastCatchUpAt == 0L) entry.copy(lastCatchUpAt = kept) else entry
            map + (entry.animeId to merged)
        }
    }

    /** Отметить, что догон расклада по тайтлу только что запускался (см. [SeasonEpisodesEntry.lastCatchUpAt]). */
    suspend fun markCatchUp(animeId: String, atMillis: Long = System.currentTimeMillis()) {
        store.update { map ->
            val entry = map[animeId] ?: return@update map
            map + (animeId to entry.copy(lastCatchUpAt = atMillis))
        }
    }

    /** Убрать записи тайтлов, которых больше нет в коллекции. */
    suspend fun retainOnly(existingIds: Set<String>) {
        store.update { map -> map.filterKeys { it in existingIds } }
    }

    companion object {
        private const val TAG = "SeasonEpisodesStore"
        private const val CACHE_FILE = "season_episodes_cache_v1.json"
        /** Полный расклад завершённых сезонов меняется только с анонсом нового — месяц. */
        const val COMPLETE_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
        /** Неполный/онгоинг — перепроверяем ежедневно, пока не соберём всё. */
        const val INCOMPLETE_TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}
