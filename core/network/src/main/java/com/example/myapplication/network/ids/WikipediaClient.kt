package com.example.myapplication.network.ids

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.TokenBucketRateLimiter
import com.example.myapplication.network.enrichment.CacheTtl
import com.example.myapplication.network.enrichment.CachePolicy
import com.example.myapplication.network.enrichment.EnrichmentHttp
import com.example.myapplication.network.enrichment.EnrichmentJson
import com.example.myapplication.network.enrichment.parse
import io.ktor.client.request.header
import java.net.URLEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Краткое описание статьи: подходит как запасное, когда в каталогах описания нет. */
data class WikipediaSummary(
    val title: String,
    /** Первый абзац статьи. */
    val extract: String,
    /** Одна строка под заголовком («фильм 1968 года», «аниме-сериал»). */
    val description: String?,
    val thumbnailUrl: String?,
    val wikidataId: String?,
    val lang: String,
)

/**
 * Википедия (REST `page/summary`) — описания редких тайтлов, которых нет в TMDB и ПоискКино.
 * Ключ не нужен; текст статей под CC BY-SA, поэтому в записи сохраняется ссылка на источник через
 * язык и заголовок. Берётся только короткий вступительный абзац.
 */
class WikipediaClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
) {
    suspend fun summary(title: String, lang: String = "ru"): LookupResult<WikipediaSummary> {
        val clean = title.trim().replace(' ', '_')
        if (clean.isEmpty() || (lang != "ru" && lang != "en")) return LookupResult.NoMatch
        val encoded = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
        return http.text(
            provider = "Wikipedia",
            url = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$encoded",
            rate = rate,
            policy = CachePolicy("wikipedia:$lang:${clean.lowercase()}", 30 * CacheTtl.DAY, negativeTtlMs = 3 * CacheTtl.DAY),
            configure = { header("Accept", "application/json") },
        ).parse { WikipediaParser.summary(it, lang) }
    }
}

internal object WikipediaParser {
    fun summary(body: String, lang: String): WikipediaSummary? {
        val o = EnrichmentJson.parseToJsonElement(body).jsonObject
        // Страница-неоднозначность — не описание тайтла.
        if (o.text("type") == "disambiguation") return null
        val extract = o.text("extract") ?: return null
        return WikipediaSummary(
            title = o.text("title") ?: return null,
            extract = extract,
            description = o.text("description"),
            thumbnailUrl = (o["thumbnail"] as? JsonObject)?.text("source"),
            wikidataId = o.text("wikibase_item"),
            lang = lang,
        )
    }

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
