package com.example.myapplication.media.source.vfmodule

import com.example.myapplication.media.source.movieseries.MovieSeriesStreamingProvider
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * VFmodule — модуль подключения внешних потоковых видеосервисов и балансеров:
 * RedHeadSound, Lumex, Rewall, FanCDN, Vibix, VideoSeed, KinoPub, Collaps,
 * Kinobase, FanSerials, AnimeLib, DDBB, Aniboom, Sibnet, VK Video, CVH (CDNVideoHub),
 * HDVB, Alloha, VeoVeo.
 */
val vfModule: Module = module {
    // 1. Вспомогательный клиент поиска плееров
    single { KinoboxPlayersClient(client = get()) }

    // 2. Низкоуровневые экстракторы
    single { CvhStreamResolver(client = get()) }
    single { HdvbExtractor(client = get()) }
    single { AllohaExtractor(client = get()) }
    single { VeoVeoExtractor(client = get()) }
    single { PlayerJsExtractor(client = get()) }
    single { CollapsExtractor(client = get()) }
    single { SibnetExtractor(client = get()) }
    single { AniboomExtractor(client = get()) }
    single { VkVideoExtractor(client = get()) }

    // 3. Провайдеры потокового каталога (MovieSeriesStreamingProvider)
    single { RedHeadSoundProvider(extractor = get(), playersClient = get()) }
    single { LumexProvider(extractor = get(), playersClient = get()) }
    single { RewallProvider(extractor = get(), playersClient = get()) }
    single { FanCdnProvider(extractor = get(), playersClient = get()) }
    single { VibixProvider(extractor = get(), playersClient = get()) }
    single { VideoSeedProvider(extractor = get(), playersClient = get()) }
    single { KinoPubProvider(extractor = get(), playersClient = get()) }
    single { CollapsProvider(extractor = get(), playersClient = get()) }
    single { KinobaseProvider(extractor = get(), playersClient = get()) }
    single { FanSerialsProvider(extractor = get(), playersClient = get()) }
    single { AnimeLibProvider(extractor = get(), playersClient = get()) }
    single { DdbbProvider(extractor = get(), playersClient = get()) }
    single { AniboomProvider(extractor = get(), playersClient = get()) }
    single { SibnetProvider(extractor = get(), playersClient = get()) }
    single { VkVideoProvider(extractor = get(), playersClient = get()) }
    single { CvhProvider(resolver = get()) }
    single { HdvbProvider(extractor = get(), playersClient = get()) }
    single { AllohaProvider(extractor = get(), playersClient = get()) }
    single { VeoVeoProvider(extractor = get(), playersClient = get()) }
}

/** Псевдоним для совместимости */
val balancersModule: Module = vfModule

/**
 * Получить все зарегистрированные провайдеры VFmodule для SourceEngine.
 */
fun Scope.getVfModuleProviders(): List<MovieSeriesStreamingProvider> = listOf(
    get<RedHeadSoundProvider>(),
    get<LumexProvider>(),
    get<RewallProvider>(),
    get<FanCdnProvider>(),
    get<VibixProvider>(),
    get<VideoSeedProvider>(),
    get<KinoPubProvider>(),
    get<CollapsProvider>(),
    get<KinobaseProvider>(),
    get<FanSerialsProvider>(),
    get<AnimeLibProvider>(),
    get<DdbbProvider>(),
    get<AniboomProvider>(),
    get<SibnetProvider>(),
    get<VkVideoProvider>(),
    get<CvhProvider>(),
    get<HdvbProvider>(),
    get<AllohaProvider>(),
    get<VeoVeoProvider>(),
)
