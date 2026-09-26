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
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup

/**
 * Knigavuhe как звуковой источник (tecplan: RU №3). Поиск — HTML, главы с длинами — JSON в
 * `new BookPlayer(…)` на странице книги. Ссылки на звук подписаны и живут несколько дней — срок
 * страница сообщает сама (`url_refresh_in`). У лицензионных книг вместо звука — фрагмент ЛитРеса:
 * такая озвучка честно недоступна, и запуск уходит к другим источникам.
 */
class KnigavuheSource(web: SourceHttp, private val nowMs: () -> Long = System::currentTimeMillis) :
    WebAudiobookSource(web, nowMs) {
    override val id = KnigavuheParser.SOURCE
    override val displayName = "Книга в ухе"
    override val languages = setOf(BookLanguage.RU)
    override val infrastructureGroup = "knigavuhe.org"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val pageParam = if (page > 0) "&page=${page + 1}" else ""
        return web.get("${KnigavuheParser.BASE}/search/?q=$q$pageParam").mapOk(KnigavuheParser::parseSearch)
    }

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> = book(ref.key).mapOk { it.details }

    override suspend fun playlist(key: String): SourceResult<SitePlaylist> = book(key).mapOk { parsed ->
        SitePlaylist(parsed.tracks, expiresAt = parsed.refreshInSec?.let { nowMs() + it * 1000 })
    }

    private suspend fun book(key: String): SourceResult<KnigavuheParser.ParsedBook> =
        web.get("${KnigavuheParser.BASE}/book/$key/").parseOk { html ->
            when (val parsed = KnigavuheParser.parseBook(html, key)) {
                null -> if (RESTRICTED_MARKERS.any { html.contains(it, ignoreCase = true) }) {
                    SourceResult.Restricted(Availability.RIGHTS_HOLDER)
                } else {
                    SourceResult.Failed(FailureKind.PARSE)
                }
                else -> if (parsed.trialOnly) SourceResult.Restricted(Availability.RIGHTS_HOLDER) else SourceResult.Ok(parsed)
            }
        }
}

internal object KnigavuheParser {
    val SOURCE = SourceId("knigavuhe")
    const val BASE = "https://knigavuhe.org"

    data class ParsedBook(
        val details: SourceBookDetails,
        val tracks: List<SiteTrack>,
        /** Через сколько секунд сайт перевыпустит подписи ссылок. */
        val refreshInSec: Long?,
        /** Вместо книги — ознакомительный фрагмент ЛитРеса. */
        val trialOnly: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun keyOf(href: String): String? = Regex("""/book/([^/?#]+)/?""").find(href)?.groupValues?.get(1)

    fun parseSearch(html: String): List<SourceBook> =
        Jsoup.parse(html, BASE).select("#books_list .bookkitem").mapNotNull { item ->
            val link = item.selectFirst("a.bookkitem_name") ?: return@mapNotNull null
            val key = keyOf(link.attr("href")) ?: return@mapNotNull null
            SourceBook(
                ref = SourceBookRef(SOURCE, key),
                title = link.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                authors = item.select(".bookkitem_author a").map { it.text().trim() },
                narrators = item.select("a[href^=/reader/]").map { it.text().trim() }.distinct(),
                // В выдаче миниатюра (`…-1.jpg`); полная обложка того же каталога — без суффикса размера.
                coverUrl = item.selectFirst("img.bookkitem_cover_img")?.absUrl("src")?.takeIf { it.isNotBlank() }
                    ?.let(::fullCover),
                durationSec = SiteText.wordsSec(item.selectFirst(".bookkitem_meta_time")?.text()),
                genres = item.select(".bookkitem_genre a").map { it.text().trim() },
            )
        }

    fun parseBook(html: String, key: String): ParsedBook? {
        val raw = SiteText.jsonAfter(html, "new BookPlayer(") ?: return null
        val items = runCatching { json.parseToJsonElement(raw) as JsonArray }.getOrNull() ?: return null
        val tracks = items.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val url = (o["url"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            SiteTrack((o["title"] as? JsonPrimitive)?.content, url, (o["duration"] as? JsonPrimitive)?.longOrNull)
        }
        if (tracks.isEmpty()) return null
        val playerData = (items.first() as? JsonObject)?.get("player_data") as? JsonObject
        val doc = Jsoup.parse(html, BASE)
        val h1 = doc.selectFirst("h1")
        val title = h1?.selectFirst(".book_title_name")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val authors = h1.select("[itemprop=author] a").map { it.text().trim() }.filter { it.isNotEmpty() }
        val book = SourceBook(
            ref = SourceBookRef(SOURCE, key),
            title = title,
            authors = authors,
            narrators = h1.select("a[href^=/reader/]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            coverUrl = (playerData?.get("cover") as? JsonPrimitive)?.content?.takeIf { it.startsWith("http") }
                ?: doc.selectFirst(".book_cover img")?.absUrl("src"),
            durationSec = tracks.sumOf { it.durationSec ?: 0 }.takeIf { it > 0 },
            genres = doc.select(".book_genre_pretitle a").map { it.text().trim() },
        )
        return ParsedBook(
            details = SourceBookDetails(
                book = book,
                description = doc.selectFirst(".book_description")?.text()?.trim()?.takeIf { it.isNotEmpty() },
                chapterDurationsSec = tracks.map { it.durationSec },
                rating = doc.selectFirst("[itemprop=ratingValue]")?.attr("content")?.toDoubleOrNull(),
                series = (playerData?.get("series") as? JsonPrimitive)?.content?.takeIf { it != "null" && it.isNotBlank() },
                authorShelfId = authors.firstOrNull()?.let { "author:$it" },
            ),
            tracks = tracks,
            refreshInSec = (playerData?.get("url_refresh_in") as? JsonPrimitive)?.longOrNull,
            trialOnly = tracks.all { "litres.ru" in it.url },
        )
    }

    private fun fullCover(url: String): String = url.replace(Regex("""/(\d+)-\d+\.jpg"""), "/$1.jpg")
}
