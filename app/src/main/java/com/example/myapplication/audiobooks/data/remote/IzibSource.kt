package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.audiobooks.data.remote.web.SitePlaylist
import com.example.myapplication.audiobooks.data.remote.web.SiteText
import com.example.myapplication.audiobooks.data.remote.web.SiteTrack
import com.example.myapplication.audiobooks.data.remote.web.SourceHttp
import com.example.myapplication.audiobooks.data.remote.web.WebAudiobookSource
import com.example.myapplication.audiobooks.data.remote.web.mapOk
import com.example.myapplication.audiobooks.data.remote.web.parseOk
import com.example.myapplication.audiobooks.domain.source.Availability
import com.example.myapplication.audiobooks.domain.source.BookLanguage
import com.example.myapplication.audiobooks.domain.source.FailureKind
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookDetails
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceId
import com.example.myapplication.audiobooks.domain.source.SourceResult
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Izib (izib.uk). Поиск — HTML, главы с длинами — JSON `new XSPlayer({…})` на странице книги:
 * префикс адреса, подпись ссылок и список файлов. Подпись протухает (`expires=`).
 */
class IzibSource(web: SourceHttp, nowMs: () -> Long = System::currentTimeMillis) : WebAudiobookSource(web, nowMs) {
    override val id = IzibParser.SOURCE
    override val displayName = "Izib"
    override val languages = setOf(BookLanguage.RU)
    override val infrastructureGroup = "audioknigi.xyz"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val pageParam = if (page > 0) "&p=${page + 1}" else ""
        return web.get("${IzibParser.BASE}/search?q=$q$pageParam").mapOk(IzibParser::parseSearch)
    }

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> = book(ref.key).mapOk { it.details }

    override suspend fun playlist(key: String): SourceResult<SitePlaylist> =
        book(key).mapOk { SitePlaylist(it.tracks, expiresAt = it.expiresAt) }

    private suspend fun book(key: String): SourceResult<IzibParser.ParsedBook> =
        web.get("${IzibParser.BASE}/$key").parseOk { html ->
            val parsed = IzibParser.parseBook(html, key)
            when {
                parsed == null -> SourceResult.Failed(FailureKind.PARSE)
                parsed.blocked -> SourceResult.Restricted(Availability.RIGHTS_HOLDER)
                else -> SourceResult.Ok(parsed)
            }
        }
}

internal object IzibParser {
    val SOURCE = SourceId("izib")
    const val BASE = "https://izib.uk"

    data class ParsedBook(
        val details: SourceBookDetails,
        val tracks: List<SiteTrack>,
        val expiresAt: Long?,
        /** Сайт сам пометил книгу закрытой. */
        val blocked: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Классы у сайта хешированные и меняются; опора — ссылки `/artN`, `/authorN`, `/readerN`. */
    fun parseSearch(html: String): List<SourceBook> {
        val doc = Jsoup.parse(html, BASE)
        return doc.select("a[href~=^/art\\d+$]:has(span)").mapNotNull { link ->
            val key = link.attr("href").removePrefix("/")
            val card = link.parents().firstOrNull { it.select("a[href^=/author]").isNotEmpty() } ?: return@mapNotNull null
            val block = card.parent() ?: card
            SourceBook(
                ref = SourceBookRef(SOURCE, key),
                title = link.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                authors = card.select("a[href^=/author]").map { it.text().trim() }.distinct(),
                narrators = card.select("a[href^=/reader]").map { it.text().trim() }.distinct(),
                coverUrl = block.selectFirst("a[href=/$key] img")?.absUrl("src")?.takeIf(::isRealCover),
                durationSec = null,
                genres = block.select("a[href^=/genre]").map { it.text().trim() }.take(1),
            )
        }.distinctBy { it.ref.key }
    }

    fun parseBook(html: String, key: String): ParsedBook? {
        val raw = SiteText.jsonAfter(html, "new XSPlayer(") ?: return null
        val player = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return null
        val prefix = (player["mp3_url_prefix"] as? JsonPrimitive)?.content ?: return null
        val sign = (player["sign"] as? JsonPrimitive)?.content.orEmpty()
        val tracks = (player["tracks"] as? JsonArray).orEmpty().mapNotNull { t ->
            val row = t as? JsonArray ?: return@mapNotNull null
            val file = (row.getOrNull(4) as? JsonPrimitive)?.content ?: return@mapNotNull null
            SiteTrack(
                title = (row.getOrNull(1) as? JsonPrimitive)?.content,
                url = "https://$prefix/$file$sign",
                durationSec = (row.getOrNull(2) as? JsonPrimitive)?.longOrNull,
            )
        }
        if (tracks.isEmpty()) return null
        val doc = Jsoup.parse(html, BASE)
        val info = info(doc)
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val authors = info["Автор"]?.links.orEmpty()
        val narrators = (info["Читает"] ?: info["Читают"])?.links.orEmpty()
        val book = SourceBook(
            ref = SourceBookRef(SOURCE, key),
            title = title,
            authors = authors,
            narrators = narrators,
            coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(::isRealCover),
            durationSec = (player["duration"] as? JsonPrimitive)?.longOrNull ?: tracks.sumOf { it.durationSec ?: 0 },
            genres = doc.select("a[href^=/genre]").map { it.text().trim() }.take(1),
        )
        return ParsedBook(
            details = SourceBookDetails(
                book = book,
                description = (doc.selectFirst("[itemprop=description]")?.text()
                    ?: doc.selectFirst("meta[property=og:description]")?.attr("content"))?.trim()?.takeIf { it.isNotEmpty() },
                chapterDurationsSec = tracks.map { it.durationSec },
                series = info["Цикл"]?.text,
                authorShelfId = authors.firstOrNull()?.let { "author:$it" },
            ),
            tracks = tracks,
            expiresAt = Regex("""expires=(\d+)""").find(sign)?.groupValues?.get(1)?.toLongOrNull()?.times(1000),
            blocked = (player["blocked"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private class InfoRow(val text: String, val links: List<String>)

    /** Блок «Автор: … / Читают: … / Время: …»: подпись в `<b>`, значения — ссылки или текст. */
    private fun info(doc: org.jsoup.nodes.Document): Map<String, InfoRow> =
        doc.select("div:has(> b)").mapNotNull { row: Element ->
            val label = row.selectFirst("> b")?.text()?.trim()?.trimEnd(':') ?: return@mapNotNull null
            label to InfoRow(
                text = row.text().substringAfter(':').trim(),
                links = row.select("a").map { it.text().trim() }.filter { it.isNotEmpty() },
            )
        }.toMap()

    /** Сайт подставляет общую заглушку вместо обложки — это не обложка. */
    private fun isRealCover(url: String): Boolean = url.isNotBlank() && !url.endsWith("/images/poster.png")
}
