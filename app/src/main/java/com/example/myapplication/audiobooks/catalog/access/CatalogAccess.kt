package com.example.myapplication.audiobooks.catalog.access

import com.example.myapplication.audiobooks.catalog.BookCatalogConfig
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Токен установки для API каталога (docs/adr/0003-api-access-keys-and-tiers.md). Приложение доказывает серверу, что оно настоящее:
 * получает задание, просит у телефона аттестованный ключ, отдаёт цепочку сертификатов и получает токен `va_…` на 30 дней.
 *
 * Если токена нет (у телефона нет аппаратной аттестации, сервер отказал, нет сети), [token] возвращает null, и приложение работает так, как работало
 * до каталога: по прежним источникам. После отказа сервер не донимаем: следующая попытка не раньше срока паузы (`retryAtMs`).
 */
class CatalogAccess(
    private val dir: File,
    /** Обычный клиент БЕЗ перехватчика токена: через него идут сами запросы регистрации. */
    private val http: OkHttpClient,
    private val apiV1: String = BookCatalogConfig.API_V1,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val attest: (ByteArray) -> List<ByteArray> = { KeyAttestation.chain(it, preferStrongBox = true) },
) {
    @Serializable
    private data class State(val token: String? = null, val expiresAtMs: Long = 0, val retryAtMs: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val file get() = File(dir, "access.json")
    private var state: State? = null

    /** Действующий токен или null. Блокирующий вызов: берётся из сетевых потоков (перехватчик), не из главного. */
    fun token(): String? = synchronized(lock) {
        val s = load()
        val now = nowMs()
        val valid = s.token?.takeIf { s.expiresAtMs > now }
        if (valid != null && s.expiresAtMs - now > REFRESH_BEFORE_MS) return valid
        if (now < s.retryAtMs) return valid
        register(s, now) ?: valid
    }

    /** Сервер не принял этот токен (отозван, подпись сменилась): забыть его, следующий [token] зарегистрируется заново. */
    fun invalidate(token: String) = synchronized(lock) {
        if (load().token == token) save(State(retryAtMs = 0))
    }

    private fun register(old: State, now: Long): String? {
        val challenge = try {
            fetchChallenge()
        } catch (_: IOException) {
            null
        } ?: return pause(old, now, NETWORK_RETRY_MS)
        val chain = try {
            attest(Base64.getUrlDecoder().decode(challenge))
        } catch (_: Exception) {
            // нет аппаратной аттестации (старое железо, эмулятор, ограничения прошивки): повторять часто бессмысленно
            return pause(old, now, UNSUPPORTED_RETRY_MS)
        }
        return try {
            submit(challenge, chain, old, now)
        } catch (_: IOException) {
            pause(old, now, NETWORK_RETRY_MS)
        }
    }

    private fun fetchChallenge(): String? =
        http.newCall(Request.Builder().url("$apiV1/auth/challenge").build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            json.parseToJsonElement(resp.body.string()).jsonObject["challenge"]?.jsonPrimitive?.contentOrNull
        }

    private fun submit(challenge: String, chain: List<ByteArray>, old: State, now: Long): String? {
        val body = buildString {
            append("""{"challenge":""").append(json.encodeToString(challenge)).append(""","chain":[""")
            append(chain.joinToString(",") { json.encodeToString(Base64.getEncoder().encodeToString(it)) }).append("]}")
        }
        val request = Request.Builder().url("$apiV1/auth/register").post(body.toRequestBody(JSON)).build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body.string()
            if (resp.isSuccessful) {
                val o = json.parseToJsonElement(text).jsonObject
                val token = o["token"]?.jsonPrimitive?.contentOrNull ?: return pause(old, now, NETWORK_RETRY_MS)
                val expires = o["expires_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: (now + DEFAULT_TTL_MS)
                save(State(token = token, expiresAtMs = expires))
                return token
            }
            // 403: сервер понял запрос и отказал (подпись APK не в списке, программная аттестация, установка отозвана): надолго
            return pause(old, now, if (resp.code == 403) DENIED_RETRY_MS else NETWORK_RETRY_MS)
        }
    }

    private fun pause(old: State, now: Long, ms: Long): String? {
        save(old.copy(retryAtMs = now + ms))
        return null
    }

    private fun load(): State = state ?: (runCatching { json.decodeFromString<State>(file.readText()) }.getOrNull() ?: State()).also { state = it }

    private fun save(s: State) {
        state = s
        runCatching {
            dir.mkdirs()
            file.writeText(json.encodeToString(s))
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val REFRESH_BEFORE_MS = 3L * 24 * 60 * 60 * 1000
        const val DEFAULT_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val NETWORK_RETRY_MS = 10L * 60 * 1000
        const val DENIED_RETRY_MS = 12L * 60 * 60 * 1000
        const val UNSUPPORTED_RETRY_MS = 24L * 60 * 60 * 1000
    }
}
