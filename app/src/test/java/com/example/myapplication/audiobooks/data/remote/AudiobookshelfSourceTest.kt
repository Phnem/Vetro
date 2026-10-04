package com.example.myapplication.audiobooks.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Формы ответов — из документации API Audiobookshelf (api.audiobookshelf.org). */
class AudiobookshelfSourceTest {
    private val base = "https://abs.example.com"

    @Test
    fun `token prefers the new access token`() {
        assertEquals("jwt", AudiobookshelfParser.token("""{"user":{"id":"root","token":"legacy","accessToken":"jwt"}}"""))
        assertEquals("legacy", AudiobookshelfParser.token("""{"user":{"id":"root","token":"legacy"}}"""))
        assertNull(AudiobookshelfParser.token("""{"error":"Invalid username or password"}"""))
    }

    @Test
    fun `only book libraries are searched`() {
        val body = """{"libraries":[{"id":"lib_main","mediaType":"book"},{"id":"lib_pod","mediaType":"podcast"}]}"""
        assertEquals(listOf("lib_main"), AudiobookshelfParser.bookLibraries(body))
    }

    @Test
    fun `search maps library items`() {
        val body = """{"book":[{"libraryItem":{"id":"li_1","media":{"duration":33854.9,"metadata":{
            "title":"Wizards First Rule","authorName":"Terry Goodkind","narratorName":"Sam Tsoutsouvas",
            "genres":["Fantasy"],"publishedYear":"1994"}}}}]}"""
        val book = AudiobookshelfParser.parseSearch(body, base, "tok").single()
        assertEquals("li_1", book.ref.key)
        assertEquals(listOf("Terry Goodkind"), book.authors)
        assertEquals(listOf("Sam Tsoutsouvas"), book.narrators)
        assertEquals(1994, book.year)
        assertEquals("$base/api/items/li_1/cover?token=tok", book.coverUrl)
    }

    @Test
    fun `item uses server track links and server chapters`() {
        val body = """{"id":"li_1","media":{"duration":12000.9,"metadata":{"title":"Book","authorName":"A"},
            "chapters":[{"id":0,"start":0,"end":6004.5,"title":"One"},{"id":1,"start":6004.5,"end":12000.9,"title":"Two"}],
            "tracks":[{"index":2,"duration":5996.4,"title":"02.mp3","contentUrl":"/api/items/li_1/file/222","mimeType":"audio/mpeg"},
                      {"index":1,"duration":6004.5,"title":"01.mp3","contentUrl":"/api/items/li_1/file/111","mimeType":"audio/mpeg"}]}}"""
        val item = AudiobookshelfParser.parseItem(body, "li_1", base, "tok")!!
        assertEquals(listOf("$base/api/items/li_1/file/111", "$base/api/items/li_1/file/222"), item.tracks.map { it.url })
        assertEquals(listOf("One", "Two"), item.chapters.map { it.title })
        assertEquals(6004500L, item.chapters[1].startMs)
        assertEquals(listOf(6004L, 5996L), item.details.chapterDurationsSec)
    }

    @Test
    fun `old servers without tracks fall back to files by ino`() {
        val body = """{"id":"li_1","media":{"metadata":{"title":"Book"},
            "audioFiles":[{"index":1,"ino":"649644248522215260","duration":60,"metadata":{"filename":"Book 01.mp3"}}]}}"""
        val item = AudiobookshelfParser.parseItem(body, "li_1", base, "tok")!!
        assertEquals("$base/api/items/li_1/file/649644248522215260", item.tracks.single().url)
        assertTrue(item.chapters.isEmpty())
    }
}
