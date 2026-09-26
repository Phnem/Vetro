package com.example.myapplication.media.source.sdk

import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.PlaybackRequest
import com.example.myapplication.media.source.VetroHoster
import com.example.myapplication.media.source.VetroSubtitleTrack
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.source.movieseries.MatchAccuracy
import com.example.myapplication.media.source.movieseries.MovieSeriesStreamingProvider
import com.example.myapplication.media.source.movieseries.ProviderCapability
import com.example.myapplication.media.source.movieseries.ProviderId
import com.example.myapplication.media.source.movieseries.ProviderResolution
import com.example.myapplication.media.source.movieseries.providerResolutionForStatus
import com.example.myapplication.media.source.movieseries.resolveTyped

/**
 * Пакет v2 как обычный провайдер каскада кино/сериалов: здоровье, приоритет и отказоустойчивость —
 * те же, что у встроенных. Путь к потоку выбирается по тому, что пакет умеет:
 * 1. потоки прямо по внешнему id (+ сезон/серия);
 * 2. поиск по внешнему id → совпадение id в выдаче → серии → поток серии.
 * Поиска по названию здесь нет: он даёт чужие тайтлы, а сопоставление по id — нет.
 */
class PackageStreamingProvider(private val runtime: ProviderPackageRuntime) : MovieSeriesStreamingProvider {
    private val pkg = runtime.pkg

    override val id: ProviderId = ProviderId("package:${pkg.id}")
    override val displayName: String = pkg.name
    override val capabilities: Set<ProviderCapability> = buildSet {
        if (PackageMediaType.MOVIE in pkg.mediaTypes) add(ProviderCapability.MOVIE)
        if (PackageMediaType.SERIES in pkg.mediaTypes) add(ProviderCapability.SERIES)
        if (ExternalIdKind.TMDB in pkg.externalIds) add(ProviderCapability.TMDB_ID)
        if (ExternalIdKind.IMDB in pkg.externalIds) add(ProviderCapability.IMDB_ID)
        if (ExternalIdKind.KINOPOISK in pkg.externalIds) add(ProviderCapability.KINOPOISK_ID)
        if (PackageCapability.SUBTITLES in pkg.capabilities) add(ProviderCapability.SUBTITLES)
        if (PackageCapability.VARIANTS in pkg.capabilities) add(ProviderCapability.MULTI_AUDIO)
        if (PackageCapability.DOWNLOAD in pkg.capabilities) add(ProviderCapability.DOWNLOAD)
        add(ProviderCapability.HLS)
        add(ProviderCapability.DIRECT)
    }

    override suspend fun resolve(request: PlaybackRequest): ProviderResolution = resolveTyped(displayName) {
        val movie = request.mediaType == MediaType.MOVIE
        val wanted = if (movie) PackageMediaType.MOVIE else PackageMediaType.SERIES
        if (request.mediaType != MediaType.MOVIE && request.mediaType != MediaType.SERIES) return@resolveTyped ProviderResolution.Unsupported
        if (wanted !in pkg.mediaTypes || pkg.operations.streams == null) return@resolveTyped ProviderResolution.Unsupported

        val ids = buildMap {
            request.tmdbId?.let { put(ExternalIdKind.TMDB, it.toString()) }
            request.imdbId?.let { put(ExternalIdKind.IMDB, it) }
            request.kinopoiskId?.let { put(ExternalIdKind.KINOPOISK, it.toString()) }
        }.filterKeys { it in pkg.externalIds }
        if (ids.isEmpty()) return@resolveTyped ProviderResolution.Unsupported
        val season = request.seasonNumber.takeUnless { movie }
        val episode = request.episodeNumber.takeUnless { movie }

        val streamsTemplate = pkg.operations.streams.request.url
        val direct = "unitId" !in ProviderPackageValidator.placeholders(streamsTemplate)
        val result = if (direct) {
            runtime.streams(ids = ids, season = season, episode = episode)
        } else {
            viaUnits(ids, movie, season, episode) ?: return@resolveTyped ProviderResolution.NotFound
        }
        when (result) {
            is PackageResult.Ok -> {
                val videos = result.value.variants.map { it.toVideo() }
                if (videos.isEmpty()) ProviderResolution.NotFound
                else ProviderResolution.Found(
                    hosters = listOf(VetroHoster(name = displayName, url = "", videos = videos)),
                    accuracy = if (ExternalIdKind.TMDB in ids) MatchAccuracy.TMDB_ID else if (ExternalIdKind.IMDB in ids) MatchAccuracy.IMDB_ID else MatchAccuracy.KINOPOISK_ID,
                )
            }
            PackageResult.NotConfigured -> ProviderResolution.NotConfigured
            PackageResult.Unsupported -> ProviderResolution.Unsupported
            // Нарушение песочницы и сбой — ошибка провайдера: каскад уйдёт к следующему, здоровье учтёт.
            is PackageResult.Blocked -> ProviderResolution.Blocked(result.reason)
            is PackageResult.Failed -> result.status?.let { providerResolutionForStatus(it) }
                ?: ProviderResolution.TemporaryError(result.reason)
        }
    }

    /** null — тайтл или серия не найдены; иначе итог запроса потоков. */
    private suspend fun viaUnits(
        ids: Map<ExternalIdKind, String>,
        movie: Boolean,
        season: Int?,
        episode: Int?,
    ): PackageResult<StreamSet>? {
        if (PackageCapability.SEARCH_BY_EXTERNAL_ID !in pkg.capabilities) return PackageResult.Unsupported
        val titles = when (val r = runtime.search(query = null, ids = ids)) {
            is PackageResult.Ok -> r.value
            else -> return r.cast()
        }
        // Совпадение по id в самой выдаче: провайдер мог вернуть «похожие», их не берём.
        val title = titles.firstOrNull { t -> ids.any { (k, v) -> t.externalIds[k]?.equals(v, ignoreCase = true) == true } }
            ?: return null
        val units = when (val r = runtime.units(title.rawId, if (movie) UnitKind.MOVIE else UnitKind.EPISODE)) {
            is PackageResult.Ok -> r.value
            else -> return r.cast()
        }
        val unit = if (movie) {
            units.firstOrNull()
        } else {
            units.firstOrNull { it.number == episode?.toDouble() && (it.season == null || it.season == season) }
        } ?: return null
        return runtime.streams(unitId = unit.rawId)
    }

    private fun StreamVariant.toVideo() = VetroVideo(
        url = url,
        label = label,
        sourceName = listOfNotNull(displayName, audioLanguage?.uppercase()).joinToString(" · "),
        resolution = resolution,
        subtitles = subtitles.map { s ->
            VetroSubtitleTrack(s.url, s.language, s.format?.let { if (it.contains("srt", true)) "application/x-subrip" else "text/$it" } ?: "text/vtt")
        },
        downloadAllowed = downloadAllowed,
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> PackageResult<*>.cast(): PackageResult<T> = this as PackageResult<T>
}
