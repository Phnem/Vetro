package com.example.myapplication.media.player

import com.example.myapplication.domain.enrichment.title.ReleaseObservation
import com.example.myapplication.media.source.VetroVideo
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingPendingRuleTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 15)
    private val nowMs = today.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()

    private fun daysAgoMs(days: Long): Long =
        today.minusDays(days).atTime(3, 0).atZone(zone).toInstant().toEpochMilli()

    private fun video(url: String, studio: String?) = VetroVideo(url = "https://cdn.example/$url.m3u8", label = "720p", sourceName = studio)

    @Test
    fun `fresh episode with few studios is pending dubbing`() {
        assertTrue(DubbingPendingRule.applies(studioCount = 3, releasedAtMs = daysAgoMs(0), nowMs = nowMs, zone = zone))
        assertTrue(DubbingPendingRule.applies(studioCount = 0, releasedAtMs = daysAgoMs(2), nowMs = nowMs, zone = zone))
    }

    @Test
    fun `four studios or more means a real source failure`() {
        assertFalse(DubbingPendingRule.applies(studioCount = 4, releasedAtMs = daysAgoMs(0), nowMs = nowMs, zone = zone))
    }

    @Test
    fun `episode older than two days is not pending`() {
        assertFalse(DubbingPendingRule.applies(studioCount = 1, releasedAtMs = daysAgoMs(3), nowMs = nowMs, zone = zone))
    }

    @Test
    fun `unknown release date never triggers the hint`() {
        assertFalse(DubbingPendingRule.applies(studioCount = 1, releasedAtMs = null, nowMs = nowMs, zone = zone))
    }

    @Test
    fun `studios are counted by distinct name ignoring case and blanks`() {
        val videos = listOf(
            video("a", "AniLibria"),
            video("b", "anilibria "),
            video("c", "Kodik · AniDUB"),
            video("d", null),
            video("e", "  "),
        )
        assertEquals(2, DubbingPendingRule.studioCount(videos))
    }

    @Test
    fun `release time is read for the requested episode only`() {
        val journal = listOf(ReleaseObservation(4, 100L), ReleaseObservation(5, 200L, exact = true))
        assertEquals(200L, DubbingPendingRule.releasedAtMs(journal, 5))
        assertNull(DubbingPendingRule.releasedAtMs(journal, 6))
    }
}
