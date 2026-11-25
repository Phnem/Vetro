package com.example.myapplication.audiobooks.data.remote.web

import com.example.myapplication.media.source.await
import com.example.myapplication.utils.runSuspendCatching
import kotlin.coroutines.cancellation.CancellationException
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
                        http.newCall(request).await().use { r ->
                            r.header("Content-Length")?.toLongOrNull()?.takeIf { r.isSuccessful && it > 0 }
                        }
                    }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                }
            }
        }.awaitAll()
    }

    /** Небольшой файл целиком (`.torrent`); не ответил — null. */
    suspend fun bytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? = withContext(Dispatchers.IO) {
        rate.acquire()
        runCatching {
            val request = Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            http.newCall(request).await().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    }

    /** Байты [from]..[to] файла (Range) — для чтения заголовка MP3; не ответил — null. */
    suspend fun range(url: String, headers: Map<String, String>, from: Long, to: Long): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Range", "bytes=$from-$to")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            http.newCall(request).await().use { r ->
                if (!r.isSuccessful) return@use null
                // B08-11: сервер без Range отдаёт файл с начала. Для смещения 0 это то что нужно, а для
                // другого смещения начало файла выдавалось бы за кадр после ID3.
                if (r.code == 206) {
                    val range = r.header("Content-Range")
                    if (range != null && !range.startsWith("bytes $from-")) return@use null
                } else if (from > 0) {
                    return@use null
                }
                r.body?.byteStream()?.use { input ->
                    val buffer = ByteArray((to - from + 1).toInt())
                    var read = 0
                    while (read < buffer.size) {
                        val n = input.read(buffer, read, buffer.size - read)
                        if (n < 0) break
                        read += n
                    }
                    buffer.copyOf(read)
                }
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    }

    private suspend fun call(builder: Request.Builder, headers: Map<String, String>): SourceResult<String> =
        withContext(Dispatchers.IO) {
            rate.acquire()
            builder.header("User-Agent", userAgent)
            headers.forEach { (k, v) -> builder.header(k, v) }
            try {
                http.newCall(builder.build()).await().use { response ->
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
    is SourceResult.Ok -> runSuspendCatching { SourceResult.Ok(transform(value)) }
        .getOrElse { SourceResult.Failed(FailureKind.PARSE, it) }
    is SourceResult.Restricted -> this
    is SourceResult.Failed -> this
}

/** Как [mapOk], но разбор сам решает, что страница — не книга (null → сбой разбора). */
internal inline fun <T, R : Any> SourceResult<T>.parseOk(transform: (T) -> SourceResult<R>): SourceResult<R> = when (this) {
    is SourceResult.Ok -> runSuspendCatching { transform(value) }.getOrElse { SourceResult.Failed(FailureKind.PARSE, it) }
    is SourceResult.Restricted -> this
    is SourceResult.Failed -> this
}
