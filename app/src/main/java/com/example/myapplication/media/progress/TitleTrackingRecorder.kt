package com.example.myapplication.media.progress

import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.local.SeriesSeasonsStore
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.domain.seasons.isAiringNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Receives opens from both players, including next-episode navigation. No network work. */
class TitleTrackingRecorder(
    private val tracking: TitleTrackingStore,
    private val local: AnimeLocalDataSource,
    private val animeSeasons: SeasonEpisodesStore,
    private val seriesSeasons: SeriesSeasonsStore,
) {
    suspend fun opened(animeId: String, season: Int, episode: Int) = withContext(Dispatchers.IO) {
        val anime = local.getAnimeById(animeId) ?: return@withContext
        if (anime.mediaType == MediaType.MANGA) return@withContext
        animeSeasons.ensureLoaded()
        seriesSeasons.ensureLoaded()
        val entry = if (anime.mediaType == MediaType.SERIES) seriesSeasons.flow.value[animeId]
            else animeSeasons.flow.value[animeId]
        // Anime.episodes is the collection's progress counter, not proof of a final episode.
        // Without a resolved layout only a standalone movie has a known single-episode end.
        val bounds = episodeBounds(entry?.seasons.orEmpty(), if (anime.mediaType == MediaType.MOVIE) 1 else 0,
            local.getAiringProgressSnapshot()[animeId]?.isAiringNow() == true)
        val key = PlaybackEpisodeKey(season, episode)
        if (tracking.recordOpened(animeId, key == bounds.first, key == bounds.second, LocalDate.now())) {
            local.setWatchStatus(animeId, WatchStatus.COMPLETED)
        }
    }
}

/** Specials do not move a franchise's start/end. An ongoing season has no final episode yet. */
internal fun episodeBounds(
    seasons: List<SeasonInfo>,
    fallbackEpisodes: Int,
    airing: Boolean,
): Pair<PlaybackEpisodeKey, PlaybackEpisodeKey?> {
    val regular = seasons.filter { !it.isSpecial }.sortedBy { it.seasonNumber }
    val first = PlaybackEpisodeKey(regular.firstOrNull()?.seasonNumber ?: 1, 1)
    val lastSeason = regular.lastOrNull()
    val last = if (lastSeason != null) {
        lastSeason.takeIf { !airing && !it.ongoing && it.episodes > 0 && (it.totalEpisodes == null || it.episodes >= it.totalEpisodes) }
            ?.let { PlaybackEpisodeKey(it.seasonNumber, it.episodes) }
    } else {
        fallbackEpisodes.takeIf { it > 0 && !airing }?.let { PlaybackEpisodeKey(1, it) }
    }
    return first to last
}
