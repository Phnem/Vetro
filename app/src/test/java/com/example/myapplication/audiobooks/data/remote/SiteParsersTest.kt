package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.audiobooks.data.remote.web.Playerjs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Живые страницы сайтов-источников от 2026-09-26 (test/resources/audiobooks/<сайт>). */
class SiteParsersTest {

    private fun fixture(path: String): String =
        checkNotNull(javaClass.classLoader?.getResource("audiobooks/$path")) { path }.readText()

    @Test
    fun `yakniga search keeps full books and drops previews`() {
        val books = YaknigaParser.parseSearch(fixture("yakniga/search-zadacha.json"))
        val knyazev = books.first { it.ref.key == "66393" }
        assertEquals("Задача трех тел", knyazev.title) // «(читает Игорь Князев)» срезано
        assertEquals(listOf("Лю Цысинь"), knyazev.authors)
        assertEquals(listOf("Князев Игорь"), knyazev.narrators)
        assertTrue(knyazev.coverUrl!!.startsWith("https://yakniga.org/"))
        // У «9442» длительность 0 — у сайта только фрагмент.
        assertTrue(books.none { it.ref.key == "9442" })
    }

    @Test
    fun `yakniga book gives chapters with lengths`() {
        val parsed = YaknigaParser.parseBook(fixture("yakniga/book-66393.json"), "66393")!!
        assertEquals(112, parsed.tracks.size)
        assertEquals("https://yakniga.org/files/sata4/books/66/66393/chapter_1.mp3", parsed.tracks.first().url)
        assertEquals(211L, parsed.tracks.first().durationSec)
        assertEquals("В память о прошлом Земли", parsed.details.series)
        assertEquals(5.0, parsed.details.rating!!, 0.001)
        assertEquals("author:Лю Цысинь", parsed.details.authorShelfId)
        assertNull(YaknigaParser.parseBook(fixture("yakniga/book-9442-empty.json"), "9442"))
    }

    @Test
    fun `audiokniga one search and book`() {
        val hit = AudioknigaOneParser.parseSearch(fixture("audiokniga-one/search-cysin.html"))
            .first { it.ref.key == "15200-selskij-uchitel" }
        assertEquals("Сельский учитель", hit.title)
        assertEquals(listOf("Лю Цысинь"), hit.authors)
        assertEquals(listOf("Роман Тесёлкин"), hit.narrators)
        assertEquals(3600L + 22 * 60 + 21, hit.durationSec)

        val book = AudioknigaOneParser.parseBook(fixture("audiokniga-one/book-selskij-uchitel.html"), "15200-selskij-uchitel")!!
        assertEquals(4, book.tracks.size)
        assertEquals(1341L, book.tracks.first().durationSec)
        assertTrue(book.tracks.first().url.endsWith("lju-cysin-selskijj-uchitel-1.mp3"))
        assertEquals(listOf("Лю Цысинь"), book.details.book.authors)
        assertEquals(listOf("Роман Тесёлкин"), book.details.book.narrators)
        assertEquals(5.0, book.details.rating!!, 0.001)
        assertTrue(book.details.description!!.contains("Ньютона"))
    }

    @Test
    fun `knigavuhe book is playable and its links expire`() {
        val hit = KnigavuheParser.parseSearch(fixture("knigavuhe/search-soljaris.html")).first()
        assertEquals("radiospektakl-soljaris", hit.ref.key)
        assertEquals(listOf("Станислав Лем"), hit.authors)
        assertEquals(3 * 3600L + 45 * 60, hit.durationSec)
        assertTrue(hit.coverUrl!!.contains("/covers/301/4.jpg"))

        val book = KnigavuheParser.parseBook(fixture("knigavuhe/book-soljaris.html"), "radiospektakl-soljaris")!!
        assertEquals(14, book.tracks.size)
        assertEquals(933L, book.tracks.first().durationSec)
        assertEquals(305859L, book.refreshInSec)
        assertFalse(book.trialOnly)
        assertEquals("Солярис", book.details.book.title)
        assertEquals(4, book.details.book.narrators.size)
    }

