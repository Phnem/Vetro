package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.audiobooks.data.remote.web.Mp3Probe
import com.example.myapplication.audiobooks.domain.source.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Izib, Internet Archive и английские сайты — живые страницы от 2026-09-26. */
class MoreSiteParsersTest {

    private fun fixture(path: String): String =
        checkNotNull(javaClass.classLoader?.getResource("audiobooks/$path")) { path }.readText()

    @Test
    fun `izib search and player json`() {
        val hits = IzibParser.parseSearch(fixture("izib/search-soljaris.html"))
        val radio = hits.first { it.ref.key == "art1992" }
        assertEquals("Солярис", radio.title)
        assertEquals(listOf("Станислав Лем"), radio.authors)
        assertTrue(radio.narrators.contains("Александр Филиппенко"))

        val book = IzibParser.parseBook(fixture("izib/book-art1992.html"), "art1992")!!
        assertEquals(14, book.tracks.size)
        assertEquals(933L, book.tracks.first().durationSec)
        assertTrue(book.tracks.first().url.startsWith("https://r4.audioknigi.xyz/"))
        assertTrue(book.tracks.first().url.contains("?md5="))
        assertEquals(listOf("Станислав Лем"), book.details.book.authors)
        assertEquals(4, book.details.book.narrators.size)
        assertTrue(book.expiresAt!! > 0)
        assertTrue(!book.blocked)
    }

    @Test
    fun `internet archive search and item files`() {
        val hits = InternetArchiveParser.parseSearch(fixture("internet-archive/search-sherlock.json"))
        val first = hits.first()
        assertEquals("adventures_holmes", first.ref.key)
        assertEquals("The Adventures of Sherlock Holmes", first.title)
        assertEquals(listOf("Sir Arthur Conan Doyle"), first.authors)

        val item = InternetArchiveParser.parseItem(fixture("internet-archive/metadata-adventures_holmes.json"), "adventures_holmes")!!
        assertEquals(12, item.tracks.size)
        assertTrue(item.tracks.first().url.startsWith("https://archive.org/download/adventures_holmes/"))
        assertTrue(item.tracks.first().url.endsWith("_64kb.mp3"))
        assertEquals(65 * 60L + 6, item.tracks.first().durationSec)
        assertEquals(3906L, InternetArchiveParser.lengthSec("3906.12"))
        assertEquals(3906L, InternetArchiveParser.lengthSec("1:05:06"))
    }

    @Test
    fun `ipaudio wordpress family`() {
        val base = "https://goldenaudiobooks.com"
        val source = SourceId("goldenaudiobooks")
        val hit = IpaudioParser.parseSearch(fixture("goldenaudiobooks/search-hail-mary.html"), source, base)
            .first { it.ref.key == "andy-weir-project-hail-mary-audiobook" }
        assertEquals("Project Hail Mary", hit.title)
        assertEquals(listOf("Andy Weir"), hit.authors)

        val book = IpaudioParser.parseBook(fixture("goldenaudiobooks/book-ready-player-one.html"), source, "x")!!
        assertEquals("Ready Player One", book.details.book.title)
        assertEquals(listOf("Ernest Cline"), book.details.book.authors)
        assertEquals(16, book.tracks.size)
        assertEquals("https://ipaudio.club/wp-content/uploads/GOLN/Ready%20Player%20One/01.mp3", book.tracks.first().url)
    }

    @Test
    fun `realaudiobooks track list`() {
        val hits = RealAudiobooksParser.parseSearch(fixture("realaudiobooks/search-hail-mary.html"))
        assertTrue(hits.any { it.title.contains("Hail Mary") })
        val book = RealAudiobooksParser.parseBook(fixture("realaudiobooks/book-frugal-wizard.html"), "frugal")!!
        assertEquals(64, book.tracks.size)
        assertTrue(book.tracks.first().url.contains("Frugal%20Wizard'"))
        assertEquals(listOf("Brandon Sanderson"), book.details.book.authors)
    }

    @Test
    fun `mp3 bitrate from the first frame after an id3 tag`() {
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 10) + ByteArray(10)
        assertEquals(20, Mp3Probe.tagSize(tag))
        // MPEG1 Layer III, 64 кбит/с (индекс 5), 44,1 кГц.
        val frame = byteArrayOf(0x00, 0xFF.toByte(), 0xFB.toByte(), 0x50, 0x00)
        assertEquals(64, Mp3Probe.bitrateKbps(frame))
        // MPEG2 Layer III, 32 кбит/с (индекс 4).
        assertEquals(32, Mp3Probe.bitrateKbps(byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x40, 0x00)))
        assertNull(Mp3Probe.bitrateKbps(ByteArray(16)))
    }
}
