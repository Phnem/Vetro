package com.example.myapplication.data.local

import android.content.Context
import com.example.myapplication.domain.seasons.SeasonEpisodesEntry
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Расклад сериалов (SERIES) по сезонам по данным TMDB — ключ animeId, значение в той же модели,
 * что у аниме ([SeasonEpisodesStore]), чтобы главная читала обе коллекции одинаково.
 *
 * Отдельный файл, а не общий стор с аниме: тот обслуживает резолвер франшиз и догон сезонов,
 * которые чистят «чужие» ключи ([SeasonEpisodesStore.retainOnly] по списку аниме) и перерезолвят
 * запись по своим правилам. Здесь запись целиком перезаписывает проверка новых серий сериалов.
 */
class SeriesSeasonsStore(context: Context) {

    private val store = JsonMapFileStore(
        File(context.filesDir, CACHE_FILE),
        SeasonEpisodesEntry.serializer(),
        TAG,
    )

    val flow: StateFlow<Map<String, SeasonEpisodesEntry>> = store.flow

    suspend fun ensureLoaded() = store.ensureLoaded()

    fun entryFor(animeId: String): SeasonEpisodesEntry? = store[animeId]

    suspend fun put(entry: SeasonEpisodesEntry) {
        store.update { map -> map + (entry.animeId to entry) }
    }

    /** Убрать записи тайтлов, которых больше нет среди сериалов коллекции. */
    suspend fun retainOnly(existingIds: Set<String>) {
        store.update { map -> map.filterKeys { it in existingIds } }
    }

    private companion object {
        const val TAG = "SeriesSeasonsStore"
        const val CACHE_FILE = "series_seasons_cache_v1.json"
    }
}
