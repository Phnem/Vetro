package com.example.myapplication.network.enrichment

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.TokenBucketRateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Общая сеть API обогащения (.scratch/sources-expansion/ARCHITECTURE.md, часть A): общий Ktor-клиент,
 * свой лимитер на каждый хост, кэш ответов с отрицательными записями и выключатель провайдера при
 * сбоях и исчерпанной квоте. Тело читается строкой, разбор — в провайдере: так провайдеры тестируются
 * на сохранённых живых ответах без сети.
 */
class EnrichmentHttp(
    private val client: HttpClient,
    private val cache: EnrichmentCache? = null,
    val gate: ProviderGate = ProviderGate(),
) {

    suspend fun text(
        provider: String,
        url: String,
        rate: TokenBucketRateLimiter? = null,
        method: HttpMethod = HttpMethod.Get,
        notFoundById: Boolean = false,
        policy: CachePolicy? = null,
        /**
         * Запрос с учётными данными пользователя (вход, скачивание в счёт его квоты): 401/403 значат
         * «неверный логин» или «его лимит», а не отозванный ключ приложения — выключатель не трогаем,
         * иначе опечатка в пароле закрыла бы провайдер до завтра.
         */
        userCredentials: Boolean = false,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): LookupResult<String> {
        val cached = policy?.let { cache?.get(it.key) }
        if (cached != null && cache?.isFresh(cached) == true) return cached.toResult(notFoundById)
        // Провайдер выключен после сбоев или до завтра по квоте: сеть не трогаем, отдаём что есть.
        if (!gate.isOpen(provider)) return cached?.toResult(notFoundById) ?: paused(provider)
        rate?.acquire()
        return try {
            val response = client.request(url) {
                this.method = method
                configure()
            }
            val status = response.status.value
            val body = response.bodyAsText()
            when {
                status in 200..299 && !ProviderGate.isQuotaExhausted(200, body.take(QUOTA_SNIFF)) -> {
                    gate.onSuccess(provider)
                    policy?.let { cache?.put(it.key, body, it.ttlMs) }
                    LookupResult.Found(body)
                }
                status == 404 -> {
                    // API жив и ответил «нет такого» — это знание, его тоже кэшируем.
                    gate.onSuccess(provider)
                    policy?.let { cache?.put(it.key, null, it.negativeTtlMs) }
                    if (notFoundById) LookupResult.NotFoundById else LookupResult.NoMatch
                }
                userCredentials && status in USER_REJECTIONS -> LookupResult.Failure(
                    EnrichmentHttpException(provider, status),
                    retryable = false,
                )
                else -> {
                    // 401/403 без признаков квоты — ключ неверен или отозван: до завтра, как квота.
                    val quota = ProviderGate.isQuotaExhausted(status, body.take(QUOTA_SNIFF)) || status == 401 || status == 403
                    gate.onFailure(provider, quota)
                    cached?.toResult(notFoundById) ?: LookupResult.Failure(
                        EnrichmentHttpException(provider, status),
                        retryable = !quota && (status == 429 || status >= 500),
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            gate.onFailure(provider, quotaExhausted = false)
            cached?.toResult(notFoundById) ?: LookupResult.Failure(e, retryable = true)
        }
    }

    private fun EnrichmentCache.Entry.toResult(notFoundById: Boolean): LookupResult<String> = when {
        body != null -> LookupResult.Found(body)
        notFoundById -> LookupResult.NotFoundById
        else -> LookupResult.NoMatch
    }

    private fun paused(provider: String) =
        LookupResult.Failure(IllegalStateException("$provider paused"), retryable = true)

    private companion object {
        /** Признак квоты ищем в начале тела: ответ с ошибкой короткий, полезный ответ не сканируем. */
        const val QUOTA_SNIFF = 400

        /** Отказы по учётке пользователя: неверный вход, нет прав, его суточный лимит (OpenSubtitles — 406). */
        val USER_REJECTIONS = setOf(401, 403, 406, 429)
    }
}

/** Ответ API с кодом ошибки — по [status] вызывающий отличает «неверный вход» от «лимит». */
class EnrichmentHttpException(val provider: String, val status: Int) : IllegalStateException("$provider HTTP $status")

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
        if (e is CancellationException) throw e
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
