package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.audiobooks.data.remote.web.SiteText
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceId
import com.example.myapplication.audiobooks.domain.source.WorkMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteTextTest {

    @Test
    fun durations() {
        assertEquals(12 * 3600L + 52 * 60 + 3, SiteText.clockSec("Время звучания: 12:52:03"))
        assertEquals(22 * 60L + 21, SiteText.clockSec("22:21"))
        assertEquals(3 * 3600L + 45 * 60, SiteText.wordsSec("3 часа 45 минут"))
        assertEquals(3600L + 22 * 60 + 21, SiteText.isoSec("PT1H22M21S"))
        assertNull(SiteText.clockSec(""))
    }

    @Test
    fun `title and author`() {
        assertEquals("Задача трех тел" to "Лю Цысинь", SiteText.splitTitleAuthor("Задача трех тел - Лю Цысинь"))
        assertEquals("Солярис" to null, SiteText.splitTitleAuthor("Солярис"))
        assertEquals("Задача трех тел", SiteText.stripNarration("Задача трех тел (читает Игорь Князев)"))
    }

    @Test
    fun `chapter names`() {
        assertEquals("Часть 1", SiteText.chapterTitle("1: Часть 1"))
        assertNull(SiteText.chapterTitle("01_02_00_Besslavnoe_nachalo"))
        assertNull(SiteText.chapterTitle("01_01"))
    }

    @Test
    fun `json after a marker survives brackets inside strings`() {
        val html = """<script>playerInit(1, 'x', 'json', [{"title":"Глава [1]","url":"a"}], 'c');</script>"""
        assertEquals("""[{"title":"Глава [1]","url":"a"}]""", SiteText.jsonAfter(html, "'json',"))
    }

    @Test
    fun `same work across sites`() {
        fun book(title: String, vararg authors: String) =
            SourceBook(SourceBookRef(SourceId("x"), title), title, authors.toList(), emptyList(), null, null)
        assertTrue(WorkMatch.same("Задача трёх тел", listOf("Лю Цысинь"), book("Задача трех тел (читает Игорь Князев)", "Цысинь Лю")))
        assertTrue(WorkMatch.same("Задача трёх тел", listOf("Лю Цысинь"), book("Задача трех тел")))
        assertFalse(WorkMatch.same("Задача трёх тел", listOf("Лю Цысинь"), book("Задача трех комнат", "Сунь Циньвэнь")))
    }
}
