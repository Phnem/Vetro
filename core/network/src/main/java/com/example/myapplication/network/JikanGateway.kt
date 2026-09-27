package com.example.myapplication.network

import com.example.myapplication.network.enrichment.ProviderGate
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLProtocol
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Единственная дверь к Jikan (неофициальный API MAL). Jikan регулярно отвечает 504, когда лежит сам
 * MAL, и каждый такой запрос ждал таймаута. Здесь:
 * - свой лимит (Jikan: 3 запроса в секунду) и короткий таймаут;
 * - выключатель: 5xx, 429, таймаут и обрыв — сбой; после второго подряд Jikan пропускается без
 *   сети на 1, 5, затем 30 минут. 429 — это посекундный лимит Jikan, а не суточная квота: до полуночи
 *   он провайдер не закрывает;
 * - 404 — нормальный ответ «нет такого», не сбой.
 * null — ответа нет (закрыт, сбой, 404): вызывающий идёт к следующему источнику каскада.
 */
class JikanGateway(
    private val client: HttpClient,
    private val gate: ProviderGate = ProviderGate(),
    private val rate: TokenBucketRateLimiter = TokenBucketRateLimiter(maxTokens = 3.0, refillTokensPerSecond = 1.0),
    private val timeoutMs: Long = 10_000,
) {
    val isOpen: Boolean get() = gate.isOpen(PROVIDER)

    suspend fun get(segments: List<String>, params: Map<String, String> = emptyMap()): JsonObject? {
        if (!gate.isOpen(PROVIDER)) return null
        return try {
            var (status, body) = fetch(segments, params, bypassCache = false)
            // Ошибка в теле могла прийти из HTTP-кэша OkHttp (Jikan разрешает кэшировать сутки, и однажды
            // закэшированный таймаут MAL держался бы весь день): один раз спрашиваем сервер заново.
            if (status >= 500 && body != null) {
                val fresh = fetch(segments, params, bypassCache = true)
                status = fresh.first
                body = fresh.second
            }
            when (status) {
                in 200..299 -> body.also { gate.onSuccess(PROVIDER) }
                404 -> null.also { gate.onSuccess(PROVIDER) }
                429, in 500..599 -> null.also { gate.onFailure(PROVIDER, quotaExhausted = false) }
                else -> null.also { if (status >= 400) gate.onSuccess(PROVIDER) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Таймаут, обрыв, битый JSON (страница ошибки вместо ответа) — сбой сервиса.
            gate.onFailure(PROVIDER, quotaExhausted = false)
            null
        }
    }

    /** Код ответа (из тела, если Jikan прячет ошибку в HTTP 200) и тело. */
    private suspend fun fetch(segments: List<String>, params: Map<String, String>, bypassCache: Boolean): Pair<Int, JsonObject?> {
        rate.acquire()
        val response = client.get {
            url {
                protocol = URLProtocol.HTTPS
                host = HOST
                appendPathSegments(listOf("v4") + segments)
                params.forEach { (name, value) -> parameters.append(name, value) }
            }
            if (bypassCache) header(HttpHeaders.CacheControl, "no-cache")
            timeout { requestTimeoutMillis = timeoutMs }
        }
        val body = if (response.status.value in 200..299) AppJson.parseToJsonElement(response.bodyAsText()).jsonObject else null
        // Jikan отдаёт ошибку MAL и внутри тела при HTTP 200: {"status":500,"type":"UpstreamException"}.
        val inBody = body?.get("status")?.jsonPrimitive?.intOrNull?.takeIf { body["data"] == null }
        return (inBody ?: response.status.value) to body
    }

    private companion object {
        const val PROVIDER = "Jikan"
        const val HOST = "api.jikan.moe"
    }
}
