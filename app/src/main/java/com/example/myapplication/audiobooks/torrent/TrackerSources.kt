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
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Название раздачи «Автор - Название [пометки] (год) MP3» по частям. */
data class TorrentTitle(val authors: List<String>, val title: String, val year: Int?, val notes: List<String>) {
    companion object {
        private val FORMAT = Regex("""\s*\b(?:MP3|M4B|M4A|AAC|OGG|OPUS|FLAC)\b.*$""", RegexOption.IGNORE_CASE)

        fun parse(raw: String): TorrentTitle {
            var t = raw.trim()
            val year = Regex("""\((\d{4})(?:\s*[-–]\s*\d{4})?\)""").find(t)?.groupValues?.get(1)?.toIntOrNull()
            val notes = Regex("""\[([^\]]*)]""").findAll(t).map { it.groupValues[1].trim() }.toList()
            t = t.replace(Regex("""\[[^\]]*]"""), " ")
                .replace(Regex("""\(\d{4}(?:\s*[-–]\s*\d{4})?\)"""), " ")
                .replace(FORMAT, "")
                .replace(Regex("""\s+"""), " ")
                .trim()
            // «Автор - Название»: у трекеров автор слева от первого тире (у сайтов наоборот).
            val dash = Regex("""\s[-–—]\s""").find(t)
            val author = dash?.let { t.substring(0, it.range.first).trim() }?.takeIf { it.isNotEmpty() }
            val title = dash?.let { t.substring(it.range.last + 1).trim() } ?: t
            return TorrentTitle(listOfNotNull(author), title, year, notes)
        }

        /** Раздача — звук, а не книга в FB2/EPUB/PDF или фильм. */
        fun isAudio(raw: String): Boolean =
            Regex("""\b(?:MP3|M4B|AAC|OGG|OPUS|FLAC)\b""", RegexOption.IGNORE_CASE).containsMatchIn(raw) ||
                raw.contains("аудиокниг", ignoreCase = true) || raw.contains("аудиоспектакл", ignoreCase = true)
    }
}

// ---------- Rutor ----------

/**
 * Rutor: открытые magnet-ссылки, без регистрации. Поиск — раздел «Книги» (11) с отбором звука,
 * страница раздачи — «Автор / Исполнитель / Формат … kbps / Продолжительность».
 */
class RutorSource(web: SourceHttp, engine: TorrentEngine) : TorrentAudiobookSource(web, engine) {
    override val id = SourceId("rutor")
    override val displayName = "Rutor"
    override val languages = setOf(BookLanguage.RU)
    override val infrastructureGroup = "bittorrent"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return web.get("$BASE/search/$page/11/100/0/$q").mapOk { RutorParser.parseSearch(it, id) }
    }

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> =
        topic(ref.key).mapOk { it.details }

    override suspend fun release(key: String): SourceResult<TorrentRelease> = topic(key).parseOk { topic ->
        topic.magnet?.let { SourceResult.Ok(TorrentRelease(TorrentLink.Magnet(it), topic.kbps, topic.details.book.durationSec)) }
            ?: SourceResult.Failed(FailureKind.PARSE)
    }

    private suspend fun topic(key: String): SourceResult<RutorParser.Topic> =
        web.get("$BASE/torrent/$key").parseOk { html ->
            RutorParser.parseTopic(html, id, key)?.let { SourceResult.Ok(it) } ?: SourceResult.Failed(FailureKind.PARSE)
        }

    companion object {
        const val BASE = "https://rutor.info"
    }
}

internal object RutorParser {
    data class Topic(val details: SourceBookDetails, val magnet: String?, val kbps: Int?)

