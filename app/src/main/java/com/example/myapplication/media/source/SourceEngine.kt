package com.example.myapplication.media.source

import android.util.Log
import com.example.myapplication.data.local.WebLinksStore
import com.example.myapplication.data.models.Anime
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.domain.seasons.forSourceLookup
import com.example.myapplication.media.source.movieseries.MovieSeriesStreamingProvider
import com.example.myapplication.media.source.movieseries.NoProviderHealth
import com.example.myapplication.media.source.movieseries.ProviderHealthRegistry
import com.example.myapplication.media.source.movieseries.ProviderHealthStore
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.WebLinkSites

/**
 * Bounded, native-first media resolution.
 *
 * Page URLs are never sent to embedded Python. Native sources run with a hard timeout, and the
 * direct URL fallback is attempted only after every applicable native source returned no video.
 */
class SourceEngine(
    private val aniLibriaSource: AniLibriaSource,
    private val animeGoSource: AnimeGoSource,
    private val jutSuSource: JutSuSource,
    private val kodikSource: KodikSource,
    private val animeHeavenSource: AnimeHeavenSource,
    private val urlSource: UrlSource,
    private val webLinksStore: WebLinksStore,
    private val movieSeriesSources: List<MovieSeriesStreamingProvider> = emptyList(),
    private val providerHealth: ProviderHealthRegistry = NoProviderHealth,
    /** User-installed sources, resolved per request so settings changes apply without a restart. */
    private val customSources: suspend () -> List<MovieSeriesStreamingProvider> = { emptyList() },
) {
    suspend fun resolveHosters(
        anime: Anime,
        episodeNumber: Int,
        seasonInfo: SeasonInfo? = null,
        language: AppLanguage = AppLanguage.RU,
    ): List<VetroHoster> = resolve(
        PlaybackRequest(anime, episodeNumber, seasonInfo, language)
    ).hostersOrEmpty

    suspend fun resolve(incoming: PlaybackRequest): PlaybackResolution {
        // Единственная точка, где номер сезона приводится к шкале источников. Спецвыпуск (OVA,
        // фильм франшизы) — самостоятельный релиз, а не N-й сезон, и его технический номер ни
        // одному источнику показывать нельзя: AniLibria берёт релиз ИНДЕКСОМ по цепочке и молча
        // отдала бы чужой сезон. Нормализуем здесь, а не в каждом провайдере, — иначе следующий
        // добавленный источник снова забудет это сделать.
        val request = incoming.copy(seasonInfo = incoming.seasonInfo?.forSourceLookup())
        if (request.episodeNumber <= 0) return PlaybackResolution.NoMatch
        val route = PlaybackRoutingPolicy.route(request.mediaType, request.language)
        if (route == PlaybackRoute.None) return PlaybackResolution.NotConfigured(request.mediaType)
        if (route.movieSeriesLanguage != null) return resolveMovieSeries(request)

        val batch = when (route) {
            PlaybackRoute.AnimeRu -> resolveRu(request.anime, request.episodeNumber, request.seasonInfo)
            PlaybackRoute.AnimeEn -> resolveEn(request.anime, request.episodeNumber, request.seasonInfo)
            PlaybackRoute.MovieSeriesRu,
            PlaybackRoute.MovieSeriesEn,
            PlaybackRoute.None,
            -> error("Route handled above")
        }
        val normalized = batch.hosters.withPropagatedSkipReference().playableHosters()
        Log.i(
            TAG,
            "Resolved ${normalized.size} hosters / " +
                "${normalized.sumOf { it.videos.orEmpty().size }} videos for " +
                "${request.anime.title} S${request.seasonNumber}E${request.episodeNumber} " +
                "[${request.language}]",
        )
        return playbackResolution(normalized, batch.hadFailure)
    }

    private suspend fun resolveMovieSeries(request: PlaybackRequest): PlaybackResolution {
        (providerHealth as? ProviderHealthStore)?.ensureLoaded()
        return resolveMovieSeriesSources(
            request = request,
            // Built-in providers first; a user-installed source joins the same cascade rather than
            // replacing anything.
            sources = movieSeriesSources + customSources(),
            timeoutMs = PERSONAL_SOURCE_TIMEOUT_MS,
            health = providerHealth,
        )
    }

    private suspend fun resolveRu(
        anime: Anime,
        episodeNumber: Int,
        seasonInfo: SeasonInfo?,
    ): SourceBatch {
        webLinksStore.ensureLoaded()
        val links = webLinksStore.flow.value[anime.id]?.ruLinks.orEmpty()
        val seasonQuery = anime.seasonSourceQuery(seasonInfo)
        val aniKnown = links.firstOrNull {
            it.siteKey == WebLinkSites.ANILIBRIA_TOP ||
                it.siteKey == WebLinkSites.ANILIBRIA_TV
        }?.url
        val seasonSpecificAniUrl = aniKnown.takeIf {
            seasonInfo == null || seasonInfo.seasonNumber <= 1
        }
        val jutKnown = links.firstOrNull { it.siteKey == WebLinkSites.JUTSU }?.url

        // Exact stored links are the most reliable title disambiguation and avoid a search roundtrip.
        val exact = runCalls(
            buildList {
                if (seasonSpecificAniUrl != null) {
                    add(
                        PlaybackProviderCall("known source", EXACT_SOURCE_TIMEOUT_MS) {
                            aniLibriaSource.resolveEpisode(
                                seasonQuery.anime,
                                episodeNumber,
                                seasonSpecificAniUrl,
                            )
                        }
                    )
                }
            }
        )
        // Keep resolving: exact links are fast, but they must not hide other studios.

        val native = runCalls(
            buildList {
                if (seasonSpecificAniUrl == null) {
                    add(
                        PlaybackProviderCall("AniLiberty", SOURCE_TIMEOUT_MS) {
                            aniLibriaSource.resolveEpisode(
                                anime = anime,
                                episodeNumber = episodeNumber,
                                seasonInfo = seasonInfo,
                            )
                        }
                    )
                }
                // AnimeGo больше не выпадает из гонки на сезонах без собственного названия:
                // он получает франшизные алиасы и ищет ими.
                add(
                    PlaybackProviderCall("AnimeGo", SOURCE_TIMEOUT_MS) {
                        animeGoSource.resolveEpisode(seasonQuery, episodeNumber)
                    }
                )
                add(
                    PlaybackProviderCall("Kodik", KODIK_SOURCE_TIMEOUT_MS) {
                        kodikSource.resolveEpisode(anime, episodeNumber, seasonInfo)
                    }
                )
                // jut.su — источник ТАЙМСКИПОВ, не видео: сайт перешёл на новый плеер и отдаёт
                // в разметке заглушки вместо адресов (§ .scratch/season-source-resolution,
                // TICKET-02). Конфиг с таймингами по-прежнему приезжает, поэтому ветку держим,
                // но видео из неё отбрасываем — как это давно делает EN-путь.
                add(
                    PlaybackProviderCall("jut.su reference", SOURCE_TIMEOUT_MS) {
                        jutSuSource.resolveEpisode(anime, episodeNumber, seasonInfo, jutKnown)
                            .map { it.copy(videos = emptyList()) }
                    }
                )
            }
        )
        val attempts = exact + native
        val resolved = attempts.flatMap { it.value.orEmpty() }
        if (resolved.hasPlayableVideo()) {
            return SourceBatch(resolved, attempts.any { it.failed })
        }

        val direct = resolveDirectFallback(links.map { it.url })
        return SourceBatch(resolved + direct.value.orEmpty(), attempts.any { it.failed } || direct.failed)
    }

    private suspend fun resolveEn(
        anime: Anime,
        episodeNumber: Int,
        seasonInfo: SeasonInfo?,
    ): SourceBatch {
        val attempts = runCalls(
            listOf(
                // AnimeHeaven needs three sequential page loads (search → title → gate).
                PlaybackProviderCall("AnimeHeaven", EN_SOURCE_TIMEOUT_MS) {
                    animeHeavenSource.resolveEpisode(anime, episodeNumber, seasonInfo)
                },
                PlaybackProviderCall("jut.su reference", SOURCE_TIMEOUT_MS) {
                    jutSuSource.resolveEpisode(anime, episodeNumber, seasonInfo)
                        .map { it.copy(videos = emptyList()) }
                },
            )
        )
        val native = attempts[0]
        val jutReference = attempts[1]
        val nativeHosters = native.value.orEmpty()
        val referenceHosters = jutReference.value.orEmpty()
        if (nativeHosters.hasPlayableVideo()) {
            return SourceBatch(
                nativeHosters + referenceHosters,
                native.failed || jutReference.failed,
            )
        }

        webLinksStore.ensureLoaded()
        val links = webLinksStore.flow.value[anime.id]?.enLinks.orEmpty()
        val direct = resolveDirectFallback(links.map { it.url })
        return SourceBatch(
            referenceHosters + direct.value.orEmpty(),
            native.failed || jutReference.failed || direct.failed,
        )
    }

    private suspend fun resolveDirectFallback(urls: List<String>): SourceAttempt<List<VetroHoster>> {
        val direct = urls.firstOrNull(urlSource::canResolveDirect)
            ?: return SourceAttempt(label = "direct URL", value = emptyList())
        return runCalls(
            listOf(
                PlaybackProviderCall("direct URL", DIRECT_TIMEOUT_MS) {
                    urlSource.resolveFromWebUrl(direct)
                }
            )
        ).single()
    }

    /**
     * Исход КАЖДОЙ ветки попадает в лог, включая пустую. Раньше молчание источника было
     * неотличимо от того, что его вообще не запускали, и отказ разбирался по сырым строкам Ktor.
     */
    private suspend fun runCalls(
        calls: List<PlaybackProviderCall<List<VetroHoster>>>,
    ): List<SourceAttempt<List<VetroHoster>>> =
        runPlaybackProviderCascade(calls).onEach { attempt ->
            when {
                attempt.timedOut -> Log.w(TAG, "${attempt.label} timed out")
                attempt.failed -> Log.w(TAG, "${attempt.label} failed")
            }
            val hosters = attempt.value.orEmpty()
            val playable = hosters.sumOf { hoster ->
                hoster.videos.orEmpty().count { isPlayableVetroVideoUrl(it.url) }
            }
            Log.i(
                TAG,
                "${attempt.label} → ${hosters.size} hoster(s), " +
                    "$playable playable video(s), ${attempt.elapsedMs}ms",
            )
        }

    suspend fun resolveBestVideo(hosters: List<VetroHoster>): VetroVideo? {
        val flat = hosters.flatMap { it.videos.orEmpty() }
        flat.firstOrNull { it.isPreferred }?.let { return it }
        return flat.maxByOrNull { it.resolution ?: 0 }
    }

    companion object {
        private const val TAG = "SourceEngine"
        private const val EXACT_SOURCE_TIMEOUT_MS = 8_000L
        private const val SOURCE_TIMEOUT_MS = 12_000L
        private const val KODIK_SOURCE_TIMEOUT_MS = 24_000L
        private const val EN_SOURCE_TIMEOUT_MS = 20_000L
        private const val DIRECT_TIMEOUT_MS = 5_000L
        private const val PERSONAL_SOURCE_TIMEOUT_MS = 20_000L
    }

    private data class SourceBatch(
        val hosters: List<VetroHoster>,
        val hadFailure: Boolean,
    )
}
