package com.example.myapplication.manga.ja

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Сайт издателя ответил не 2xx. [code] нужен, чтобы отличать "закрыто/платно" (403/400) от сбоя сети. */
class JaHttpException(val code: Int, val url: String) : IOException("HTTP $code for $url")

/** Полный ответ сайта: [finalUrl] - адрес после редиректов, [cookies] - пары `имя=значение` из заголовков Set-Cookie. */
class JaResponse(val body: String, val finalUrl: String, val cookies: List<String>)

/**
 * HTTP для японских источников. Обычные запросы обычного браузера без входа и без обхода
 * доступа; единственное, что добавлено, - вежливость: не чаще одного запроса к хосту за
 * [minGapMillis], чтобы поиск по двенадцати сайтам не выглядел нагрузкой.
 */
class JaHttp(
    private val client: OkHttpClient,
    private val minGapMillis: Long = 250L,
) {
    private val lastCallAt = ConcurrentHashMap<String, Long>()

    suspend fun text(url: String, headers: Map<String, String> = emptyMap()): String =
        execute(url, headers) { it.body?.string() ?: throw IOException("empty body: $url") }

    suspend fun json(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        Json.parseToJsonElement(text(url, headers + ("Accept" to "application/json, text/plain, */*")))

    /**
     * Ответ целиком: тело, адрес после редиректов и cookie, которые сервер выдал в самом ответе.
     * Нужен сайтам, где идентификатор главы лежит в адресе, на который ведёт редирект, а доступ к
     * картинкам даёт подписанная cookie из ответа на описание главы.
     */
    suspend fun exchange(url: String, headers: Map<String, String> = emptyMap()): JaResponse =
        execute(url, headers) { response ->
            JaResponse(
                body = response.body?.string() ?: throw IOException("empty body: $url"),
                finalUrl = response.request.url.toString(),
                cookies = response.headers("Set-Cookie").map { it.substringBefore(';').trim() },
            )
        }

    suspend fun bytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        execute(url, headers) { it.body?.bytes() ?: throw IOException("empty body: $url") }

    /** POST с JSON-телом (GraphQL AniList). */
    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): JsonElement {
        throttle(url)
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw JaHttpException(response.code, url)
                Json.parseToJsonElement(response.body?.string() ?: throw IOException("empty body: $url"))
            }
        }
    }

    private suspend fun <T> execute(
        url: String,
        headers: Map<String, String>,
        read: (okhttp3.Response) -> T,
    ): T {
        throttle(url)
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "ja,en;q=0.8")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw JaHttpException(response.code, url)
                read(response)
            }
        }
    }

    private suspend fun throttle(url: String) {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val previous = lastCallAt.put(host, now) ?: return
        val wait = minGapMillis - (now - previous)
        if (wait > 0) {
            // Следующий вызов к этому хосту отложится ещё: резервируем слот заранее.
            lastCallAt[host] = now + wait
            delay(wait)
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
