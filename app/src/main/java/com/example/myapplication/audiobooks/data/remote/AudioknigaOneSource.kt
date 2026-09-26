package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.audiobooks.data.remote.web.JsonLd
import com.example.myapplication.audiobooks.data.remote.web.JsonLd.text
import com.example.myapplication.audiobooks.data.remote.web.JsonLd.texts
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup

/**
 * Audiokniga.one (tecplan: RU №2). Поиск — форма DLE, метаданные — schema.org на странице книги,
 * главы с длинами — JSON в вызове `playerInit(…)` там же. Звук — прямые MP3 в S3, без подписи.
 */
class AudioknigaOneSource(web: SourceHttp, nowMs: () -> Long = System::currentTimeMillis) : WebAudiobookSource(web, nowMs) {
    override val id = AudioknigaOneParser.SOURCE
    override val displayName = "Audiokniga.one"
    override val languages = setOf(BookLanguage.RU)
    override val infrastructureGroup = "readsound.ru"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> =
        web.postForm(
            "${AudioknigaOneParser.BASE}/index.php?do=search",
            mapOf(
                "do" to "search", "subaction" to "search", "story" to query.trim(),
                "search_start" to "${page + 1}", "full_search" to "0", "result_from" to "${page * 16 + 1}",
            ),
        ).mapOk(AudioknigaOneParser::parseSearch)

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> = book(ref.key).mapOk { it.details }

    override suspend fun playlist(key: String): SourceResult<SitePlaylist> = book(key).mapOk { SitePlaylist(it.tracks) }

    private suspend fun book(key: String): SourceResult<AudioknigaOneParser.ParsedBook> =
        web.get("${AudioknigaOneParser.BASE}/$key.html").parseOk { html ->
            AudioknigaOneParser.parseBook(html, key)?.let { SourceResult.Ok(it) }
                ?: if (RESTRICTED_MARKERS.any { html.contains(it, ignoreCase = true) }) {
                    SourceResult.Restricted(Availability.RIGHTS_HOLDER)
                } else {
                    SourceResult.Failed(FailureKind.PARSE)
                }
        }
}

internal object AudioknigaOneParser {
    val SOURCE = SourceId("audiokniga_one")
    const val BASE = "https://audiokniga.one"

    data class ParsedBook(val details: SourceBookDetails, val tracks: List<SiteTrack>)

    private val json = Json { ignoreUnknownKeys = true }

    /** `https://audiokniga.one/15200-selskij-uchitel.html` → `15200-selskij-uchitel`. */
    fun keyOf(url: String): String? = Regex("""audiokniga\.one/(\d+-[^/]+?)\.html""").find(url)?.groupValues?.get(1)

    fun parseSearch(html: String): List<SourceBook> =
        Jsoup.parse(html, BASE).select(".short-item").mapNotNull { item ->
            val link = item.selectFirst("a.short-title") ?: return@mapNotNull null
            val key = keyOf(link.absUrl("href")) ?: return@mapNotNull null
            SourceBook(
                ref = SourceBookRef(SOURCE, key),
                title = link.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                authors = item.select(".icon_author a").map { it.text().trim() }.filter { it.isNotEmpty() },
                narrators = item.select(".icon_reader a").map { it.text().trim() }.filter { it.isNotEmpty() },
                coverUrl = item.selectFirst("img.cover")?.absUrl("src")?.takeIf { it.isNotBlank() },
                durationSec = SiteText.clockSec(item.selectFirst(".icon_duration")?.text()),
                genres = item.select(".icon_genre a").map { it.text().trim() },
            )
        }

    fun parseBook(html: String, key: String): ParsedBook? {
        val tracks = SiteText.jsonAfter(html, "'json',")?.let { raw ->
            runCatching { json.parseToJsonElement(raw) as JsonArray }.getOrNull()
        }.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val url = (o["url"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            SiteTrack(
                title = (o["single_name"] as? JsonPrimitive)?.content ?: (o["title"] as? JsonPrimitive)?.content,
                url = url,
                durationSec = (o["duration"] as? JsonPrimitive)?.longOrNull,
            )
        }
        if (tracks.isEmpty()) return null
        val doc = Jsoup.parse(html, BASE)
        val ld = JsonLd.book(doc)
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: ld?.text("name") ?: return null
        val authors = ld?.let { JsonLd.names(it["author"]) }.orEmpty()
            .ifEmpty { doc.select(".icon_author a").map { it.text().trim() } }
        val book = SourceBook(
            ref = SourceBookRef(SOURCE, key),
            title = title,
            authors = authors,
            narrators = ld?.let { JsonLd.names(it["readBy"]) }.orEmpty()
                .ifEmpty { doc.select(".icon_reader a").map { it.text().trim() } },
            coverUrl = ld?.text("image") ?: doc.selectFirst(".fimg img")?.absUrl("src"),
            durationSec = SiteText.isoSec(ld?.text("duration")) ?: tracks.sumOf { it.durationSec ?: 0 }.takeIf { it > 0 },
            // datePublished у сайта — дата загрузки, а не год книги: года не показываем.
            genres = ld?.texts("genre").orEmpty().ifEmpty { doc.select(".icon_genre a").map { it.text().trim() } },
        )
        return ParsedBook(
            details = SourceBookDetails(
                book = book,
                description = doc.selectFirst(".fullstory")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: ld?.text("description"),
                chapterDurationsSec = tracks.map { it.durationSec },
                rating = ld?.let(JsonLd::rating),
                series = null,
                authorShelfId = authors.firstOrNull()?.let { "author:$it" },
            ),
            tracks = tracks,
        )
    }
}
