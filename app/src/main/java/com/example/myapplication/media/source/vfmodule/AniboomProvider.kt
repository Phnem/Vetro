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
 * Провайдер видеохостинга AniBoom для VFmodule.
 */
class AniboomProvider(
    private val extractor: AniboomExtractor,
    private val playersClient: KinoboxPlayersClient? = null,
) : MovieSeriesStreamingProvider {
    override val id = ProviderId("aniboom")
    override val displayName = "AniBoom"
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
        val players = playersClient?.fetchPlayers(
            kinopoiskId = request.kinopoiskId,
            imdbId = request.imdbId,
            tmdbId = request.tmdbId,
            title = request.anime.titleRu ?: request.anime.title,
        ).orEmpty()

        val player = players.firstOrNull { it.type == "aniboom" }
        val iframeUrl = player?.iframeUrl

        if (iframeUrl.isNullOrBlank()) {
            return@resolveTyped ProviderResolution.NotFound
        }

        val videos = extractor.extract(iframeUrl)
        if (videos.isEmpty()) {
            return@resolveTyped ProviderResolution.NotFound
        }

        val hosterName = if (!player.translation.isNullOrBlank()) {
            "$displayName · ${player.translation}"
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
