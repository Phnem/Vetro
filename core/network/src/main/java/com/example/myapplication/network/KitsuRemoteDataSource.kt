package com.example.myapplication.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

private const val TAG = "KitsuRemote"

/**
 * Kitsu (kitsu.io) — стабильный REST по спецификации JSON:API, без авторизации на чтение.
 * EN-источник: английские названия, synopsis, постеры, оценки (0–100, как у AniList).
 *
 * Жанры тянем тем же запросом через `include=categories` — JSON:API кладёт их в `included`.
 */
class KitsuRemoteDataSource(
    private val client: HttpClient,
    private val burstRate: TokenBucketRateLimiter,
) {
    private val json = AppJson

    suspend fun searchAnime(query: String, limit: Int = 10): Result<List<ApiSearchResult>> = runCatching {
        val q = query.trim()
        if (q.isEmpty()) return@runCatching emptyList()
        burstRate.acquire()
        val body = client.get("https://kitsu.io/api/edge/anime") {
            parameter("filter[text]", q)
            parameter("page[limit]", limit.coerceIn(1, 20))
            parameter("include", "categories")
            parameter("fields[categories]", "title")
            header("Accept", "application/vnd.api+json")
        }.bodyAsText()
        parseAnimeList(body)
    }

    /** Детали по названию: первый результат с совпадающим именем, EN synopsis. */
    suspend fun fetchAnimeDetails(query: String): Result<AnimeDetails?> = runCatching {
        val q = query.trim()
        if (q.isEmpty()) return@runCatching null
        val results = searchAnime(q, 5).getOrThrow()
        results.firstOrNull { isTitleSimilar(q, it.title, it.altTitle ?: "") }
            ?.toAnimeDetails()
            ?.copy(source = SOURCE)
    }

    suspend fun animeById(id: Int): Result<ApiSearchResult?> = runCatching {
        burstRate.acquire()
        val body = client.get("https://kitsu.io/api/edge/anime/$id") {
            parameter("include", "categories")
            parameter("fields[categories]", "title")
            header("Accept", "application/vnd.api+json")
        }.bodyAsText()
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"] as? JsonObject ?: return@runCatching null
        val categories = categoryTitlesById(root)
        data.toApiSearchResult(categories)
    }.onFailure { Log.w(TAG, "animeById failed: ${it.message}") }

    private fun parseAnimeList(body: String): List<ApiSearchResult> {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        val categories = categoryTitlesById(root)
        return data.mapNotNull { (it as? JsonObject)?.toApiSearchResult(categories) }
    }

    /** Map id категории → её title из `included` (JSON:API). */
    private fun categoryTitlesById(root: JsonObject): Map<String, String> {
        val included = root["included"]?.jsonArray ?: return emptyMap()
        return included.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "categories") return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = (obj["attributes"] as? JsonObject)
                ?.get("title")?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            id to title
        }.toMap()
    }

    private fun JsonObject.toApiSearchResult(categories: Map<String, String>): ApiSearchResult? {
        val attrs = this["attributes"] as? JsonObject ?: return null
        val titlesObj = attrs["titles"] as? JsonObject
        val en = titlesObj?.get("en")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val enJp = titlesObj?.get("en_jp")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val canonical = attrs["canonicalTitle"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val title = en ?: canonical ?: enJp ?: return null
        val alt = listOfNotNull(canonical, enJp).firstOrNull { it != title }

        val synopsis = attrs["synopsis"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        // averageRating — строка процентов "82.5" (та же шкала 0–100, что averageScore AniList)
        val rating = attrs["averageRating"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.toInt()
        val episodes = attrs["episodeCount"]?.jsonPrimitive?.intOrNull ?: 0
        val poster = (attrs["posterImage"] as? JsonObject)?.let { p ->
            p["large"]?.jsonPrimitive?.contentOrNull
                ?: p["original"]?.jsonPrimitive?.contentOrNull
                ?: p["medium"]?.jsonPrimitive?.contentOrNull
        }
        val genreIds = ((this["relationships"] as? JsonObject)
            ?.get("categories") as? JsonObject)
            ?.get("data")?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
            .orEmpty()
        val genres = genreIds.mapNotNull { categories[it] }

        return ApiSearchResult(
            title = title,
            altTitle = alt,
            posterUrl = poster,
            episodes = episodes,
            description = synopsis,
            type = attrs["subtype"]?.jsonPrimitive?.contentOrNull ?: "",
            genres = genres,
            rating = rating,
            source = SOURCE,
            categoryType = "ANIME",
            externalId = this["id"]?.jsonPrimitive?.contentOrNull,
            // Kitsu отдаёт статус и план серий, но не «сколько вышло сейчас».
            isOngoing = attrs["status"]?.jsonPrimitive?.contentOrNull?.let { it == "current" },
            totalEpisodes = episodes.takeIf { it > 0 },
        )
    }

    companion object {
        const val SOURCE = "Kitsu"
    }
}
