package com.example.myapplication.di

import com.example.myapplication.data.ai.AiLlmEndpoint
import com.example.myapplication.data.ai.AiLlmFallbackRouter
import com.example.myapplication.data.ai.AiProviderLatencyProber
import com.example.myapplication.data.local.CollectionPdfGenerator
import com.example.myapplication.data.remote.GeminiStructuredClient
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.data.repository.AppUpdateRepository
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.data.local.CoverDescriptorCacheStore
import com.example.myapplication.data.local.RecommendationCacheStore
import com.example.myapplication.domain.inspect.InspectImageUseCase
import com.example.myapplication.domain.recommendations.CoverDescriptorProvider
import com.example.myapplication.domain.recommendations.RecommendationEngine
import com.example.myapplication.domain.search.AddFromApiUseCase
import com.example.myapplication.domain.settings.ImportAnimeDbUseCase
import com.example.myapplication.domain.settings.RepairAnimeDbUseCase
import com.example.myapplication.domain.settings.ShikimoriPlaceholderPurge
import com.example.myapplication.domain.titles.AiTitleTranslationUseCase
import com.example.myapplication.domain.titles.RussianTitleEnrichmentUseCase
import com.example.myapplication.domain.titles.TitleDubbingCoordinator
import com.example.myapplication.domain.titles.TitleEnrichmentUseCase
import com.example.myapplication.domain.settings.RepairDbCoordinator
import com.example.myapplication.domain.enrichment.CollectionEnrichmentCoordinator
import com.example.myapplication.domain.enrichment.CollectionGapDetector
import com.example.myapplication.domain.enrichment.EnrichmentGapJournal
import com.example.myapplication.domain.enrichment.weblinks.WebLinkEnrichmentUseCase
import com.example.myapplication.data.local.WebLinksStore
import com.example.myapplication.data.local.StatsExplanationCacheStore
import com.example.myapplication.domain.stats.ResolveStatsFooterPhraseUseCase
import com.example.myapplication.domain.stats.StatsCardExplanationUseCase
import com.example.myapplication.domain.stats.StatsExplanationCoordinator
import com.example.myapplication.domain.stats.StatsPhraseCatalog
import com.example.myapplication.updates.BatchEpisodeCheckUseCase
import com.example.myapplication.updates.SeriesEpisodeCheckUseCase
import com.example.myapplication.updates.EpisodeUpdateCheckCoordinator
import com.example.myapplication.notifications.AnimeNotifier
import com.example.myapplication.notifications.AnimeNotifierImpl
import com.example.myapplication.notifications.MangaNotifier
import com.example.myapplication.notifications.MangaNotifierImpl
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    single { StatsPhraseCatalog(androidContext()) }
    single { ResolveStatsFooterPhraseUseCase(catalog = get(), settingsDataStore = get(named("settings"))) }
    single<AnimeRepository> { AnimeRepository(apiService = get(), localDataSource = get()) }
    single { AppUpdateRepository(settingsDataStore = get(named("settings")), animeRepository = get()) }
    single<GenreRepository> { GenreRepository() }
    // Стопка обновлений → колокольчик: живёт до смерти процесса, то есть до холодного старта.
    single { com.example.myapplication.ui.home.updates.EpisodeNotificationTray() }
    // Одна область корутин уровня процесса (см. AppScope).
    single { com.example.myapplication.AppScope() }
    single { GeminiStructuredClient(get(com.example.myapplication.network.di.AI_HTTP_CLIENT)) }
    single { AiLlmEndpoint(get(com.example.myapplication.network.di.AI_HTTP_CLIENT)) }
    single { AiProviderLatencyProber(get(), get()) }
    single { AiLlmFallbackRouter(get(), get(), get()) }
    single { InspectImageUseCase(get(), get(), get(), get()) }
    single { AddFromApiUseCase(get(), get(), get(), get(), get()) }
    single { BatchEpisodeCheckUseCase(repository = get(), localDataSource = get()) }
    single { SeriesEpisodeCheckUseCase(repository = get(), localDataSource = get()) }
    single {
        EpisodeUpdateCheckCoordinator(
            animeCheck = get(),
            seriesCheck = get(),
            seasonCatchUp = get(),
            appScope = get<com.example.myapplication.AppScope>(),
        )
    }
    single { TitleEnrichmentUseCase(repository = get(), localDataSource = get()) }
    single { RussianTitleEnrichmentUseCase(repository = get(), localDataSource = get()) }
    single { AiTitleTranslationUseCase(router = get(), localDataSource = get()) }
    single { TitleDubbingCoordinator(androidContext()) }
    single { ImportAnimeDbUseCase(get()) }
    single { ShikimoriPlaceholderPurge(httpClient = get(), imageStorage = get()) }
    single { RepairAnimeDbUseCase(get(), get(), get(), get(), get(), get()) }
    single { RepairDbCoordinator(androidContext()) }
    single { EnrichmentGapJournal(androidContext()) }
    single { CollectionGapDetector(localDataSource = get(), repairUseCase = get(), journal = get()) }
    single { WebLinksStore(androidContext()) }
    single { WebLinkEnrichmentUseCase(resolver = get(), store = get()) }
    // Серии по сезонам: файловый стор + фоновый резолвер (AniList → Shikimori → MAL).
    single { com.example.myapplication.data.local.SeasonEpisodesStore(androidContext()) }
    single {
        com.example.myapplication.domain.seasons.SeasonEpisodesResolver(
            repository = get(),
            localDataSource = get(),
            store = get(),
        )
    }
    // «Найти ещё»: сезоны глазами источников ПРОСМОТРА — там, где каталожные API их не знают.
    // Запускается только по кнопке, поэтому в фоновом резолвере его нет.
    single {
        com.example.myapplication.domain.seasons.StreamingSeasonDiscovery(
            kodikDirectSearch = get(),
            jutSuSource = get(),
            animeHeavenSource = get(),
            localDataSource = get(),
            store = get(),
            seasonEpisodesResolver = get(),
        )
    }
    // Догон расклада сезонов, когда он отстал от пришедшего уведомления о новой серии.
    single {
        com.example.myapplication.domain.seasons.SeasonCatchUp(
            localDataSource = get(),
            store = get(),
            resolver = get(),
            discovery = get(),
        )
    }
    single { com.example.myapplication.localplayer.domain.FranchiseEpisodeMapper(get(), get()) }
    single {
        com.example.myapplication.localplayer.domain.AniSkipSegmentProvider(
            httpClient = get<io.ktor.client.HttpClient>(),
            franchiseMapper =
                get<com.example.myapplication.localplayer.domain.FranchiseEpisodeMapper>(),
        )
    }
    single {
        com.example.myapplication.localplayer.domain.SkipSegmentResolver(
            aniSkip = get<com.example.myapplication.localplayer.domain.AniSkipSegmentProvider>(),
            external = com.example.myapplication.localplayer.domain.ExternalSkipSegmentProvider(
                animeSkip = get(),
                introDb = get(),
            ),
        )
    }
    // Manga engine (isolated feature — remove these lines + манифест-запись ридера, чтобы отключить).
    single { com.example.myapplication.manga.data.MangaBindingStore(androidContext()) }
    single { com.example.myapplication.manga.data.MangaChapterCacheStore(androidContext()) }
    single { com.example.myapplication.manga.data.MangaReadingStore(get(named("settings"))) }
    single { com.example.myapplication.manga.data.MangaDownloadStore(androidContext()) }
    single {
        com.example.myapplication.manga.download.MangaChapterDownloader(
            client = get(),
            store = get(),
        )
    }
    single {
        com.example.myapplication.manga.download.MangaPageResolver(
            engine = get(),
            downloadStore = get(),
        )
    }
    single {
        com.example.myapplication.manga.domain.MangaPagePrefetcher(
            context = androidContext(),
            pageResolver = get(),
        )
    }
    single(named("remanga_rate")) {
        // Remanga лимиты не публикует: оглавление грузится страницами по 100, идём спокойно.
        com.example.myapplication.network.TokenBucketRateLimiter(
            maxTokens = 1.0,
            refillTokensPerSecond = 1.0 / 0.4,
        )
    }
    single {
        com.example.myapplication.manga.source.MangaDexSource(
            client = get(),
            // Тот же лимитер, что у каталога MangaDex в core: хост один.
            rateLimiter = get(com.example.myapplication.network.di.RATE_MANGADEX),
        )
    }
    single {
        com.example.myapplication.manga.source.RemangaSource(
            client = get(),
            rateLimiter = get(named("remanga_rate")),
        )
    }
    single {
        com.example.myapplication.manga.source.MangaSourceEngine(
            // Remanga первым: у русскоязычной коллекции шанс попадания выше.
            sources = listOf(
                get<com.example.myapplication.manga.source.RemangaSource>(),
                get<com.example.myapplication.manga.source.MangaDexSource>(),
            ),
        )
    }
    // Новые главы по привязкам — тем же фоновым проходом, что и новые серии.
    single {
        com.example.myapplication.manga.updates.MangaUpdateCheckUseCase(
            bindingStore = get(),
            chapterCache = get(),
            sourceEngine = get(),
        )
    }

    // Media engine (stream + download)
    // OkHttp медиа-движка — корневой из coreNetworkModule (общий пул соединений).
    single { com.example.myapplication.media.source.AniLibriaSource(client = get()) }
    single { com.example.myapplication.media.source.AnimeGoSource(client = get()) }
    single {
        // Зеркало читаем на каждом резолве: источник — синглтон, а домен меняется в настройках
        // (jut.su периодически блокируют) и должен подхватываться без перезапуска приложения.
        val settings: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> =
            get(named("settings"))
        com.example.myapplication.media.source.JutSuSource(
            client = get(),
            mirrorProvider = {
                val prefs = settings.data.first()
                prefs[com.example.myapplication.data.local.DevPreferencesKeys.JUTSU_MIRROR_DOMAIN]
            },
        )
    }
    // Токены kodik-api лежат в assets, поэтому прямому поиску нужен Context (как UrlSource ниже).
    single {
        com.example.myapplication.media.source.KodikDirectSearch(
            client = get(),
            appContext = androidContext(),
        )
    }
    single {
        com.example.myapplication.media.source.KodikSource(
            client = get(),
            directSearch = get(),
        )
    }
    // Browser-UA client without the cookie plugin: gate.php is selected by a per-request `key` cookie.
    single {
        val seasonStore = get<com.example.myapplication.data.local.SeasonEpisodesStore>()
        com.example.myapplication.media.source.AnimeHeavenSource(
            client = get(org.koin.core.qualifier.named("weblink")),
            seasonTitles = { animeId ->
                seasonStore.ensureLoaded()
                seasonStore.entryFor(animeId)?.seasons.orEmpty().mapNotNull { it.title }
            },
        )
    }
    single { com.example.myapplication.media.source.UrlSource(context = androidContext()) }
    single {
        com.example.myapplication.media.source.DirectHttpPlaybackSource(
            urlSource = get(),
            webLinksStore = get(),
        )
    }
    single { com.example.myapplication.media.source.PlaybackSourceCredentialsStore(androidContext()) }
    single<com.example.myapplication.media.source.PlaybackSourceConfigStore> {
        get<com.example.myapplication.media.source.PlaybackSourceCredentialsStore>()
    }
    single(named("webdav")) {
        io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
            engine { preconfigured = get<okhttp3.OkHttpClient>() }
            followRedirects = false
        }
    }
    single(named("personal-media")) {
        io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
            engine { preconfigured = get<okhttp3.OkHttpClient>() }
            followRedirects = false
        }
    }
    single<com.example.myapplication.media.source.PlaybackSourceConnectionTester> {
        com.example.myapplication.media.source.KtorPlaybackSourceConnectionTester(
            webDavClient = get(named("webdav")),
            personalServerClient = get(named("personal-media")),
        )
    }
    single<com.example.myapplication.media.source.PlaybackSourceSettingsService> {
        com.example.myapplication.media.source.DefaultPlaybackSourceSettingsService(
            store = get(),
            connectionTester = get(),
        )
    }
    single {
        val credentials = get<com.example.myapplication.media.source.PlaybackSourceCredentialsStore>()
        com.example.myapplication.media.source.WebDavPlaybackSource(client = get(named("webdav"))) {
            credentials.webDav()
        }
    }
    single(named("jellyfin-source")) {
        val credentials = get<com.example.myapplication.media.source.PlaybackSourceCredentialsStore>()
        val provider = com.example.myapplication.media.source.PersonalMediaServerProvider.JELLYFIN
        com.example.myapplication.media.source.PersonalMediaServerPlaybackSource(
            client = get(named("personal-media")),
            provider = provider,
            configProvider = { credentials.personalServer(provider) },
        )
    }
    single(named("emby-source")) {
        val credentials = get<com.example.myapplication.media.source.PlaybackSourceCredentialsStore>()
        val provider = com.example.myapplication.media.source.PersonalMediaServerProvider.EMBY
        com.example.myapplication.media.source.PersonalMediaServerPlaybackSource(
            client = get(named("personal-media")),
            provider = provider,
            configProvider = { credentials.personalServer(provider) },
        )
    }
    single { com.example.myapplication.media.source.movieseries.ProviderHealthStore(androidContext()) }
    single { com.example.myapplication.media.source.movieseries.custom.CustomSourceStore(androidContext()) }
    // Привязка интерфейса обязательна: Koin разрешает зависимость по ОБЪЯВЛЕННОМУ типу, а
    // CustomSourceRegistry и CustomSourceSettingsService просят InstalledSourceStore. Без этой
    // строки открытие «Источников видео» роняло приложение — одна конкретная регистрация
    // интерфейс собой не закрывает. Тот же приём, что у PlaybackSourceConfigStore выше.
    single<com.example.myapplication.media.source.movieseries.custom.InstalledSourceStore> {
        get<com.example.myapplication.media.source.movieseries.custom.CustomSourceStore>()
    }
    single { com.example.myapplication.media.source.movieseries.custom.CustomSourceInstaller() }
    single {
        com.example.myapplication.media.source.movieseries.custom.CustomSourceRegistry(
            store = get(),
            client = get(),
        )
    }
    single {
        com.example.myapplication.media.source.movieseries.custom.CustomSourceSettingsService(
            store = get(),
            installer = get(),
            client = get(),
        )
    }
    single {
        com.example.myapplication.media.source.SourceEngine(
            aniLibriaSource = get(),
            animeGoSource = get(),
            jutSuSource = get(),
            kodikSource = get(),
            animeHeavenSource = get(),
            urlSource = get(),
            webLinksStore = get(),
            movieSeriesSources = listOf(
                get<com.example.myapplication.media.source.DirectHttpPlaybackSource>(),
                get<com.example.myapplication.media.source.WebDavPlaybackSource>(),
                get<com.example.myapplication.media.source.PersonalMediaServerPlaybackSource>(
                    named("jellyfin-source")
                ),
                get<com.example.myapplication.media.source.PersonalMediaServerPlaybackSource>(
                    named("emby-source")
                ),
            ),
            providerHealth = get<com.example.myapplication.media.source.movieseries.ProviderHealthStore>(),
            customSources = {
                get<com.example.myapplication.media.source.movieseries.custom.CustomSourceRegistry>()
                    .providers()
            },
        )
    }
    single { com.example.myapplication.media.cookies.MediaCookieStore(androidContext()) }
    single<com.example.myapplication.media.MediaGateway> {
        com.example.myapplication.media.MediaGatewayImpl(
            context = androidContext(),
            sourceEngine = get(),
            settingsDataStore = get(named("settings")),
        )
    }
    single { com.example.myapplication.media.download.SeasonBatchDownloader(get()) }
    single { com.example.myapplication.media.metadata.EpisodeArtworkRepository(get()) }
    single {
        com.example.myapplication.media.progress.EpisodePlaybackStore(
            store = get(named("playback")),
            legacySettings = get(named("settings")),
        )
    }
    single {
        CollectionEnrichmentCoordinator(
            context = androidContext(),
            settingsDataStore = get(named("settings")),
            appScope = get(),
        )
    }
    single { CollectionPdfGenerator(androidContext()) }
    single<AnimeNotifier> { AnimeNotifierImpl(context = androidContext()) }
    single<MangaNotifier> { MangaNotifierImpl(context = androidContext()) }
    single { RecommendationCacheStore(androidContext()) }
    single { CoverDescriptorCacheStore(androidContext()) }
    single {
        CoverDescriptorProvider(
            credentialsStore = get(),
            aiEndpoint = get(),
            cache = get(),
            httpClient = get(),
        )
    }
    single { StatsExplanationCacheStore(androidContext()) }
    single { StatsCardExplanationUseCase(router = get(), genreRepository = get()) }
    single {
        StatsExplanationCoordinator(
            localDataSource = get(),
            explanationUseCase = get(),
            credentialsStore = get(),
            cacheStore = get(),
            settingsDataStore = get(named("settings")),
            appScope = get<com.example.myapplication.AppScope>(),
        )
    }
    single {
        RecommendationEngine(
            apiService = get(),
            localDataSource = get(),
            genreRepository = get(),
            cache = get(),
            coverDescriptorProvider = get(),
            imageStorageRepository = get(),
        )
    }
}
