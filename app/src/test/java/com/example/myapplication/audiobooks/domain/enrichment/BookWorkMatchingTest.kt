package com.example.myapplication.audiobooks.domain.enrichment

import com.example.myapplication.network.enrichment.BookEdition
import com.example.myapplication.network.enrichment.BookWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookWorkMatchingTest {

    private fun work(key: String, title: String, authors: List<String>, editions: Int, edition: String? = null) = BookWork(
        key = key, title = title, authors = authors, firstPublishYear = null, editionCount = editions,
        coverId = null, subjects = emptyList(), languages = emptyList(),
        matchedEdition = edition?.let { BookEdition("/books/$key", it, emptyList(), emptyList(), listOf("rus"), emptyList(), null, emptyList()) },
    )

    @Test
    fun `sequel with the query in its series name is not the book`() {
        val works = listOf(
            work("OL16314245W", "The Dark Forest (The Three-Body Problem Series Book 2)", listOf("刘慈欣"), 31, "The Dark Forest"),
            work("OL17267881W", "三体", listOf("刘慈欣"), 44, "The Three-Body Problem"),
        )
        assertEquals("OL17267881W", BookWorkMatching.best(works, "The Three-Body Problem", listOf("Liu Cixin"))!!.key)
    }

    @Test
    fun `russian edition title finds the original work even with a different author spelling`() {
        val works = listOf(
            work("OL17267881W", "三体", listOf("刘慈欣"), 44, "Задача трёх тел"),
            work("OL16807818W", "Книга Еноха", emptyList(), 1, "Книга Еноха"),
        )
        assertEquals("OL17267881W", BookWorkMatching.best(works, "Задача трех тел", listOf("Лю Цысинь"))!!.key)
    }

    @Test
    fun `author overlap beats a same-named stub`() {
        val works = listOf(
            work("OL20329383W", "Мастер и Маргарита", listOf("Valentin Bulgakov"), 0),
            work("OL676009W", "Мастер и Маргарита", listOf("Михаил Афанасьевич Булгаков"), 236),
            work("OL24760747W", "Белая гвардия ; Мастер и Маргарита", listOf("Михаил Афанасьевич Булгаков"), 1),
        )
        assertEquals("OL676009W", BookWorkMatching.best(works, "Мастер и Маргарита", listOf("Михаил Булгаков"))!!.key)
    }

    @Test
    fun `no title match means no enrichment rather than a wrong book`() {
        assertNull(BookWorkMatching.best(listOf(work("x", "Summary of Project Hail Mary", listOf("Irb Media"), 1)), "Project Hail Mary", listOf("Andy Weir")))
        assertEquals("dune", BookWorkMatching.normalize("Dune: Deluxe Edition"))
    }

    @Test
    fun `catalog book is the same when the title matches without the series suffix and an author word is shared`() {
        val titles = listOf("Задача трёх тел", "The Three-Body Problem")
        val authors = listOf("Лю Цысинь", "Liu Cixin")
        assertEquals(true, BookWorkMatching.sameAsCatalog("Задача трех тел (Книга 1)", listOf("Лю Цысинь"), titles, authors))
        assertEquals(true, BookWorkMatching.sameAsCatalog("The Three-Body Problem: Deluxe", listOf("Cixin Liu"), titles, authors))
        // Автор источника неизвестен — хватает названия.
        assertEquals(true, BookWorkMatching.sameAsCatalog("Задача трёх тел", emptyList(), titles, authors))
    }

    @Test
    fun `catalog book with the same title but another author is a different book`() {
        // «Чайка» Чехова и «Чайка по имени Джонатан Ливингстон» не склеиваются, а одноимённая книга другого автора — тоже.
        assertEquals(false, BookWorkMatching.sameAsCatalog("Чайка", listOf("Ричард Бах"), listOf("Чайка"), listOf("Антон Чехов", "Anton Chekhov")))
        assertEquals(false, BookWorkMatching.sameAsCatalog("Чайка", listOf("Антон Чехов"), listOf("Чайка по имени Джонатан Ливингстон"), listOf("Ричард Бах")))
        assertEquals(false, BookWorkMatching.sameAsCatalog("  ", emptyList(), listOf("Чайка"), emptyList()))
    }

    @Test
    fun `google volume is trusted only when its title is the same book`() {
        // Живой ответ Google Books на ISBN 9785041619015 — «Три раза. стихи», а не «Задача трёх тел».
        assertEquals(false, BookWorkMatching.sameBook("Три раза. стихи", listOf("Задача трёх тел", "三体")))
        assertEquals(true, BookWorkMatching.sameBook("Задача трех тел", listOf("Задача трёх тел", "三体")))
    }
}
