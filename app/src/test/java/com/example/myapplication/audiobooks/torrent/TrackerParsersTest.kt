package com.example.myapplication.audiobooks.torrent

import com.example.myapplication.audiobooks.domain.source.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Трекеры — живые страницы от 2026-09-26. Движок libtorrent здесь не участвует. */
class TrackerParsersTest {

    private fun fixture(path: String): String =
        checkNotNull(javaClass.classLoader?.getResource("audiobooks/$path")) { path }.readText()

    @Test
    fun `torrent titles`() {
        val t = TorrentTitle.parse("Станислав Лем - Солярис [аудиоспектакль] (2007) MP3")
        assertEquals(listOf("Станислав Лем"), t.authors)
        assertEquals("Солярис", t.title)
        assertEquals(2007, t.year)
        assertEquals(listOf("аудиоспектакль"), t.notes)
        assertEquals("Project Hail Mary", TorrentTitle.parse("Andy Weir - Project Hail Mary").title)
        assertTrue(TorrentTitle.isAudio("Лю Цысинь - Эпоха сверхновой (2020) MP3"))
        assertFalse(TorrentTitle.isAudio("Лю Цысинь и другие - Сломанные звезды (2020) FB2"))
    }

    @Test
    fun `rutor search and topic`() {
        val source = SourceId("rutor")
        val hits = RutorParser.parseSearch(fixture("rutor/search-soljaris.html"), source)
        assertEquals(2, hits.size)
        assertEquals("579150", hits.first().ref.key)
        assertEquals("Солярис", hits.first().title)

        val topic = RutorParser.parseTopic(fixture("rutor/topic-soljaris.html"), source, "579150")!!
        assertEquals(listOf("Станислав Лем"), topic.details.book.authors)
        assertEquals(listOf("Пётр Каледин"), topic.details.book.narrators)
        assertEquals(6 * 3600L + 57 * 60 + 21, topic.details.book.durationSec)
        assertEquals(160, topic.kbps)
        assertTrue(topic.magnet!!.startsWith("magnet:?xt=urn:btih:"))
        assertTrue(topic.details.description!!.contains("Кельвин"))
    }

    @Test
    fun `audiobookbay search and topic give a magnet`() {
        val source = SourceId("audiobookbay")
        val hits = AudioBookBayParser.parseSearch(fixture("audiobookbay/search-hail-mary.html"), source)
        assertTrue(hits.isNotEmpty())
        assertEquals("Project Hail Mary", hits.first().title)
        assertEquals(listOf("Andy Weir"), hits.first().authors)

        val topic = AudioBookBayParser.parseTopic(fixture("audiobookbay/topic.html"), source, "x")!!
        assertEquals("ad5fae5ffda056f9f45131045d140326bbafc4dc", topic.hash)
        assertTrue(topic.trackers.isNotEmpty())
        assertEquals(128, topic.kbps)
        assertEquals(listOf("Ray Porter"), topic.details.book.narrators)
        val magnet = TorrentLink.magnet(topic.hash!!, "Project Hail Mary", topic.trackers)
        assertEquals("ad5fae5ffda056f9f45131045d140326bbafc4dc", magnet.hash)
    }

    @Test
    fun `pirate bay json`() {
        val source = SourceId("piratebay")
        val hits = PirateBayParser.parseSearch(fixture("apibay/search-hail-mary.json"), source)
        assertEquals("Project Hail Mary", hits.single().title)
        assertEquals(listOf("Andy Weir"), hits.single().authors)
        assertTrue(PirateBayParser.parseSearch(fixture("apibay/search-empty.json"), source).isEmpty())
        val (details, hash) = PirateBayParser.parseTorrent(fixture("apibay/torrent.json"), source, "45465717")!!
        assertEquals(40, hash.length)
        assertNotNull(details.description)
    }

    @Test
    fun `audioboo search and releases`() {
        val hit = AudiobooParser.parseSearch(fixture("audioboo/search-zadacha.html")).first { it.ref.key == "19307" }
        assertEquals("Задача трех тел", hit.title)
        assertEquals(listOf("Лю Цысинь"), hit.authors)

        val (details, release) = AudiobooParser.parseTopic(fixture("audioboo/topic-19307.html"), "19307")!!
        assertEquals("27141", release.downloadId)
        assertEquals(128, release.kbps)
        assertEquals(listOf("Лю Цысинь"), details.book.authors)
        assertEquals(listOf("Оробчук Сергей"), details.book.narrators)
        assertEquals(12 * 3600L + 52 * 60 + 3, details.book.durationSec)
        assertEquals("В память о прошлом Земли", details.series)
        val (_, second) = AudiobooParser.parseTopic(fixture("audioboo/topic-19307.html"), "19307~30428")!!
        assertEquals("30428", second.downloadId)
    }
}
