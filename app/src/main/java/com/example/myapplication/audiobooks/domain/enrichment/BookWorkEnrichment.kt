package com.example.myapplication.audiobooks.domain.enrichment

import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.enrichment.BookEdition
import com.example.myapplication.network.enrichment.BookWork
import com.example.myapplication.network.enrichment.GoogleBooksClient
import com.example.myapplication.network.enrichment.OpenLibraryClient
import java.util.Locale
import kotlin.math.ln

/**
 * Произведение за озвучкой: Work → Edition → Narration. Источник аудиокниги знает озвучку; Open
 * Library — произведение (оригинальное название, год первого издания) и издание на языке книги
 * (ISBN, обложка). Описание добирается, только если его нет у источника.
 */
data class BookWorkInfo(
    val workKey: String,
    /** Название произведения, если оно другое («三体» у «Задачи трёх тел»). */
    val originalTitle: String?,
    val firstPublishYear: Int?,
    val editionCount: Int,
    val edition: BookEdition?,
    val coverUrl: String?,
    /** Только когда у источника описания нет. */
    val description: String?,
)

class BookWorkEnrichment(
    private val openLibrary: OpenLibraryClient,
    private val googleBooks: GoogleBooksClient,
) {
    suspend fun lookup(
        title: String,
        authors: List<String>,
        sourceDescription: String?,
        language: AppLanguage,
    ): BookWorkInfo? {
        val works = (openLibrary.searchWorks(title) as? LookupResult.Found)?.value ?: return null
        val work = BookWorkMatching.best(works, title, authors) ?: return null
        val edition = work.matchedEdition
        val description = if (sourceDescription.isNullOrBlank()) describe(work, edition, language) else null
        val coverId = edition?.coverIds?.firstOrNull() ?: work.coverId
        return BookWorkInfo(
            workKey = work.key,
            originalTitle = work.title.takeIf { BookWorkMatching.normalize(it) != BookWorkMatching.normalize(title) },
            firstPublishYear = work.firstPublishYear,
            editionCount = work.editionCount,
            edition = edition,
            coverUrl = coverId?.let { OpenLibraryClient.coverUrl(it) },
            description = description,
        )
    }

    /**
     * Описание на языке интерфейса: Google Books по ISBN издания (у русских изданий — русская
     * аннотация), затем описание произведения Open Library — оно почти всегда английское, поэтому
     * только для английского интерфейса.
     */
    private suspend fun describe(work: BookWork, edition: BookEdition?, language: AppLanguage): String? {
        val ui = if (language == AppLanguage.RU) "ru" else "en"
        val isbn = edition?.isbn13?.firstOrNull() ?: edition?.isbn10?.firstOrNull()
        if (isbn != null) {
            val volume = (googleBooks.byIsbn(isbn) as? LookupResult.Found)?.value
            volume?.description?.takeIf { volume.language == null || volume.language == ui }?.let { return it }
        }
        if (ui != "en") return null
        return (openLibrary.work(work.key) as? LookupResult.Found)?.value?.description
    }
}

/** Выбор произведения из выдачи поиска: совпадение названия обязательно, автор и число изданий — ранг. */
internal object BookWorkMatching {

    fun best(works: List<BookWork>, title: String, authors: List<String>): BookWork? {
        val wanted = normalize(title)
        if (wanted.isBlank()) return null
        val authorWords = authors.flatMap(::words).toSet()
        return works
            .filter { w -> normalize(w.title) == wanted || w.matchedEdition?.title?.let(::normalize) == wanted }
            .maxByOrNull { w ->
                val overlap = w.authors.flatMap(::words).count { it in authorWords }
                // Совпавший автор важнее популярности; у пустышек (0 изданий, чужой автор) ранг ниже.
                overlap * 10.0 + ln(1.0 + w.editionCount)
            }
    }

    /**
     * «Задача трёх тел (The Three-Body Problem Series Book 1)» → «задача трех тел». Подзаголовок после
     * двоеточия и скобки отбрасываются: «The Dark Forest (… Book 2)» не совпадёт с «The Three-Body
     * Problem», а «Dune: Deluxe Edition» — совпадёт с «Dune».
     */
    fun normalize(text: String): String = text
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("""\([^)]*\)|\[[^\]]*\]"""), " ")
        .substringBefore(':')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun words(name: String): List<String> = normalize(name).split(' ').filter { it.length > 1 }
}
