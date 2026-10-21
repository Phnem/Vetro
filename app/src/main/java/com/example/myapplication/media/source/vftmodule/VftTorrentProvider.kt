package com.example.myapplication.media.source.vftmodule

import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.PlaybackRequest
import com.example.myapplication.media.source.VetroHoster
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.source.movieseries.MatchAccuracy
import com.example.myapplication.media.source.movieseries.MovieSeriesStreamingProvider
import com.example.myapplication.media.source.movieseries.ProviderCapability
import com.example.myapplication.media.source.movieseries.ProviderId
import com.example.myapplication.media.source.movieseries.ProviderResolution
import com.example.myapplication.media.source.movieseries.resolveTyped
import com.example.myapplication.media.source.vftmodule.clients.TorrServerClient
import com.example.myapplication.network.AppLanguage

/**
 * Провайдер потокового воспроизведения кино, сериалов и аниме через торренты (VFTmodule).
 * Поддерживает как встроенный торрент-движок (libtorrent), так и внешний TorrServer MatriX.
 * Ищет раздачи по трекерам (Rutor, RuTracker, Nyaa, The Pirate Bay, Jackett),
 * определяет оригинальную дорожку и позволяет воспроизводить и скачивать видео.
 */
class VftTorrentProvider(
    private val prefs: VftPreferences,
    private val searcher: VftTorrentSearcher,
    private val localStreamServer: VftLocalStreamServer,
    private val torrServerClient: TorrServerClient,
) : MovieSeriesStreamingProvider {

    override val id: ProviderId = ProviderId("vft_torrents")
    override val displayName: String = "VFT Торренты"

    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.MOVIE,
        ProviderCapability.SERIES,
        ProviderCapability.RU,
        ProviderCapability.EN,
        ProviderCapability.DIRECT,
        ProviderCapability.MULTI_AUDIO,
        ProviderCapability.DOWNLOAD,
        ProviderCapability.KINOPOISK_ID,
        ProviderCapability.IMDB_ID,
        ProviderCapability.TMDB_ID,
    )

    override suspend fun resolve(request: PlaybackRequest): ProviderResolution = resolveTyped(displayName) {
        if (!prefs.isModuleEnabled) {
            return@resolveTyped ProviderResolution.NotConfigured
        }

        val anime = request.anime
        val titleRu = anime.titleRu
        val titleEn = anime.titleEn
        val mainTitle = titleRu ?: anime.title

        val isEpisodic = request.mediaType == MediaType.SERIES || request.mediaType == MediaType.ANIME
        val season = if (isEpisodic) request.seasonNumber else null
        val episode = if (isEpisodic) request.episodeNumber else null

        val preferOriginal = prefs.preferOriginalAudio || (request.language == AppLanguage.EN)

        val query = VftSearchQuery(
            query = mainTitle,
            titleRu = titleRu,
            titleEn = titleEn,
            season = season,
            episode = episode,
            mediaType = request.mediaType,
            imdbId = request.imdbId,
            kinopoiskId = request.kinopoiskId,
            preferOriginalAudio = preferOriginal,
        )

        val items = searcher.search(query)
        if (items.isEmpty()) {
            return@resolveTyped ProviderResolution.NotFound
        }

        val isTorrServerUp = prefs.isTorrServerEnabled && torrServerClient.isAlive()
        val videos = mutableListOf<VetroVideo>()

        // Берём лучшие раздачи (до 8 штук)
        val topItems = items.take(8)

        val hosters = topItems.map { item ->
            val qualityLabel = item.parsedInfo.quality ?: "Auto"
            val resolution = resolutionFromQuality(item.parsedInfo.quality)

            // Формируем детальное описание источников звука (оригинал / дубляж / студия)
            val audioNotes = buildList {
                if (item.parsedInfo.audioTracks.isNotEmpty()) {
                    addAll(item.parsedInfo.audioTracks)
                }
                if (item.parsedInfo.hasOriginalAudio) {
                    add("Original")
                }
            }.distinct()

            val audioDesc = if (audioNotes.isNotEmpty()) " • " + audioNotes.joinToString(", ") else ""
            val seederDesc = if (item.seeders > 0) " [S: ${item.seeders}]" else ""
            val label = "[$qualityLabel] ${item.tracker}$audioDesc$seederDesc"

            val streamUrl = if (isTorrServerUp) {
                torrServerClient.buildStreamUrl(item.infoHash, 0, item.title)
            } else {
                localStreamServer.buildAutoStreamUrl(item.infoHash, season, episode, item.title)
            }

            val video = VetroVideo(
                url = streamUrl,
                label = label,
                resolution = resolution,
                sourceName = "${item.tracker}$audioDesc",
                downloadAllowed = true,
                isPreferred = preferOriginal && item.parsedInfo.hasOriginalAudio,
                providerKey = "vft_torrents",
            )

            val hosterName = "${item.tracker}$audioDesc$seederDesc"

            VetroHoster(
                name = hosterName,
                url = "vft://torrents/${anime.id}/${item.infoHash}",
                videos = listOf(video),
            )
        }

        if (hosters.isEmpty()) {
            return@resolveTyped ProviderResolution.NotFound
        }

        ProviderResolution.Found(
            hosters = hosters,
            accuracy = when {
                request.imdbId != null -> MatchAccuracy.IMDB_ID
                request.kinopoiskId != null -> MatchAccuracy.KINOPOISK_ID
                request.tmdbId != null -> MatchAccuracy.TMDB_ID
                else -> MatchAccuracy.TITLE_ONLY
            },
            language = request.language,
        )
    }

    private fun resolutionFromQuality(quality: String?): Int? = when (quality?.lowercase()) {
        "2160p", "4k", "uhd" -> 2160
        "1080p", "1080i" -> 1080
        "720p" -> 720
        "480p" -> 480
        "360p" -> 360
        else -> null
    }
}
