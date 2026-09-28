package com.example.myapplication.audiobooks.text.source

import com.example.myapplication.audiobooks.text.BookText
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

private val json = Json { ignoreUnknownKeys = true }

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun JsonObject.str(key: String) = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()

private fun surname(authors: List<String>) = authors.firstOrNull()?.split(' ', ',')?.filter { it.length > 2 }?.maxByOrNull { it.length }.orEmpty()

/**
 * Project Gutenberg: поиск через OPDS, текст — plain text UTF-8. Для записей LibriVox (они на
 * Internet Archive) — ровно тот текст, по которому читали: у LibriVox он указан в url_text_source.
 */
class GutenbergTextSource(private val http: TextHttp) : BookTextSource {
    override val id = "gutenberg"
    override val displayName = "Project Gutenberg"

    override suspend fun find(query: TextQuery): List<TextCandidate> {
        val out = ArrayList<TextCandidate>()
        librivoxText(query)?.let { out += it }
        val q = listOf(query.titleOriginal ?: query.title, surname(query.authors)).filter { it.isNotBlank() }.joinToString(" ")
        val feed = runCatching { http.string("https://www.gutenberg.org/ebooks/search.opds/?query=${enc(q)}") }.getOrNull() ?: return out
        val doc = Jsoup.parse(feed, "", Parser.xmlParser())
        for (entry in doc.select("entry").take(8)) {
            val bookId = Regex("/ebooks/(\\d+)").find(entry.selectFirst("id")?.text().orEmpty())?.groupValues?.get(1) ?: continue
            val rawTitle = entry.selectFirst("title")?.text().orEmpty()
            // «Az időgép (Hungarian)» — язык в скобках; без скобок — английский.
            val lang = Regex("\\((\\w+)\\)$").find(rawTitle)?.groupValues?.get(1)?.let(::languageCode) ?: "en"
            out += TextCandidate(id, bookId, rawTitle.replace(Regex("\\s*\\(\\w+\\)$"), ""),
                listOfNotNull(entry.selectFirst("content")?.text()), lang)
        }
        return out.distinctBy { it.id }
    }

    override suspend fun load(candidate: TextCandidate): BookText? {
        val bytes = http.bytes("https://www.gutenberg.org/cache/epub/${candidate.id}/pg${candidate.id}.txt")
        return TextParsers.parse(bytes, "book.txt", candidate.language)
    }

    /** LibriVox знает, по какому тексту читали: ссылка на Гутенберг — точный кандидат. */
    private suspend fun librivoxText(query: TextQuery): TextCandidate? {
        val key = query.sourceKey?.takeIf { query.sourceId == "archive_org" && it.contains("librivox", ignoreCase = true) } ?: return null
        val body = runCatching {
            http.string("https://librivox.org/api/feed/audiobooks/?title=${enc(query.title)}&format=json&extended=1&limit=20")
        }.getOrNull() ?: return null
        val books = runCatching { json.parseToJsonElement(body).jsonObject["books"]?.jsonArray }.getOrNull() ?: return null
        for (b in books) {
            val o = b.jsonObject
            if (o.str("url_iarchive")?.trimEnd('/')?.endsWith("/$key") != true) continue
            val text = o.str("url_text_source") ?: return null
            val gid = Regex("gutenberg\\.org/(?:etext|ebooks)/(\\d+)").find(text)?.groupValues?.get(1) ?: return null
            return TextCandidate(id, gid, o.str("title") ?: query.title, query.authors, languageCode(o.str("language")), exact = true)
        }
        return null
    }
}

/** Standard Ebooks: вычитанные EPUB общественного достояния (английский). */
class StandardEbooksTextSource(private val http: TextHttp) : BookTextSource {
    override val id = "standard_ebooks"
    override val displayName = "Standard Ebooks"

    override suspend fun find(query: TextQuery): List<TextCandidate> {
        if (query.language != null && query.language.take(2) != "en") return emptyList()
        val q = listOf(query.titleOriginal ?: query.title, surname(query.authors)).filter { it.isNotBlank() }.joinToString(" ")
        val page = runCatching { http.string("https://standardebooks.org/ebooks?query=${enc(q)}") }.getOrNull() ?: return emptyList()
        return Jsoup.parse(page).select("li[typeof=schema:Book]").take(6).mapNotNull { li ->
            val path = li.attr("about").takeIf { it.startsWith("/ebooks/") } ?: return@mapNotNull null
            TextCandidate(
                id, path,
                li.selectFirst("span[property=schema:name]")?.text().orEmpty(),
                li.select("p.author span[property=schema:name]").map { it.text() },
                "en",
            )
        }
    }

