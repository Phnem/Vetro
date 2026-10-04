package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.media.source.UserAccountConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ответы в форме Navidrome 0.64 (OpenSubsonic), см. .scratch/sources-expansion/RESEARCH.md, часть B. */
class SubsonicSourceTest {
    private val api = SubsonicApi(
        UserAccountConfig(username = "demo", password = "secret", baseUrl = "https://music.example.com/"),
        salt = "c19b2d",
    )

    @Test
    fun `auth is a salted token, never the password`() {
        val url = api.url("ping")
        assertTrue(url.startsWith("https://music.example.com/rest/ping.view?"))
        // md5("secretc19b2d")
        assertTrue(url.contains("t=" + java.security.MessageDigest.getInstance("MD5").digest("secretc19b2d".toByteArray()).joinToString("") { "%02x".format(it) }))
        assertTrue(url.contains("s=c19b2d") && url.contains("u=demo") && url.contains("f=json"))
        assertTrue("secret" !in url.replace("c19b2d", ""))
    }

    @Test
    fun `failed status is not a result`() {
        val failed = """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}"""
        assertNull(SubsonicParser.okBody(failed))
        assertNull(SubsonicParser.parseSearch(failed, api))
    }

    @Test
    fun `search maps albums to books`() {
        val body = """{"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonic":true,
            "searchResult3":{"album":[{"id":"al-1","name":"The Time Machine","artist":"H. G. Wells",
            "coverArt":"al-1","duration":11815,"year":1895,"genre":"Audiobook"}]}}}"""
        val books = SubsonicParser.parseSearch(body, api)!!
        assertEquals(1, books.size)
        val book = books.single()
        assertEquals("al-1", book.ref.key)
        assertEquals("The Time Machine", book.title)
        assertEquals(listOf("H. G. Wells"), book.authors)
        assertEquals(11815L, book.durationSec)
        assertTrue(book.coverUrl!!.contains("getCoverArt.view") && book.coverUrl!!.contains("id=al-1"))
    }

    @Test
    fun `album tracks become ordered chapters with stream links`() {
        val body = """{"subsonic-response":{"status":"ok","version":"1.16.1","album":{"id":"al-1",
            "name":"The Time Machine","artist":"H. G. Wells","coverArt":"al-1","song":[
            {"id":"s2","title":"Chapter 2","track":2,"discNumber":1,"duration":600},
            {"id":"s1","title":"Chapter 1","track":1,"discNumber":1,"duration":540},
            {"id":"s3","title":"Chapter 3","track":1,"discNumber":2,"duration":720}]}}}"""
        val album = SubsonicParser.parseAlbum(body, "al-1", api)!!
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), album.tracks.map { it.title })
        assertTrue(album.tracks.first().url.contains("stream.view") && album.tracks.first().url.contains("id=s1"))
        assertEquals(listOf(540L, 600L, 720L), album.details.chapterDurationsSec)
        assertEquals(1860L, album.details.book.durationSec)
    }
}
