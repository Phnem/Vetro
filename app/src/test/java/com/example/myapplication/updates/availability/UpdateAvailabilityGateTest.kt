package com.example.myapplication.updates.availability

import com.example.myapplication.data.models.AnimeUpdate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateAvailabilityGateTest {

    private class FakeHeld : HeldChecks {
        val marks = mutableMapOf<Pair<String, Int>, Long>()
        override suspend fun lastCheckedMs(animeId: String, episodes: Int) = marks[animeId to episodes]
        override suspend fun markChecked(animeId: String, episodes: Int, atMs: Long) {
            marks[animeId to episodes] = atMs
        }
        override suspend fun release(animeId: String, episodes: Int) {
            marks.remove(animeId to episodes)
        }
    }

    private val zone = ZoneId.of("UTC")
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = 1_800_000_000_000L
    private val update = AnimeUpdate("a1", "Title", currentEpisodes = 4, newEpisodes = 5, source = "test")

    private fun gate(
        held: FakeHeld,
        enabled: Boolean = true,
        clock: () -> Long = { now },
        audit: suspend (AnimeUpdate) -> EpisodeAudit?,
    ) = UpdateAvailabilityGate(
        enabled = { enabled },
        held = held,
        audit = audit,
        clock = clock,
        zone = { zone },
    )

    @Test
    fun `disabled setting lets everything through without auditing`() = runBlocking {
        var audits = 0
        val result = gate(FakeHeld(), enabled = false) { audits++; EpisodeAudit(0, now) }.filter(listOf(update))
        assertEquals(listOf(update), result)
        assertEquals(0, audits)
    }

    @Test
    fun `few studios and a fresh episode are held and stamped`() = runBlocking {
        val held = FakeHeld()
        val result = gate(held) { EpisodeAudit(studioCount = 2, releasedAtMs = now - 3 * hour) }.filter(listOf(update))
        assertEquals(emptyList<AnimeUpdate>(), result)
        assertEquals(now, held.marks["a1" to 5])
    }

    @Test
    fun `four studios release the notification`() = runBlocking {
        val held = FakeHeld().also { it.marks["a1" to 5] = now - 7 * hour }
        val result = gate(held) { EpisodeAudit(studioCount = 4, releasedAtMs = now - 3 * hour) }.filter(listOf(update))
        assertEquals(listOf(update), result)
        assertNull(held.marks["a1" to 5])
    }

    @Test
    fun `an episode older than two days releases the notification`() = runBlocking {
        val result = gate(FakeHeld()) { EpisodeAudit(studioCount = 1, releasedAtMs = now - 3 * day) }.filter(listOf(update))
        assertEquals(listOf(update), result)
    }

    @Test
    fun `a held episode is not audited again before six hours pass`() = runBlocking {
        var audits = 0
        val held = FakeHeld().also { it.marks["a1" to 5] = now - 5 * hour }
        val result = gate(held) { audits++; EpisodeAudit(0, now - hour) }.filter(listOf(update))
        assertEquals(emptyList<AnimeUpdate>(), result)
        assertEquals(0, audits)
    }

    @Test
    fun `a held episode is audited again after six hours`() = runBlocking {
        var audits = 0
        val held = FakeHeld().also { it.marks["a1" to 5] = now - 6 * hour }
        gate(held) { audits++; EpisodeAudit(0, now - hour) }.filter(listOf(update))
        assertEquals(1, audits)
        assertEquals(now, held.marks["a1" to 5])
    }

    @Test
    fun `a failed audit never withholds a notification`() = runBlocking {
        val result = gate(FakeHeld()) { null }.filter(listOf(update))
        assertEquals(listOf(update), result)
    }

    @Test
    fun `unknown release date never withholds a notification`() = runBlocking {
        val result = gate(FakeHeld()) { EpisodeAudit(studioCount = 0, releasedAtMs = null) }.filter(listOf(update))
        assertEquals(listOf(update), result)
    }
}
