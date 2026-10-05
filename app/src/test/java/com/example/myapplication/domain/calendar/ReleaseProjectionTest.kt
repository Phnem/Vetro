package com.example.myapplication.domain.calendar

import com.example.myapplication.domain.enrichment.title.NextRelease
import com.example.myapplication.domain.enrichment.title.ReleaseTrack
import com.example.myapplication.network.enrichment.EnrichmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReleaseProjectionTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 5)

    private fun at(date: LocalDate): Instant = date.atTime(12, 0).atZone(zone).toInstant()

    private fun next(date: LocalDate, episode: Int?) =
        NextRelease(ReleaseTrack.RU, episode, at(date), exactTime = false, source = EnrichmentSource.OBSERVED)

    private fun project(
        next: NextRelease?,
        total: Int? = null,
        aired: Int? = null,
        observed: List<Pair<Int, Instant>> = emptyList(),
    ) = ReleaseProjection.forTitle("a", "Title", null, next, aired, total, observed, today, zone)

    @Test
    fun `the nearest release is the source's word, later weeks are projected`() {
        val result = project(next(LocalDate.of(2026, 10, 7), episode = 4))
        val upcoming = result.filter { !it.released }.sortedBy { it.date }
        assertEquals(LocalDate.of(2026, 10, 7), upcoming[0].date)
        assertEquals(4, upcoming[0].episode)
        assertFalse(upcoming[0].projected)
        assertEquals(LocalDate.of(2026, 10, 14), upcoming[1].date)
        assertEquals(5, upcoming[1].episode)
        assertTrue(upcoming[1].projected)
    }

    @Test
    fun `projection stops at the announced season length`() {
        val result = project(next(LocalDate.of(2026, 10, 7), episode = 10), total = 12)
        assertEquals(listOf(10, 11, 12), result.map { it.episode })
    }

    @Test
    fun `projection stays inside the horizon`() {
        val result = project(next(LocalDate.of(2026, 10, 7), episode = 1))
        val last = result.maxOf { it.date }
        assertTrue(!last.isAfter(today.plusDays(ReleaseProjection.HORIZON_DAYS)))
    }

    @Test
    fun `without an episode number it falls back to aired plus one`() {
        val result = project(next(LocalDate.of(2026, 10, 7), episode = null), aired = 6, total = 8)
        assertEquals(listOf(7, 8), result.map { it.episode })
    }

    @Test
    fun `released episodes from the log appear only within the lookback window`() {
        val result = project(
            next = null,
            observed = listOf(
                1 to at(today.minusDays(3)),
                2 to at(today.minusDays(ReleaseProjection.LOOKBACK_DAYS + 5)),
                3 to at(today.plusDays(2)),
            ),
        )
        assertEquals(listOf(1), result.map { it.episode })
        assertTrue(result.single().released)
    }

    @Test
    fun `an episode released today and listed as next is a single point`() {
        val result = project(
            next = next(today, episode = 4),
            observed = listOf(4 to at(today)),
        )
        assertEquals(1, result.count { it.date == today && it.episode == 4 })
    }

    @Test
    fun `no next release and no log gives nothing`() {
        assertTrue(project(next = null).isEmpty())
    }
}
