package com.example.myapplication.mediacatalog

import com.example.myapplication.audiobooks.catalog.BookCatalogConfig
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.ExternalIds
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Облачный каталог аниме, фильмов и сериалов (`GET /v1/find?kind=`): при поиске по выбранному направлению ищет только в своей базе
 * (аниме — в аниме, фильмы — в фильмах, сериалы — в сериалах), а каждая запись приходит с пометкой `kind`.
 * Сбой сети или таймаут — пустой список: поиск идёт по прежним источникам, как до каталога.
 */
class CloudMediaSearch(
    private val http: OkHttpClient,
    private val apiV1: String = BookCatalogConfig.API_V1,
    private val covers: String = BookCatalogConfig.COVERS,
    private val coversSupported: () -> Boolean = { BookCatalogConfig.coversSupported },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Направление поиска → `kind` API; для манги в облаке базы нет. */
    fun kindOf(contentType: AppContentType): String? = when (contentType) {
        AppContentType.ANIME -> "anime"
        AppContentType.MOVIE -> "movie"
        AppContentType.SERIES -> "tv"
        else -> null
    }

    suspend fun search(query: String, contentType: AppContentType, language: AppLanguage, limit: Int = 20): List<ApiSearchResult> {
        val kind = kindOf(contentType) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val url = "$apiV1/find".toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("q", query)?.addQueryParameter("kind", kind)?.addQueryParameter("limit", limit.toString())?.build()
                    ?: return@withContext emptyList()
                val client = http.newBuilder().callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    parse(resp.body.string(), contentType, language)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    internal fun parse(body: String, contentType: AppContentType, language: AppLanguage): List<ApiSearchResult> =
        json.parseToJsonElement(body).jsonObject["items"]?.jsonArray.orEmpty().mapNotNull { toResult(it.jsonObject, contentType, language) }

    /**
     * Запись облака в тот же вид, что дают прежние источники: по id в других базах (AniList, Shikimori, MAL, TMDB) результат открывается и
     * добавляется прежним путём. Записи без таких id пропускаются: открыть их было бы нечем.
     */
    internal fun toResult(o: JsonObject, contentType: AppContentType, language: AppLanguage): ApiSearchResult? {
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val ext = o["ext"]?.jsonObject
        fun id(k: String) = ext?.get(k)?.jsonPrimitive?.intOrNull
        val ru = s("ru")
        val en = s("en")
        val ro = s("ro")
        val orig = s("orig")
        val title = (if (language == AppLanguage.RU) ru ?: en ?: ro ?: orig else en ?: ro ?: ru ?: orig) ?: return null
        val alt = listOfNotNull(en, ro, ru, orig).firstOrNull { it != title }
        val cloudId = s("id") ?: return null
        val cv = o["cv"]?.jsonPrimitive?.intOrNull ?: 0
        val hasCover = ((o["f"]?.jsonPrimitive?.intOrNull ?: 0) and 1) != 0 && coversSupported()
        val poster = if (hasCover) "$covers/media/large/$cloudId.avif?v=$cv" else null
        val year = o["y"]?.jsonPrimitive?.intOrNull
        val ids = ExternalIds(
            anilist = id("anilist"), mal = id("mal"), shikimori = id("shikimori"),
            tmdb = id("tmdb"), imdb = ext?.get("imdb")?.jsonPrimitive?.contentOrNull, tvdb = id("tvdb"),
        )
        val (source, externalId) = when (contentType) {
            AppContentType.ANIME -> when {
                ids.anilist != null -> "AniList" to ids.anilist.toString()
                ids.shikimori != null -> "Shikimori" to ids.shikimori.toString()
                ids.mal != null -> "MAL" to ids.mal.toString()
                else -> return null
            }
            AppContentType.MOVIE, AppContentType.SERIES -> ids.tmdb?.let { "TMDB" to it.toString() } ?: return null
            else -> return null
        }
        return ApiSearchResult(
            title = title,
            altTitle = alt,
            posterUrl = poster,
            episodes = if (contentType == AppContentType.MOVIE) 1 else 0,
            description = "",
            type = when (contentType) {
                AppContentType.ANIME -> "ANIME"
                AppContentType.MOVIE -> "Movie"
                else -> "TV"
            },
            genres = emptyList(),
            rating = null,
            source = source,
            categoryType = contentType.name,
            externalId = externalId,
            malId = ids.mal,
            format = s("st")?.uppercase(),
            seasonYear = year,
            originalTitle = orig,
            titleEn = en,
            titleRu = ru,
            externalIds = ids,
        )
    }

    companion object {
        private const val TIMEOUT_MS = 2500L

        /**
         * Облачная выдача — первой (она быстрее и уже отфильтрована по базе), остальное из прежних источников следом. Если запись есть и там,
         * и там (тот же источник и id), остаётся прежняя: у неё богаче постер, описание и жанры, а место берётся облачное.
         */
        fun mergeCloudFirst(cloud: List<ApiSearchResult>, existing: List<ApiSearchResult>): List<ApiSearchResult> {
            if (cloud.isEmpty()) return existing
            fun key(r: ApiSearchResult) = r.externalId?.let { "${r.source.lowercase()}:$it" }
            val richer = existing.mapNotNull { r -> key(r)?.let { it to r } }.toMap()
            val used = HashSet<String>()
            val head = cloud.map { c ->
                val k = key(c)
                val better = k?.let { richer[it] }
                if (k != null && better != null) used += k
                better ?: c
            }
            return head + existing.filter { r -> key(r)?.let { it !in used } ?: true }
        }
    }
}
