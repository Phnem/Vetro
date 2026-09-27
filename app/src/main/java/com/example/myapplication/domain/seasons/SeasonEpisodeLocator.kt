package com.example.myapplication.domain.seasons

import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.local.SeriesSeasonsStore
import com.example.myapplication.data.models.AnimeUpdate

/**
 * Подписи «S3 E5» для системных уведомлений о новых сериях — вне UI, где нет потоков главной.
 * Читает те же источники, что карточка: расклад аниме, расклад сериалов и снимок выходящих сезонов.
 */
class SeasonEpisodeLocator(
    private val seasonEpisodesStore: SeasonEpisodesStore,
    private val seriesSeasonsStore: SeriesSeasonsStore,
    private val localDataSource: AnimeLocalDataSource,
) {
    /** animeId → подпись; тайтлы без расклада и номера сезона в карту не попадают. */
    suspend fun labelsFor(updates: List<AnimeUpdate>): Map<String, String> {
        if (updates.isEmpty()) return emptyMap()
        seasonEpisodesStore.ensureLoaded()
        seriesSeasonsStore.ensureLoaded()
        val airing = runCatching { localDataSource.getAiringProgressSnapshot() }.getOrElse { emptyMap() }
        return updates.mapNotNull { update ->
            val layout = seriesSeasonsStore.entryFor(update.animeId)
                ?: seasonEpisodesStore.entryFor(update.animeId)
            val latest = latestSeasonEpisode(layout, airing[update.animeId]) ?: return@mapNotNull null
            update.animeId to releasedEpisodesLabel(update.currentEpisodes, update.newEpisodes, latest)
        }.toMap()
    }
}
