package com.example.myapplication.di

import com.example.myapplication.AppScope
import com.example.myapplication.media.remote.RemoteMediaPreparer
import com.example.myapplication.media.remote.RemotePlaybackAdapter
import com.example.myapplication.media.remote.RemotePlaybackManager
import com.example.myapplication.media.remote.cast.GoogleCastAdapter
import com.example.myapplication.media.remote.dlna.DlnaAdapter
import com.example.myapplication.media.remote.proxy.CastProxyServer
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * «Воспроизвести на…»: Google Cast (если есть Google Play services) и DLNA, локальный прокси и
 * общий менеджер. Адаптеры — снизу, плеер и UI знают только менеджер.
 */
val remotePlaybackModule = module {
    single { CastProxyServer(get<OkHttpClient>()) }
    single { RemoteMediaPreparer(get(), get<OkHttpClient>()) }
    single {
        val cast = GoogleCastAdapter(androidContext())
        val adapters = buildList<RemotePlaybackAdapter> {
            if (cast.available) add(cast)
            add(DlnaAdapter(androidContext(), get<OkHttpClient>()))
        }
        RemotePlaybackManager(androidContext(), adapters, get(), get<AppScope>())
    }
}
