package com.example.myapplication.network

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * trace.moe — поиск аниме по кадру.
 * @param withAnilistInfo если true, добавляется `?anilistInfo` (расширенные title в ответе).
 */
class TraceMoeRemoteDataSource(
    private val httpClient: HttpClient
) {
    private val json = AppJson

    suspend fun search(
        imageBytes: ByteArray,
        contentType: ContentType,
        withAnilistInfo: Boolean
    ): Result<TraceMoeSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (withAnilistInfo) {
                "https://api.trace.moe/search?anilistInfo"
            } else {
                "https://api.trace.moe/search"
            }
            val response = httpClient.post(url) {
                contentType(contentType)
                setBody(imageBytes)
            }.bodyAsText()
            parseTraceResponse(response, withAnilistInfo)
        }
    }

    private fun parseTraceResponse(body: String, withAnilistInfo: Boolean): TraceMoeSearchResult {
        val root = json.parseToJsonElement(body).jsonObject
        val resultArr = root["result"]?.jsonArray
            ?: throw IllegalStateException("trace.moe: empty result")
        val first = resultArr.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("trace.moe: no matches")

        val similarity = first["similarity"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: 0.0

        if (withAnilistInfo) {
            val anilist = first["anilist"]?.jsonObject
                ?: throw IllegalStateException("trace.moe: missing anilist")
            val titleObj = anilist["title"]?.jsonObject
                ?: throw IllegalStateException("trace.moe: missing title")
            val romaji = titleObj.stringField("romaji")
            val english = titleObj.stringField("english")
            val anilistId = anilist["id"]?.jsonPrimitive?.intOrNull
                ?: throw IllegalStateException("trace.moe: missing anilist id")
            return TraceMoeSearchResult(
                anilistId = anilistId,
                romajiTitle = romaji,
                englishTitle = english,
                similarity = similarity
            )
        } else {
            val anilistEl = first["anilist"] ?: throw IllegalStateException("trace.moe: missing anilist id")
            val id = when (anilistEl) {
                is JsonPrimitive -> anilistEl.intOrNull ?: throw IllegalStateException("trace.moe: expected numeric anilist id")
                else -> throw IllegalStateException("trace.moe: expected numeric anilist id")
            }
            return TraceMoeSearchResult(
                anilistId = id,
                romajiTitle = "",
                englishTitle = "",
                similarity = similarity
            )
        }
    }
}

private fun JsonObject.stringField(key: String): String {
    val e = this[key] ?: return ""
    if (e is JsonNull) return ""
    val p = e as? JsonPrimitive ?: return ""
    return if (p.isString) p.content else ""
}

data class TraceMoeSearchResult(
    val anilistId: Int,
    val romajiTitle: String,
    val englishTitle: String,
    val similarity: Double
)
