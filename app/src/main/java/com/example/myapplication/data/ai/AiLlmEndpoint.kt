package com.example.myapplication.data.ai

import com.example.myapplication.network.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Универсальный клиент к любому [AiProvider]: валидация ключа, текстовый и мультимодальный
 * (image) чат. Инкапсулирует различия 4 форматов API ([AiApiStyle]) и 3 схем авторизации
 * ([AiAuthScheme]) — вызывающий код (Visual Search, дубляж названий, fallback-роутер)
 * работает с единым интерфейсом.
 *
 * HTTP — тот же Ktor [HttpClient], что и в остальной сети (см. coreNetworkModule);
 * `expectSuccess` не включён, поэтому статус проверяется вручную.
 */
class AiLlmEndpoint(
    private val httpClient: HttpClient,
) {
    private val json = AppJson

    /**
     * Проверка работоспособности ключа «дешёвым» запросом (список моделей) — без расхода токенов.
     * 2xx => ключ рабочий; 401/403 => недействителен.
     */
    suspend fun validateApiKey(provider: AiProvider, apiKey: String): Result<Unit> = runCatching {
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "Empty API key" }
        val url = "${provider.baseUrl}${provider.modelsPath}"
        val response = httpClient.get(url) { applyAuth(provider, key) }
        ensureSuccess(response)
    }

    /** Текстовый запрос. Возвращает сырой текст ответа модели. */
    suspend fun completeText(
        provider: AiProvider,
        apiKey: String,
        userPrompt: String,
        systemPrompt: String? = null,
        jsonMode: Boolean = false,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        model: String = provider.defaultModel,
    ): Result<String> = runCatching {
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "Empty API key" }
        val body = buildChatBody(provider, model, systemPrompt, userPrompt, null, null, jsonMode, maxTokens)
        postChat(provider, key, model, body)
    }

    /**
     * Мультимодальный запрос (текст + изображение). Только для vision-capable провайдеров
     * ([AiProvider.supportsVision]); иначе — ошибка.
     */
    suspend fun completeWithImage(
        provider: AiProvider,
        apiKey: String,
        userPrompt: String,
        imageBase64: String,
        mimeType: String,
        systemPrompt: String? = null,
        jsonMode: Boolean = false,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): Result<String> = runCatching {
        require(provider.supportsVision) { "${provider.displayName} does not support image input" }
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "Empty API key" }
        val model = provider.effectiveVisionModel
        val body = buildChatBody(provider, model, systemPrompt, userPrompt, imageBase64, mimeType, jsonMode, maxTokens)
        postChat(provider, key, model, body)
    }

    // region HTTP

    private suspend fun postChat(
        provider: AiProvider,
        apiKey: String,
        model: String,
        body: JsonObject,
    ): String {
        val url = when (provider.apiStyle) {
            AiApiStyle.GEMINI ->
                "${provider.baseUrl}/models/$model:generateContent"
            else -> "${provider.baseUrl}${provider.chatPath}"
        }
        val response = httpClient.post(url) {
            applyAuth(provider, apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        val text = response.bodyAsText()
        ensureSuccess(response, text)
        return parseChatText(provider, text)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth(provider: AiProvider, apiKey: String) {
        when (provider.authScheme) {
            AiAuthScheme.BEARER -> header("Authorization", "Bearer $apiKey")
            AiAuthScheme.X_API_KEY -> {
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
            }
            AiAuthScheme.GOOG_API_KEY_HEADER -> header(GOOG_API_KEY_HEADER, apiKey)
        }
    }

    private fun ensureSuccess(response: HttpResponse, bodyText: String? = null) {
        val code = response.status.value
        if (code in 200..299) return
        val message = bodyText?.let { extractError(it) }
        val full = "HTTP $code${message?.let { ": $it" }.orEmpty()}"
        if (code == 429) throw AiRateLimitException(parseRetryAfterMs(response, bodyText), full)
        error(full)
    }

    /** Задержка до повтора из 429: заголовок `Retry-After` (секунды) или Gemini `RetryInfo.retryDelay`. */
    private fun parseRetryAfterMs(response: HttpResponse, bodyText: String?): Long {
        response.headers["Retry-After"]?.trim()?.toDoubleOrNull()?.let {
            return (it * 1000).toLong().coerceAtLeast(0L)
        }
        return bodyText?.let { extractGeminiRetryDelayMs(it) } ?: DEFAULT_RATE_LIMIT_BACKOFF_MS
    }

    private fun extractGeminiRetryDelayMs(bodyText: String): Long? = runCatching {
        val details = json.parseToJsonElement(bodyText).jsonObject["error"]?.jsonObject
            ?.get("details")?.jsonArray
        details?.firstNotNullOfOrNull { element ->
            val obj = element as? JsonObject ?: return@firstNotNullOfOrNull null
            val type = obj["@type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!type.contains("RetryInfo")) return@firstNotNullOfOrNull null
            obj["retryDelay"]?.jsonPrimitive?.contentOrNull?.let { parseDurationMs(it) }
        }
    }.getOrNull()

    /** "7.7s" / "58s" / "676ms" → миллисекунды. */
    private fun parseDurationMs(raw: String): Long? {
        val t = raw.trim()
        val ms = when {
            t.endsWith("ms") -> t.dropLast(2).toDoubleOrNull()
            t.endsWith("s") -> t.dropLast(1).toDoubleOrNull()?.times(1000)
            else -> t.toDoubleOrNull()?.times(1000)
        }
        return ms?.toLong()?.coerceAtLeast(0L)
    }

    private fun extractError(bodyText: String): String? = runCatching {
        val root = json.parseToJsonElement(bodyText).jsonObject
        root["error"]?.let { err ->
            (err as? JsonObject)?.get("message")?.jsonPrimitive?.content
                ?: err.jsonPrimitive.content
        } ?: root["message"]?.jsonPrimitive?.content
    }.getOrNull()

    // endregion

    // region Request body builders

    private fun buildChatBody(
        provider: AiProvider,
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        imageBase64: String?,
        mimeType: String?,
        jsonMode: Boolean,
        maxTokens: Int,
    ): JsonObject = when (provider.apiStyle) {
        AiApiStyle.OPENAI -> buildOpenAiBody(model, systemPrompt, userPrompt, imageBase64, mimeType, jsonMode)
        AiApiStyle.ANTHROPIC -> buildAnthropicBody(model, systemPrompt, userPrompt, imageBase64, mimeType, maxTokens)
        AiApiStyle.GEMINI -> buildGeminiBody(model, systemPrompt, userPrompt, imageBase64, mimeType, jsonMode, maxTokens)
        AiApiStyle.COHERE -> buildCohereBody(model, systemPrompt, userPrompt, jsonMode)
    }

    private fun buildOpenAiBody(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        imageBase64: String?,
        mimeType: String?,
        jsonMode: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            if (systemPrompt != null) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            add(buildJsonObject {
                put("role", "user")
                if (imageBase64 != null && mimeType != null) {
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", userPrompt)
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:$mimeType;base64,$imageBase64")
                            }
                        })
                    }
                } else {
                    put("content", userPrompt)
                }
            })
        }
        if (jsonMode) {
            putJsonObject("response_format") { put("type", "json_object") }
        }
    }

    private fun buildAnthropicBody(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        imageBase64: String?,
        mimeType: String?,
        maxTokens: Int,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        if (systemPrompt != null) put("system", systemPrompt)
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    if (imageBase64 != null && mimeType != null) {
                        add(buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", mimeType)
                                put("data", imageBase64)
                            }
                        })
                    }
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", userPrompt)
                    })
                }
            })
        }
    }

    private fun buildGeminiBody(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        imageBase64: String?,
        mimeType: String?,
        jsonMode: Boolean,
        maxTokens: Int,
    ): JsonObject = buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", userPrompt) })
                    if (imageBase64 != null && mimeType != null) {
                        add(buildJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", mimeType)
                                put("data", imageBase64)
                            }
                        })
                    }
                }
            })
        }
        if (systemPrompt != null) {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", systemPrompt) })
                }
            }
        }
        // gemini-2.5* по умолчанию тратит budget на thinking и часто отдаёт пустой text.
        putJsonObject("generationConfig") {
            put("maxOutputTokens", maxTokens.coerceAtLeast(512))
            if (model.contains("2.5")) {
                putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
            }
            if (jsonMode) put("responseMimeType", "application/json")
        }
    }

    private fun buildCohereBody(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        jsonMode: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            if (systemPrompt != null) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            add(buildJsonObject {
                put("role", "user")
                put("content", userPrompt)
            })
        }
        if (jsonMode) {
            putJsonObject("response_format") { put("type", "json_object") }
        }
    }

    // endregion

    // region Response parsing

    private fun parseChatText(provider: AiProvider, bodyText: String): String {
        val root = json.parseToJsonElement(bodyText).jsonObject
        val text = when (provider.apiStyle) {
            AiApiStyle.OPENAI -> root["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content

            AiApiStyle.ANTHROPIC -> root["content"]?.jsonArray
                ?.firstNonEmptyText()

            AiApiStyle.GEMINI -> root["candidates"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray
                ?.firstNonThoughtText()

            AiApiStyle.COHERE -> root["message"]?.jsonObject
                ?.get("content")?.jsonArray
                ?.firstNonEmptyText()
        }
        return text?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("${provider.displayName}: empty response")
    }

    /** Первый непустой `text` в массиве content-частей (Anthropic/Gemini/Cohere). */
    private fun JsonArray.firstNonEmptyText(): String? = firstNotNullOfOrNull { element ->
        (element as? JsonObject)?.get("text")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }

    /** Gemini 2.5 может отдавать thought-части — их пропускаем, берём обычный text. */
    private fun JsonArray.firstNonThoughtText(): String? = firstNotNullOfOrNull { element ->
        val obj = element as? JsonObject ?: return@firstNotNullOfOrNull null
        val thought = obj["thought"]?.jsonPrimitive
        val isThought = thought?.booleanOrNull == true ||
            thought?.contentOrNull.equals("true", ignoreCase = true)
        if (isThought) return@firstNotNullOfOrNull null
        obj["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }

    // endregion

    private companion object {
        const val DEFAULT_MAX_TOKENS = 4096
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val GOOG_API_KEY_HEADER = "x-goog-api-key"
        const val DEFAULT_RATE_LIMIT_BACKOFF_MS = 30_000L
    }
}

/** Провайдер вернул HTTP 429 (rate limit / quota). [retryAfterMs] — рекомендованная пауза до повтора. */
class AiRateLimitException(val retryAfterMs: Long, message: String) : Exception(message)
