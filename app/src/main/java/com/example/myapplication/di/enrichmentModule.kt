package com.example.myapplication.di

import com.example.myapplication.domain.enrichment.title.TitleEnrichmentRepository
import com.example.myapplication.network.TokenBucketRateLimiter
import com.example.myapplication.network.enrichment.AniLibriaScheduleClient
import com.example.myapplication.network.enrichment.AnimeSkipClient
import com.example.myapplication.network.enrichment.BookBrainzClient
import com.example.myapplication.network.enrichment.EnrichmentCache
import com.example.myapplication.network.enrichment.EnrichmentHttp
import com.example.myapplication.network.enrichment.FanartClient
import com.example.myapplication.network.enrichment.GoogleBooksClient
import com.example.myapplication.network.enrichment.ITunesClient
import com.example.myapplication.network.enrichment.IntroDbClient
import com.example.myapplication.network.enrichment.NytBooksClient
import com.example.myapplication.network.enrichment.OmdbClient
import com.example.myapplication.network.enrichment.OpenLibraryClient
import com.example.myapplication.network.enrichment.OpenSubtitlesClient
import com.example.myapplication.network.enrichment.ProviderGate
import com.example.myapplication.network.enrichment.TasteDiveClient
import com.example.myapplication.network.enrichment.TmdbEnrichmentClient
import com.example.myapplication.network.enrichment.TvMazeClient
import com.example.myapplication.network.enrichment.YouTubeClient
import io.ktor.client.HttpClient
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Слой обогащения (.scratch/sources-expansion/ARCHITECTURE.md, часть A). Один [EnrichmentHttp] с
 * кэшем в `cacheDir` (система может его чистить — это кэш) и общим выключателем провайдеров; у
 * каждого хоста свой лимитер по его документации.
 */
val enrichmentModule = module {
    single { EnrichmentCache(File(androidContext().cacheDir, "enrichment")) }
    single { ProviderGate() }
    single { EnrichmentHttp(get<HttpClient>(), get(), get()) }

    single { TmdbEnrichmentClient(get(), rate(perSecond = 4.0, burst = 8.0)) }
    single { TvMazeClient(get(), rate(perSecond = 2.0, burst = 5.0)) }
    single { IntroDbClient(get(), rate(perSecond = 1.0, burst = 3.0)) }
    // Общий client id Anime-Skip «сильно лимитирован» — ходим редко, ответы кэшируются на неделю.
    single { AnimeSkipClient(get(), rate(perSecond = 0.5, burst = 2.0)) }
    single { OpenSubtitlesClient(get(), rate(perSecond = 4.0, burst = 4.0)) }
    single { OmdbClient(get(), rate(perSecond = 2.0, burst = 4.0)) }
    single { FanartClient(get(), rate(perSecond = 2.0, burst = 4.0)) }
    single { YouTubeClient(get(), rate(perSecond = 2.0, burst = 2.0)) }
    single { OpenLibraryClient(get(), rate(perSecond = 1.0, burst = 3.0)) }
    single { GoogleBooksClient(get(), rate(perSecond = 1.0, burst = 2.0)) }
    single { BookBrainzClient(get(), rate(perSecond = 1.0, burst = 2.0)) }
    single { ITunesClient(get(), rate(perSecond = 0.3, burst = 3.0)) }
    // NYT: 5 запросов в минуту.
    single { NytBooksClient(get(), rate(perSecond = 5.0 / 60.0, burst = 2.0)) }
    single { TasteDiveClient(get(), rate(perSecond = 1.0, burst = 3.0)) }
    single { AniLibriaScheduleClient(get(), rate(perSecond = 1.0, burst = 2.0)) }

    single { com.example.myapplication.audiobooks.domain.enrichment.BookWorkEnrichment(get(), get()) }

    single {
        TitleEnrichmentRepository(
            tmdb = get(),
            aniList = get(),
            shikimori = get(),
            tvMaze = get(),
            omdb = get(),
            fanart = get(),
            youTube = get(),
            aniLibria = get(),
        )
    }
}

private fun rate(perSecond: Double, burst: Double) = TokenBucketRateLimiter(maxTokens = burst, refillTokensPerSecond = perSecond)
