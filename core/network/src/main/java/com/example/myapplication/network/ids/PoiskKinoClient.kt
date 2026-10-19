package com.example.myapplication.network.ids

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.TokenBucketRateLimiter
import com.example.myapplication.network.enrichment.CacheTtl
import com.example.myapplication.network.enrichment.CachePolicy
import com.example.myapplication.network.enrichment.EnrichmentHttp
import com.example.myapplication.network.enrichment.EnrichmentJson
import com.example.myapplication.network.enrichment.disabled
import com.example.myapplication.network.enrichment.parse
import com.phnem.vetro.network.BuildConfig
import io.ktor.client.request.header
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Тайтл из ПоискКино: русские названия и описание — оттуда, id TMDB и IMDb — в той же записи. */
data class PoiskKinoTitle(
    val kinopoiskId: Int,
    val nameRu: String?,
    val nameOriginal: String?,
    val nameEn: String?,
    val year: Int?,
    /** `movie`, `tv-series`, `cartoon`, `anime`, `animated-series`, `tv-show`. */
    val type: String?,
    val description: String?,
    val shortDescription: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val ratingKp: Double?,
    val ratingImdb: Double?,
    val imdbId: String?,
    val tmdbId: Int?,
    val genres: List<String>,
    val countries: List<String>,
) {
    val isSeries: Boolean get() = type == "tv-series" || type == "animated-series" || type == "tv-show"

    fun crossIds(): CrossIds = CrossIds(
        kinopoisk = kinopoiskId,
        imdb = imdbId,
        tmdbMovie = tmdbId.takeUnless { isSeries },
        tmdbTv = tmdbId.takeIf { isSeries },
    )
}

/**
 * ПоискКино (бывший kinopoisk.dev, `api.poiskkino.dev`) — каталог на данных Кинопоиска с id TMDB и
 * IMDb в одной записи: русские названия, описание, постеры, оценки КП и IMDb. Бесплатный ключ даёт
 * 200 запросов в сутки (его присылает Telegram-бот @poiskkinodev_bot), поэтому ответы кэшируются на
 * неделю, а без ключа провайдер выключен и сеть не трогает.
 *
 * Описание есть примерно у четверти записей каталога и у всех самых популярных — клиент отдаёт то,
 * что есть, а остальные поля добирают TMDB и Википедия.
 */
class PoiskKinoClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
    private val apiKey: () -> String = { BuildConfig.POISKKINO_API_KEY },
) {
    suspend fun byId(kinopoiskId: Int): LookupResult<PoiskKinoTitle> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        return http.text(
            provider = "PoiskKino",
            url = "$BASE/movie/$kinopoiskId",
            rate = rate,
            notFoundById = true,
            policy = CachePolicy("poiskkino:movie:$kinopoiskId", 7 * CacheTtl.DAY, negativeTtlMs = CacheTtl.DAY),
            configure = { header("X-API-KEY", key) },
        ).parse { PoiskKinoParser.title(it) }
    }

    suspend fun search(query: String, year: Int? = null): LookupResult<List<PoiskKinoTitle>> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        val clean = query.trim()
        if (clean.isEmpty()) return LookupResult.NoMatch
        val yearPart = year?.let { "&year=$it" }.orEmpty()
        return http.text(
            provider = "PoiskKino",
            url = "$BASE/movie/search?page=1&limit=10&query=" + URLEncoder.encode(clean, "UTF-8") + yearPart,
            rate = rate,
            policy = CachePolicy("poiskkino:search:${clean.lowercase()}:${year ?: 0}", 7 * CacheTtl.DAY, negativeTtlMs = CacheTtl.DAY),
            configure = { header("X-API-KEY", key) },
        ).parse { PoiskKinoParser.search(it).takeIf { list -> list.isNotEmpty() } }
    }

    private companion object {
        const val BASE = "https://api.poiskkino.dev/v1.4"
    }
}

internal object PoiskKinoParser {
    fun title(body: String): PoiskKinoTitle? = parseDoc(EnrichmentJson.parseToJsonElement(body).jsonObject)

    fun search(body: String): List<PoiskKinoTitle> {
        val docs = (EnrichmentJson.parseToJsonElement(body).jsonObject["docs"] as? JsonArray).orEmpty()
        return docs.mapNotNull { (it as? JsonObject)?.let(::parseDoc) }
    }

    private fun parseDoc(o: JsonObject): PoiskKinoTitle? {
        val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return null
        val external = o["externalId"] as? JsonObject
        val rating = o["rating"] as? JsonObject
        return PoiskKinoTitle(
            kinopoiskId = id,
            nameRu = o.text("name"),
            nameOriginal = o.text("alternativeName"),
            nameEn = o.text("enName") ?: englishName(o["names"]),
            year = (o["year"] as? JsonPrimitive)?.intOrNull,
            type = o.text("type"),
            description = o.text("description"),
            shortDescription = o.text("shortDescription"),
            posterUrl = (o["poster"] as? JsonObject)?.text("url"),
            backdropUrl = (o["backdrop"] as? JsonObject)?.text("url"),
            ratingKp = rating?.number("kp")?.takeIf { it > 0 },
            ratingImdb = rating?.number("imdb")?.takeIf { it > 0 },
            imdbId = canonicalImdb(external?.text("imdb")),
            tmdbId = (external?.get("tmdb") as? JsonPrimitive)?.intOrNull,
            genres = names(o["genres"]),
            countries = names(o["countries"]),
        )
    }

    /** Английское название из списка `names`, если отдельного поля `enName` нет. */
    private fun englishName(e: JsonElement?): String? =
        (e as? JsonArray).orEmpty().firstNotNullOfOrNull { item ->
            val n = item as? JsonObject ?: return@firstNotNullOfOrNull null
            n.text("name")?.takeIf { n.text("language").equals("en", ignoreCase = true) }
        }

    private fun names(e: JsonElement?): List<String> =
        (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name") }

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.number(name: String): Double? = (get(name) as? JsonPrimitive)?.doubleOrNull
}
