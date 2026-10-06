package com.example.myapplication.di

import io.ktor.client.call.body
import io.ktor.client.request.get
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
    // Обновление приложения: режим решает подпись (UpdatePolicy), всё остальное живёт в AppUpdateManager.
    single { com.example.myapplication.update.UpdatePolicy(get()) }
    single {
        com.example.myapplication.update.UpdateDownloader(
            // Отдельная копия клиента: файл в десятки мегабайт по слабой сети не должен упираться в
            // короткие таймауты общего клиента.
            client = get<okhttp3.OkHttpClient>().newBuilder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
            dir = java.io.File(androidContext().filesDir, "updates"),
        )
    }
    single { com.example.myapplication.update.ApkVerifier(androidContext()) }
    single { com.example.myapplication.update.UpdateInstaller(androidContext()) }
    single { com.example.myapplication.update.UpdateNotifier(androidContext()) }
    single {
        com.example.myapplication.update.AppUpdateManager(
            context = androidContext(),
            policy = get(),
            repository = get(),
            downloader = get(),
            verifier = get(),
            installer = get(),
            notifier = get(),
            settingsDataStore = get(named("settings")),
            scope = get<com.example.myapplication.AppScope>(),
        )
    }
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
    single { BatchEpisodeCheckUseCase(repository = get(), localDataSource = get(), observations = get()) }
    single { com.example.myapplication.data.local.SeriesSeasonsStore(androidContext()) }
    single { com.example.myapplication.media.prefs.ContentPlaybackPreferences(androidContext()) }
    single {
        com.example.myapplication.domain.seasons.SeasonEpisodeLocator(
            seasonEpisodesStore = get(),
            seriesSeasonsStore = get(),
            localDataSource = get(),
        )
    }
    single { SeriesEpisodeCheckUseCase(repository = get(), localDataSource = get(), seasonsStore = get()) }
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
    // Автоперевод манги: доступен только в официальной сборке и с подключённым BYOK.
    single { com.example.myapplication.manga.translate.OfficialBuild(androidContext()) }
    single { com.example.myapplication.manga.translate.AutoTranslateGate(get(), get()) }
    single { com.example.myapplication.manga.translate.TranslationModelStore(androidContext(), get()) }
    single {
        com.example.myapplication.manga.translate.MangaTranslateSettings(
            dataStore = get(named("settings")),
            gate = get(),
            models = get(),
            scope = get<com.example.myapplication.AppScope>(),
        )
    }
    single {
        com.example.myapplication.manga.translate.MangaPageTranslationService(
            context = androidContext(),
            client = get(),
            models = get(),
            router = get(),
            scope = get<com.example.myapplication.AppScope>(),
        )
    }
    single { com.example.myapplication.manga.translate.MangaWorkContextProvider(androidContext(), get(), get()) }
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
    // Японская цепочка: бесплатные главы с сайтов издателей, которых ещё нет на языке пользователя.
    single { com.example.myapplication.manga.ja.JaHttp(get<okhttp3.OkHttpClient>()) }
    single(named("ja_sources")) {
        com.example.myapplication.manga.ja.JapaneseSources.create(get())
    }
    // Японское название и номер AniList тайтла: нужны и японской, и английской цепочке.
    single {
        com.example.myapplication.manga.ja.NativeTitleResolver(
            http = get(),
            store = com.example.myapplication.data.local.JsonMapFileStore(
                java.io.File(androidContext().filesDir, "manga_ja_titles_v1.json"),
                com.example.myapplication.manga.ja.NativeEntry.serializer(),
                "JaTitles",
            ),
        )
    }
    single {
        com.example.myapplication.manga.ja.JaTailResolver(
            sources = get(named("ja_sources")),
            natives = get<com.example.myapplication.manga.ja.NativeTitleResolver>(),
            english = com.example.myapplication.manga.ja.MangaDexEnglishTail(
                catalog = get<com.example.myapplication.manga.source.MangaDexSource>(),
                source = get<com.example.myapplication.manga.source.MangaDexSource>(),
                natives = get<com.example.myapplication.manga.ja.NativeTitleResolver>(),
                matches = com.example.myapplication.data.local.JsonMapFileStore(
                    java.io.File(androidContext().filesDir, "manga_en_matches_v1.json"),
                    com.example.myapplication.manga.ja.EnMatch.serializer(),
                    "EnMatches",
                ),
            ),
            matches = com.example.myapplication.data.local.JsonMapFileStore(
                java.io.File(androidContext().filesDir, "manga_ja_matches_v1.json"),
                com.example.myapplication.manga.ja.JaMatch.serializer(),
                "JaMatches",
            ),
            health = com.example.myapplication.data.local.JsonMapFileStore(
                java.io.File(androidContext().filesDir, "manga_ja_health_v1.json"),
                com.example.myapplication.manga.ja.JaHealth.serializer(),
                "JaHealth",
            ),
        )
    }
    single {
        com.example.myapplication.manga.source.MangaSourceEngine(
            // Remanga первым: у русскоязычной коллекции шанс попадания выше.
            sources = listOf(
                get<com.example.myapplication.manga.source.RemangaSource>(),
                get<com.example.myapplication.manga.source.MangaDexSource>(),
            ),
            japaneseSources = get(named("ja_sources")),
        )
    }
    // Новые главы по привязкам — тем же фоновым проходом, что и новые серии.
    single {
        com.example.myapplication.manga.updates.MangaUpdateCheckUseCase(
            bindingStore = get(),
            chapterCache = get(),
            sourceEngine = get(),
            translateSettings = get(),
            tailResolver = get(),
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
    // Пакеты провайдеров v2: редиректы выполняет песочница (только внутрь allowedHosts), не OkHttp.
    single(named("provider-packages")) {
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
            accountTesters = mapOf(
                com.example.myapplication.media.source.UserAccountKind.OPENSUBTITLES to { account ->
                    get<com.example.myapplication.network.enrichment.OpenSubtitlesClient>()
                        .login(account.username, account.password, apiKeyOverride = account.apiKey) is com.example.myapplication.network.LookupResult.Found
                },
                com.example.myapplication.media.source.UserAccountKind.SUBSONIC to { account ->
                    val client = get<okhttp3.OkHttpClient>()
                    val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        client.newCall(
                            okhttp3.Request.Builder()
                                .url(com.example.myapplication.audiobooks.data.remote.SubsonicApi(account).url("ping"))
                                .build(),
                        ).execute().use { it.body?.string() }
                    }
                    body != null && com.example.myapplication.audiobooks.data.remote.SubsonicParser.okBody(body) != null
                },
                com.example.myapplication.media.source.UserAccountKind.AUDIOBOOKSHELF to { account ->
                    com.example.myapplication.audiobooks.data.remote.audiobookshelfLogin(
                        com.example.myapplication.audiobooks.data.remote.web.SourceHttp(
                            get<okhttp3.OkHttpClient>(),
                            com.example.myapplication.network.TokenBucketRateLimiter(maxTokens = 2.0, refillTokensPerSecond = 1.0),
                        ),
                        account,
                    )
                },
            ),
        )
    }
    single<com.example.myapplication.media.source.PlaybackSourceSettingsService> {
        com.example.myapplication.media.source.DefaultPlaybackSourceSettingsService(
            store = get(),
            connectionTester = get(),
        )
    }
    single {
        val http = get<io.ktor.client.HttpClient>()
        com.example.myapplication.media.subtitles.ExternalSubtitleService(
            client = get(),
            accounts = get(),
            dir = java.io.File(androidContext().cacheDir, "subtitles"),
            fetchBytes = { url ->
                runCatching {
                    val response = http.get(url)
                    if (response.status.value in 200..299) response.body<ByteArray>() else null
                }.getOrNull()
            },
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
    single { com.example.myapplication.media.intelligence.SourceIntelligence(androidContext()) }
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
            packageClient = get(named("provider-packages")),
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
                // Кино в общественном достоянии — после своих серверов пользователя.
                com.example.myapplication.media.source.movieseries.InternetArchiveFilmsProvider(
                    client = get<io.ktor.client.HttpClient>(),
                ),
            ),
            providerHealth = get<com.example.myapplication.media.source.movieseries.ProviderHealthStore>(),
            intelligence = get(),
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
    single {
        com.example.myapplication.domain.calendar.ReleaseCalendarRepository(
            localDataSource = get(),
            enrichment = get(),
            animeSeasons = get(),
            seriesSeasons = get(),
            observations = get(),
        )
    }
    single { StatsCardExplanationUseCase(router = get(), genreRepository = get()) }
    single {
        com.example.myapplication.domain.stats.CollectionInsightsLoader(
            episodeStore = get(),
            mangaStore = get(),
            db = get(),
            localDataSource = get(),
        )
    }
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
