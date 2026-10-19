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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Провайдер балансера Alloha для VFmodule.
 */
class AllohaProvider(
    private val extractor: AllohaExtractor,
    private val playersClient: KinoboxPlayersClient? = null,
    private val tokenProvider: (() -> String?)? = null,
) : MovieSeriesStreamingProvider {
    override val id = ProviderId("alloha")
    override val displayName = "Alloha"
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

        // 1. Если настроен токен Alloha API, пробуем прямой запрос
        val token = tokenProvider?.invoke()
        if (!token.isNullOrBlank()) {
            val apiData = extractor.queryApi(
                token = token,
                kinopoiskId = request.kinopoiskId,
                imdbId = request.imdbId,
                title = request.anime.titleRu ?: request.anime.title,
            )
            val dataObj = apiData?.get("data") as? JsonObject
            val iframeFromApi = (dataObj?.get("iframe") as? JsonPrimitive)?.contentOrNull

            if (!iframeFromApi.isNullOrBlank()) {
                val videos = extractor.extractFromIframe(iframeFromApi, season, episode)
                if (videos.isNotEmpty()) {
                    return@resolveTyped ProviderResolution.Found(
                        hosters = listOf(VetroHoster(name = displayName, url = iframeFromApi, videos = videos)),
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
        }

        // 2. Иначе поиск плеера через playersClient
        val players = playersClient?.fetchPlayers(
            kinopoiskId = request.kinopoiskId,
            imdbId = request.imdbId,
            tmdbId = request.tmdbId,
            title = request.anime.titleRu ?: request.anime.title,
        ).orEmpty()

        val allohaPlayer = players.firstOrNull { it.type == "alloha" }
        val iframeUrl = allohaPlayer?.iframeUrl

        if (iframeUrl.isNullOrBlank()) {
            return@resolveTyped ProviderResolution.NotFound
        }

        val videos = extractor.extractFromIframe(iframeUrl, season, episode)
        if (videos.isEmpty()) {
            return@resolveTyped ProviderResolution.NotFound
        }

        val hosterName = if (!allohaPlayer.translation.isNullOrBlank()) {
            "$displayName · ${allohaPlayer.translation}"
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
