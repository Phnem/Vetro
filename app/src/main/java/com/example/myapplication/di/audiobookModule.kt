package com.example.myapplication.di

import com.example.myapplication.audiobooks.AudiobookFeatureGate
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.data.BooksCatalog
import com.example.myapplication.audiobooks.data.CachedShelf
import com.example.myapplication.audiobooks.playback.AudiobookLauncher
import com.example.myapplication.audiobooks.ui.home.BooksHomeViewModel
import com.example.myapplication.data.local.JsonMapFileStore
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import com.example.myapplication.audiobooks.data.local.LocalFolderSource
import com.example.myapplication.audiobooks.data.remote.Aknigi24Source
import com.example.myapplication.audiobooks.data.remote.KnigavuheMetadata
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
    single { KnigavuheMetadata(get(), TokenBucketRateLimiter(maxTokens = 2.0, refillTokensPerSecond = 1.0)) }
    single { Aknigi24Source(get(), get(named("rate_aknigi24"))) } bind AudiobookSource::class bind ManifestSource::class
    single { ManifestResolver(sources = getAll<ManifestSource>()) }
    single { AudiobookRepository(get()) }
    single {
        BooksCatalog(
            sources = getAll<AudiobookSource>(),
            store = JsonMapFileStore(
                File(androidContext().filesDir, "audiobooks/catalog.json"),
                CachedShelf.serializer(),
                "BooksCatalog",
            ),
            metadata = get(),
        )
    }
    single { AudiobookLauncher(androidContext(), getAll<AudiobookSource>(), get(), get()) }
    viewModel { BooksHomeViewModel(get(), get(), get()) }
}
