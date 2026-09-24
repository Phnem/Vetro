package com.example.myapplication.network.di

import android.util.Log
import com.apollographql.apollo.ApolloClient
import com.phnem.vetro.network.BuildConfig
import com.example.myapplication.network.AniListRemoteDataSource
import com.example.myapplication.network.AnilibriaRemoteDataSource
import com.example.myapplication.network.ApiService
import com.example.myapplication.network.KitsuRemoteDataSource
import com.example.myapplication.network.ShikimoriRemoteDataSource
import com.example.myapplication.network.TraceMoeRemoteDataSource
import com.example.myapplication.network.VetroApiService
import com.example.myapplication.network.WebLinkResolver
import com.example.myapplication.network.KtorWebLinkResolver
import com.example.myapplication.network.mangadex.MangaDexRemoteDataSource
import com.example.myapplication.network.kinopoisk.KinopoiskRemoteDataSource
import com.example.myapplication.network.movie.MovieSeriesRepository
import com.example.myapplication.network.remanga.RemangaRemoteDataSource
import com.example.myapplication.network.tmdb.TmdbRemoteDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import com.example.myapplication.network.TokenBucketRateLimiter
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val rateHeavy = named("api_rate_heavy")
private val rateSearch = named("api_rate_search")
private val rateBurst = named("api_rate_burst")
private val rateAnilistGraphql = named("anilist_graphql")

val coreNetworkModule = module {
    single(rateHeavy) { TokenBucketRateLimiter(maxTokens = 1.0, refillTokensPerSecond = 1.0 / 1.2) }
    single(rateSearch) { TokenBucketRateLimiter(maxTokens = 1.0, refillTokensPerSecond = 1.0 / 0.4) }
    single(rateBurst) { TokenBucketRateLimiter(maxTokens = 1.0, refillTokensPerSecond = 1.0 / 0.3) }
    /** AniList GraphQL: ~90 запросов в минуту (усреднённо). */
    single(rateAnilistGraphql) { TokenBucketRateLimiter(maxTokens = 90.0, refillTokensPerSecond = 90.0 / 60.0) }

    single {
        HttpClient(OkHttp) {
            install(HttpCookies) {
                storage = AcceptAllCookiesStorage()
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
            install(UserAgent) {
                agent = "VetroApp/1.0 (https://github.com/2004i/Vetro)"
            }
            // Только в отладочной сборке: в релизе лог каждого запроса — это и работа на каждом
            // вызове, и утечка URL с параметрами в logcat.
            if (BuildConfig.DEBUG) {
                install(Logging) {
                    level = LogLevel.INFO
                    logger = object : Logger {
                        override fun log(message: String) {
                            Log.d("Ktor", message)
                        }
                    }
                }
            }
            install(HttpTimeout) {
                // Gemini с большим промптом (блэклист) + structured output — ответ часто >10s; иначе OkHttp: Socket timeout.
                requestTimeoutMillis = 300_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 300_000
            }
        }
    }
    single {
        ApolloClient.Builder()
            .serverUrl("https://graphql.anilist.co")
            .addHttpHeader("Accept", "application/json")
            .addHttpHeader("Content-Type", "application/json")
            .build()
    }
    single { ShikimoriRemoteDataSource(get<HttpClient>(), get(rateBurst)) }
    single { TraceMoeRemoteDataSource(get<HttpClient>()) }
    single { AniListRemoteDataSource(get<ApolloClient>(), get(rateAnilistGraphql)) }
    single { KitsuRemoteDataSource(get<HttpClient>(), get(rateBurst)) }
    single { AnilibriaRemoteDataSource(get<HttpClient>(), get(rateBurst)) }
    single { RemangaRemoteDataSource(get<HttpClient>()) }
    single { MangaDexRemoteDataSource(get<HttpClient>(), get(rateBurst)) }
    single { TmdbRemoteDataSource(get<HttpClient>()) }
    single { KinopoiskRemoteDataSource(get<HttpClient>()) }
    single { MovieSeriesRepository(get<TmdbRemoteDataSource>(), get<KinopoiskRemoteDataSource>()) }
    /**
     * Отдельный клиент для скрапа/резолва одобренных сайтов: браузерный User-Agent (без плагина
     * UserAgent, чтобы не слать VetroApp/1.0), короткие таймауты, редиректы по умолчанию.
     * Никакого обхода антибота — просто корректный обычный запрос.
     */
    single(named("weblink")) {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 12_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 12_000
            }
            if (BuildConfig.DEBUG) {
                install(Logging) {
                    level = LogLevel.INFO
                    logger = object : Logger {
                        override fun log(message: String) {
                            Log.d("WebLinkKtor", message)
                        }
                    }
                }
            }
        }
    }
    single<WebLinkResolver> { KtorWebLinkResolver(client = get(named("weblink"))) }

    single<ApiService> {
        VetroApiService(
            httpClient = get<HttpClient>(),
            shikimori = get(),
            aniList = get(),
            kitsu = get(),
            anilibria = get(),
            remanga = get(),
            mangaDex = get(),
            movieSeriesRepository = get(),
            heavyRate = get(rateHeavy),
            searchRate = get(rateSearch),
            burstRate = get(rateBurst)
        )
    }
}
