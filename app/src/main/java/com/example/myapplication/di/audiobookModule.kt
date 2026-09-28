package com.example.myapplication.di

import com.example.myapplication.audiobooks.AudiobookFeatureGate
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.data.BooksCatalog
import com.example.myapplication.audiobooks.data.CachedShelf
import com.example.myapplication.audiobooks.data.NarrationChain
import com.example.myapplication.audiobooks.playback.AudiobookLauncher
import com.example.myapplication.audiobooks.ui.AudiobookPlayerState
import com.example.myapplication.audiobooks.ui.home.BooksHomeViewModel
import com.example.myapplication.audiobooks.ui.details.BookDetailsViewModel
import com.example.myapplication.data.local.JsonMapFileStore
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import com.example.myapplication.audiobooks.data.local.LocalFolderSource
import com.example.myapplication.audiobooks.data.remote.Aknigi24Source
import com.example.myapplication.audiobooks.data.remote.AudioknigaOneSource
import com.example.myapplication.audiobooks.data.remote.InternetArchiveSource
import com.example.myapplication.audiobooks.data.remote.IpaudioWpSource
import com.example.myapplication.audiobooks.data.remote.IzibSource
import com.example.myapplication.audiobooks.data.remote.RealAudiobooksSource
import com.example.myapplication.audiobooks.torrent.AudioBookBaySource
import com.example.myapplication.audiobooks.torrent.AudiobooSource
import com.example.myapplication.audiobooks.torrent.PirateBaySource
import com.example.myapplication.audiobooks.torrent.RutorSource
import com.example.myapplication.audiobooks.torrent.TorrentEngine
import com.example.myapplication.audiobooks.data.remote.AudioknigiFunSource
import com.example.myapplication.audiobooks.data.remote.BazaKnigSource
import com.example.myapplication.audiobooks.data.remote.KnigavuheSource
import com.example.myapplication.audiobooks.data.remote.SlushatKnigiSource
import com.example.myapplication.audiobooks.data.remote.YaknigaSource
import com.example.myapplication.audiobooks.data.remote.web.SourceHttp
import com.example.myapplication.audiobooks.domain.source.AudiobookSearch
import okhttp3.OkHttpClient
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
    single { TorrentEngine(androidContext()) }
    single { com.example.myapplication.audiobooks.data.AudiobookMarkerStore(get()) }
    single { KnigavuheMetadata(get(), TokenBucketRateLimiter(maxTokens = 2.0, refillTokensPerSecond = 1.0)) }
    // Все онлайн-источники одним списком — он же приоритет для витрины, озвучек и цепочки запасных:
    // сперва стабильные прямые ссылки с длинами глав, затем подписанные ссылки и сайты без длин,
    // английские — после русских. Лимит на хост — вежливые 2 запроса в секунду с запасом 3.
    single(named(SOURCES)) {
        val http = get<OkHttpClient>()
        buildList<AudiobookSource> {
            // RU
            add(Aknigi24Source(http, TokenBucketRateLimiter(maxTokens = 3.0, refillTokensPerSecond = 2.0)))
            add(YaknigaSource(site(http)))
            add(AudioknigaOneSource(site(http)))
            add(IzibSource(site(http)))
            add(KnigavuheSource(site(http)))
            add(BazaKnigSource(site(http)))
            add(SlushatKnigiSource(site(http)))
            add(AudioknigiFunSource(site(http)))
            // EN (+ Internet Archive: LibriVox и «Audio Books & Poetry», в том числе на других языках)
            add(InternetArchiveSource(site(http)))
            add(RealAudiobooksSource(site(http)))
            IpaudioWpSource.SITES.forEach { (id, name, base) -> add(IpaudioWpSource(site(http), id, name, base)) }
            // Трекеры — последними: звук приходит не сразу, зато раздача остаётся на устройстве.
            // Движок собран только под 64-битные ABI: на 32-битном телефоне торрент-источников нет вовсе,
            // а не «есть, но падают» при первом обращении к libtorrent.
            if (TorrentEngine.isSupported) {
                val torrents = get<TorrentEngine>()
                add(RutorSource(site(http), torrents))
                add(AudiobooSource(site(http), torrents))
                add(AudioBookBaySource(site(http), torrents))
                add(PirateBaySource(site(http), torrents))
            }
        }
    }
    single { AudiobookSearch(get(named(SOURCES))) }
    single { NarrationChain(get(), get(named(SOURCES)), get(), get()) }
    // Резолвер знает все источники манифестов: сайты и локальную папку.
    single { ManifestResolver(sources = get<List<AudiobookSource>>(named(SOURCES)) + get<LocalFolderSource>()) }
    single { AudiobookRepository(get()) }
    single {
        BooksCatalog(
            sources = get(named(SOURCES)),
            store = JsonMapFileStore(
                File(androidContext().filesDir, "audiobooks/catalog.json"),
                CachedShelf.serializer(),
                "BooksCatalog",
            ),
            metadata = get(),
            search = get(),
        )
    }
    single { AudiobookPlayerState() }
    single { AudiobookLauncher(androidContext(), get(named(SOURCES)), get(), get(), get(), get(), get()) }
    single {
        com.example.myapplication.audiobooks.data.local.LocalLibraryImporter(
            local = get(),
            resolver = get(),
            search = get(),
            sources = get(named(SOURCES)),
            repository = get(),
        )
    }
    viewModel { BooksHomeViewModel(get(), get(), get(), get()) }
    viewModel { (source: String, key: String, title: String) ->
        BookDetailsViewModel(
            source, key, title, get(named(SOURCES)), get(), get(), get(),
            workEnrichment = get(),
            language = { com.example.myapplication.data.local.AppLanguagePrefs.current(get(named("settings"))) },
        )
    }
}

internal const val AUDIOBOOK_SOURCES = "audiobook_sources"
private const val SOURCES = AUDIOBOOK_SOURCES

/** Сеть сайта-источника: общий клиент и свой вежливый лимит — 2 запроса в секунду с запасом 3. */
private fun site(http: OkHttpClient) =
    SourceHttp(http, TokenBucketRateLimiter(maxTokens = 3.0, refillTokensPerSecond = 2.0))
