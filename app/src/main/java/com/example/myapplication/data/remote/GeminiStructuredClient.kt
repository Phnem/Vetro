package com.example.myapplication.data.remote

import com.example.myapplication.network.AppContentType
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Gemini Generative Language API с [Structured Outputs](https://ai.google.dev/gemini-api/docs/json-mode):
 * `responseMimeType` + `responseSchema` — без regex-очистки markdown.
 */
class GeminiStructuredClient(
    private val httpClient: HttpClient
) {
    suspend fun validateApiKey(apiKey: String): Result<Unit> = runCatching {
        requireConfigured(apiKey)
        val body = buildGeminiJsonRequest(
            textPrompt = "Reply with {\"ok\": true}.",
            inlineImageBase64 = null,
            inlineMimeType = null,
            responseSchema = schemaValidation()
        )
        val text = postGenerateContent(body, apiKey)
        val parsed = json.decodeFromString<ValidationResponse>(text)
        check(parsed.ok) { "Gemini key check failed" }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val modelId = "gemini-3.1-flash-lite"

    suspend fun russianAnimeTitle(
        romaji: String,
        english: String,
        apiKey: String
    ): Result<String> = runCatching {
        requireConfigured(apiKey)
        val prompt =
            "You are helping a Russian anime database search. Given official titles: romaji=\"$romaji\", english=\"$english\". " +
                "Respond with the best Russian-language title users would recognize on Shikimori/Kinopoisk-style sites."
        val body = buildGeminiJsonRequest(
            textPrompt = prompt,
            inlineImageBase64 = null,
            inlineMimeType = null,
            responseSchema = schemaRussianTitle()
        )
        val text = postGenerateContent(body, apiKey)
        json.decodeFromString<RussianTitleResponse>(text).russianTitle.trim()
            .takeIf { it.isNotEmpty() }
            ?: error("Empty russianTitle")
    }

    suspend fun identifyMovieOrTvFromImage(
        imageBase64: String,
        mimeType: String,
        apiKey: String
    ): Result<Pair<String, AppContentType>> = runCatching {
        requireConfigured(apiKey)
        val prompt =
            "Identify the movie, TV series, or anime from this screenshot. " +
                "Return the primary English (or international) title as users would search on TMDB."
        val body = buildGeminiJsonRequest(
            textPrompt = prompt,
            inlineImageBase64 = imageBase64,
            inlineMimeType = mimeType,
            responseSchema = schemaMovieSeries()
        )
        val text = postGenerateContent(body, apiKey)
        val parsed = json.decodeFromString<MovieSeriesResponse>(text)
        val title = parsed.title.trim()
        if (title.isEmpty()) error("Empty title")
        val type = when (parsed.type.lowercase()) {
            "movie", "movies" -> AppContentType.MOVIE
            "series", "tv", "tv series", "television" -> AppContentType.SERIES
            else -> AppContentType.MOVIE
        }
        title to type
    }

    private fun requireConfigured(apiKey: String) {
        check(apiKey.isNotBlank()) { "Gemini API key is missing." }
    }

    private suspend fun postGenerateContent(body: JsonObject, apiKey: String): String {
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent"
        val responseText = httpClient.post(url) {
            // Ключ в заголовке, а не в query: URL целиком уходит в логи HTTP-клиента.
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }.bodyAsText()
        val root = json.parseToJsonElement(responseText).jsonObject
        root["error"]?.jsonObject?.let { err ->
            val msg = err["message"]?.jsonPrimitive?.content ?: "Gemini error"
            error(msg)
        }
        val candidates = root["candidates"]?.jsonArray
            ?: error("Gemini: no candidates")
        val first = candidates.firstOrNull()?.jsonObject ?: error("Gemini: empty candidates")
        val parts = first["content"]?.jsonObject?.get("parts")?.jsonArray
            ?: error("Gemini: no parts")
        val text = parts.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: error("Gemini: no text")
        return text
    }

    private fun buildGeminiJsonRequest(
        textPrompt: String,
        inlineImageBase64: String?,
        inlineMimeType: String?,
        responseSchema: JsonObject
    ): JsonObject = buildJsonObject {
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", textPrompt) })
                    if (inlineImageBase64 != null && inlineMimeType != null) {
                        add(
                            buildJsonObject {
                                put(
                                    "inline_data",
                                    buildJsonObject {
                                        put("mime_type", inlineMimeType)
                                        put("data", inlineImageBase64)
                                    }
                                )
                            }
                        )
                    }
                })
            })
        })
        put(
            "generationConfig",
            buildJsonObject {
                put("responseMimeType", "application/json")
                put("responseSchema", responseSchema)
            }
        )
    }

    private fun schemaRussianTitle(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        put(
            "properties",
            buildJsonObject {
                put(
                    "russianTitle",
                    buildJsonObject { put("type", "STRING") }
                )
            }
        )
        put("required", buildJsonArray { add(JsonPrimitive("russianTitle")) })
    }

    private fun schemaMovieSeries(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        put(
            "properties",
            buildJsonObject {
                put("title", buildJsonObject { put("type", "STRING") })
                put(
                    "type",
                    buildJsonObject {
                        put("type", "STRING")
                        put(
                            "enum",
                            buildJsonArray {
                                add(JsonPrimitive("Movie"))
                                add(JsonPrimitive("Series"))
                            }
                        )
                    }
                )
            }
        )
        put("required", buildJsonArray {
            add(JsonPrimitive("title"))
            add(JsonPrimitive("type"))
        })
    }

    private fun schemaValidation(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        put(
            "properties",
            buildJsonObject {
                put("ok", buildJsonObject { put("type", "BOOLEAN") })
            }
        )
        put("required", buildJsonArray { add(JsonPrimitive("ok")) })
    }
}

@Serializable
private data class RussianTitleResponse(
    @SerialName("russianTitle") val russianTitle: String
)

@Serializable
private data class MovieSeriesResponse(
    val title: String,
    val type: String
)

@Serializable
private data class ValidationResponse(
    val ok: Boolean
)
