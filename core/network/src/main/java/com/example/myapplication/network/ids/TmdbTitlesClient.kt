package com.example.myapplication.network.ids

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.TokenBucketRateLimiter
import com.example.myapplication.network.enrichment.CacheTtl
import com.example.myapplication.network.enrichment.CachePolicy
import com.example.myapplication.network.enrichment.EnrichmentHttp
import com.example.myapplication.network.enrichment.EnrichmentJson
import com.example.myapplication.network.enrichment.TmdbKind
import com.example.myapplication.network.enrichment.disabled
import com.example.myapplication.network.enrichment.parse
import com.phnem.vetro.network.BuildConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Все названия тайтла в TMDB: русское и английское по переводам, оригинальное и альтернативные. */
data class TmdbTitles(
    val kind: TmdbKind,
    val id: Int,
    val titleRu: String?,
    val titleEn: String?,
    val titleOriginal: String?,
    val originalLanguage: String?,
    /** Альтернативные названия (в том числе русские прокатные) — для сопоставления и поиска. */
    val altTitles: List<String>,
    val overviewRu: String?,
    val overviewEn: String?,
    val posterPath: String?,
    val imdbId: String?,
    val tvdbId: Int?,
    val year: Int?,
)

/** Результат `find` по внешнему id: тайтл в TMDB. */
data class TmdbFound(
    val kind: TmdbKind,
    val id: Int,
    val title: String?,
    val originalTitle: String?,
)

/**
 * TMDB для названий и сопоставления id: `append_to_response=translations,alternative_titles,
 * external_ids` — один запрос даёт русское и английское название по настоящим переводам (а не по
 * подстановке оригинала), прокатные названия и IMDb/TVDB id. `find` ищет тайтл по IMDb или TVDB id.
 * Ключ проекта обязателен; без него модуль выключен.
 */
class TmdbTitlesClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
    private val apiKey: () -> String = { BuildConfig.TMDB_API_KEY },
) {
    suspend fun titles(kind: TmdbKind, id: Int): LookupResult<TmdbTitles> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        val url = "https://api.themoviedb.org/3/${kind.path}/$id?api_key=$key&language=ru-RU" +
            "&append_to_response=translations,alternative_titles,external_ids"
        return http.text(
            provider = "TMDB",
            url = url,
            rate = rate,
            notFoundById = true,
            policy = CachePolicy("tmdb:titles:${kind.path}:$id", 14 * CacheTtl.DAY, negativeTtlMs = CacheTtl.DAY),
        ).parse { TmdbTitlesParser.titles(it, kind) }
    }

    suspend fun findByImdb(imdbId: String): LookupResult<TmdbFound> = find("imdb_id", imdbId)

    suspend fun findByTvdb(tvdbId: Int): LookupResult<TmdbFound> = find("tvdb_id", tvdbId.toString())

    private suspend fun find(source: String, id: String): LookupResult<TmdbFound> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        return http.text(
            provider = "TMDB",
            url = "https://api.themoviedb.org/3/find/$id?api_key=$key&external_source=$source&language=ru-RU",
            rate = rate,
            policy = CachePolicy("tmdb:find:$source:$id", 14 * CacheTtl.DAY, negativeTtlMs = CacheTtl.DAY),
        ).parse { TmdbTitlesParser.found(it) }
    }
}

internal object TmdbTitlesParser {
    fun titles(body: String, kind: TmdbKind): TmdbTitles {
        val root = EnrichmentJson.parseToJsonElement(body).jsonObject
        val titleKey = if (kind == TmdbKind.MOVIE) "title" else "name"
        val originalKey = if (kind == TmdbKind.MOVIE) "original_title" else "original_name"
        val translations = (root["translations"] as? JsonObject)?.get("translations") as? JsonArray
        val ru = translation(translations, "ru")
        val en = translation(translations, "en")
        val originalLanguage = root.text("original_language")
        val original = root.text(originalKey)
        val external = root["external_ids"] as? JsonObject
        val dateKey = if (kind == TmdbKind.MOVIE) "release_date" else "first_air_date"
        return TmdbTitles(
            kind = kind,
            id = (root["id"] as? JsonPrimitive)?.intOrNull ?: 0,
            // Настоящие переводы; ответ на language=ru-RU без перевода подставляет оригинал — его не берём.
            titleRu = ru?.text(titleKey) ?: root.text(titleKey)?.takeIf { it != original && containsCyrillic(it) },
            titleEn = en?.text(titleKey) ?: original.takeIf { originalLanguage == "en" },
            titleOriginal = original,
            originalLanguage = originalLanguage,
            altTitles = alternatives(root["alternative_titles"] as? JsonObject),
            overviewRu = ru?.text("overview") ?: root.text("overview"),
            overviewEn = en?.text("overview"),
            posterPath = root.text("poster_path"),
            imdbId = canonicalImdb(root.text("imdb_id") ?: external?.text("imdb_id")),
            tvdbId = (external?.get("tvdb_id") as? JsonPrimitive)?.intOrNull,
            year = root.text(dateKey)?.take(4)?.toIntOrNull(),
        )
    }

    fun found(body: String): TmdbFound? {
        val root = EnrichmentJson.parseToJsonElement(body).jsonObject
        (root["movie_results"] as? JsonArray)?.firstOrNull()?.let { return foundOf(it, TmdbKind.MOVIE) }
        (root["tv_results"] as? JsonArray)?.firstOrNull()?.let { return foundOf(it, TmdbKind.TV) }
        return null
    }

    private fun foundOf(e: JsonElement, kind: TmdbKind): TmdbFound? {
        val o = e as? JsonObject ?: return null
        val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return null
        return TmdbFound(
            kind = kind,
            id = id,
            title = o.text(if (kind == TmdbKind.MOVIE) "title" else "name"),
            originalTitle = o.text(if (kind == TmdbKind.MOVIE) "original_title" else "original_name"),
        )
    }

    /** Перевод на язык: берётся запись `data` только если в ней есть заголовок или описание. */
    private fun translation(items: JsonArray?, lang: String): JsonObject? =
        items.orEmpty().firstNotNullOfOrNull { e ->
            val o = e as? JsonObject ?: return@firstNotNullOfOrNull null
            if (o.text("iso_639_1") != lang) return@firstNotNullOfOrNull null
            (o["data"] as? JsonObject)?.takeIf { it.text("title") != null || it.text("name") != null || it.text("overview") != null }
        }

    /** У фильма список лежит в `titles`, у сериала — в `results`. */
    private fun alternatives(o: JsonObject?): List<String> {
        val list = (o?.get("titles") as? JsonArray) ?: (o?.get("results") as? JsonArray) ?: return emptyList()
        return list.mapNotNull { (it as? JsonObject)?.text("title") }.distinct()
    }

    private fun containsCyrillic(s: String): Boolean = s.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
