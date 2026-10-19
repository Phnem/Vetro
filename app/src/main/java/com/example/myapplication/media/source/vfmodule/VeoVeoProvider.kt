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
 * Провайдер балансера VeoVeo для VFmodule.
 */
class VeoVeoProvider(
    private val extractor: VeoVeoExtractor,
    private val playersClient: KinoboxPlayersClient? = null,
) : MovieSeriesStreamingProvider {
    override val id = ProviderId("veoveo")
    override val displayName = "VeoVeo"
    override val capabilities = setOf(
        ProviderCapability.MOVIE,
        ProviderCapability.SERIES,
        ProviderCapability.RU,
        ProviderCapability.HLS,
        ProviderCapability.DIRECT,
        ProviderCapability.KINOPOISK_ID,
        ProviderCapability.IMDB_ID,
        ProviderCapability.TMDB_ID,
    )

    override suspend fun resolve(request: PlaybackRequest): ProviderResolution = resolveTyped(displayName) {
        if (request.mediaType != MediaType.MOVIE && request.mediaType != MediaType.SERIES) {
            return@resolveTyped ProviderResolution.Unsupported
        }

        val episode = if (request.mediaType == MediaType.SERIES) request.episodeNumber else null
        val season = if (request.mediaType == MediaType.SERIES) request.seasonNumber else null

        val players = playersClient?.fetchPlayers(
            kinopoiskId = request.kinopoiskId,
            imdbId = request.imdbId,
            tmdbId = request.tmdbId,
            title = request.anime.titleRu ?: request.anime.title,
        ).orEmpty()

        val veoveoPlayer = players.firstOrNull { it.type == "veoveo" }
        val iframeUrl = veoveoPlayer?.iframeUrl

        if (iframeUrl.isNullOrBlank()) {
            return@resolveTyped ProviderResolution.NotFound
        }

        val videos = extractor.extractFromIframe(iframeUrl, season, episode)
        if (videos.isEmpty()) {
            return@resolveTyped ProviderResolution.NotFound
        }

        val hosterName = if (!veoveoPlayer.translation.isNullOrBlank()) {
            "$displayName · ${veoveoPlayer.translation}"
        } else {
            displayName
        }

        ProviderResolution.Found(
            hosters = listOf(VetroHoster(name = hosterName, url = iframeUrl, videos = videos)),
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
