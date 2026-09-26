package com.example.myapplication.audiobooks.torrent

import com.example.myapplication.audiobooks.data.remote.web.SiteText
import com.example.myapplication.audiobooks.data.remote.web.SourceHttp
import com.example.myapplication.audiobooks.data.remote.web.mapOk
import com.example.myapplication.audiobooks.data.remote.web.parseOk
import com.example.myapplication.audiobooks.domain.source.BookLanguage
import com.example.myapplication.audiobooks.domain.source.FailureKind
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookDetails
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceId
import com.example.myapplication.audiobooks.domain.source.SourceResult
import org.jsoup.Jsoup

/**
 * Audioboo — большой русский каталог раздач: `.torrent` без регистрации (`index.php?do=download&id=`).
 * На странице книги может быть несколько раздач (перезаливка, другой чтец) — у каждой свой блок
 * «Метка: значение» и своя ссылка. Ключ книги — номер страницы, `номер~раздача` — конкретная раздача.
 */
class AudiobooSource(web: SourceHttp, engine: TorrentEngine) : TorrentAudiobookSource(web, engine) {
    override val id = AudiobooParser.SOURCE
    override val displayName = "Audioboo"
    override val languages = setOf(BookLanguage.RU)
    override val infrastructureGroup = "bittorrent"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> =
        web.postForm(
            "${AudiobooParser.BASE}/index.php?do=search",
            mapOf("do" to "search", "subaction" to "search", "story" to query.trim(), "search_start" to "${page + 1}"),
        ).mapOk(AudiobooParser::parseSearch)

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> = topic(ref.key).mapOk { it.first }

    override suspend fun release(key: String): SourceResult<TorrentRelease> = topic(key).mapOk { (details, release) ->
        TorrentRelease(
            link = TorrentLink.File(
                "${AudiobooParser.BASE}/index.php?do=download&id=${release.downloadId}",
                mapOf("Referer" to "${AudiobooParser.BASE}/index.php?newsid=${key.substringBefore('~')}"),
            ),
            bitrateKbps = release.kbps,
            totalSec = details.book.durationSec,
        )
    }

    private suspend fun topic(key: String): SourceResult<Pair<SourceBookDetails, AudiobooParser.Release>> =
        web.get("${AudiobooParser.BASE}/index.php?newsid=${key.substringBefore('~')}").parseOk { html ->
            AudiobooParser.parseTopic(html, key)?.let { SourceResult.Ok(it) } ?: SourceResult.Failed(FailureKind.PARSE)
        }
}

internal object AudiobooParser {
    val SOURCE = SourceId("audioboo")
    const val BASE = "https://audioboo.org"

    data class Release(val downloadId: String, val fields: Map<String, String>, val kbps: Int?)

    fun idOf(url: String): String? = Regex("""audioboo\.org/[^/]+/(\d+)-""").find(url)?.groupValues?.get(1)

    /** «Лю Цысинь - В память о прошлом Земли 01. Задача трех тел» → «Задача трех тел» (номер в цикле отрезан). */
    fun bookTitle(raw: String): TorrentTitle {
        val parsed = TorrentTitle.parse(raw)
        val title = parsed.title.replace(Regex("""^.*?\b\d{1,3}\.\s+"""), "").trim().ifEmpty { parsed.title }
        return parsed.copy(title = title)
    }

    fun parseSearch(html: String): List<SourceBook> =
        Jsoup.parse(html, BASE).select("article.card").mapNotNull { card ->
            val link = card.selectFirst(".card__title a[href]") ?: return@mapNotNull null
            val id = idOf(link.absUrl("href")) ?: return@mapNotNull null
            val parsed = bookTitle(link.text())
            val authors = card.select("a[href*=/xfsearch/avtora/]").map { it.text().trim() }.ifEmpty { parsed.authors }
            SourceBook(
                ref = SourceBookRef(SOURCE, id),
                title = parsed.title.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                authors = authors,
                narrators = card.select("a[href*=/xfsearch/ispolnitel/], a[href*=/xfsearch/chitaet/]").map { it.text().trim() },
                coverUrl = card.selectFirst(".card__img img[src]")?.absUrl("src")?.takeIf { it.isNotBlank() },
                durationSec = null,
                genres = card.select("a[href]").filter { it.parent()?.text()?.contains("Жанр") == true }.map { it.text().trim() }.take(1),
            )
        }.distinctBy { it.ref.key }

    /** Раздача из ключа (`номер~раздача`) или первая на странице. */
    fun parseTopic(html: String, key: String): Pair<SourceBookDetails, Release>? {
        val releases = releases(html)
        if (releases.isEmpty()) return null
        val wanted = key.substringAfter('~', "")
        val release = releases.firstOrNull { it.downloadId == wanted } ?: releases.first()
        val doc = Jsoup.parse(html, BASE)
        val heading = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val parsed = bookTitle(heading)
        val f = release.fields
        val surname = f["Фамилия автора"]
        val name = f["Имя автора"]
        val authors = listOfNotNull(listOfNotNull(name, surname).joinToString(" ").takeIf { it.isNotBlank() })
            .ifEmpty { parsed.authors }
        return SourceBookDetails(
            book = SourceBook(
                ref = SourceBookRef(SOURCE, key),
                title = f["Название"] ?: parsed.title,
                authors = authors,
                narrators = SiteText.names(f["Исполнитель"] ?: f["Читает"]),
                coverUrl = doc.selectFirst("meta[property=og:image]")?.absUrl("content")?.takeIf { it.isNotBlank() }
                    ?: doc.selectFirst(".fimg img[src], .full-story img[src]")?.absUrl("src"),
                durationSec = SiteText.clockSec(f["Время звучания"]),
                genres = SiteText.names(f["Жанр"]),
                year = f["Год выпуска"]?.take(4)?.toIntOrNull(),
            ),
            description = f["Описание"],
            chapterDurationsSec = emptyList(),
            series = f["Цикл/серия"],
            authorShelfId = authors.firstOrNull()?.let { "author:$it" },
        ) to release
    }

    /** Блоки раздач: текст между соседними ссылками на `.torrent`, разобранный на «Метка: значение». */
    private fun releases(html: String): List<Release> {
        val links = Regex("""do=download&(?:amp;)?id=(\d+)""").findAll(html).toList()
        val seen = mutableSetOf<String>()
        var from = html.indexOf("<article").takeIf { it >= 0 } ?: 0
        val result = mutableListOf<Release>()
        for (m in links) {
            val id = m.groupValues[1]
            if (!seen.add(id)) {
                from = m.range.last
                continue
            }
            val text = Jsoup.parse(html.substring(from, m.range.first).replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")).wholeText()
            from = m.range.last
            val fields = linkedMapOf<String, String>()
            var current: String? = null
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                val label = Regex("""^([А-ЯЁA-Z][^:]{1,40}):\s*(.*)$""").find(line)
                if (label != null) {
                    current = label.groupValues[1].trim()
                    fields[current!!] = label.groupValues[2].trim()
                } else if (current == "Описание") {
                    fields["Описание"] = (fields["Описание"].orEmpty() + " " + line).trim()
                }
            }
            val kbps = fields["Битрейт"]?.let { Regex("""(\d{2,3})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            result += Release(id, fields, kbps)
        }
        return result
    }
}