    @Test
    fun `knigavuhe litres preview is not a book`() {
        val book = KnigavuheParser.parseBook(fixture("knigavuhe/book-zadacha-trekh-tel-litres.html"), "zadacha-trekh-tel")
        assertTrue(book == null || book.trialOnly)
    }

    @Test
    fun `baza knig search, book and playlist`() {
        val hit = BazaKnigParser.parseSearch(fixture("baza_knig/search-zadacha.html"))
            .first { it.ref.key == "audio-2724-zadacha-treh-tel-lyu-cysin" }
        assertEquals("Задача трех тел", hit.title)
        assertEquals(listOf("Лю Цысинь"), hit.authors)

        val page = BazaKnigParser.parseBook(fixture("baza_knig/book-2724.html"), hit.ref.key)!!
        assertEquals("Задача трех тел", page.details.book.title)
        assertEquals(listOf("Оробчук Сергей"), page.details.book.narrators)
        assertEquals(12 * 3600L + 52 * 60 + 3, page.details.book.durationSec)
        assertTrue(page.playerFile.endsWith("2724.pl.txt"))

        val tracks = Playerjs.parsePlaylist(fixture("baza_knig/playlist-2724.json"))
        assertEquals(35, tracks.size)
        assertTrue(tracks.first().url.endsWith("/0.mp3"))
    }

    @Test
    fun `slushat knigi search and book`() {
        val hit = SlushatKnigiParser.parseSearch(fixture("slushat_knigi/search-zadacha.html"))
            .first { it.ref.key == "31612-audiokniga-zadacha-treh-tel-lju-cysin" }
        assertEquals("Задача трех тел", hit.title)
        assertEquals(listOf("Лю Цысинь"), hit.authors)
        assertEquals(12 * 3600L + 52 * 60 + 3, hit.durationSec)

        val page = SlushatKnigiParser.parseBook(fixture("slushat_knigi/book-31612.html"), hit.ref.key)!!
        assertEquals("Задача трех тел", page.details.book.title)
        assertEquals(listOf("Оробчук Сергей"), page.details.book.narrators)
        assertEquals("В память о прошлом Земли", page.details.series)
        assertTrue(page.playerFile.endsWith(".pl.txt"))
    }

    @Test
    fun `audioknigi fun search, book and signed playlist`() {
        val hit = AudioknigiFunParser.parseSearch(fixture("audioknigi_fun/search-pedagogicheskaya.html"))
            .first { it.ref.key == "34516-pedagogicheskaya-poema" }
        assertEquals(listOf("Антон Макаренко"), hit.authors)
        assertEquals(listOf("Владимир Сушков"), hit.narrators)
        assertEquals(30 * 3600L + 9 * 60 + 48, hit.durationSec)

        val book = AudioknigiFunParser.parseBook(fixture("audioknigi_fun/book-34516.html"), hit.ref.key)!!
        assertEquals("Педагогическая поэма", book.book.title)
        assertNotNull(book.description)

        val tracks = AudioknigiFunParser.parsePlaylist(fixture("audioknigi_fun/playlist-34516.json"))
        assertEquals(77, tracks.size)
        assertTrue(tracks.first().url.startsWith("https://s4.slovushko.com/"))
        assertNotNull(AudioknigiFunParser.expiresAt(tracks))
    }

    @Test
    fun `audioknigi fun playlist escaped twice`() {
        val body = """{"ok":true,"playlist":"\\\"title\\\":\\\"01\\\",\\\"file\\\":\\\"https://s4.slovushko.com/a/01.mp3?expires=5\\\"}"}"""
        val tracks = AudioknigiFunParser.parsePlaylist(body)
        assertEquals(1, tracks.size)
        assertEquals("https://s4.slovushko.com/a/01.mp3?expires=5", tracks.first().url)
        assertEquals(5000L, AudioknigiFunParser.expiresAt(tracks))
    }
}
