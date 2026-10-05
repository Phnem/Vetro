package com.example.myapplication.manga.translate

import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterTranslationPlanTest {

    private fun chapter(number: Double?, language: String, key: String = "$language-$number", paid: Boolean = false, at: Long = 0L) =
        MangaChapter(
            sourceId = MangaSourceId("test"),
            mangaKey = "m",
            key = key,
            number = number,
            language = language,
            paid = paid,
            publishedAt = at,
        )

    @Test
    fun the_kingdom_case_890_russian_chapters_and_10_japanese_ones() {
        val russian = (1..890).map { chapter(it.toDouble(), "ru") }
        val japanese = (1..900).map { chapter(it.toDouble(), "ja") }
        val all = russian + japanese

        val plan = ChapterTranslationPlan.chapters(all, preferredLanguage = "ru", autoTranslate = true)

        assertEquals(900, plan.size)
        assertEquals(890, plan.count { it.language == "ru" })
        val translated = plan.filter { it.language == "ja" }
        assertEquals((891..900).map { it.toDouble() }, translated.map { it.number })
        // Все десять - те, что реально нужно переводить; русские - нет.
        assertTrue(translated.all { ChapterTranslationPlan.needsTranslation(it, "ru", true) })
        assertTrue(plan.filter { it.language == "ru" }.none { ChapterTranslationPlan.needsTranslation(it, "ru", true) })
        // Порядок чтения сохранён.
        assertEquals(plan.map { it.number }, plan.sortedBy { it.number }.map { it.number })
    }

    @Test
    fun without_auto_translate_the_old_language_filter_applies() {
        val all = (1..5).map { chapter(it.toDouble(), "ru") } + (1..8).map { chapter(it.toDouble(), "ja") }
        val plan = ChapterTranslationPlan.chapters(all, "ru", autoTranslate = false)
        assertEquals(5, plan.size)
        assertTrue(plan.all { it.language == "ru" })
    }

    @Test
    fun a_gap_in_the_middle_is_filled_from_the_original_too() {
        val russian = listOf(1.0, 2.0, 4.0).map { chapter(it, "ru") }
        val japanese = listOf(1.0, 2.0, 3.0, 4.0).map { chapter(it, "ja") }
        val plan = ChapterTranslationPlan.chapters(russian + japanese, "ru", true)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), plan.map { it.number })
        assertEquals("ja", plan[2].language)
    }

    @Test
    fun a_title_with_no_chapters_in_the_language_falls_back_to_showing_everything() {
        val all = listOf(chapter(1.0, "en"), chapter(1.0, "ja"), chapter(2.0, "ja"))
        assertEquals(3, ChapterTranslationPlan.chapters(all, "ru", true).size)
    }

    @Test
    fun paid_originals_are_skipped_and_other_languages_are_not_translated() {
        val all = listOf(
            chapter(1.0, "ru"),
            chapter(2.0, "ja", paid = true),
            chapter(3.0, "ja"),
            chapter(4.0, "fr"), // с французского переводить не умеем
        )
        val plan = ChapterTranslationPlan.chapters(all, "ru", true)
        assertEquals(listOf(1.0, 3.0), plan.map { it.number })
    }

    @Test
    fun english_fills_the_chapters_the_user_language_does_not_have() {
        val all = listOf(chapter(1.0, "ru"), chapter(2.0, "ru"), chapter(3.0, "en"), chapter(4.0, "en"))
        val plan = ChapterTranslationPlan.chapters(all, "ru", true)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), plan.map { it.number })
        assertTrue(plan.filter { it.language == "en" }.all { ChapterTranslationPlan.needsTranslation(it, "ru", true) })
    }

    @Test
    fun a_japanese_original_beats_an_english_translation_of_the_same_chapter() {
        val all = listOf(
            chapter(1.0, "ru"),
            chapter(2.0, "en", key = "en-2", at = 900),
            chapter(2.0, "ja", key = "ja-2", at = 100),
            chapter(3.0, "en", key = "en-3"),
        )
        val plan = ChapterTranslationPlan.chapters(all, "ru", true)
        assertEquals(listOf("ja-2", "en-3"), plan.filter { it.language != "ru" }.map { it.key })
    }

    @Test
    fun the_freshest_upload_of_a_repeated_original_wins() {
        val all = listOf(
            chapter(1.0, "ru"),
            chapter(2.0, "ja", key = "old", at = 100),
            chapter(2.0, "ja", key = "new", at = 200),
        )
        val plan = ChapterTranslationPlan.chapters(all, "ru", true)
        assertEquals(2, plan.size)
        assertEquals("new", plan.single { it.language == "ja" }.key)
    }

    @Test
    fun needs_translation_requires_the_switch_a_preference_and_a_foreign_japanese_chapter() {
        val ja = chapter(5.0, "ja")
        assertTrue(ChapterTranslationPlan.needsTranslation(ja, "ru", true))
        assertFalse(ChapterTranslationPlan.needsTranslation(ja, "ru", false))
        assertFalse(ChapterTranslationPlan.needsTranslation(ja, null, true))
        assertFalse(ChapterTranslationPlan.needsTranslation(ja, "ja", true))
        assertTrue(ChapterTranslationPlan.needsTranslation(chapter(5.0, "en"), "ru", true))
        assertFalse(ChapterTranslationPlan.needsTranslation(chapter(5.0, "en"), "en", true))
        assertFalse(ChapterTranslationPlan.needsTranslation(chapter(5.0, "fr"), "ru", true))
        assertFalse(ChapterTranslationPlan.needsTranslation(chapter(5.0, "ru"), "ru", true))
    }
}