    override suspend fun load(candidate: TextCandidate): BookText? {
        val segments = candidate.id.removePrefix("/ebooks/").trim('/')
        val file = segments.replace('/', '_')
        val bytes = http.bytes("https://standardebooks.org/ebooks/$segments/downloads/$file.epub?source=download")
        return TextParsers.parse(bytes, "$file.epub", "en")
    }
}

/**
 * Викитека (русская и английская): поиск MediaWiki, текст — EPUB из ws-export (собирает
 * произведение со всеми подстраницами-главами).
 */
class WikisourceTextSource(private val http: TextHttp) : BookTextSource {
    override val id = "wikisource"
    override val displayName = "Викитека"

    override suspend fun find(query: TextQuery): List<TextCandidate> {
        val lang = query.language?.take(2)?.takeIf { it == "ru" || it == "en" } ?: "ru"
        val q = listOf(query.title, surname(query.authors)).filter { it.isNotBlank() }.joinToString(" ")
        val body = runCatching {
            http.string("https://$lang.wikisource.org/w/api.php?action=query&list=search&srnamespace=0&srlimit=10&format=json&srsearch=${enc(q)}")
        }.getOrNull() ?: return emptyList()
        val results = runCatching { json.parseToJsonElement(body).jsonObject["query"]?.jsonObject?.get("search")?.jsonArray }.getOrNull() ?: return emptyList()
        return results.mapNotNull { r ->
            val title = r.jsonObject.str("title") ?: return@mapNotNull null
            // Подстраницы («…/Глава 2», «…/Версия 2») — части произведения, берём само произведение.
            if ('/' in title) return@mapNotNull null
            val author = Regex("\\(([^)]+)\\)$").find(title)?.groupValues?.get(1)
            TextCandidate(id, "$lang:$title", title.replace(Regex("\\s*\\([^)]+\\)$"), ""), listOfNotNull(author), lang)
        }.take(5)
    }

    override suspend fun load(candidate: TextCandidate): BookText? {
        val lang = candidate.id.substringBefore(':')
        val page = candidate.id.substringAfter(':').replace(' ', '_')
        val bytes = http.bytes("https://ws-export.wmcloud.org/?format=epub-3&fonts=&lang=$lang&page=${enc(page)}")
        return TextParsers.parse(bytes, "book.epub", lang)
    }
}

/**
 * Open Library → Internet Archive: только книги в открытом доступе (`ebook_access = public`),
 * текст — распознанный с печатного издания (_djvu.txt).
 */
class OpenLibraryTextSource(private val http: TextHttp) : BookTextSource {
    override val id = "open_library"
    override val displayName = "Open Library"

    override suspend fun find(query: TextQuery): List<TextCandidate> {
        val url = "https://openlibrary.org/search.json?title=${enc(query.titleOriginal ?: query.title)}" +
            (surname(query.authors).takeIf { it.isNotBlank() }?.let { "&author=${enc(it)}" } ?: "") +
            "&fields=title,author_name,language,ebook_access,ia&limit=5"
        val body = runCatching { http.string(url) }.getOrNull() ?: return emptyList()
        val docs = runCatching { json.parseToJsonElement(body).jsonObject["docs"]?.jsonArray }.getOrNull() ?: return emptyList()
        val wanted = query.language?.take(2)
        return docs.flatMap { d ->
            val o = d.jsonObject
            if (o.str("ebook_access") != "public") return@flatMap emptyList()
            val languages = o.strings("language").map(::languageCode)
            val lang = wanted?.takeIf { it in languages } ?: languages.firstOrNull()
            o.strings("ia").take(2).map { ia -> TextCandidate(id, ia, o.str("title").orEmpty(), o.strings("author_name"), lang) }
        }.take(4)
    }

    override suspend fun load(candidate: TextCandidate): BookText? {
        val bytes = http.bytes("https://archive.org/download/${candidate.id}/${candidate.id}_djvu.txt")
        return TextParsers.parse(bytes, "book.txt", candidate.language)
    }
}

/** «English», «Russian», «eng», «rus» → «en», «ru». */
internal fun languageCode(value: String?): String? = when (value?.lowercase()?.trim()) {
    null, "" -> null
    "english", "eng", "en" -> "en"
    "russian", "rus", "ru" -> "ru"
    "german", "ger", "deu", "de" -> "de"
    "french", "fre", "fra", "fr" -> "fr"
    "spanish", "spa", "es" -> "es"
    "italian", "ita", "it" -> "it"
    else -> value.lowercase().take(3)
}
