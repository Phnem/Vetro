package com.example.myapplication.di

import com.example.myapplication.audiobooks.AudiobookFeatureGate
import com.example.myapplication.audiobooks.data.local.LocalFolderSource
import com.example.myapplication.audiobooks.data.remote.Aknigi24Source
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.domain.source.ManifestSource
import com.example.myapplication.network.TokenBucketRateLimiter
import com.phnem.vetro.BuildConfig
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** Audiobook registrations live here as the feature is built ticket by ticket. */
val audiobookModule = module {
    single { AudiobookFeatureGate(enabled = BuildConfig.AUDIOBOOKS_ENABLED) }
    single { LocalFolderSource(get()) }
    single<ManifestSource> { get<LocalFolderSource>() }
    // Онлайн-источники: каждый ещё и ManifestSource, чтобы резолвер находил его по VariantId.
    // Лимит на хост — вежливые 2 запроса в секунду с запасом 3: запросы только от действий пользователя.
    single(named("rate_aknigi24")) { TokenBucketRateLimiter(maxTokens = 3.0, refillTokensPerSecond = 2.0) }
    single { Aknigi24Source(get(), get(named("rate_aknigi24"))) } bind AudiobookSource::class bind ManifestSource::class
    single { ManifestResolver(sources = getAll<ManifestSource>()) }
}
