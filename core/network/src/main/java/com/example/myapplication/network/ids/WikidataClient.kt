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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Запись Wikidata: связанные id каталогов и подписи на русском и английском. */
data class WikidataEntity(
    val ids: CrossIds,
    val labelRu: String?,
    val labelEn: String?,
)

/**
 * Wikidata — бесплатный открытый (CC0) сводный справочник: по подписи на русском даёт id Кинопоиска,
 * IMDb, TMDB, TheTVDB, MyAnimeList, AniList и Kitsu разом. Ключ не нужен; запросы вежливые — не
 * чаще раза в секунду, ответы лежат в кэше месяц.
 *
 * Подпись должна совпасть точно (с учётом регистра): шире искать через SPARQL дорого, а неверное
 * совпадение хуже, чем отсутствие.
 */
class WikidataClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
) {
    /** Записи с этой подписью на языке [lang] (`ru`, `en`), у которых есть хоть один известный id. */
    suspend fun byLabel(label: String, lang: String): LookupResult<List<WikidataEntity>> {
        val clean = label.trim()
        if (clean.isEmpty()) return LookupResult.NoMatch
        val escaped = clean.replace("\\", "\\\\").replace("\"", "\\\"")
        return run("wikidata:label:$lang:${clean.lowercase()}", "?i rdfs:label \"$escaped\"@$lang.")
    }

    /** Записи по id во внешнем каталоге — расширяет известные id остальными. */
    suspend fun byExternalId(kind: ExternalIdKind, value: String): LookupResult<List<WikidataEntity>> {
        val clean = value.trim()
        if (clean.isEmpty() || clean.any { it == '"' || it == '\\' }) return LookupResult.NoMatch
        return run("wikidata:ext:${kind.wikidataProperty}:$clean", "?i wdt:${kind.wikidataProperty} \"$clean\".")
    }

    /** Название русской статьи Википедии для записи (`Q…`), если она есть. */
    suspend fun ruWikipediaTitle(qid: String): LookupResult<String> {
        if (!Regex("Q\\d+").matches(qid)) return LookupResult.NoMatch
        val url = "$API?action=wbgetentities&ids=$qid&props=sitelinks&sitefilter=ruwiki&format=json"
        return http.text(
            provider = "Wikidata",
            url = url,
            rate = rate,
            policy = CachePolicy("wikidata:ruwiki:$qid", 30 * CacheTtl.DAY),
            configure = { header("Accept", "application/json") },
        ).parse { WikidataParser.ruWikiTitle(it, qid) }
    }

    /**
     * Английские названия литературных произведений, которые на языке [lang] называются [title]
     * («Задача трёх тел» → «The Three-Body Problem»). Берутся только записи, описанные как книга,
     * роман, рассказ и т. п.: «Дюна» — ещё и фильм, и город.
     */
    suspend fun workEnglishTitles(title: String, lang: String): LookupResult<List<String>> {
        val clean = title.trim()
        if (clean.isEmpty()) return LookupResult.NoMatch
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val found = http.text(
            provider = "Wikidata",
            url = "$API?action=wbsearchentities&search=$encoded&language=$lang&uselang=$lang&type=item&limit=8&format=json",
            rate = rate,
            policy = CachePolicy("wikidata:work:$lang:${clean.lowercase()}", 30 * CacheTtl.DAY, negativeTtlMs = 3 * CacheTtl.DAY),
            configure = { header("Accept", "application/json") },
        ).parse { WikidataParser.bookIds(it).takeIf(List<String>::isNotEmpty) }
        val ids = (found as? LookupResult.Found)?.value ?: return LookupResult.NoMatch
        return http.text(
            provider = "Wikidata",
            url = "$API?action=wbgetentities&ids=${ids.joinToString("%7C")}&props=labels&languages=en&format=json",
            rate = rate,
            policy = CachePolicy("wikidata:labels-en:${ids.joinToString(",")}", 30 * CacheTtl.DAY, negativeTtlMs = 3 * CacheTtl.DAY),
            configure = { header("Accept", "application/json") },
        ).parse { WikidataParser.englishLabels(it, ids).takeIf(List<String>::isNotEmpty) }
    }

    private suspend fun run(cacheKey: String, match: String): LookupResult<List<WikidataEntity>> {
        val query = buildQuery(match)
        val url = "$SPARQL?format=json&query=" + URLEncoder.encode(query, "UTF-8")
        return http.text(
            provider = "Wikidata",
            url = url,
            rate = rate,
            policy = CachePolicy(cacheKey, 30 * CacheTtl.DAY, negativeTtlMs = 3 * CacheTtl.DAY),
            configure = { header("Accept", "application/sparql-results+json") },
        ).parse { WikidataParser.entities(it).takeIf { list -> list.isNotEmpty() } }
    }

    internal companion object {
        const val SPARQL = "https://query.wikidata.org/sparql"
        const val API = "https://www.wikidata.org/w/api.php"

        fun buildQuery(match: String): String = """
            SELECT ?i (SAMPLE(?ru) AS ?ruL) (SAMPLE(?en) AS ?enL) (SAMPLE(?kp) AS ?kpV) (SAMPLE(?imdb) AS ?imdbV)
                   (SAMPLE(?tmdbM) AS ?tmdbMV) (SAMPLE(?tmdbT) AS ?tmdbTV) (SAMPLE(?tvdb) AS ?tvdbV)
                   (SAMPLE(?mal) AS ?malV) (SAMPLE(?al) AS ?alV) (SAMPLE(?kitsu) AS ?kitsuV)
            WHERE {
              $match
              OPTIONAL { ?i wdt:P2603 ?kp }
              OPTIONAL { ?i wdt:P345 ?imdb }
              OPTIONAL { ?i wdt:P4947 ?tmdbM }
              OPTIONAL { ?i wdt:P4983 ?tmdbT }
              OPTIONAL { ?i wdt:P4835 ?tvdb }
              OPTIONAL { ?i wdt:P4086 ?mal }
              OPTIONAL { ?i wdt:P8729 ?al }
              OPTIONAL { ?i wdt:P11495 ?kitsu }
              OPTIONAL { ?i rdfs:label ?ru FILTER(LANG(?ru) = "ru") }
              OPTIONAL { ?i rdfs:label ?en FILTER(LANG(?en) = "en") }
              FILTER(BOUND(?kp) || BOUND(?imdb) || BOUND(?tmdbM) || BOUND(?tmdbT) || BOUND(?tvdb) || BOUND(?mal) || BOUND(?al) || BOUND(?kitsu))
            }
            GROUP BY ?i
            LIMIT 12
        """.trimIndent()
    }
}

