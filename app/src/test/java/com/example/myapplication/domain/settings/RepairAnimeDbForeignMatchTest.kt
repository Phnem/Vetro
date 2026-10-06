package com.example.myapplication.domain.settings

import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.EnrichedTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairAnimeDbForeignMatchTest {

    private fun result(title: String, altTitle: String? = null, source: String = "AniList") = ApiSearchResult(
        title = title,
        altTitle = altTitle,
        posterUrl = null,
        episodes = 12,
        description = "",
        type = "TV",
        genres = emptyList(),
        rating = null,
        source = source,
        categoryType = "ANIME",
        externalId = "1",
    )

    private val russianTitle = "Я, владыка демонов, взял эльфийку-рабыню в жены"

    @Test
    fun cyrillic_query_never_takes_an_unrelated_first_result_from_english_catalogues() {
        val junk = listOf(result("Psyren"), result("Naruto"))
        assertNull(pickRelaxedMatch(russianTitle, junk, ruAware = false))
    }

    @Test
    fun cyrillic_query_rejects_an_unrelated_first_result_even_from_a_russian_catalogue() {
        val junk = listOf(result("Псайрен", source = "Shikimori"))
        assertNull(pickRelaxedMatch(russianTitle, junk, ruAware = true))
    }

    @Test
    fun cyrillic_query_accepts_a_close_result_from_a_russian_catalogue() {
        val close = result("Я, владыка демонов, взял эльфийку-рабыню в жёны!", source = "Shikimori")
        assertEquals(close, pickRelaxedMatch(russianTitle, listOf(result("Psyren"), close), ruAware = true))
    }

    @Test
    fun latin_query_keeps_the_relaxed_first_result() {
        val first = result("Shingeki no Kyojin Final")
        assertEquals(first, pickRelaxedMatch("Attack on Titan", listOf(first), ruAware = false))
    }

    @Test
    fun strict_match_always_wins() {
        val exact = result("Attack on Titan")
        assertEquals(exact, pickRelaxedMatch("Attack on Titan", listOf(result("Psyren"), exact), ruAware = false))
    }

    @Test
    fun empty_results_give_nothing() {
        assertNull(pickRelaxedMatch(russianTitle, emptyList(), ruAware = true))
        assertNull(pickStrictMatch(russianTitle, emptyList()))
    }

    @Test
    fun entry_bound_to_another_title_is_foreign() {
        val psyren = EnrichedTitles(shikimoriId = 9, malId = 9, romaji = "Psyren", russian = "Псайрен")
        assertTrue(isForeignMatch(listOf(russianTitle), psyren))
    }

    @Test
    fun entry_bound_to_its_own_title_is_not_foreign() {
        val own = EnrichedTitles(
            shikimoriId = 1,
            romaji = "Maou-sama, Dorei Elf wo Yome ni Shimasu",
            russian = "Я, владыка демонов, взял эльфийку-рабыню в жёны",
        )
        assertFalse(isForeignMatch(listOf(russianTitle), own))
    }

    @Test
    fun a_loose_custom_name_is_not_flagged_when_it_shares_words() {
        val own = EnrichedTitles(shikimoriId = 1, russian = "Магическая битва 2")
        assertFalse(isForeignMatch(listOf("Магическая битва"), own))
    }

    @Test
    fun missing_data_never_flags_an_entry() {
        assertFalse(isForeignMatch(listOf(russianTitle), EnrichedTitles(shikimoriId = 1)))
        assertFalse(isForeignMatch(emptyList(), EnrichedTitles(romaji = "Psyren")))
    }

    @Test
    fun cyrillic_detection() {
        assertTrue(containsCyrillic("Наруто"))
        assertTrue(containsCyrillic("Naruto: Шиппуден"))
        assertFalse(containsCyrillic("Naruto"))
    }
}
