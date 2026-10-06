package com.example.myapplication.di

import com.example.myapplication.ui.addedit.AddEditViewModel
import com.example.myapplication.ui.details.DetailsViewModel
import com.example.myapplication.ui.home.HomeViewModel
import com.example.myapplication.ui.home.recommendations.RecommendationsViewModel
import com.example.myapplication.ui.inspect.InspectViewModel
import com.example.myapplication.ui.settings.AiConnectViewModel
import com.example.myapplication.ui.settings.SettingsViewModel
import com.example.myapplication.ui.settings.PlaybackSourcesSettingsViewModel
import com.example.myapplication.ui.splash.SplashViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val viewModelModule = module {
    viewModel {
        SplashViewModel(
            legacyStorageMigrator = get(),
            legacyCollectionSafMigrator = get(),
            migrationManager = get(),
            authRepository = get(),
            appUpdateManager = get(),
            imageCompressionMigrator = get(),
            startupSweeps = get(),
        )
    }
    viewModel {
        HomeViewModel(
            repository = get(),
            localDataSource = get(),
            notifier = get(),
            imageStorage = get(),
            settingsDataStore = get(named("settings")),
            addFromApiUseCase = get(),
            statsFooterPhraseUseCase = get(),
            episodeUpdateCheckCoordinator = get(),
            webLinksStore = get(),
            seasonEpisodesStore = get(),
            seriesSeasonsStore = get(),
            episodePlaybackStore = get(),
            mangaBindingStore = get(),
            mangaChapterCacheStore = get(),
            mangaReadingStore = get(),
            mangaTranslateSettings = get(),
            bookSearch = if (get<com.example.myapplication.audiobooks.AudiobookFeatureGate>().enabled) get() else null,
        )
    }
    viewModel {
        RecommendationsViewModel(
            engine = get(),
            localDataSource = get(),
            addFromApiUseCase = get(),
            settingsDataStore = get(named("settings"))
        )
    }
    viewModel {
        AddEditViewModel(
            getAnimeUseCase = get(),
            saveAnimeUseCase = get(),
            updateCommentUseCase = get(),
            imageStorage = get(),
            settingsDataStore = get(named("settings"))
        )
    }
    viewModel {
        SettingsViewModel(
            repository = get(),
            settingsDataStore = get(named("settings")),
            databaseFactory = get(),
            importAnimeDbUseCase = get(),
            repairDbCoordinator = get(),
            collectionPdfGenerator = get(),
            titleDubbingCoordinator = get(),
            enrichmentCoordinator = get(),
            aiCredentialsStore = get(),
            app = androidApplication()
        )
    }
    viewModel {
        PlaybackSourcesSettingsViewModel(
            service = get(),
            customSources = get(),
        )
    }
    viewModel {
        InspectViewModel(
            inspectImageUseCase = get(),
            localDataSource = get(),
            addFromApiUseCase = get(),
            settingsDataStore = get(named("settings")),
            credentialsStore = get()
        )
    }
    viewModel {
        AiConnectViewModel(
            credentialsStore = get(),
            endpoint = get(),
            apiKeySyncRepository = get(),
        )
    }
    viewModel { (animeId: String) ->
        DetailsViewModel(
            animeId = animeId,
            repository = get(),
            settingsDataStore = get(named("settings")),
            imageStorage = get(),
            webLinksStore = get(),
            seasonEpisodesStore = get(),
            seasonEpisodesResolver = get(),
            seasonCatchUp = get(),
            titleEnrichment = get(),
        )
    }
    viewModel { (anime: com.example.myapplication.data.models.Anime) ->
        com.example.myapplication.ui.details.EpisodeMenuViewModel(
            application = androidApplication(),
            anime = anime,
            mediaGateway = get(),
            artworkRepository = get(),
            playbackStore = get(),
            seasonDiscovery = get(),
        )
    }
    // Manga engine (isolated feature — remove to unwire it).
    viewModel { (animeId: String, animeTitle: String, animeTitleEn: String) ->
        com.example.myapplication.manga.ui.MangaLibraryViewModel(
            app = androidApplication(),
            animeId = animeId,
            animeTitle = animeTitle,
            animeTitleEn = animeTitleEn,
            engine = get(),
            bindingStore = get(),
            readingStore = get(),
            cacheStore = get(),
            downloadStore = get(),
            translateSettings = get(),
            tailResolver = get(),
        )
    }
    viewModel { (animeId: String, chapterKey: String) ->
        com.example.myapplication.manga.ui.MangaReaderViewModel(
            animeId = animeId,
            initialChapterKey = chapterKey,
            pageResolver = get(),
            readingStore = get(),
            prefetcher = get(),
            translateSettings = get(),
            bindings = get(),
            workContext = get(),
            translationService = get(),
        )
    }
    viewModel { (anime: com.example.myapplication.data.models.Anime, episodeNumber: Int) ->
        com.example.myapplication.media.ui.StreamWatchViewModel(
            anime = anime,
            episodeNumber = episodeNumber,
            mediaGateway = get(),
        )
    }
}