internal object WikidataParser {
    fun entities(body: String): List<WikidataEntity> {
        val root = EnrichmentJson.parseToJsonElement(body).jsonObject
        val bindings = ((root["results"] as? JsonObject)?.get("bindings") as? JsonArray).orEmpty()
        return bindings.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val iri = row.value("i") ?: return@mapNotNull null
            val qid = iri.substringAfterLast('/').takeIf { Regex("Q\\d+").matches(it) } ?: return@mapNotNull null
            WikidataEntity(
                ids = CrossIds(
                    wikidata = qid,
                    kinopoisk = row.value("kpV")?.toIntOrNull(),
                    imdb = canonicalImdb(row.value("imdbV")),
                    tmdbMovie = row.value("tmdbMV")?.toIntOrNull(),
                    tmdbTv = row.value("tmdbTV")?.toIntOrNull(),
                    tvdb = row.value("tvdbV")?.toIntOrNull(),
                    mal = row.value("malV")?.toIntOrNull(),
                    anilist = row.value("alV")?.toIntOrNull(),
                    kitsu = row.value("kitsuV")?.toIntOrNull(),
                ),
                labelRu = row.value("ruL"),
                labelEn = row.value("enL"),
            )
        }
    }

    fun ruWikiTitle(body: String, qid: String): String? {
        val root = EnrichmentJson.parseToJsonElement(body).jsonObject
        val entity = (root["entities"] as? JsonObject)?.get(qid) as? JsonObject ?: return null
        val sitelinks = entity["sitelinks"] as? JsonObject ?: return null
        return ((sitelinks["ruwiki"] as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull
    }

    /** Записи поиска `wbsearchentities`, описание которых говорит «книга/роман/рассказ…». */
    fun bookIds(body: String): List<String> {
        val hits = EnrichmentJson.parseToJsonElement(body).jsonObject["search"] as? JsonArray ?: return emptyList()
        return hits.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { Regex("Q\\d+").matches(it) } ?: return@mapNotNull null
            val description = (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            id.takeIf { BOOK_DESCRIPTION.containsMatchIn(description) }
        }.take(4)
    }

    /** Английские подписи записей в порядке [ids]; записи без подписи пропускаются. */
    fun englishLabels(body: String, ids: List<String>): List<String> {
        val entities = EnrichmentJson.parseToJsonElement(body).jsonObject["entities"] as? JsonObject ?: return emptyList()
        return ids.mapNotNull { id ->
            val labels = (entities[id] as? JsonObject)?.get("labels") as? JsonObject
            ((labels?.get("en") as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()
    }

    private val BOOK_DESCRIPTION = Regex(
        "(?i)(роман|повесть|рассказ|книга|поэма|пьеса|сказк|новелл|цикл|сборник|литературн|произведение|" +
            "novel|book|story|novella|poem|play|fairy|series|collection|literary|written work)",
    )

    private fun JsonObject.value(name: String): String? =
        ((get(name) as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
