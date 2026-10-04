package com.example.myapplication.media.source.movieseries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ответы Internet Archive, снятые 2026-10-04 (коллекция feature_films). */
class InternetArchiveFilmsTest {

    @Test
    fun `only public domain, creative commons or old enough films are offered`() {
        val pd = InternetArchiveFilms.Item("a", "Nosferatu", 1922, "https://creativecommons.org/publicdomain/mark/1.0/")
        val cc = InternetArchiveFilms.Item("b", "Short", 2015, "http://creativecommons.org/licenses/by/4.0/")
        val old = InternetArchiveFilms.Item("c", "Old", 1929, null)
        val recent = InternetArchiveFilms.Item("d", "Upload", 1999, null)
        val unknown = InternetArchiveFilms.Item("e", "Unknown", null, null)
        assertTrue(InternetArchiveFilms.isFreeToWatch(pd, 2026))
        assertTrue(InternetArchiveFilms.isFreeToWatch(cc, 2026))
        assertTrue(InternetArchiveFilms.isFreeToWatch(old, 2026))
        assertFalse(InternetArchiveFilms.isFreeToWatch(recent, 2026))
        assertFalse(InternetArchiveFilms.isFreeToWatch(unknown, 2026))
    }

    @Test
    fun `titles match exactly after normalization`() {
        assertTrue(InternetArchiveFilms.sameTitle("Nosferatu: A Symphony of Horror", "nosferatu a symphony of horror"))
        assertFalse(InternetArchiveFilms.sameTitle("Nosferatu", "Nosferatu the Vampyre"))
    }

    @Test
    fun `search docs are parsed`() {
        val body = """{"response":{"numFound":2,"docs":[
            {"identifier":"Nosferatu_DVD_quality","title":"Nosferatu","licenseurl":"http://creativecommons.org/licenses/publicdomain/","year":1922},
            {"identifier":"nosferatu_1929","title":"Nosferatu","year":"1929"}]}}"""
        val items = InternetArchiveFilms.parseSearch(body)
        assertEquals(listOf("Nosferatu_DVD_quality", "nosferatu_1929"), items.map { it.identifier })
        assertEquals(1929, items[1].year)
    }

    @Test
    fun `a film split into parts keeps one format in order`() {
        val body = """{"files":[
            {"name":"nosferatu-2of5_512kb.mp4","format":"512Kb MPEG4","height":"240","size":"86490513"},
            {"name":"nosferatu-1of5.ogv","format":"Ogg Video"},
            {"name":"nosferatu-1of5_512kb.mp4","format":"512Kb MPEG4","height":"240","size":"86457002"}]}"""
        val videos = InternetArchiveFilms.parseFiles(body, "Nosferatu_DVD_quality", "Internet Archive")
        assertEquals(
            listOf(
                "https://archive.org/download/Nosferatu_DVD_quality/nosferatu-1of5_512kb.mp4",
                "https://archive.org/download/Nosferatu_DVD_quality/nosferatu-2of5_512kb.mp4",
            ),
            videos.map { it.url },
        )
        assertEquals("Part 1/2 · 240p", videos.first().label)
        assertTrue(videos.all { it.downloadAllowed })
    }

    @Test
    fun `the original h264 file wins over derivatives`() {
        val body = """{"files":[
            {"name":"film_512kb.mp4","format":"512Kb MPEG4"},
            {"name":"film.mp4","format":"h.264","height":"1080"}]}"""
        val videos = InternetArchiveFilms.parseFiles(body, "film", "Internet Archive")
        assertEquals(listOf("https://archive.org/download/film/film.mp4"), videos.map { it.url })
        assertEquals("1080p", videos.single().label)
    }
}
