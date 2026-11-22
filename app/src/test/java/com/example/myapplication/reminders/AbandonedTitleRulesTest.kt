package com.example.myapplication.reminders

import com.example.myapplication.data.models.AiringProgress
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.domain.seasons.SeasonEpisodesEntry
import com.example.myapplication.domain.seasons.SeasonInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbandonedTitleRulesTest {
    private val day = 24L * 60L * 60L * 1000L
    private val now = 100L * day

    @Test
    fun idleThresholdIsThreeWeeks() {
        assertFalse(AbandonedTitleRules.isIdleLongEnough(now - 20 * day, now))
        assertTrue(AbandonedTitleRules.isIdleLongEnough(now - 21 * day, now))
        assertFalse(AbandonedTitleRules.isIdleLongEnough(0L, now))
    }

    @Test
    fun completedAndDroppedAreSkippedAutoIsFine() {
        assertFalse(AbandonedTitleRules.isStatusEligible(WatchStatus.COMPLETED))
        assertFalse(AbandonedTitleRules.isStatusEligible(WatchStatus.DROPPED))
        assertTrue(AbandonedTitleRules.isStatusEligible(null))
        assertTrue(AbandonedTitleRules.isStatusEligible(WatchStatus.ON_HOLD))
        assertTrue(AbandonedTitleRules.isStatusEligible(WatchStatus.WATCHING))
    }

    @Test
    fun releasedPrefersLayoutThenAiringThenStored() {
        val layout = SeasonEpisodesEntry(
            animeId = "a",
            seasons = listOf(
                SeasonInfo(seasonNumber = 1, episodes = 12, source = "AniList"),
                SeasonInfo(seasonNumber = 2, episodes = 8, source = "AniList"),
            ),
        )
        val airing = AiringProgress("a", 1, airedEpisodes = 5, totalEpisodes = 12, updatedAt = 0L)
        assertEquals(20, AbandonedTitleRules.releasedEpisodes(layout, airing, 24))
        assertEquals(5, AbandonedTitleRules.releasedEpisodes(null, airing, 24))
        assertEquals(24, AbandonedTitleRules.releasedEpisodes(null, null, 24))
        assertNull(AbandonedTitleRules.releasedEpisodes(null, null, 0))
    }

    @Test
    fun finishedOrUnknownTitleIsNotUnfinished() {
        assertTrue(AbandonedTitleRules.hasUnfinishedContent(12, 5))
        assertFalse(AbandonedTitleRules.hasUnfinishedContent(12, 12))
        assertFalse(AbandonedTitleRules.hasUnfinishedContent(null, 5))
    }

    @Test
    fun historyLimitsRepeats() {
        val last = now - 100 * day
        assertTrue(AbandonedTitleRules.passesHistory(null, last, now))
        // Недавно напоминали и с тех пор тайтл не открывали.
        assertFalse(AbandonedTitleRules.passesHistory(ReminderRecord(now - 10 * day, 1), last, now))
        // Прошло достаточно, но уже напоминали дважды.
        assertFalse(AbandonedTitleRules.passesHistory(ReminderRecord(now - 60 * day, 2), last, now))
        assertTrue(AbandonedTitleRules.passesHistory(ReminderRecord(now - 60 * day, 1), last, now))
        // Человек вернулся к тайтлу после напоминания: счёт начинается заново.
        assertTrue(AbandonedTitleRules.passesHistory(ReminderRecord(now - 60 * day, 2), now - 30 * day, now))
    }

    @Test
    fun globalCooldownAndWindow() {
        assertFalse(AbandonedTitleRules.passesGlobalCooldown(now - 2 * day, now))
        assertTrue(AbandonedTitleRules.passesGlobalCooldown(now - 3 * day, now))
        assertTrue(AbandonedTitleRules.passesGlobalCooldown(0L, now))
        assertFalse(AbandonedTitleRules.isInWindow(9))
        assertTrue(AbandonedTitleRules.isInWindow(10))
        assertTrue(AbandonedTitleRules.isInWindow(20))
        assertFalse(AbandonedTitleRules.isInWindow(21))
    }

    @Test
    fun pickPrefersMoreEngagedThenLaterAbandoned() {
        val a = AbandonedCandidate("a", "A", ReminderUnit.EPISODES, 5, "E5", 10L, 30)
        val b = AbandonedCandidate("b", "B", ReminderUnit.EPISODES, 9, "E9", 5L, 40)
        val c = AbandonedCandidate("c", "C", ReminderUnit.EPISODES, 9, "E9", 8L, 35)
        assertEquals("c", AbandonedTitleRules.pick(listOf(a, b, c))?.animeId)
        assertNull(AbandonedTitleRules.pick(emptyList()))
    }
}
