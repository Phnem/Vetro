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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Audioknigi.fun. Поиск — форма DLE, книга — HTML с «чипами» (автор, исполнитель, длительность),
 * плейлист — открытый AJAX плеера сайта. Ссылки в нём подписаны и протухают (`expires=`):
 * манифест живёт до самого раннего срока и потом перезапрашивается.
 */
class AudioknigiFunSource(web: SourceHttp, nowMs: () -> Long = System::currentTimeMillis) : WebAudiobookSource(web, nowMs) {
    override val id = AudioknigiFunParser.SOURCE
    override val displayName = "Audioknigi.fun"
    override val languages = setOf(BookLanguage.RU)
    override val infrastructureGroup = "slovushko.com"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> =
        web.postForm(
            "${AudioknigiFunParser.BASE}/index.php?do=search",
            mapOf("do" to "search", "subaction" to "search", "story" to query.trim(), "search_start" to "${page + 1}"),
        ).mapOk(AudioknigiFunParser::parseSearch)

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> = page(ref.key).parseOk { details ->
        val count = (tracks(ref.key) as? SourceResult.Ok)?.value?.size ?: 0
        SourceResult.Ok(details.copy(chapterDurationsSec = List(count) { null }))
    }

    override suspend fun playlist(key: String): SourceResult<SitePlaylist> = page(key).parseOk { details ->
        tracks(key).mapOk { tracks ->
            SitePlaylist(tracks, expiresAt = AudioknigiFunParser.expiresAt(tracks), totalSec = details.book.durationSec)
        }
    }

    private suspend fun tracks(key: String): SourceResult<List<SiteTrack>> {
        val newsId = key.substringBefore('-')
        return web.get(
            "${AudioknigiFunParser.BASE}/engine/ajax/controller.php?mod=audioplaylist&newsid=$newsId",
            mapOf("Referer" to "${AudioknigiFunParser.BASE}/$key.html", "X-Requested-With" to "XMLHttpRequest"),
        ).mapOk(AudioknigiFunParser::parsePlaylist)
    }

    private suspend fun page(key: String): SourceResult<SourceBookDetails> =
        web.get("${AudioknigiFunParser.BASE}/$key.html").parseOk { html ->
            AudioknigiFunParser.parseBook(html, key)?.let { SourceResult.Ok(it) }
                ?: if (RESTRICTED_MARKERS.any { html.contains(it, ignoreCase = true) }) {
                    SourceResult.Restricted(Availability.RIGHTS_HOLDER)
                } else {
                    SourceResult.Failed(FailureKind.PARSE)
                }
        }
}

internal object AudioknigiFunParser {
    val SOURCE = SourceId("audioknigi_fun")
    const val BASE = "https://audioknigi.fun"

    private val json = Json { ignoreUnknownKeys = true }

    fun keyOf(href: String): String? = Regex("""audioknigi\.fun/(\d+-[^/?#]+?)\.html""").find(href)?.groupValues?.get(1)

    fun parseSearch(html: String): List<SourceBook> =
        Jsoup.parse(html, BASE).select("article.card").mapNotNull { card ->
            val link = card.selectFirst(".card__title a") ?: return@mapNotNull null
            val key = keyOf(link.absUrl("href")) ?: return@mapNotNull null
            val info = labeled(card.select(".card__list li"), "span")
            SourceBook(
                ref = SourceBookRef(SOURCE, key),
                title = link.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                authors = info["Автор"].orEmpty(),
                narrators = info["Исполнитель"].orEmpty(),
                coverUrl = card.selectFirst(".card__img img")?.absUrl("src")?.takeIf { it.isNotBlank() },
                durationSec = SiteText.clockSec(info["Продолжительность"]?.joinToString(" ")),
                genres = info["Жанр"].orEmpty(),
            )
        }

    fun parseBook(html: String, key: String): SourceBookDetails? {
        val doc = Jsoup.parse(html, BASE)
        if (doc.selectFirst("[data-ap-src]") == null) return null
        val title = doc.selectFirst("h1.book-hero__title")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val info = labeled(doc.select(".book-chip"), "b")
        val authors = info["Автор"].orEmpty()
        val description = doc.selectFirst(".full-text")?.let { el ->
            el.select("h2").remove()
            el.text().trim()
        }?.takeIf { it.isNotEmpty() }
        return SourceBookDetails(
            book = SourceBook(
                ref = SourceBookRef(SOURCE, key),
                title = title,
                authors = authors,
                narrators = info["Исполнитель"].orEmpty(),
                coverUrl = doc.selectFirst("[data-cover]")?.absUrl("data-cover")?.takeIf { it.isNotBlank() },
                durationSec = SiteText.clockSec(info["Длительность"]?.joinToString(" ")),
                genres = info["Жанр"].orEmpty(),
            ),
            description = description,
            chapterDurationsSec = emptyList(),
            authorShelfId = authors.firstOrNull()?.let { "author:$it" },
        )
    }

    /**
     * Ответ `{"ok":true,"playlist":"…"}`: плейлист — строка с объектами `{"title":…,"file":…}` без
     * внешних скобок. Пары «название — ссылка» вытаскиваются по порядку, скобки не нужны.
     */
    fun parsePlaylist(body: String): List<SiteTrack> {
        val o = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return emptyList()
        var raw = (o["playlist"] as? JsonPrimitive)?.content ?: return emptyList()
        // У части книг плейлист экранирован дважды (`\"title\":\"01\"`) — снимаем лишние слои.
        repeat(3) { if ("\\\"" in raw) raw = unescape(raw) }
        val pair = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"file"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        return pair.findAll(raw).mapNotNull { m ->
            val url = unescape(m.groupValues[2]).takeIf { it.startsWith("http") } ?: return@mapNotNull null
            SiteTrack(unescape(m.groupValues[1]), url)
        }.toList()
    }

    /** Самый ранний `expires=` среди ссылок, в миллисекундах; без подписи — null. */
    fun expiresAt(tracks: List<SiteTrack>): Long? = tracks
        .mapNotNull { Regex("""[?&]expires=(\d+)""").find(it.url)?.groupValues?.get(1)?.toLongOrNull() }
        .minOrNull()?.times(1000)

    private fun unescape(s: String) = s.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")

    /** «<b>Автор:</b> <a>…</a>, <a>…</a>» → «Автор» → [имена ссылок или текст после подписи]. */
    private fun labeled(items: List<Element>, labelTag: String): Map<String, List<String>> =
        items.mapNotNull { item ->
            val label = item.selectFirst(labelTag)?.text()?.trim()?.trimEnd(':') ?: return@mapNotNull null
            val links = item.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
            val values = links.ifEmpty {
                listOf(item.text().substringAfter(':').trim()).filter { it.isNotEmpty() }
            }
            label to values
        }.toMap()
}
