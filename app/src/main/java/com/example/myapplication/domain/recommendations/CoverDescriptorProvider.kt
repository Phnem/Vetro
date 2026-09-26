package com.example.myapplication.domain.recommendations

import com.example.myapplication.network.AppJson
import android.util.Log
import com.example.myapplication.data.ai.AiCredentialsStore
import com.example.myapplication.data.ai.AiLlmEndpoint
import com.example.myapplication.data.local.CoverDescriptorCacheStore
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Base64

private const val TAG = "CoverDescriptorProvider"
private const val DESCRIPTOR_PROMPT = "Describe this anime/manga cover in 3 to 6 short visual " +
    "style tags (art style, color palette, mood) — e.g. \"pastel\", \"dark fantasy\", " +
    "\"chibi\", \"mecha\", \"watercolor\". Respond with a JSON object {\"tags\": [\"...\"]}. " +
    "Return ONLY the JSON."

/**
 * BALSE-style visual cold-start signal, adapted to this app's BYOK vision-chat AI: [AiProvider]
 * has no embeddings endpoint, so similarity is computed over a small set of visual descriptor
 * tags extracted through a vision-capable provider (cached per cover), not a literal embedding
 * vector — see visualSimilarityScore.
 *
 * Every entry point degrades to `null`/`false` on any failure — no configured vision-capable
 * provider, network error, rate limit, malformed response — since this signal is optional
 * additive polish for cold start only, never allowed to block or error the recommendation flow.
 */
class CoverDescriptorProvider(
    private val credentialsStore: AiCredentialsStore,
    private val aiEndpoint: AiLlmEndpoint,
    private val cache: CoverDescriptorCacheStore,
    private val httpClient: OkHttpClient,
) {
    private val json = AppJson

    /** Есть ли смысл вообще пытаться — вызывающий пропускает весь визуальный путь иначе. */
    fun isAvailable(): Boolean =
        credentialsStore.getAllConnectedProviders().any { it.supportsVision }

    /** Дескрипторы обложки из коллекции пользователя (уже сохранена локально). */
    suspend fun descriptorsForLocalFile(filePath: String): Set<String>? =
        descriptorsFor(cacheKey = filePath) { File(filePath).readBytes() }

    /** Дескрипторы обложки кандидата — скачивается по HTTP. */
    suspend fun descriptorsForUrl(url: String): Set<String>? =
        descriptorsFor(cacheKey = url) {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.bytes() ?: error("Empty response body")
            }
        }

    private suspend fun descriptorsFor(cacheKey: String, loadBytes: () -> ByteArray): Set<String>? {
        cache.read(cacheKey)?.let { return it.toSet() }
        val provider = credentialsStore.getAllConnectedProviders().firstOrNull { it.supportsVision }
            ?: return null
        val apiKey = credentialsStore.getApiKey(provider) ?: return null
        return runCatching {
            val imageBase64 = Base64.getEncoder().encodeToString(loadBytes())
            val text = aiEndpoint.completeWithImage(
                provider = provider,
                apiKey = apiKey,
                userPrompt = DESCRIPTOR_PROMPT,
                imageBase64 = imageBase64,
                mimeType = "image/jpeg",
                jsonMode = true,
            ).getOrThrow()
            val tags = parseTags(text)
            cache.write(cacheKey, tags)
            tags.toSet()
        }.getOrElse { error ->
            // Рейт-лимит и любая другая ошибка — тихо «нет сигнала», а не блокировка cold start.
            Log.w(TAG, "Cover descriptor extraction skipped for $cacheKey", error)
            null
        }
    }

    private fun parseTags(raw: String): List<String> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        val jsonText = if (start in 0 until end) raw.substring(start, end + 1) else raw
        val obj = json.parseToJsonElement(jsonText).jsonObject
        return obj["tags"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.trim().lowercase().ifBlank { null } }
            .orEmpty()
    }
}
