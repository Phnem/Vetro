package com.example.myapplication.network.enrichment

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.TokenBucketRateLimiter
import com.example.myapplication.network.executeHttpLookup
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

/**
 * Общая сеть API обогащения (.scratch/sources-expansion/ARCHITECTURE.md, часть A): общий Ktor-клиент,
 * свой лимитер на каждый хост, единый [LookupResult]. Тело читается строкой, разбор — в провайдере:
 * так провайдеры тестируются на сохранённых живых ответах без сети.
 */
class EnrichmentHttp(private val client: HttpClient) {

    suspend fun text(
        provider: String,
        url: String,
        rate: TokenBucketRateLimiter? = null,
        method: HttpMethod = HttpMethod.Get,
        notFoundById: Boolean = false,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): LookupResult<String> {
        rate?.acquire()
        return when (val r = executeHttpLookup(provider, notFoundById) {
            client.request(url) {
                this.method = method
                configure()
            }
        }) {
            is LookupResult.Found -> LookupResult.Found(r.value.bodyAsText())
            is LookupResult.NoMatch -> LookupResult.NoMatch
            is LookupResult.NotFoundById -> LookupResult.NotFoundById
            is LookupResult.Failure -> r
        }
    }
}

/** Разбор ответов обогащения: новые поля в ответах API не ломают клиент. */
internal val EnrichmentJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Разобрать удачный ответ; ошибка разбора — неповторяемый сбой, а не «не найдено». */
internal inline fun <T> LookupResult<String>.parse(block: (String) -> T?): LookupResult<T> = when (this) {
    is LookupResult.Found -> try {
        block(value)?.let { LookupResult.Found(it) } ?: LookupResult.NoMatch
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        LookupResult.Failure(e, retryable = false)
    }
    is LookupResult.NoMatch -> LookupResult.NoMatch
    is LookupResult.NotFoundById -> LookupResult.NotFoundById
    is LookupResult.Failure -> this
}

/** Ключ не задан — провайдер выключен, запрос не делается. */
internal fun disabled(): LookupResult.Failure =
    LookupResult.Failure(IllegalStateException("provider disabled: no API key"), retryable = false)

/** Откуда пришло поле обогащения — для отладки, в UI не показывается. */
enum class EnrichmentSource {
    TMDB, FANART, OMDB, TVMAZE, ANILIST, SHIKIMORI, JIKAN, YOUTUBE, INTRODB, ANIME_SKIP, ANISKIP,
    OPENSUBTITLES, OPEN_LIBRARY, GOOGLE_BOOKS, BOOKBRAINZ, ITUNES, NYT, TASTEDIVE,
}
