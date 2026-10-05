package com.example.myapplication.manga.ja

import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaDetails
import com.example.myapplication.manga.domain.MangaItem
import com.example.myapplication.manga.domain.MangaPage
import com.example.myapplication.manga.domain.MangaSearchPage
import com.example.myapplication.manga.domain.MangaSourceId
import com.example.myapplication.manga.domain.PageDecode
import com.example.myapplication.manga.domain.VetroMangaSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Один сайт семейства GigaViewer (Shueisha, Shogakukan, Kodansha и др. используют общий движок). */
data class GigaSite(val key: String, val name: String, val baseUrl: String)

/**
 * GigaViewer - веб-читалка, на которой работает десяток издательских сайтов. У всех одинаково:
 *  - поиск `/search?q=` отдаёт серии со ссылкой на первую главу;
 *  - RSS `/rss/series/{id}` перечисляет только БЕСПЛАТНЫЕ в данный момент главы;
 *  - страница главы содержит `#episode-json` со списком страниц.
 * Платные главы сайт не отдаёт без входа, и мы их не пытаемся открыть: в выдаче их просто нет.
 *
 * Если у главы `choJuGiga == "baku"`, веб-читалка перед показом переставляет блоки картинки обратно
 * (см. [PageDecode.GigaScramble]); без этого страница выглядит как мозаика.
 */
class GigaViewerSource(
    private val site: GigaSite,
    private val http: JaHttp,
) : VetroMangaSource {

    override val id = MangaSourceId("ja-giga-${site.key}")
    override val name = site.name
    override val language = "ja"

    /** episodeId первой главы -> id серии (aggregate). Меняется только при смене тайтла. */
    private val aggregateIds = ConcurrentHashMap<String, String>()

    override suspend fun search(query: String, page: Int): MangaSearchPage {
        if (page > 1 || query.isBlank()) return MangaSearchPage(emptyList())
        val html = try {
            http.text("${site.baseUrl}/search?q=${URLEncoder.encode(query, "UTF-8")}")
        } catch (e: JaHttpException) {
            // Часть сайтов на пустую выдачу отвечает 404: это "ничего не найдено", а не поломка сайта.
            if (e.code == 404) return MangaSearchPage(emptyList()) else throw e
        }
        return MangaSearchPage(GigaViewerParser.parseSearch(html, site.baseUrl).map { it.toItem(id) })
    }

    override suspend fun details(manga: MangaItem): MangaDetails = MangaDetails(item = manga)

    override suspend fun chapters(manga: MangaItem): List<MangaChapter> {
        val aggregate = aggregateIdOf(manga.key) ?: return emptyList()
        val rss = http.text("${site.baseUrl}/rss/series/$aggregate")
        return GigaViewerParser.parseRss(rss).map { entry ->
            MangaChapter(
                sourceId = id,
                mangaKey = manga.key,
                key = entry.episodeId,
                number = entry.number,
                title = entry.title,
                language = "ja",
                scanlator = site.name,
                publishedAt = entry.publishedAtMillis,
            )
        }
    }

    override suspend fun pages(chapter: MangaChapter): List<MangaPage> {
        val html = http.text("${site.baseUrl}/episode/${chapter.key}")
        val episode = GigaViewerParser.parseEpisode(html) ?: return emptyList()
        if (!episode.isPublic) return emptyList()
        val headers = mapOf("Referer" to "${site.baseUrl}/")
        return episode.pages.mapIndexed { i, url ->
            MangaPage(index = i, url = url, headers = headers, decode = if (episode.scrambled) PageDecode.GigaScramble else null)
        }
    }

    private suspend fun aggregateIdOf(firstEpisodeId: String): String? {
        aggregateIds[firstEpisodeId]?.let { return it }
        val html = http.text("${site.baseUrl}/episode/$firstEpisodeId")
        val found = GigaViewerParser.parseAggregateId(html) ?: return null
        aggregateIds[firstEpisodeId] = found
        return found
    }
}

/** Разбор разметки GigaViewer. Отдельно от сети, чтобы проверять на сохранённых образцах. */
object GigaViewerParser {

    data class SearchHit(val episodeId: String, val title: String, val author: String?, val coverUrl: String?) {
        fun toItem(source: MangaSourceId) = MangaItem(
            sourceId = source,
            key = episodeId,
            title = title,
            coverUrl = coverUrl,
            languages = listOf("ja"),
            author = author,
        )
    }

    data class RssEntry(val episodeId: String, val title: String, val number: Double?, val publishedAtMillis: Long)

    data class Episode(val isPublic: Boolean, val scrambled: Boolean, val pages: List<String>)

