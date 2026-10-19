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
 * Провайдер балансера CDNVideoHub (CVH) для VFmodule.
 */
class CvhProvider(
    private val resolver: CvhStreamResolver,
    private val vkIdLookup: (suspend (PlaybackRequest) -> String?)? = null,
) : MovieSeriesStreamingProvider {
    override val id = ProviderId("cvh")
    override val displayName = "CDNVideoHub"
    override val capabilities = setOf(
        ProviderCapability.MOVIE,
        ProviderCapability.SERIES,
        ProviderCapability.RU,
        ProviderCapability.HLS,
        ProviderCapability.DIRECT,
        ProviderCapability.KINOPOISK_ID,
    )

    override suspend fun resolve(request: PlaybackRequest): ProviderResolution = resolveTyped(displayName) {
        if (request.mediaType != MediaType.MOVIE && request.mediaType != MediaType.SERIES) {
            return@resolveTyped ProviderResolution.Unsupported
        }

        val season = if (request.mediaType == MediaType.SERIES) request.seasonNumber else null
        val episode = if (request.mediaType == MediaType.SERIES) request.episodeNumber else null

        // Поиск VK ID: через переданный vkIdLookup, из идентификатора аниме или через plapi по Kinopoisk ID
        val vkId = vkIdLookup?.invoke(request)
            ?: extractVkIdFromAnime(request)
            ?: request.kinopoiskId?.let { kp -> resolver.lookupVkIdByKinopoisk(kp, season, episode) }
            ?: return@resolveTyped ProviderResolution.NotFound

        val videos = resolver.resolveByVkId(vkId)
        if (videos.isEmpty()) {
            return@resolveTyped ProviderResolution.NotFound
        }

        ProviderResolution.Found(
            hosters = listOf(
                VetroHoster(
                    name = displayName,
                    url = "${CvhStreamResolver.PLAPI_BASE}/video/$vkId",
                    videos = videos,
                ),
            ),
            accuracy = if (request.kinopoiskId != null) MatchAccuracy.KINOPOISK_ID else MatchAccuracy.TITLE_ONLY,
            language = AppLanguage.RU,
        )
    }

    private fun extractVkIdFromAnime(request: PlaybackRequest): String? {
        val rawId = request.anime.id
        if (rawId.startsWith("vk:") || rawId.startsWith("cvh:")) {
            return rawId.substringAfter(':')
        }
        return null
    }
}
