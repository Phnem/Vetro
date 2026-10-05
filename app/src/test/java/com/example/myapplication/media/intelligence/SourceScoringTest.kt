package com.example.myapplication.media.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceScoringTest {

    private val now = 1_000_000L

    private fun fastReliable() = SourceStats(
        resolveAttempts = 20, resolveSuccesses = 19, resolveLatencyMs = 1_500,
        sessions = 10, sessionFailures = 0, startupMs = 1_800, bufferRatio = 0.005, heightPx = 1080,
        dubSessions = 4, dubKept = 4,
    )

    private fun flaky() = SourceStats(
        resolveAttempts = 20, resolveSuccesses = 8, resolveLatencyMs = 11_000,
        sessions = 10, sessionFailures = 5, startupMs = 8_000, bufferRatio = 0.09, heightPx = 480,
        dubSessions = 4, dubKept = 1,
    )

    @Test
    fun `no data is neutral with zero confidence`() {
        val score = SourceScoring.score(null)
        assertEquals(SourceScoring.NEUTRAL, score.value)
        assertEquals(0f, score.confidence, 0f)
        assertEquals(SourceScoring.NEUTRAL, SourceScoring.score(SourceStats()).value)
    }

    @Test
    fun `a fast reliable source outranks a flaky slow one by a wide margin`() {
        val good = SourceScoring.score(fastReliable())
        val bad = SourceScoring.score(flaky())
        assertTrue("good=${good.value}", good.value >= 80)
        assertTrue("bad=${bad.value}", bad.value <= 40)
        assertTrue(good.value - bad.value >= 30)
        assertEquals(1f, good.confidence, 0f)
    }

    @Test
    fun `one failure does not kill a new source and one success does not crown it`() {
        val oneFail = SourceScoring.score(SourceScoring.withResolve(SourceStats(), ok = false, latencyMs = 3_000, now = now))
        val oneWin = SourceScoring.score(SourceScoring.withResolve(SourceStats(), ok = true, latencyMs = 900, now = now))
        assertTrue("fail=${oneFail.value}", oneFail.value in 35..50)
        assertTrue("win=${oneWin.value}", oneWin.value in 50..65)
    }

    @Test
    fun `thin evidence is pulled toward neutral`() {
        val thin = fastReliable().copy(resolveAttempts = 2, resolveSuccesses = 2, sessions = 0, sessionFailures = 0)
        val full = SourceScoring.score(fastReliable())
        val low = SourceScoring.score(thin)
        assertTrue(low.value < full.value)
        assertTrue(low.confidence < 1f)
    }

    @Test
    fun `resolve latency only moves on successful answers`() {
        val a = SourceScoring.withResolve(SourceStats(), ok = true, latencyMs = 2_000, now = now)
        val b = SourceScoring.withResolve(a, ok = false, latencyMs = 20_000, now = now)
        assertEquals(2_000, a.resolveLatencyMs)
        assertEquals(2_000, b.resolveLatencyMs)
        assertEquals(2, b.resolveAttempts)
        assertEquals(1, b.resolveSuccesses)
    }

    @Test
    fun `a session updates startup quality and buffering, short ones skip buffering`() {
        val long = PlaybackSample(startupMs = 2_000, playedMs = 600_000, bufferingMs = 30_000, heightPx = 720, failed = false)
        val s1 = SourceScoring.withSession(SourceStats(), long, now)
        assertEquals(1, s1.sessions)
        assertEquals(2_000, s1.startupMs)
        assertEquals(720, s1.heightPx)
        assertEquals(30_000.0 / 630_000.0, s1.bufferRatio, 1e-9)

        val probe = PlaybackSample(startupMs = 1_000, playedMs = 5_000, bufferingMs = 4_000, heightPx = null, failed = false)
        val s2 = SourceScoring.withSession(s1, probe, now)
        assertEquals(s1.bufferRatio, s2.bufferRatio, 0.0)
        assertEquals(2, s2.sessions)
    }

    @Test
    fun `dub continuity counts only sessions that followed a switch`() {
        val plain = SourceScoring.withSession(SourceStats(), PlaybackSample(1_000, 60_000, 0, 1080, false), now)
        assertEquals(0, plain.dubSessions)
        val kept = SourceScoring.withSession(plain, PlaybackSample(1_000, 60_000, 0, 1080, false, dubKept = true), now)
        val lost = SourceScoring.withSession(kept, PlaybackSample(1_000, 60_000, 0, 1080, false, dubKept = false), now)
        assertEquals(2, lost.dubSessions)
        assertEquals(1, lost.dubKept)
    }

    @Test
    fun `failed sessions are counted`() {
        val s = SourceScoring.withSession(SourceStats(), PlaybackSample(null, 0, 0, null, failed = true), now)
        assertEquals(1, s.sessionFailures)
    }

    @Test
    fun `provider keys are canonical and reference labels are ignored`() {
        assertEquals("anilibria", SourceIntelligence.providerKey("AniLiberty"))
        assertEquals("anilibria", SourceIntelligence.providerKey("known source"))
        assertEquals("kodik", SourceIntelligence.providerKey(" Kodik "))
        assertEquals("animego", SourceIntelligence.providerKey("AnimeGo"))
        assertEquals("direct", SourceIntelligence.providerKey("direct URL"))
        assertNull(SourceIntelligence.providerKey("jut.su reference"))
        assertNull(SourceIntelligence.providerKey(""))
        assertNull(SourceIntelligence.providerKey(null))
    }
}
