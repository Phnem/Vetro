package com.example.myapplication.audiobooks.text.source

import com.example.myapplication.audiobooks.domain.model.VariantId
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSourcesTest {

    private val body = List(60) { "Это предложение номер $it, и в нём достаточно слов для проверки." }.joinToString(" ")

    @Test
    fun `gutenberg txt loses its license header and hard line breaks`() {
        val raw = "The Project Gutenberg eBook\nlicense blah\n*** START OF THE PROJECT GUTENBERG EBOOK THE TIME MACHINE ***\n\n" +
            "CHAPTER I\n\nThe Time Traveller (for so it will be convenient\nto speak of him) was expounding.\n\n" + "More text. ".repeat(300) +
            "\n\n*** END OF THE PROJECT GUTENBERG EBOOK THE TIME MACHINE ***\nlicense again"
        val book = TextParsers.parse(raw.toByteArray(), "pg35.txt")!!
        assertFalse(book.display.contains("license"))
        assertTrue(book.display.contains("convenient to speak of him"))
        assertEquals("CHAPTER I", book.chapters.first().title)
    }

    @Test
    fun `fb2 sections become chapters, notes are skipped`() {
        val fb2 = """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook><description><title-info><lang>ru</lang></title-info></description>
            <body><section><title><p>Глава первая</p></title><p>$body</p><p>Второй абзац<a type="note">[1]</a>.</p></section></body>
            <body name="notes"><section><p>Сноска, которой нет в аудио.</p></section></body></FictionBook>""".trimIndent()
        val book = TextParsers.parse(fb2.toByteArray(), "book.fb2")!!
        assertEquals("ru", book.language)
        assertEquals("Глава первая", book.chapters.single().title)
        assertFalse(book.display.contains("Сноска"))
        assertFalse(book.display.contains("[1]"))
    }

    @Test
    fun `epub is read in spine order`() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<package><metadata><dc:language>en</dc:language></metadata>
                <manifest><item id="a" href="text/one.xhtml"/><item id="b" href="text/two.xhtml"/></manifest>
                <spine><itemref idref="b"/><itemref idref="a"/></spine></package>""")
            put("OEBPS/text/one.xhtml", "<html><body><h2>Second</h2><p>$body</p></body></html>")
            put("OEBPS/text/two.xhtml", "<html><body><h2>First</h2><p>$body</p></body></html>")
        }
        val book = TextParsers.parse(zip.toByteArray(), "book.epub")!!
        assertEquals(listOf("First", "Second"), book.chapters.map { it.title })
        assertEquals("en", book.language)
    }

    @Test
    fun `cp1251 text is decoded`() {
        val text = ("Глава 1\n\n" + body).toByteArray(charset("windows-1251"))
        val book = TextParsers.parse(text, "book.txt")!!
        assertTrue(book.display.startsWith("Глава 1"))
    }

    private fun query(title: String, authors: List<String>, language: String? = "ru") =
        TextQuery(title, null, authors, language, VariantId("x"), null, null)

    @Test
    fun `title alone is not enough when the author differs`() {
        val q = query("Дама с собачкой", listOf("Антон Чехов"))
        assertTrue(TextMatch.score(q, TextCandidate("w", "1", "Дама с собачкой", listOf("Чехов"), "ru")) >= 0.9f)
        assertEquals(0f, TextMatch.score(q, TextCandidate("w", "2", "Дама с собачкой", listOf("Иванов"), "ru")))
        assertEquals(0f, TextMatch.score(q, TextCandidate("w", "3", "The Lady with the Dog", listOf("Anton Chekhov"), "en")))
    }

    @Test
    fun `authors match across scripts`() {
        assertTrue(TextMatch.sameAuthor("Антон Павлович Чехов", "Anton Chekhov"))
        assertTrue(TextMatch.sameAuthor("Лев Толстой", "Leo Tolstoy"))
        assertFalse(TextMatch.sameAuthor("Лев Толстой", "Anton Chekhov"))
    }
}
