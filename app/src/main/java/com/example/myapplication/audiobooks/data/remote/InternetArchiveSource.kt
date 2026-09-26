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
import com.example.myapplication.audiobooks.domain.source.SourceShelf
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup

/**
 * Internet Archive — открытое API: коллекции LibriVox (public domain, законно везде, где действует
 * общественное достояние США) и «Audio Books & Poetry». Поиск — `advancedsearch`, файлы с длинами —
 * `/metadata/<id>`, звук — `/download/<id>/<файл>` с Range. Ссылки бессрочные.
 */
class InternetArchiveSource(web: SourceHttp, nowMs: () -> Long = System::currentTimeMillis) : WebAudiobookSource(web, nowMs) {
    override val id = InternetArchiveParser.SOURCE
    override val displayName = "Internet Archive"
    override val languages = setOf(BookLanguage.EN)
    override val infrastructureGroup = "archive.org"
    override val shelves = listOf(SourceShelf("popular", "LibriVox: классика вслух", "Бесплатно и законно, на английском"))

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> {
        val words = query.trim().replace(Regex("""[()":]"""), " ").trim()
        if (words.isEmpty()) return SourceResult.Ok(emptyList())
        return advanced("(title:($words) OR creator:($words)) AND $COLLECTIONS", page)
    }

    override suspend fun shelf(id: String, page: Int): SourceResult<List<SourceBook>> =
        if (id == "popular") advanced("collection:librivoxaudio", page) else super.shelf(id, page)

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> = item(ref.key).mapOk { it.details }

    override suspend fun playlist(key: String): SourceResult<SitePlaylist> = item(key).mapOk { SitePlaylist(it.tracks) }

    private suspend fun advanced(q: String, page: Int): SourceResult<List<SourceBook>> {
        val fields = listOf("identifier", "title", "creator", "runtime").joinToString("") { "&fl[]=$it" }
        val url = "${InternetArchiveParser.BASE}/advancedsearch.php?q=${URLEncoder.encode("$q AND mediatype:audio", "UTF-8")}" +
            "$fields&rows=$ROWS&page=${page + 1}&output=json&sort[]=downloads+desc"
        return web.get(url).mapOk(InternetArchiveParser::parseSearch)
    }

    private suspend fun item(key: String): SourceResult<InternetArchiveParser.ParsedItem> =
        web.get("${InternetArchiveParser.BASE}/metadata/$key").parseOk { body ->
            InternetArchiveParser.parseItem(body, key)?.let { SourceResult.Ok(it) } ?: SourceResult.Failed(FailureKind.PARSE)
        }

    private companion object {
        const val COLLECTIONS = "(collection:librivoxaudio OR collection:audio_bookspoetry)"
        const val ROWS = 30
    }
}

internal object InternetArchiveParser {
    val SOURCE = SourceId("archive_org")
    const val BASE = "https://archive.org"

    data class ParsedItem(val details: SourceBookDetails, val tracks: List<SiteTrack>)

    private val json = Json { ignoreUnknownKeys = true }

    fun parseSearch(body: String): List<SourceBook> {
        val docs = runCatching {
            ((json.parseToJsonElement(body) as JsonObject)["response"] as JsonObject)["docs"] as JsonArray
        }.getOrNull().orEmpty()
        return docs.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.text("identifier") ?: return@mapNotNull null
            SourceBook(
                ref = SourceBookRef(SOURCE, id),
                title = o.text("title") ?: return@mapNotNull null,
                authors = o.texts("creator"),
                narrators = emptyList(),
                coverUrl = "$BASE/services/img/$id",
                durationSec = SiteText.clockSec(o.text("runtime")),
            )
        }
    }

    /**
     * Файлы одного формата (предпочтительно 64 Кбит/с — у LibriVox это основной поток, дальше VBR и
     * 128 Кбит/с), по порядку имён. Длина файла — секунды или «мм:сс».
     */
    fun parseItem(body: String, key: String): ParsedItem? {
        val root = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return null
        val meta = root["metadata"] as? JsonObject ?: return null
        val files = (root["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val format = FORMATS.firstOrNull { f -> files.any { it.text("format") == f } } ?: return null
        val tracks = files.filter { it.text("format") == format }
            .sortedWith(compareBy({ it.text("track")?.substringBefore('/')?.toIntOrNull() ?: Int.MAX_VALUE }, { it.text("name") }))
            .mapNotNull { f ->
                val name = f.text("name") ?: return@mapNotNull null
                SiteTrack(
                    title = f.text("title"),
                    url = "$BASE/download/$key/" + name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") },
                    durationSec = f.text("length")?.let(::lengthSec),
                )
            }
        if (tracks.isEmpty()) return null
        val authors = meta.texts("creator")
        val book = SourceBook(
            ref = SourceBookRef(SOURCE, key),
            title = meta.text("title") ?: return null,
            authors = authors,
            narrators = emptyList(),
            coverUrl = "$BASE/services/img/$key",
            durationSec = tracks.sumOf { it.durationSec ?: 0 }.takeIf { it > 0 } ?: SiteText.clockSec(meta.text("runtime")),
            genres = meta.texts("subject").filterNot { it.equals("librivox", true) || it.contains("audiobook", true) }.take(3),
            year = meta.text("date")?.take(4)?.toIntOrNull(),
        )
        return ParsedItem(
            details = SourceBookDetails(
                book = book,
                description = meta.text("description")?.let { Jsoup.parse(it).text() }?.takeIf { it.isNotBlank() },
                chapterDurationsSec = tracks.map { it.durationSec },
                authorShelfId = authors.firstOrNull()?.let { "author:$it" },
            ),
            tracks = tracks,
        )
    }

    private val FORMATS = listOf("64Kbps MP3", "VBR MP3", "128Kbps MP3", "MP3")

    /** «3906.12» или «65:06» / «1:05:06». */
    fun lengthSec(text: String): Long? = text.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
        ?: text.split(':').mapNotNull { it.trim().toDoubleOrNull() }.takeIf { it.size in 2..3 }
            ?.fold(0.0) { acc, part -> acc * 60 + part }?.toLong()?.takeIf { it > 0 }

    private fun JsonObject.text(name: String): String? = when (val e: JsonElement? = get(name)) {
        is JsonPrimitive -> e.content.trim().takeIf { it.isNotEmpty() }
        is JsonArray -> (e.firstOrNull() as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun JsonObject.texts(name: String): List<String> = when (val e = get(name)) {
        is JsonPrimitive -> listOf(e.content.trim()).filter { it.isNotEmpty() }
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
        else -> emptyList()
    }
}
