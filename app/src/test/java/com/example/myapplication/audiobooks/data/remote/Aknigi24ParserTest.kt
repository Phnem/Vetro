package com.example.myapplication.audiobooks.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Живые страницы Aknigi24 от 2026-09-26: поиск «Солярис» и озвучка Герасимова. */
class Aknigi24ParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("audiobooks/aknigi24/$name")) { name }.readText()

    @Test
    fun `search lists every narration as its own book`() {
        val books = Aknigi24Parser.parseList(fixture("search-soliaris.html"))
        val solaris = books.filter { it.title == "Солярис" }
        assertTrue("narrations: ${solaris.size}", solaris.size >= 5)
        val gerasimov = solaris.first { it.ref.key == "soliaris-lem-stanislav-gerasimov-viaceslav" }
        assertEquals(listOf("Лем Станислав"), gerasimov.authors)
        assertEquals(listOf("Герасимов Вячеслав"), gerasimov.narrators)
        assertEquals(7 * 3600L + 16 * 60, gerasimov.durationSec)
        assertTrue(gerasimov.coverUrl!!.startsWith("https://aknigi24.com/cover/"))
    }

    @Test
    fun `book page yields chapters from the site player`() {
        val parsed = Aknigi24Parser.parseBook(fixture("book-soliaris.html"), "soliaris-lem-stanislav-gerasimov-viaceslav")
        assertNotNull(parsed)
        parsed!!
        assertEquals(17, parsed.chapterUrls.size)
        assertEquals("https://aknigi24.com/book/854/chapter/1", parsed.chapterUrls.first())
        assertEquals(1403L, parsed.details.chapterDurationsSec.first())
        val book = parsed.details.book
        assertEquals("Солярис", book.title)
        assertEquals(listOf("Лем Станислав"), book.authors)
        assertEquals(listOf("Герасимов Вячеслав"), book.narrators)
        assertEquals(2013, book.year)
        assertTrue(parsed.details.description!!.contains("Кельвин"))
        assertEquals(4.3, parsed.details.rating!!, 0.001)
        assertEquals("author:lem-stanislav", parsed.details.authorShelfId)
    }

    @Test
    fun `page without the player is not a book`() {
        assertNull(Aknigi24Parser.parseBook(fixture("search-soliaris.html"), "x"))
    }

    @Test
    fun `duration badge`() {
        assertEquals(7 * 3600L + 16 * 60, Aknigi24Parser.parseDurationSec("7 ч 16 мин"))
        assertEquals(45 * 60L, Aknigi24Parser.parseDurationSec("45 мин"))
        assertNull(Aknigi24Parser.parseDurationSec(""))
    }
}