    private val EPISODE_ID = Regex("/episode/(\\d+)")
    private val SERIES_CLASS = Regex("series[-_]title", RegexOption.IGNORE_CASE)
    private val AUTHOR_CLASS = Regex("author", RegexOption.IGNORE_CASE)

    fun parseSearch(html: String, baseUrl: String): List<SearchHit> {
        val doc = Jsoup.parse(html, baseUrl)
        val hits = LinkedHashMap<String, SearchHit>()
        for (titleEl in doc.getElementsByAttributeValueMatching("class", SERIES_CLASS.toPattern())) {
            val title = titleEl.text().trim()
            if (title.isEmpty()) continue
            // Ближайший предок, внутри которого есть ссылка на главу: карточка серии.
            var card = titleEl.parent()
            var link = card?.selectFirst("a[href*=/episode/]")
            while (link == null && card?.parent() != null && card.tagName() != "body") {
                card = card.parent()
                link = card?.selectFirst("a[href*=/episode/]")
            }
            val episodeId = link?.attr("abs:href")?.let { EPISODE_ID.find(it)?.groupValues?.get(1) } ?: continue
            val author = card?.getElementsByAttributeValueMatching("class", AUTHOR_CLASS.toPattern())
                ?.firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val cover = card?.selectFirst("img[src]")?.attr("abs:src")?.takeIf { it.startsWith("http") }
            hits.putIfAbsent(episodeId, SearchHit(episodeId, title, author, cover))
        }
        return hits.values.toList()
    }

    fun parseAggregateId(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.selectFirst("[data-aggregate-id]")?.attr("data-aggregate-id")?.takeIf { it.isNotBlank() }?.let { return it }
        val json = episodeJson(doc) ?: return null
        return json["readableProduct"]?.jsonObject?.get("series")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }

    fun parseRss(xml: String): List<RssEntry> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("item").mapNotNull { item ->
            val link = item.selectFirst("link")?.text().orEmpty()
            val episodeId = EPISODE_ID.find(link)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = item.selectFirst("title")?.text().orEmpty().trim()
            RssEntry(
                episodeId = episodeId,
                title = title,
                number = JaTitles.chapterNumber(title),
                publishedAtMillis = parseRfc1123(item.selectFirst("pubDate")?.text()),
            )
        }
    }

    fun parseEpisode(html: String): Episode? {
        val json = episodeJson(Jsoup.parse(html)) ?: return null
        val product = json["readableProduct"]?.jsonObject ?: return null
        val structure = (product["pageStructure"] as? JsonObject)
            ?: return Episode(isPublic = false, scrambled = false, pages = emptyList())
        val pages = ((structure["pages"] as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "main" }
            .mapNotNull { it["src"]?.jsonPrimitive?.contentOrNull }
        val isPublic = (product["isPublic"] as? JsonPrimitive)?.contentOrNull != "false"
        return Episode(
            isPublic = isPublic,
            scrambled = structure["choJuGiga"]?.jsonPrimitive?.contentOrNull == "baku",
            pages = pages,
        )
    }

    private fun episodeJson(doc: org.jsoup.nodes.Document): JsonObject? {
        val raw = doc.selectFirst("script#episode-json")?.attr("data-value")?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        }.getOrNull()
    }

    private fun parseRfc1123(value: String?): Long = runCatching {
        ZonedDateTime.parse(value?.trim().orEmpty(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrDefault(0L)
}

/** Проверенные сайты семейства (поиск, RSS и episode-json отвечают без входа). Ichijin Plus переехал на ichicomi.com. */
object GigaSites {
    val ALL = listOf(
        GigaSite("tonarinoyj", "となりのヤングジャンプ", "https://tonarinoyj.jp"),
        GigaSite("shonenjumpplus", "少年ジャンプ+", "https://shonenjumpplus.com"),
        GigaSite("comicdays", "コミックDAYS", "https://comic-days.com"),
        GigaSite("kuragebunch", "くらげバンチ", "https://kuragebunch.com"),
        GigaSite("sundaywebry", "サンデーうぇぶり", "https://www.sunday-webry.com"),
        GigaSite("comiczenon", "コミックゼノン", "https://comic-zenon.com"),
        GigaSite("comictrail", "コミックトレイル", "https://comic-trail.com"),
        GigaSite("comicgardo", "コミックガルド", "https://comic-gardo.com"),
        GigaSite("comicearthstar", "コミック アース・スター", "https://comic-earthstar.com"),
        GigaSite("magcomi", "マガジンポケット MAGCOMI", "https://magcomi.com"),
        GigaSite("ichicomi", "一迅プラス", "https://ichicomi.com"),
        GigaSite("ourfeel", "OUR FEEL", "https://ourfeel.jp"),
    )
}
