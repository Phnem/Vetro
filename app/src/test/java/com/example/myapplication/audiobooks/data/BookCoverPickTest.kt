package com.example.myapplication.audiobooks.data

import coil3.toUri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookCoverPickTest {

    private fun pick(candidates: List<String>, seen: Set<String> = emptySet(), maxChecks: Int = 8, usable: (String) -> Boolean = { true }) =
        runBlocking { pickNextCover(candidates, seen, maxChecks) { usable(it) } }

    @Test
    fun `first unseen web address wins and order is kept`() {
        assertEquals(
            "https://b/2.jpg",
            pick(listOf("https://a/1.jpg", "https://b/2.jpg", "https://c/3.jpg"), seen = setOf("https://a/1.jpg")),
        )
    }

    @Test
    fun `duplicates, local files and cloud links are never offered`() {
        val candidates = listOf("file:///x.webp", "collection-attachment://id", "https://a/1.jpg", "https://a/1.jpg")
        assertEquals("https://a/1.jpg", pick(candidates))
        assertNull(pick(candidates, seen = setOf("https://a/1.jpg")))
    }

    @Test
    fun `a broken address is skipped for the next one`() {
        assertEquals(
            "https://b/2.jpg",
            pick(listOf("https://a/1.jpg", "https://b/2.jpg")) { it != "https://a/1.jpg" },
        )
    }

    @Test
    fun `checking stops after the limit so one press cannot become a crawl`() {
        val checked = mutableListOf<String>()
        val urls = (1..20).map { "https://h/$it.jpg" }
        assertNull(pick(urls, maxChecks = 3) { checked += it; false })
        assertEquals(urls.take(3), checked)
    }

    /** Загрузчик Coil узнаёт облачную ссылку по схеме: без этого обложка из R2 не откроется. */
    @Test
    fun `cloud cover link keeps its scheme for the image loader`() {
        assertEquals("collection-attachment", "collection-attachment://0b8f5c1e".toUri().scheme)
        assertEquals("https", "https://a/1.jpg".toUri().scheme)
    }
}
