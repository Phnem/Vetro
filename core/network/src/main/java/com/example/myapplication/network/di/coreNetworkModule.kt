package com.example.myapplication.network.di

import android.util.Log
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
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
private val rateKitsu = named("rate_kitsu")
private val rateAnilibria = named("rate_anilibria")

/**
 * Лимитер на хост, а не на всех сразу: раньше Shikimori, Kitsu, Anilibria и MangaDex делили один
 * `api_rate_burst` и тормозили друг друга, а у Shikimori и MangaDex были ещё вторые лимитеры
 * (синк списков, главы манги) — вместе они превышали лимит хоста.
 */
val RATE_SHIKIMORI = named("rate_shikimori")
val RATE_MANGADEX = named("rate_mangadex")
private val rateAnilistGraphql = named("anilist_graphql")

/** Ktor-клиент для ИИ-запросов: ответы с большим промптом идут минутами. */
val AI_HTTP_CLIENT = named("ai")

val coreNetworkModule = module {
    /**
     * Корневой OkHttp — один пул соединений и один диспетчер на всё приложение.
     *
     * Раньше каждый клиент (Ktor каталога, Ktor ссылок, Apollo, медиа-движок, синк списков, Coil)
     * создавал свой OkHttp: свой пул, свои потоки, и запрос к хосту, с которым только что говорил
     * соседний клиент, заново делал TLS-рукопожатие. Остальные клиенты берут этот через
     * `newBuilder()` — общий пул и диспетчер, свои таймауты. Таймауты здесь — умолчания OkHttp,
     * с которыми и жил медиа-движок.
     */
    single<OkHttpClient> { OkHttpClient.Builder().build() }

    single(rateHeavy) { TokenBucketRateLimiter(maxTokens = 1.0, refillTokensPerSecond = 1.0 / 1.2) }
    single(rateSearch) { TokenBucketRateLimiter(maxTokens = 1.0, refillTokensPerSecond = 1.0 / 0.4) }
    single(rateBurst) { TokenBucketRateLimiter(maxTokens = 1.0, refillTokensPerSecond = 1.0 / 0.3) }
    /** AniList GraphQL: ~90 запросов в минуту (усреднённо). */
    // Shikimori: 5 запросов/с и 90/мин — всплеск до 5, в среднем 1,5/с.
    single(RATE_SHIKIMORI) { TokenBucketRateLimiter(maxTokens = 5.0, refillTokensPerSecond = 1.5) }
    // MangaDex: 5 запросов/с на IP — каталог и главы вместе держим ниже потолка.
    single(RATE_MANGADEX) { TokenBucketRateLimiter(maxTokens = 4.0, refillTokensPerSecond = 4.0) }
    single(rateKitsu) { TokenBucketRateLimiter(maxTokens = 1.0, refillTokensPerSecond = 1.0 / 0.3) }
    single(rateAnilibria) { TokenBucketRateLimiter(maxTokens = 1.0, refillTokensPerSecond = 1.0 / 0.3) }
    single(rateAnilistGraphql) { TokenBucketRateLimiter(maxTokens = 90.0, refillTokensPerSecond = 90.0 / 60.0) }

    single {
        HttpClient(OkHttp) {
            engine { preconfigured = get<OkHttpClient>() }
            install(HttpCookies) {
                storage = AcceptAllCookiesStorage()
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
            install(UserAgent) {
                agent = VETRO_USER_AGENT
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
            // Каталоги и источники отвечают за секунды; раньше здесь стояли 5 минут ради Gemini, и
            // зависший запрос к каталогу держал экран и воркер столько же. ИИ — в своём клиенте ниже.
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
        }
    }
    single(AI_HTTP_CLIENT) {
        HttpClient(OkHttp) {
            engine { preconfigured = get<OkHttpClient>() }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
            install(UserAgent) {
                agent = VETRO_USER_AGENT
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
            .okHttpClient(
                get<OkHttpClient>().newBuilder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build(),
            )
            .addHttpHeader("Accept", "application/json")
            .addHttpHeader("Content-Type", "application/json")
            .build()
    }
    single { ShikimoriRemoteDataSource(get<HttpClient>(), get(RATE_SHIKIMORI)) }
    single { TraceMoeRemoteDataSource(get<HttpClient>(AI_HTTP_CLIENT)) }
    single { AniListRemoteDataSource(get<ApolloClient>(), get(rateAnilistGraphql)) }
    single { KitsuRemoteDataSource(get<HttpClient>(), get(rateKitsu)) }
    single { AnilibriaRemoteDataSource(get<HttpClient>(), get(rateAnilibria)) }
    single { RemangaRemoteDataSource(get<HttpClient>()) }
    single { MangaDexRemoteDataSource(get<HttpClient>(), get(RATE_MANGADEX)) }
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
            engine { preconfigured = get<OkHttpClient>() }
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

/** Один User-Agent приложения для API-запросов (скрап сайтов и плееров шлёт свои, браузерные). */
const val VETRO_USER_AGENT = "VetroApp/1.0 (https://github.com/2004i/Vetro)"
