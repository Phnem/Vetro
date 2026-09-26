package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.audiobooks.data.remote.web.Playerjs
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Slushat-Knigi. Поиск — форма DLE, книга — HTML со списком «Информация о книге», звук — тот же
 * Playerjs-плейлист, что у Baza-Knig (общий CDN с Referer). Поэтому в fallback они — разные сайты,
 * но одна инфраструктура: если лёг CDN, лежат оба.
 */
class SlushatKnigiSource(web: SourceHttp, nowMs: () -> Long = System::currentTimeMillis) : WebAudiobookSource(web, nowMs) {
    override val id = SlushatKnigiParser.SOURCE
    override val displayName = "Slushat-Knigi"
    override val languages = setOf(BookLanguage.RU)
    override val infrastructureGroup = "redirectto.cc"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> =
        web.postForm(
            "${SlushatKnigiParser.BASE}/index.php?do=search",
            mapOf("do" to "search", "subaction" to "search", "story" to query.trim(), "search_start" to "${page + 1}"),
        ).mapOk(SlushatKnigiParser::parseSearch)

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> = page(ref.key).parseOk { parsed ->
        val count = (tracks(parsed) as? SourceResult.Ok)?.value?.size ?: 0
        SourceResult.Ok(parsed.details.copy(chapterDurationsSec = List(count) { null }))
    }

    override suspend fun playlist(key: String): SourceResult<SitePlaylist> = page(key).parseOk { parsed ->
        tracks(parsed).mapOk { SitePlaylist(it, headers = HEADERS, totalSec = parsed.details.book.durationSec) }
    }

    private suspend fun tracks(parsed: SlushatKnigiParser.ParsedPage): SourceResult<List<SiteTrack>> =
        if (Playerjs.isDirectAudio(parsed.playerFile)) {
            SourceResult.Ok(listOf(SiteTrack(null, parsed.playerFile)))
        } else {
            web.get(parsed.playerFile, HEADERS).mapOk(Playerjs::parsePlaylist)
        }

    private suspend fun page(key: String): SourceResult<SlushatKnigiParser.ParsedPage> =
        web.get("${SlushatKnigiParser.BASE}/$key.html").parseOk { html ->
            SlushatKnigiParser.parseBook(html, key)?.let { SourceResult.Ok(it) }
                ?: if (RESTRICTED_MARKERS.any { html.contains(it, ignoreCase = true) }) {
                    SourceResult.Restricted(Availability.RIGHTS_HOLDER)
                } else {
                    SourceResult.Failed(FailureKind.PARSE)
                }
        }

    private companion object {
        val HEADERS = mapOf("Referer" to "${SlushatKnigiParser.BASE}/")
    }
}

internal object SlushatKnigiParser {
    val SOURCE = SourceId("slushat_knigi")
    const val BASE = "https://slushat-knigi.com"

    data class ParsedPage(val details: SourceBookDetails, val playerFile: String)

    fun keyOf(href: String): String? = Regex("""slushat-knigi\.com/(\d+-[^/?#]+?)\.html""").find(href)?.groupValues?.get(1)

    fun parseSearch(html: String): List<SourceBook> =
        Jsoup.parse(html, BASE).select("a.poster-item").mapNotNull { a ->
            val key = keyOf(a.absUrl("href")) ?: return@mapNotNull null
            val (title, author) = SiteText.splitTitleAuthor(a.selectFirst(".poster-item__title")?.text() ?: return@mapNotNull null)
            SourceBook(
                ref = SourceBookRef(SOURCE, key),
                title = title.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                authors = listOfNotNull(author),
                narrators = emptyList(),
                coverUrl = a.selectFirst("img")?.let { img -> img.absUrl("data-src").ifBlank { img.absUrl("src") } }
                    ?.takeIf { it.isNotBlank() },
                durationSec = SiteText.clockSec(a.selectFirst(".poster-item__label")?.text()),
                genres = SiteText.names(a.selectFirst(".poster-item__meta")?.text()),
            )
        }.distinctBy { it.ref.key }

    fun parseBook(html: String, key: String): ParsedPage? {
        val file = Playerjs.fileOf(html) ?: return null
        val doc = Jsoup.parse(html, BASE)
        val info = details(doc)
        val heading = doc.selectFirst("h1")?.text().orEmpty().substringAfter('"').substringBeforeLast('"')
        val (titleFromHeading, authorFromHeading) = SiteText.splitTitleAuthor(heading)
        val title = info["Название"] ?: titleFromHeading.takeIf { it.isNotEmpty() } ?: return null
        val authors = SiteText.names(info["Автор"]).ifEmpty { listOfNotNull(authorFromHeading) }
        val book = SourceBook(
            ref = SourceBookRef(SOURCE, key),
            title = title,
            authors = authors,
            narrators = SiteText.names(info["Озвучивает"] ?: info["Читает"]),
            coverUrl = doc.selectFirst(".page__poster")?.let { p ->
                p.absUrl("data-poster").ifBlank { p.selectFirst("img")?.absUrl("data-src").orEmpty() }
            }?.takeIf { it.isNotBlank() },
            durationSec = SiteText.clockSec(info["Время озвучки"]),
            genres = SiteText.names(info["Жанр"]),
        )
        return ParsedPage(
            details = SourceBookDetails(
                book = book,
                description = doc.selectFirst(".page__text.full-text")?.text()?.trim()?.takeIf { it.isNotEmpty() },
                chapterDurationsSec = emptyList(),
                rating = doc.selectFirst(".page__rating-item--audience div")?.text()?.trim()?.toDoubleOrNull()
                    ?.takeIf { it > 0 },
                series = info["Серия (цикл)"],
                authorShelfId = authors.firstOrNull()?.let { "author:$it" },
            ),
            playerFile = file,
        )
    }

    /** «Информация о книге»: подпись → значение. */
    private fun details(doc: Document): Map<String, String> =
        doc.select(".page__details-list li").mapNotNull { li ->
            val spans = li.select("> span")
            if (spans.size < 2) return@mapNotNull null
            spans[0].text().trim() to spans[1].text().trim()
        }.filter { it.second.isNotEmpty() }.toMap()
}
