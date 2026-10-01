package com.example.myapplication.audiobooks.data

import com.example.myapplication.audiobooks.domain.source.AudiobookSearch
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.audiobooks.domain.source.WorkMatch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Аудиокниги в общем поиске главной: поиск по всем источникам сразу и «Добавить» — книга встаёт
 * в библиотеку раздела «Книги» как своя, без запуска плеера.
 */
class BookSearchAdder(
    private val sources: List<AudiobookSource>,
    private val search: AudiobookSearch,
    private val repository: AudiobookRepository,
) {
    /**
     * Одна строка на озвучку: одна и та же лежит на нескольких сайтах, а порядок выдачи — приоритет
     * источников, так что остаётся копия с лучшего.
     */
    suspend fun search(query: String): List<SourceBook> {
        // Сайты ищут по-своему: «Мы — Легион. Мы — Боб» с тире и точками у части из них не находится,
        // поэтому запрос уходит и как есть, и очищенным от знаков.
        val plain = WorkMatch.normalize(query)
        val found = coroutineScope {
            val raw = async { search.searchAll(query, pages = PAGES) }
            val cleaned = if (plain.isNotBlank() && plain != query.trim().lowercase()) async { search.searchAll(plain, pages = PAGES) } else null
            raw.await() + cleaned?.await().orEmpty()
        }
        return BookSearchRanking.rank(query, found)
            .distinctBy { key(it.title) + "|" + WorkMatch.words(it.authors).sorted() + "|" + WorkMatch.words(it.narrators).sorted() }
    }

    /** Названия произведений библиотеки — чтобы кнопка у уже добавленной книги стояла «✓». */
    val libraryTitles: Flow<Set<String>> = repository.library().map { books -> books.map { key(it.title) }.toSet() }

    fun key(title: String): String = WorkMatch.titleKey(title)

    /** Страница книги у источника → записи в БД → в библиотеку. false — источник книгу не отдал. */
    suspend fun add(book: SourceBook): Boolean {
        val source = sources.firstOrNull { it.id == book.ref.source } ?: return false
        val details = (source.details(book.ref) as? SourceResult.Ok)?.value ?: return false
        val opened = repository.saveOpened(source, details)
        repository.setInLibrary(opened.workId, true)
        return true
    }
}

private const val PAGES = 2

/**
 * Порядок выдачи по смыслу запроса, а не по тому, что вернул сайт: «Марсианин» должен стоять выше
 * «Последнего марсианина», а книга, где название не совпало, но автор — по имени автора.
 */
internal object BookSearchRanking {
    /** Лучшие первыми; при равенстве остаётся порядок источников (приоритет). Книги без единого совпадения отбрасываются. */
    fun rank(query: String, books: List<SourceBook>): List<SourceBook> {
        val q = tokens(query)
        if (q.isEmpty()) return books
        return books.map { it to score(q, it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second } // sortedBy стабилен
            .map { it.first }
    }

    internal fun score(q: List<String>, book: SourceBook): Int {
        val title = tokens(WorkMatch.titleKey(book.title))
        val authors = tokens(book.authors.joinToString(" "))
        if (title == q) return 1000
        val inTitle = q.count { qt -> title.any { it == qt } }
        val inTitleStem = q.count { qt -> title.any { stem(it) == stem(qt) } }
        val inAuthor = q.count { qt -> authors.any { stem(it) == stem(qt) } }
        val covered = q.indices.count { i -> title.any { stem(it) == stem(q[i]) } || authors.any { stem(it) == stem(q[i]) } }
        if (covered == 0) return 0
        var s = covered * 100 + inTitle * 20 + (inTitleStem - inTitle) * 10 + inAuthor * 10
        if (covered == q.size) s += 200
        // Лишние слова в названии («Последний марсианин» на запрос «марсианин») — чуть ниже точного.
        s -= (title.size - inTitleStem).coerceAtLeast(0) * 5
        if (title.take(q.size) == q) s += 50
        return s
    }

    private fun tokens(s: String): List<String> = WorkMatch.normalize(s).split(' ').filter { it.length > 1 }

    /** Грубая основа слова для русских окончаний: «марсианина» ≈ «марсианин». */
    private fun stem(w: String): String = if (w.length > 5) w.dropLast(2) else if (w.length > 3) w.dropLast(1) else w
}
