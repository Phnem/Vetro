package com.example.myapplication.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val TAG = "AnilibriaRemote"

/**
 * AniLibria — русскоязычные описания, постеры и серии в их озвучке. Ключей не требует.
 *
 * Основной эндпоинт — новый anilibria.top /api/v1 (тот же, что уже проверен в
 * [KtorWebLinkResolver.resolveAniLibria]); при сбое — легаси api.anilibria.tv /v3.
 * Форматы ответов различаются, поэтому маппер парсит оба варианта полей.
 */
class AnilibriaRemoteDataSource(
    private val client: HttpClient,
    private val burstRate: TokenBucketRateLimiter,
) {
    private val json = AppJson

    suspend fun searchAnime(query: String, limit: Int = 10): Result<List<ApiSearchResult>> = runCatching {
        val q = query.trim()
        if (q.isEmpty()) return@runCatching emptyList()
        burstRate.acquire()
        val items = searchV1(q, limit) ?: searchV3(q, limit) ?: emptyList()
        items.mapNotNull { it.toApiSearchResult() }
    }

    /** Детали (RU-описание) по названию: первый релиз с совпадающим именем. */
    suspend fun fetchAnimeDetails(query: String): Result<AnimeDetails?> = runCatching {
        val q = query.trim()
        if (q.isEmpty()) return@runCatching null
        burstRate.acquire()
        val items = searchV1(q, 5) ?: searchV3(q, 5) ?: return@runCatching null
        val matched = items.firstOrNull { obj ->
            val (main, english) = obj.titles()
            isTitleSimilar(q, main ?: "", english ?: "")
        } ?: return@runCatching null

        // v1-поиск может не отдавать description — дотягиваем полный релиз по alias/id.
        val enriched = if (matched.descriptionText().isNullOrBlank()) {
            fetchFullReleaseV1(matched) ?: matched
        } else {
            matched
        }
        val result = enriched.toApiSearchResult() ?: return@runCatching null
        AnimeDetails(
            title = result.title,
            altTitle = result.altTitle,
            description = result.description,
            type = result.type,
            status = enriched.statusText(),
            episodesAired = result.episodes,
            episodesTotal = result.episodes.takeIf { it > 0 },
            nextEpisode = null,
            genres = result.genres,
            rating = null,
            posterUrl = result.posterUrl,
            source = SOURCE,
            airedOn = enriched.yearText(),
        )
    }

    // ---- HTTP ----

    private suspend fun searchV1(query: String, limit: Int): List<JsonObject>? = runCatching {
        val body = client.get("https://anilibria.top/api/v1/app/search/releases") {
            parameter("query", query)
        }.bodyAsText()
        val root = json.parseToJsonElement(body)
        val arr = (root as? JsonArray) ?: (root as? JsonObject)?.get("data")?.jsonArray ?: return@runCatching null
        arr.mapNotNull { it as? JsonObject }.take(limit)
    }.onFailure { Log.w(TAG, "v1 search failed: ${it.message}") }.getOrNull()

    private suspend fun searchV3(query: String, limit: Int): List<JsonObject>? = runCatching {
        val body = client.get("https://api.anilibria.tv/v3/title/search") {
            parameter("search", query)
            parameter("limit", limit)
        }.bodyAsText()
        val root = json.parseToJsonElement(body).jsonObject
        root["list"]?.jsonArray?.mapNotNull { it as? JsonObject }
    }.onFailure { Log.w(TAG, "v3 search failed: ${it.message}") }.getOrNull()

    private suspend fun fetchFullReleaseV1(item: JsonObject): JsonObject? = runCatching {
        val key = item["alias"]?.jsonPrimitive?.contentOrNull
            ?: item["id"]?.jsonPrimitive?.intOrNull?.toString()
            ?: return@runCatching null
        burstRate.acquire()
        val body = client.get("https://anilibria.top/api/v1/anime/releases/$key").bodyAsText()
        json.parseToJsonElement(body) as? JsonObject
    }.onFailure { Log.w(TAG, "v1 release fetch failed: ${it.message}") }.getOrNull()

    // ---- Парсинг (оба формата: v1 name{main,english} / v3 names{ru,en}) ----

    private fun JsonObject.titles(): Pair<String?, String?> {
        (this["name"] as? JsonObject)?.let { n ->
            return n.str("main") to n.str("english")
        }
        (this["names"] as? JsonObject)?.let { n ->
            return n.str("ru") to n.str("en")
        }
        return null to null
    }

    private fun JsonObject.descriptionText(): String? = str("description")

    private fun JsonObject.posterUrl(): String? {
        // v1: poster{src|preview|optimized{src}}, пути относительно anilibria.top
        (this["poster"] as? JsonObject)?.let { p ->
            val path = p.str("src")
                ?: p.str("preview")
                ?: (p["optimized"] as? JsonObject)?.str("src")
            path?.let { return absolutize(it, "https://anilibria.top") }
        }
        // v3: posters{original{url}}, пути относительно anilibria.tv
        (this["posters"] as? JsonObject)?.let { p ->
            val path = (p["original"] as? JsonObject)?.str("url")
                ?: (p["medium"] as? JsonObject)?.str("url")
            path?.let { return absolutize(it, "https://anilibria.tv") }
        }
        return null
    }

    /** Онгоинг: v1 is_ongoing, v3 status.code == 1 («в работе»). */
    private fun JsonObject.isOngoingFlag(): Boolean? {
        this["is_ongoing"]?.jsonPrimitive?.booleanOrNull?.let { return it }
        (this["status"] as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNull?.let { return it == 1 }
        return null
    }

    /** Озвучено серий на сейчас: v3 player.episodes.last; v1 такого поля не отдаёт. */
    private fun JsonObject.airedNow(): Int? =
        ((this["player"] as? JsonObject)?.get("episodes") as? JsonObject)
            ?.get("last")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }

    /** Заявлено серий всего: v1 episodes_total, v3 type.episodes. */
    private fun JsonObject.totalPlanned(): Int? =
        this["episodes_total"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            ?: (this["type"] as? JsonObject)?.get("episodes")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }

    private fun JsonObject.episodesCount(): Int {
        this["episodes_total"]?.jsonPrimitive?.intOrNull?.let { if (it > 0) return it }
        (this["player"] as? JsonObject)?.let { pl ->
            ((pl["episodes"] as? JsonObject)?.get("last"))?.jsonPrimitive?.intOrNull?.let { if (it > 0) return it }
        }
        (this["type"] as? JsonObject)?.get("episodes")?.jsonPrimitive?.intOrNull?.let { if (it > 0) return it }
        return 0
    }

    private fun JsonObject.genreNames(): List<String> {
        val arr = this["genres"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            when (el) {
                is JsonObject -> el.str("name")
                else -> el.jsonPrimitive.contentOrNull
            }?.takeIf { it.isNotBlank() }
        }
    }

    private fun JsonObject.typeText(): String {
        val t = this["type"] as? JsonObject ?: return ""
        return t.str("description") ?: t.str("string") ?: t.str("full_string") ?: ""
    }

    private fun JsonObject.statusText(): String {
        this["is_ongoing"]?.jsonPrimitive?.booleanOrNull?.let { return if (it) "ongoing" else "released" }
        return (this["status"] as? JsonObject)?.str("string") ?: ""
    }

    private fun JsonObject.yearText(): String? {
        this["year"]?.jsonPrimitive?.intOrNull?.let { return it.toString() }
        return (this["season"] as? JsonObject)?.get("year")?.jsonPrimitive?.intOrNull?.toString()
    }

    private fun JsonObject.toApiSearchResult(): ApiSearchResult? {
        val (ru, en) = titles()
        val title = ru?.takeIf { it.isNotBlank() } ?: en?.takeIf { it.isNotBlank() } ?: return null
        return ApiSearchResult(
            title = title,
            altTitle = en?.takeIf { it.isNotBlank() && it != title },
            posterUrl = posterUrl(),
            episodes = episodesCount(),
            description = descriptionText()?.trim().orEmpty(),
            type = typeText(),
            genres = genreNames(),
            // Своей пользовательской оценки у AniLibria нет.
            rating = null,
            source = SOURCE,
            categoryType = "ANIME",
            externalId = this["id"]?.jsonPrimitive?.intOrNull?.toString(),
            isOngoing = isOngoingFlag(),
            airedEpisodes = airedNow(),
            totalEpisodes = totalPlanned(),
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonElement)?.let { el ->
            (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

    private fun absolutize(path: String, host: String): String =
        if (path.startsWith("http")) path else "$host$path"

    companion object {
        const val SOURCE = "Anilibria"
    }
}
