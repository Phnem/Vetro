package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookDetails
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Разбор страниц Aknigi24 (research/sources/aknigi24.md). Чистые функции над HTML — покрыты
 * фикстурами в тестах, сеть живёт в [Aknigi24Source].
 */
internal object Aknigi24Parser {

    val SOURCE = SourceId("aknigi24")
    const val BASE = "https://aknigi24.com"

    /** Карточки из поиска и из списков жанров: одна и та же сетка `.books-grid`. */
    fun parseList(html: String): List<SourceBook> =
        Jsoup.parse(html, BASE).select(".books-grid .card").mapNotNull(::parseCard)

    private fun parseCard(card: Element): SourceBook? {
        val link = card.selectFirst(".card-title a[href*=/book/]") ?: return null
        val key = slugOf(link.absUrl("href")) ?: return null
        return SourceBook(
            ref = SourceBookRef(SOURCE, key),
            title = link.text().trim(),
            authors = card.select("a[href*=/author/]").map { it.text().trim() }.distinct(),
            narrators = card.select("a[href*=/reader/]").map { it.text().trim() }.distinct(),
            coverUrl = card.selectFirst(".book-cover img")?.absUrl("src")?.takeIf { it.isNotBlank() },
            durationSec = card.selectFirst(".cover-badge-duration")?.text()?.let(::parseDurationSec),
            genres = card.select("a[href*=/genre/]").map { it.text().trim() }.distinct(),
        )
    }

    /**
     * Страница книги. Главы и их длительности — из JSON `#player-data`, которым кормится плеер сайта.
     * null — на странице нет плеера (книга снята или страница не книги).
     */
    fun parseBook(html: String, key: String): ParsedBook? {
        val doc = Jsoup.parse(html, BASE)
        val data = doc.selectFirst("script#player-data")?.data()?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<PlayerData>(it) }.getOrNull() }
            ?: return null
        val chapterBase = data.chapterBase.takeIf { it.startsWith("http") } ?: return null
        if (data.chapters.isEmpty()) return null
        // Блок метаданных книги — одна строка «Метка: ссылки». Ищем по подписи, а не по порядку.
        fun linksAfter(label: String): List<String> = doc.select("p:has(> strong)")
            .firstOrNull { it.selectFirst("> strong")?.text()?.startsWith(label) == true }
            ?.select("a")?.map { it.text().trim() }?.distinct().orEmpty()
        val description = doc.select("h5.section-title").firstOrNull { it.text().startsWith("Описание") }
            ?.let { header ->
                generateSequence(header.nextElementSibling()) { it.nextElementSibling() }
                    .takeWhile { it.tagName() == "p" }
                    .joinToString("\n\n") { it.text().trim() }
            }?.takeIf { it.isNotBlank() }
        val durations = data.chapters.map { c -> c.d?.takeIf { it > 0 } }
        val book = SourceBook(
            ref = SourceBookRef(SOURCE, key),
            title = data.title.ifBlank { doc.selectFirst("h1 .d-md-none")?.text().orEmpty() }.trim(),
            authors = linksAfter("Автор"),
            narrators = linksAfter("Исполнитель"),
            coverUrl = data.cover?.takeIf { it.startsWith("http") }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content"),
            durationSec = durations.takeIf { d -> d.all { it != null } }?.sumOf { it!! },
            genres = linksAfter("Жанр"),
            year = doc.select("span").firstOrNull { it.ownText().startsWith("📅") }
                ?.ownText()?.filter(Char::isDigit)?.toIntOrNull(),
        )
        val authorSlug = doc.select("a[href*=/author/]").firstNotNullOfOrNull { a ->
            Regex("""/author/([a-z0-9-]+)""").find(a.attr("href"))?.groupValues?.get(1)
        }
        return ParsedBook(
            details = SourceBookDetails(
                book = book,
                description = description,
                chapterDurationsSec = durations,
                rating = doc.selectFirst("#rating-stars")?.attr("data-initial-rating")?.toDoubleOrNull()?.takeIf { it > 0 },
                series = (linksAfter("Серия") + linksAfter("Цикл")).firstOrNull(),
                authorShelfId = authorSlug?.let { "author:$it" },
            ),
            chapterUrls = data.chapters.map { chapterBase + it.n },
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** JSON `#player-data`: `{title, cover, chapterBase, chapters:[{n, d}]}`, `d` — секунды или null. */
    @Serializable
    private data class PlayerData(
        val title: String = "",
        val cover: String? = null,
        val chapterBase: String = "",
        val chapters: List<PlayerChapter> = emptyList(),
    )

    @Serializable
    private data class PlayerChapter(val n: Int, val d: Long? = null)

    data class ParsedBook(val details: SourceBookDetails, val chapterUrls: List<String>)

    /** «7 ч 16 мин», «45 мин», «1 ч» → секунды. */
    fun parseDurationSec(text: String): Long? {
        val hours = Regex("""(\d+)\s*ч""").find(text)?.groupValues?.get(1)?.toLong() ?: 0L
        val minutes = Regex("""(\d+)\s*мин""").find(text)?.groupValues?.get(1)?.toLong() ?: 0L
        return (hours * 3600 + minutes * 60).takeIf { it > 0 }
    }

    fun slugOf(url: String): String? =
        Regex("""/book/([a-z0-9-]+)/?$""").find(url.substringBefore('?'))?.groupValues?.get(1)
}
