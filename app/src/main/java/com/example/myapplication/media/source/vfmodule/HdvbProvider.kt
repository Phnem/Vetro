package com.example.myapplication.media.source.vfmodule

import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.PlaybackRequest
import com.example.myapplication.media.source.VetroHoster
import com.example.myapplication.media.source.movieseries.MatchAccuracy
import com.example.myapplication.media.source.movieseries.MovieSeriesStreamingProvider
import com.example.myapplication.media.source.movieseries.ProviderCapability
import com.example.myapplication.media.source.movieseries.ProviderId
import com.example.myapplication.media.source.movieseries.ProviderResolution
import com.example.myapplication.media.source.movieseries.resolveTyped
import com.example.myapplication.network.AppLanguage

/**
 * Провайдер балансера HDVB для VFmodule.
 */
class HdvbProvider(
    private val extractor: HdvbExtractor,
    private val playersClient: KinoboxPlayersClient? = null,
) : MovieSeriesStreamingProvider {
    override val id = ProviderId("hdvb")
    override val displayName = "HDVB"
    override val capabilities = setOf(
        ProviderCapability.MOVIE,
        ProviderCapability.SERIES,
        ProviderCapability.RU,
        ProviderCapability.HLS,
        ProviderCapability.KINOPOISK_ID,
        ProviderCapability.IMDB_ID,
        ProviderCapability.TMDB_ID,
    )

    override suspend fun resolve(request: PlaybackRequest): ProviderResolution = resolveTyped(displayName) {
        if (request.mediaType != MediaType.MOVIE && request.mediaType != MediaType.SERIES) {
            return@resolveTyped ProviderResolution.Unsupported
        }

        // 1. Поиск плеера через playersClient по Kinopoisk ID / IMDb / TMDB
        val players = playersClient?.fetchPlayers(
            kinopoiskId = request.kinopoiskId,
            imdbId = request.imdbId,
            tmdbId = request.tmdbId,
            title = request.anime.titleRu ?: request.anime.title,
        ).orEmpty()

        val hdvbPlayer = players.firstOrNull { it.type == "hdvb" }
        val iframeUrl = hdvbPlayer?.iframeUrl

        if (iframeUrl.isNullOrBlank()) {
            return@resolveTyped ProviderResolution.NotFound
        }

        val season = if (request.mediaType == MediaType.SERIES) request.seasonNumber else null
        val episode = if (request.mediaType == MediaType.SERIES) request.episodeNumber else null

        val videos = extractor.extract(iframeUrl, season = season, episode = episode)
        if (videos.isEmpty()) {
            return@resolveTyped ProviderResolution.NotFound
        }

        val hosterName = if (!hdvbPlayer.translation.isNullOrBlank()) {
            "$displayName · ${hdvbPlayer.translation}"
        } else {
            displayName
        }

        ProviderResolution.Found(
            hosters = listOf(
                VetroHoster(
                    name = hosterName,
                    url = iframeUrl,
                    videos = videos,
                ),
            ),
            accuracy = when {
                request.kinopoiskId != null -> MatchAccuracy.KINOPOISK_ID
                request.imdbId != null -> MatchAccuracy.IMDB_ID
                request.tmdbId != null -> MatchAccuracy.TMDB_ID
                else -> MatchAccuracy.TITLE_ONLY
            },
            language = AppLanguage.RU,
        )
    }
}
