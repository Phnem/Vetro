package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.audiobooks.data.remote.web.SitePlaylist
import com.example.myapplication.audiobooks.data.remote.web.SiteText
import com.example.myapplication.audiobooks.data.remote.web.SiteTrack
import com.example.myapplication.audiobooks.data.remote.web.SourceHttp
import com.example.myapplication.audiobooks.data.remote.web.WebAudiobookSource
import com.example.myapplication.audiobooks.data.remote.web.mapOk
import com.example.myapplication.audiobooks.data.remote.web.parseOk
import com.example.myapplication.audiobooks.domain.source.BookLanguage
import com.example.myapplication.audiobooks.domain.source.FailureKind
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookDetails
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceId
import com.example.myapplication.audiobooks.domain.source.SourceResult
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * RealAudiobooks (tecplan: EN №1). Поиск `/?s=` — плитки `baud-tile`, книга — список треков
 * `ol.baud-tracks li[data-src]` (или один `audio#mainPlayer`), длина книги — в карточке «Length».
 * Звук — прямые MP3 на ipaudio7.
 */
class RealAudiobooksSource(web: SourceHttp, nowMs: () -> Long = System::currentTimeMillis) : WebAudiobookSource(web, nowMs) {
    override val id = RealAudiobooksParser.SOURCE
    override val displayName = "RealAudiobooks"
    override val languages = setOf(BookLanguage.EN)
    override val infrastructureGroup = "ipaudio"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val path = if (page > 0) "/page/${page + 1}/?s=$q" else "/?s=$q"
        return web.get(RealAudiobooksParser.BASE + path).mapOk(RealAudiobooksParser::parseSearch)
    }

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> = book(ref.key).mapOk { it.details }

    override suspend fun playlist(key: String): SourceResult<SitePlaylist> =
        book(key).mapOk { SitePlaylist(it.tracks, totalSec = it.details.book.durationSec) }

    private suspend fun book(key: String): SourceResult<RealAudiobooksParser.ParsedBook> =
        web.get("${RealAudiobooksParser.BASE}/$key/").parseOk { html ->
            RealAudiobooksParser.parseBook(html, key)?.let { SourceResult.Ok(it) } ?: SourceResult.Failed(FailureKind.PARSE)
        }
}

internal object RealAudiobooksParser {
    val SOURCE = SourceId("realaudiobooks")
    const val BASE = "https://realaudiobooks.com"

    data class ParsedBook(val details: SourceBookDetails, val tracks: List<SiteTrack>)

    fun keyOf(url: String): String? =
        IpaudioParser.keyOf(url.replace("://www.", "://"), BASE)

    fun parseSearch(html: String): List<SourceBook> =
        Jsoup.parse(html, BASE).select("article.baud-tile").mapNotNull { tile ->
            val link = tile.selectFirst("h3 a[href]") ?: return@mapNotNull null
            val key = keyOf(link.absUrl("href")) ?: return@mapNotNull null
            SourceBook(
                ref = SourceBookRef(SOURCE, key),
                title = cleanTitle(link.text()).takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                authors = tile.select(".baud-tile-by a").map { it.text().trim() }.filter { it.isNotEmpty() },
                narrators = emptyList(),
                coverUrl = tile.selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() },
                durationSec = null,
                genres = tile.select(".baud-tile-cat").map { it.text().trim() },
            )
        }.distinctBy { it.ref.key }

    fun parseBook(html: String, key: String): ParsedBook? {
        val doc = Jsoup.parse(html, BASE)
        // data-src хранит HTML-сущности (&#039;) — раскодируем, иначе адрес с апострофом не откроется.
        val listed = doc.select("ol.baud-tracks li[data-src]").map { li ->
            SiteTrack(li.selectFirst(".baud-track-title")?.text(), Parser.unescapeEntities(li.attr("data-src"), true))
        }
        val tracks = listed.ifEmpty {
            listOfNotNull(doc.selectFirst("audio#mainPlayer[src], audio[src]")?.attr("src")?.let { SiteTrack(null, it) })
        }.filter { it.url.startsWith("http") }
        if (tracks.isEmpty()) return null
        val meta = doc.select("dl.baud-meta > div").associate { row ->
            row.selectFirst("dt")?.text()?.trim().orEmpty() to row.selectFirst("dd")
        }
        val authors = meta["Author"]?.select("a")?.map { it.text().trim() }.orEmpty()
            .ifEmpty { listOfNotNull(meta["Author"]?.text()?.trim()?.takeIf { it.isNotEmpty() }) }
        val narrators = (meta["Narrator"] ?: meta["Narrated by"] ?: meta["Read by"])?.text()
            ?.let(SiteText::names).orEmpty()
        return ParsedBook(
            details = SourceBookDetails(
                book = SourceBook(
                    ref = SourceBookRef(SOURCE, key),
                    title = cleanTitle(doc.selectFirst("h1")?.text() ?: return null),
                    authors = authors,
                    narrators = narrators,
                    coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() },
                    durationSec = SiteText.clockSec(meta["Length"]?.text()),
                    genres = meta["Genre"]?.select("a")?.map { it.text().trim() }.orEmpty(),
                ),
                description = doc.selectFirst(".baud-synopsis, .baud-description, .entry-content p")?.text()?.trim()
                    ?.takeIf { it.length > 40 },
                chapterDurationsSec = List(tracks.size) { null },
                authorShelfId = authors.firstOrNull()?.let { "author:$it" },
            ),
            tracks = tracks,
        )
    }

    /** «The Casanova — Narrated Edition», «Listen to Project Hail Mary Audiobook» → чистое название. */
    fun cleanTitle(text: String): String = text
        .replace(Regex("""^\s*(?:Listen\s+to|Stream)\s+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*[—–-]\s*Narrated Edition\s*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+Audiobook\s*$""", RegexOption.IGNORE_CASE), "")
        .trim()
}