    fun parseSearch(html: String, source: SourceId): List<SourceBook> =
        Jsoup.parse(html, RutorSource.BASE).select("tr.gai, tr.tum").mapNotNull { row ->
            val link = row.selectFirst("a[href^=/torrent/]") ?: return@mapNotNull null
            val raw = link.text().trim()
            if (!TorrentTitle.isAudio(raw)) return@mapNotNull null
            val key = Regex("""/torrent/(\d+)""").find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val parsed = TorrentTitle.parse(raw)
            SourceBook(
                ref = SourceBookRef(source, key),
                title = parsed.title.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                authors = parsed.authors,
                narrators = emptyList(),
                coverUrl = null,
                durationSec = null,
                year = parsed.year,
            )
        }

    fun parseTopic(html: String, source: SourceId, key: String): Topic? {
        val doc = Jsoup.parse(html, RutorSource.BASE)
        val raw = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val cell = doc.selectFirst("#details td:eq(1)") ?: doc.selectFirst("#details") ?: return null
        val fields = labeled(cell)
        val parsed = TorrentTitle.parse(raw)
        val authors = SiteText.names(fields["Автор"]).ifEmpty { parsed.authors }
        val format = fields["Формат"] ?: fields["Аудио кодек"] ?: fields["Битрейт"]
        val book = SourceBook(
            ref = SourceBookRef(source, key),
            title = fields["Название"] ?: parsed.title,
            authors = authors,
            narrators = SiteText.names(fields["Исполнитель"] ?: fields["Читает"] ?: fields["Исполнители"]),
            coverUrl = cell.selectFirst("img[src]")?.absUrl("src")?.takeIf { it.isNotBlank() },
            durationSec = SiteText.clockSec(fields["Продолжительность"] ?: fields["Время звучания"]),
            genres = SiteText.names(fields["Жанр"]).filterNot { it.contains("аудиокниг", ignoreCase = true) },
            year = fields["Год выпуска"]?.take(4)?.toIntOrNull() ?: parsed.year,
        )
        return Topic(
            details = SourceBookDetails(
                book = book,
                description = fields["Описание"],
                chapterDurationsSec = emptyList(),
                authorShelfId = authors.firstOrNull()?.let { "author:$it" },
            ),
            magnet = doc.selectFirst("a[href^=magnet:]")?.attr("href"),
            kbps = format?.let { Regex("""(\d{2,3})\s*kbps""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() },
        )
    }

    /** «<b>Метка</b>: значение<br>» → карта; описание может занимать несколько строк. */
    private fun labeled(cell: Element): Map<String, String> {
        val html = cell.html()
        val result = linkedMapOf<String, String>()
        Regex("""<b>([^<:]{2,40})</b>\s*:?\s*(.*?)(?=<b>[^<:]{2,40}</b>\s*:|$)""", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
            val label = m.groupValues[1].trim().trimEnd(':')
            val value = Jsoup.parse(m.groupValues[2]).text().trim().trimStart(':').trim()
            if (value.isNotEmpty() && label !in result) result[label] = value
        }
        return result
    }
}

// ---------- AudioBookBay ----------

/**
 * AudioBookBay — крупнейший англоязычный трекер аудиокниг. На странице — Info Hash и список
 * трекеров, из них собирается magnet; формат и битрейт — в описании.
 */
class AudioBookBaySource(web: SourceHttp, engine: TorrentEngine) : TorrentAudiobookSource(web, engine) {
    override val id = SourceId("audiobookbay")
    override val displayName = "AudioBook Bay"
    override val languages = setOf(BookLanguage.EN)
    override val infrastructureGroup = "bittorrent"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> {
        val q = URLEncoder.encode(query.trim().lowercase(), "UTF-8")
        val path = if (page > 0) "/page/${page + 1}/?s=$q&tt=1" else "/?s=$q&tt=1"
        return web.get(BASE + path).mapOk { AudioBookBayParser.parseSearch(it, id) }
    }

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> = topic(ref.key).mapOk { it.details }

    override suspend fun release(key: String): SourceResult<TorrentRelease> = topic(key).parseOk { topic ->
        val hash = topic.hash ?: return@parseOk SourceResult.Failed(FailureKind.PARSE)
        SourceResult.Ok(TorrentRelease(TorrentLink.magnet(hash, topic.details.book.title, topic.trackers), topic.kbps))
    }

    private suspend fun topic(key: String): SourceResult<AudioBookBayParser.Topic> =
        web.get("$BASE/abss/$key/").parseOk { html ->
            AudioBookBayParser.parseTopic(html, id, key)?.let { SourceResult.Ok(it) } ?: SourceResult.Failed(FailureKind.PARSE)
        }

    companion object {
        const val BASE = "https://audiobookbay.lu"
    }
}

internal object AudioBookBayParser {
    data class Topic(val details: SourceBookDetails, val hash: String?, val trackers: List<String>, val kbps: Int?)

    fun keyOf(href: String): String? = Regex("""/abss/([^/?#]+)/?""").find(href)?.groupValues?.get(1)

    fun parseSearch(html: String, source: SourceId): List<SourceBook> =
        Jsoup.parse(html, AudioBookBaySource.BASE).select("div.post").mapNotNull { post ->
            val link = post.selectFirst(".postTitle a[href]") ?: return@mapNotNull null
            val key = keyOf(link.attr("href")) ?: return@mapNotNull null
            val (title, author) = SiteText.splitTitleAuthor(link.text())
            SourceBook(
                ref = SourceBookRef(source, key),
                title = title.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                authors = listOfNotNull(author),
                narrators = emptyList(),
                coverUrl = post.selectFirst(".postContent img[src]")?.absUrl("src")?.takeIf { it.isNotBlank() },
                durationSec = null,
                genres = post.selectFirst(".postInfo")?.text()?.substringAfter("Category:", "")?.substringBefore("Language")
                    ?.split(' ', ' ')?.map { it.trim() }?.filter { it.length > 2 }?.take(2).orEmpty(),
            )
        }

    fun parseTopic(html: String, source: SourceId, key: String): Topic? {
        val doc = Jsoup.parse(html, AudioBookBaySource.BASE)
        val heading = doc.selectFirst(".postTitle h1, h1")?.text()?.trim() ?: return null
        val (title, authorFromTitle) = SiteText.splitTitleAuthor(heading)
        val rows = doc.select("table.torrent_info tr, tr").mapNotNull { tr ->
            val cells = tr.select("td")
            if (cells.size < 2) null else cells[0].text().trim().trimEnd(':') to cells[1].text().trim()
        }
        val hash = rows.firstOrNull { it.first.equals("Info Hash", true) }?.second
            ?.takeIf { Regex("""^[0-9a-fA-F]{40}$""").matches(it) }
        val trackers = rows.filter { it.first.equals("Tracker", true) }.map { it.second }.filter { "://" in it }
        val desc = doc.selectFirst(".desc")
        val text = desc?.text().orEmpty()
        val authors = desc?.select(".author")?.map { it.text().trim() }.orEmpty().ifEmpty { listOfNotNull(authorFromTitle) }
        val narrators = desc?.select(".narrator")?.map { it.text().trim() }.orEmpty().ifEmpty {
            listOfNotNull(Regex("""(?:Narrator|Narrated by|Read by)\s*:?\s*([^\n:]{3,60}?)(?=\s+(?:Format|Bitrate|Unabridged|Abridged|Synopsis|Ripped|Written)|$)""")
                .find(text)?.groupValues?.get(1)?.trim())
        }
        val kbps = Regex("""Bitrate\s*:?\s*(\d{2,3})\s*K""", RegexOption.IGNORE_CASE).find(doc.text())?.groupValues?.get(1)?.toIntOrNull()
        return Topic(
            details = SourceBookDetails(
                book = SourceBook(
                    ref = SourceBookRef(source, key),
                    title = title,
                    authors = authors,
                    narrators = narrators,
                    coverUrl = doc.selectFirst("img[itemprop=image]")?.absUrl("src")?.takeIf { it.isNotBlank() },
                    durationSec = null,
                ),
                description = Regex("""Synopsis\s*:?\s*(.+)""").find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.length > 20 },
                chapterDurationsSec = emptyList(),
                authorShelfId = authors.firstOrNull()?.let { "author:$it" },
            ),
            hash = hash?.lowercase(),
            trackers = trackers,
            kbps = kbps,
        )
    }
}

// ---------- The Pirate Bay (apibay) ----------

/**
 * The Pirate Bay через открытый JSON apibay: раздел «Audio books» (102). Хеш и сидеры есть сразу в
 * выдаче, описание — `t.php`. Magnet собирается из хеша и открытых трекеров.
 */
class PirateBaySource(web: SourceHttp, engine: TorrentEngine) : TorrentAudiobookSource(web, engine) {
    override val id = SourceId("piratebay")
    override val displayName = "The Pirate Bay"
    override val languages = setOf(BookLanguage.EN)
    override val infrastructureGroup = "bittorrent"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> {
        if (page > 0) return SourceResult.Ok(emptyList())
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return web.get("$API/q.php?q=$q&cat=102").mapOk { PirateBayParser.parseSearch(it, id) }
    }

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> = torrent(ref.key).mapOk { it.first }

    override suspend fun release(key: String): SourceResult<TorrentRelease> = torrent(key).mapOk { (details, hash) ->
        TorrentRelease(TorrentLink.magnet(hash, details.book.title, PirateBayParser.TRACKERS))
    }

    private suspend fun torrent(key: String): SourceResult<Pair<SourceBookDetails, String>> =
        web.get("$API/t.php?id=$key").parseOk { body ->
            PirateBayParser.parseTorrent(body, id, key)?.let { SourceResult.Ok(it) } ?: SourceResult.Failed(FailureKind.PARSE)
        }

    companion object {
        const val API = "https://apibay.org"
    }
}

internal object PirateBayParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Открытые трекеры, которые сайт сам подставляет в magnet. */
    val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
    )

    fun parseSearch(body: String, source: SourceId): List<SourceBook> {
        val items = runCatching { json.parseToJsonElement(body) as JsonArray }.getOrNull().orEmpty()
        return items.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it != "0" } ?: return@mapNotNull null
            if ((o.str("seeders")?.toIntOrNull() ?: 0) <= 0) return@mapNotNull null
            val parsed = TorrentTitle.parse(o.str("name") ?: return@mapNotNull null)
            SourceBook(
                ref = SourceBookRef(source, id),
                title = parsed.title.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                authors = parsed.authors,
                narrators = emptyList(),
                coverUrl = null,
                durationSec = null,
                year = parsed.year,
            )
        }
    }

    fun parseTorrent(body: String, source: SourceId, key: String): Pair<SourceBookDetails, String>? {
        val o = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return null
        val hash = o.str("info_hash")?.lowercase()?.takeIf { Regex("""^[0-9a-f]{40}$""").matches(it) && it.any { c -> c != '0' } }
            ?: return null
        val parsed = TorrentTitle.parse(o.str("name") ?: return null)
        val descr = o.str("descr").orEmpty()
        val narrator = Regex("""(?:Narrated by|Narrator|Read by)\s*:?\s*([A-Z][\p{L}.'\-]+(?:\s+[A-Z][\p{L}.'\-]+){0,3})""").find(descr)
            ?.groupValues?.get(1)
        return SourceBookDetails(
            book = SourceBook(
                ref = SourceBookRef(source, key),
                title = parsed.title,
                authors = parsed.authors,
                narrators = listOfNotNull(narrator),
                coverUrl = null,
                durationSec = null,
                year = parsed.year,
            ),
            description = descr.takeIf { it.length > 20 },
            chapterDurationsSec = emptyList(),
            authorShelfId = parsed.authors.firstOrNull()?.let { "author:$it" },
        ) to hash
    }

    private fun JsonObject.str(name: String): String? = (get(name) as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
}
