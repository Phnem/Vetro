package com.example.myapplication.audiobooks.data

import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class BookSearchRankingTest {
    private fun book(key: String, title: String, vararg authors: String) =
        SourceBook(SourceBookRef(SourceId("s"), key), title, authors.toList(), emptyList(), null, null)

    @Test
    fun exactTitleBeatsTitleWithExtraWords() {
        val found = listOf(
            book("1", "Последний марсианин", "Браун Фредерик"),
            book("2", "Марсианин", "Вейер Энди"),
        )
        assertEquals(listOf("2", "1"), BookSearchRanking.rank("марсианин", found).map { it.ref.key })
    }

    @Test
    fun fullTitleWithPunctuationFindsTheBook() {
        val found = listOf(
            book("1", "Большой Боб", "Сименон Жорж"),
            book("2", "Легион", "Сандерсон Брендон"),
            book("3", "Мы — Легион. Мы — Боб", "Тейлор Деннис"),
        )
        assertEquals("3", BookSearchRanking.rank("Мы — Легион. Мы — Боб", found).first().ref.key)
    }

    @Test
    fun authorQueryMatchesAndUnrelatedBooksAreDropped() {
        val found = listOf(book("1", "Черная Вселенная", "Максимов Макс"), book("2", "Марсианин", "Вейер Энди"))
        assertEquals(listOf("2"), BookSearchRanking.rank("вейер", found).map { it.ref.key })
    }
}
