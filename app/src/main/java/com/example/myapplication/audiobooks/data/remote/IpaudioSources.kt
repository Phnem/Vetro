package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.audiobooks.data.remote.web.SitePlaylist
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
import org.jsoup.nodes.Document

/**
 * Семейство английских WordPress-сайтов на общем CDN ipaudio (tecplan: EN №2 «Golden» и соседи):
 * goldenaudiobooks, fulllengthaudiobooks, bookaudiobooks, appaudiobooks. Разметка одна: поиск
 * `/?s=`, книга — пост с `<audio class="wp-audio-shortcode"><source src="…mp3">` на каждую часть,
 * заголовок «Автор – Название Audiobook». Звук — прямые MP3 без подписи.
 *
 * Сайты разные, CDN один: в цепочке это отдельные звенья, но общая инфраструктура.
 */
class IpaudioWpSource(
    web: SourceHttp,
    sourceId: String,
    override val displayName: String,
    private val base: String,
    nowMs: () -> Long = System::currentTimeMillis,
) : WebAudiobookSource(web, nowMs) {
    override val id = SourceId(sourceId)
    override val languages = setOf(BookLanguage.EN)
    override val infrastructureGroup = "ipaudio"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val path = if (page > 0) "/page/${page + 1}/?s=$q" else "/?s=$q"
        return web.get(base + path).mapOk { IpaudioParser.parseSearch(it, id, base) }
    }

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> = post(ref.key).mapOk { it.details }

    override suspend fun playlist(key: String): SourceResult<SitePlaylist> = post(key).mapOk { SitePlaylist(it.tracks) }

    private suspend fun post(key: String): SourceResult<IpaudioParser.ParsedBook> =
        web.get("$base/$key/").parseOk { html ->
            IpaudioParser.parseBook(html, id, key)?.let { SourceResult.Ok(it) }
                ?: if (RESTRICTED_MARKERS_EN.any { html.contains(it, ignoreCase = true) }) {
                    SourceResult.Restricted(Availability.RIGHTS_HOLDER)
                } else {
                    SourceResult.Failed(FailureKind.PARSE)
                }
        }

    companion object {
        val RESTRICTED_MARKERS_EN = listOf("removed due to dmca", "has been removed at the request", "dmca takedown")

        /** Сайты семейства: id, имя, адрес. */
        val SITES = listOf(
            Triple("goldenaudiobooks", "Golden Audiobooks", "https://goldenaudiobooks.com"),
            Triple("fulllengthaudiobooks", "Full Length Audiobooks", "https://fulllengthaudiobooks.com"),
            Triple("bookaudiobooks", "Book Audiobooks", "https://bookaudiobooks.com"),
            Triple("appaudiobooks", "App Audiobooks", "https://appaudiobooks.com"),
        )
    }
}

internal object IpaudioParser {
    data class ParsedBook(val details: SourceBookDetails, val tracks: List<SiteTrack>)

    /** Ключ поста — путь без слешей: `ernest-cline-ready-player-one-audio-book`. */
    fun keyOf(url: String, base: String): String? {
        if (!url.startsWith(base)) return null
        val path = url.removePrefix(base).trim('/').substringBefore('?')
        return path.takeIf { it.isNotEmpty() && '/' !in path && !path.startsWith("page") }
    }

    fun parseSearch(html: String, source: SourceId, base: String): List<SourceBook> =
        Jsoup.parse(html, base).select("article").mapNotNull { article ->
            val link = article.selectFirst(".post-cover a[href], h2 a[href], .entry-title a[href]") ?: return@mapNotNull null
            val key = keyOf(link.absUrl("href"), base) ?: return@mapNotNull null
            val (author, title) = splitHeading(link.attr("title").ifBlank { link.text() })
            SourceBook(
                ref = SourceBookRef(source, key),
                title = title.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                authors = listOfNotNull(author),
                narrators = emptyList(),
                coverUrl = article.selectFirst("img")?.let { img -> img.absUrl("data-src").ifBlank { img.absUrl("src") } }
                    ?.takeIf { it.isNotBlank() }?.let(::fullSizeImage),
                durationSec = null,
            )
        }.distinctBy { it.ref.key }

    fun parseBook(html: String, source: SourceId, key: String): ParsedBook? {
        val doc = Jsoup.parse(html)
        val tracks = doc.select("audio.wp-audio-shortcode source[src], audio source[src]")
            .map { it.attr("src").substringBefore("?_=") }
            .filter { it.startsWith("http") && it.substringBefore('?').endsWith(".mp3", ignoreCase = true) }
            .distinct()
            .map { url -> SiteTrack(null, url) }
        if (tracks.isEmpty()) return null
        val heading = doc.selectFirst("h1")?.text() ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val (author, title) = splitHeading(heading)
        val authors = listOfNotNull(author)
        return ParsedBook(
            details = SourceBookDetails(
                book = SourceBook(
                    ref = SourceBookRef(source, key),
                    title = title,
                    authors = authors,
                    narrators = narrators(doc),
                    coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() },
                    durationSec = null,
                    genres = doc.select(".post-meta-category a, a[rel~=category]").map { it.text().trim() }.distinct().take(3),
                ),
                description = null,
                chapterDurationsSec = List(tracks.size) { null },
                authorShelfId = authors.firstOrNull()?.let { "author:$it" },
            ),
            tracks = tracks,
        )
    }

    /** «Ernest Cline – Ready Player One Audiobook» → (Ernest Cline, Ready Player One). */
    fun splitHeading(text: String): Pair<String?, String> {
        val clean = text.replace(Regex("""\s*(?:\(|\[)?\s*(?:free\s+)?audio\s*book(?:s)?(?:\s+(?:free|online|download|stream(?:ing)?))*\s*(?:\)|\])?\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        val parts = clean.split(Regex("""\s+[–—-]\s+"""), limit = 2)
        return if (parts.size == 2) parts[0].trim() to parts[1].trim() else null to clean
    }

    /** В тексте поста чтец обычно назван так: «Narrated by …» / «Read by …». */
    private fun narrators(doc: Document): List<String> {
        val text = doc.select("article p, .entry-content p").joinToString(" ") { it.text() }
        val name = Regex("""(?:Narrated|Read)\s+by[:\s]+([A-Z][\p{L}.'\-]+(?:\s+[A-Z][\p{L}.'\-]+){0,3})""").find(text)?.groupValues?.get(1)
        return listOfNotNull(name?.trim())
    }

    /** Миниатюра WordPress `…-175x120.jpg` → исходник `….jpg`. */
    private fun fullSizeImage(url: String): String = url.replace(Regex("""-\d+x\d+(\.\w+)$"""), "$1")
}
