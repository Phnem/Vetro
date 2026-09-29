package com.example.myapplication.audiobooks.data

import com.example.myapplication.audiobooks.domain.source.AudiobookSearch
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.audiobooks.domain.source.WorkMatch
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
    suspend fun search(query: String): List<SourceBook> =
        search.searchAll(query).distinctBy { key(it.title) + "|" + WorkMatch.words(it.authors).sorted() + "|" + WorkMatch.words(it.narrators).sorted() }

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
