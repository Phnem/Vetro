package com.example.myapplication.audiobooks.data.remote.web

import com.example.myapplication.audiobooks.domain.source.Availability
import com.example.myapplication.audiobooks.domain.source.FailureKind
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.network.TokenBucketRateLimiter
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Сеть одного сайта-источника: общий клиент, свой лимит запросов на хост и единый перевод HTTP-ответа
 * в [SourceResult]. Запросы только от действий пользователя (поиск, открытие книги, запуск).
 */
class SourceHttp(
    private val http: OkHttpClient,
    private val rate: TokenBucketRateLimiter,
    private val userAgent: String = DESKTOP_UA,
) {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): SourceResult<String> =
        call(Request.Builder().url(url).get(), headers)

    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): SourceResult<String> {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return call(Request.Builder().url(url).post(body), headers)
    }

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): SourceResult<String> {
        val body: RequestBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        return call(Request.Builder().url(url).post(body), headers)
    }

    /**
     * Размеры файлов (HEAD, Content-Length) — для раскладки общей длительности книги по трекам, когда
     * сайт отдаёт только ссылки. Не больше [PARALLEL] запросов разом; не ответил — null.
     */
    suspend fun contentLengths(urls: List<String>, headers: Map<String, String>): List<Long?> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        urls.map { url ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        val request = Request.Builder().url(url).head()
                            .header("User-Agent", userAgent)
                            .apply { headers.forEach { (k, v) -> header(k, v) } }
                            .build()
                        http.newCall(request).execute().use { r ->
                            r.header("Content-Length")?.toLongOrNull()?.takeIf { r.isSuccessful && it > 0 }
                        }
                    }.getOrNull()
                }
            }
        }.awaitAll()
    }

    private suspend fun call(builder: Request.Builder, headers: Map<String, String>): SourceResult<String> =
        withContext(Dispatchers.IO) {
            rate.acquire()
            builder.header("User-Agent", userAgent)
            headers.forEach { (k, v) -> builder.header(k, v) }
            try {
                http.newCall(builder.build()).execute().use { response ->
                    when {
                        response.isSuccessful -> SourceResult.Ok(response.body?.string().orEmpty())
                        response.code == 404 || response.code == 410 -> SourceResult.Restricted(Availability.REMOVED)
                        response.code == 429 -> SourceResult.Failed(FailureKind.RATE_LIMITED)
                        response.code == 403 || response.code == 503 -> SourceResult.Failed(FailureKind.BLOCKED)
                        else -> SourceResult.Failed(FailureKind.NETWORK, IOException("HTTP ${response.code}"))
                    }
                }
            } catch (e: SocketTimeoutException) {
                SourceResult.Failed(FailureKind.TIMEOUT, e)
            } catch (e: IOException) {
                SourceResult.Failed(FailureKind.NETWORK, e)
            }
        }

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"
        private const val PARALLEL = 6
    }
}

/** Преобразование удачного ответа; исключение парсера — сбой разбора, а не падение. */
internal inline fun <T, R> SourceResult<T>.mapOk(transform: (T) -> R): SourceResult<R> = when (this) {
    is SourceResult.Ok -> runCatching { SourceResult.Ok(transform(value)) }
        .getOrElse { SourceResult.Failed(FailureKind.PARSE, it) }
    is SourceResult.Restricted -> this
    is SourceResult.Failed -> this
}

/** Как [mapOk], но разбор сам решает, что страница — не книга (null → сбой разбора). */
internal inline fun <T, R : Any> SourceResult<T>.parseOk(transform: (T) -> SourceResult<R>): SourceResult<R> = when (this) {
    is SourceResult.Ok -> runCatching { transform(value) }.getOrElse { SourceResult.Failed(FailureKind.PARSE, it) }
    is SourceResult.Restricted -> this
    is SourceResult.Failed -> this
}
