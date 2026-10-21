package com.example.myapplication.media.source.vftmodule

import com.example.myapplication.media.source.vftmodule.clients.JackettClient
import com.example.myapplication.media.source.vftmodule.clients.TorrServerClient
import com.example.myapplication.media.source.vftmodule.scrapers.NyaaScraper
import com.example.myapplication.media.source.vftmodule.scrapers.PirateBayScraper
import com.example.myapplication.media.source.vftmodule.scrapers.RutorScraper
import com.example.myapplication.media.source.vftmodule.scrapers.RutrackerScraper
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Koin-модуль для VFTmodule (торрент-поиск и стриминг видео).
 */
val vftModule = module {
    single { VftPreferences(androidContext()) }

    single {
        VftTorrentEngine(
            context = androidContext(),
            client = get<OkHttpClient>(),
        )
    }

    single {
        VftLocalStreamServer(
            torrentEngine = get<VftTorrentEngine>(),
        ).apply {
            start()
        }
    }

    single { RutorScraper(client = get<OkHttpClient>()) }
    single { RutrackerScraper(client = get<OkHttpClient>()) }
    single { NyaaScraper(client = get<OkHttpClient>()) }
    single { PirateBayScraper(client = get<OkHttpClient>()) }

    single {
        JackettClient(
            client = get<OkHttpClient>(),
            prefs = get<VftPreferences>(),
        )
    }

    single {
        TorrServerClient(
            client = get<OkHttpClient>(),
            prefs = get<VftPreferences>(),
        )
    }

    single {
        VftTorrentSearcher(
            prefs = get<VftPreferences>(),
            rutorScraper = get<RutorScraper>(),
            rutrackerScraper = get<RutrackerScraper>(),
            nyaaScraper = get<NyaaScraper>(),
            pirateBayScraper = get<PirateBayScraper>(),
            jackettClient = get<JackettClient>(),
        )
    }

    single {
        VftTorrentProvider(
            prefs = get<VftPreferences>(),
            searcher = get<VftTorrentSearcher>(),
            localStreamServer = get<VftLocalStreamServer>(),
            torrServerClient = get<TorrServerClient>(),
        )
    }
}
