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
import java.net.URLEncoder
import org.jsoup.Jsoup

/**
 * Baza-Knig. Поиск — `/search?text=`, книга — HTML, звук — плейлист Playerjs (`….pl.txt`, JSON) на
 * CDN, который отдаёт файлы только с Referer сайта (так их запрашивает и плеер самого сайта).
 * Длин треков сайт не даёт — их раскладывает [WebAudiobookSource] по общей длине и размерам файлов.
 */
class BazaKnigSource(web: SourceHttp, nowMs: () -> Long = System::currentTimeMillis) : WebAudiobookSource(web, nowMs) {
    override val id = BazaKnigParser.SOURCE
    override val displayName = "Baza-Knig"
    override val languages = setOf(BookLanguage.RU)
    override val infrastructureGroup = "redirectto.cc"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> {
        if (page > 0) return SourceResult.Ok(emptyList())
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return web.get("${BazaKnigParser.BASE}/search?text=$q").mapOk(BazaKnigParser::parseSearch)
    }

    /** Детали + число глав из плейлиста (один запрос; длины глав известны только после запуска). */
    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> = page(ref.key).parseOk { parsed ->
        val count = (tracks(parsed) as? SourceResult.Ok)?.value?.size ?: 0
        SourceResult.Ok(parsed.details.copy(chapterDurationsSec = List(count) { null }))
    }

    override suspend fun playlist(key: String): SourceResult<SitePlaylist> = page(key).parseOk { parsed ->
        tracks(parsed).mapOk { SitePlaylist(it, headers = HEADERS, totalSec = parsed.details.book.durationSec) }
    }

    private suspend fun tracks(parsed: BazaKnigParser.ParsedPage): SourceResult<List<SiteTrack>> =
        if (Playerjs.isDirectAudio(parsed.playerFile)) {
            SourceResult.Ok(listOf(SiteTrack(null, parsed.playerFile)))
        } else {
            web.get(parsed.playerFile, HEADERS).mapOk(Playerjs::parsePlaylist)
        }

    private companion object {
        val HEADERS = mapOf("Referer" to "${BazaKnigParser.BASE}/")
    }

    private suspend fun page(key: String): SourceResult<BazaKnigParser.ParsedPage> =
        web.get("${BazaKnigParser.BASE}/$key").parseOk { html ->
            BazaKnigParser.parseBook(html, key)?.let { SourceResult.Ok(it) }
                ?: if (RESTRICTED_MARKERS.any { html.contains(it, ignoreCase = true) }) {
                    SourceResult.Restricted(Availability.RIGHTS_HOLDER)
                } else {
                    SourceResult.Failed(FailureKind.PARSE)
                }
        }
}

internal object BazaKnigParser {
    val SOURCE = SourceId("baza_knig")
    const val BASE = "https://baza-knig.info"

    data class ParsedPage(val details: SourceBookDetails, val playerFile: String)

    fun keyOf(href: String): String? = Regex("""/(audio-\d+-[^/?#]+)""").find(href)?.groupValues?.get(1)

    /** Выдача — только «Название - Автор» без обложек: остальное приходит со страницы книги. */
    fun parseSearch(html: String): List<SourceBook> =
        Jsoup.parse(html, BASE).select(".b-statictop__items a[href]").mapNotNull { a ->
            val key = keyOf(a.attr("href")) ?: return@mapNotNull null
            val (title, author) = SiteText.splitTitleAuthor(a.text())
            SourceBook(
                ref = SourceBookRef(SOURCE, key),
                title = title.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                authors = listOfNotNull(author),
                narrators = emptyList(),
                coverUrl = null,
                durationSec = null,
            )
        }.distinctBy { it.ref.key }

    fun parseBook(html: String, key: String): ParsedPage? {
        val file = Playerjs.fileOf(html) ?: return null
        val doc = Jsoup.parse(html, BASE)
        val h1 = doc.selectFirst("h1") ?: return null
        val (title, authorFromTitle) = SiteText.splitTitleAuthor(h1.selectFirst(".book_title_name")?.text() ?: return null)
        val authors = h1.select("a[href^=/avtor-]").map { it.text().trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOfNotNull(authorFromTitle) }
        val book = SourceBook(
            ref = SourceBookRef(SOURCE, key),
            title = title,
            authors = authors,
            narrators = h1.select("a[href^=/ispolnitel-]").map { it.text().trim() }.filter { it.isNotEmpty() },
            coverUrl = doc.selectFirst(".book_cover img")?.absUrl("src")?.takeIf { it.isNotBlank() },
            durationSec = SiteText.clockSec(doc.selectFirst(".book_blue_block")?.text()),
            genres = doc.select(".fullentry_info a[href^=/genre-]").map { it.text().trim() },
        )
        return ParsedPage(
            details = SourceBookDetails(
                book = book,
                description = doc.selectFirst(".book_description")?.text()?.trim()?.takeIf { it.isNotEmpty() },
                // Число и длины глав станут известны из плейлиста при запуске.
                chapterDurationsSec = emptyList(),
                authorShelfId = authors.firstOrNull()?.let { "author:$it" },
            ),
            playerFile = file,
        )
    }
}
