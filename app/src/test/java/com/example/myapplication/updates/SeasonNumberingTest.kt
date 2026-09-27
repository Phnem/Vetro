package com.example.myapplication.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Названия — реальные узлы AniList (цепочки Re:ZERO и JoJo, 28.09.2026). */
class SeasonNumberingTest {

    @Test
    fun `season number written in the title wins`() {
        assertEquals(4, SeasonNumbering.explicitSeason("Re:ZERO -Starting Life in Another World- Season 4", null))
        assertEquals(4, SeasonNumbering.explicitSeason(null, "Re:Zero kara Hajimeru Isekai Seikatsu 4th Season"))
        assertEquals(2, SeasonNumbering.explicitSeason("Re:ZERO -Starting Life in Another World- Season 2 Part 2", null))
        assertEquals(3, SeasonNumbering.explicitSeason("Mushoku Tensei Season III", null))
        assertEquals(2, SeasonNumbering.explicitSeason("Some Show Second Season", null))
        assertNull(SeasonNumbering.explicitSeason("JoJo's Bizarre Adventure: Golden Wind", "JoJo no Kimyou na Bouken: Ougon no Kaze"))
    }

    @Test
    fun `second half of a season is a continuation, the first half is not`() {
        assertTrue(SeasonNumbering.isContinuation("Re:ZERO -Starting Life in Another World- Season 2 Part 2"))
        assertTrue(SeasonNumbering.isContinuation("JoJo's Bizarre Adventure: STONE OCEAN Part 2"))
        assertTrue(SeasonNumbering.isContinuation("STEEL BALL RUN JoJo's Bizarre Adventure 2nd - 3rd STAGE"))
        assertTrue(SeasonNumbering.isContinuation("Spy x Family Cour 2"))
        assertTrue(SeasonNumbering.isContinuation("Some Show 2nd Cour"))
        assertFalse(SeasonNumbering.isContinuation("STEEL BALL RUN JoJo's Bizarre Adventure 1st STAGE"))
        assertFalse(SeasonNumbering.isContinuation("JoJo's Bizarre Adventure: Stardust Crusaders - Battle in Egypt"))
        assertFalse(SeasonNumbering.isContinuation("Re:ZERO -Starting Life in Another World- Season 3"))
        assertFalse(SeasonNumbering.isContinuation(null))
    }
}
