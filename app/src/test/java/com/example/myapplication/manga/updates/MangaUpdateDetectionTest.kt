package com.example.myapplication.manga.updates

import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что именно считается «новой главой» для пуша.
 *
 * Оглавление источника — это не список выпусков: один и тот же выпуск приходит отдельной записью
 * от каждой команды перевода и на каждом языке. Сравнение «в лоб» по ключам объявило бы новинкой
 * второй перевод главы, прочитанной полгода назад, — и пуши пошли бы потоком.
 */
class MangaUpdateDetectionTest {

    private val source = MangaSourceId("mangadex")

    private fun chapter(
        key: String,
        number: Double?,
        language: String? = "en",
        scanlator: String? = null,
        paid: Boolean = false,
    ) = MangaChapter(
        sourceId = source,
        mangaKey = "manga",
        key = key,
        number = number,
        language = language,
        scanlator = scanlator,
        paid = paid,
    )

    @Test
    fun `a chapter number absent from the cache is new`() {
        val previous = listOf(chapter("a", 1.0), chapter("b", 2.0))
        val current = previous + chapter("c", 3.0)

        val fresh = newChapterKeys(previous, current, language = null)

        assertEquals(listOf(3.0), fresh.map { it.number })
    }

    @Test
    fun `another translation of a known chapter is not new`() {
        val previous = listOf(chapter("a", 12.0, scanlator = "Team A"))
        val current = previous + chapter("b", 12.0, scanlator = "Team B")

        assertTrue(newChapterKeys(previous, current, language = null).isEmpty())
    }

    @Test
    fun `duplicates within one batch are reported once`() {
        val previous = listOf(chapter("a", 1.0))
        val current = previous + chapter("b", 2.0, scanlator = "A") + chapter("c", 2.0, scanlator = "B")

        assertEquals(listOf(2.0), newChapterKeys(previous, current, language = null).map { it.number })
    }

    @Test
    fun `paid chapters never trigger a push`() {
        // Страниц по платной главе не придёт — пуш про неё был бы обещанием, которого приложение
        // не сдержит.
        val previous = listOf(chapter("a", 1.0))
        val current = previous + chapter("b", 2.0, paid = true)

        assertTrue(newChapterKeys(previous, current, language = null).isEmpty())
    }

    @Test
    fun `a chapter in another language is ignored when a language is chosen`() {
        val previous = listOf(chapter("a", 1.0, language = "ru"))
        val current = previous + chapter("b", 2.0, language = "en")

        assertTrue(newChapterKeys(previous, current, language = "ru").isEmpty())
        assertEquals(1, newChapterKeys(previous, current, language = null).size)
    }

    @Test
    fun `unnumbered extras are compared by key`() {
        // Пролог и бонусы без номера сравнивать не по чему — только по ключу главы.
        val previous = listOf(chapter("extra-1", null))
        val current = previous + chapter("extra-2", null)

        assertEquals(listOf("extra-2"), newChapterKeys(previous, current, language = null).map { it.key })
    }
}
