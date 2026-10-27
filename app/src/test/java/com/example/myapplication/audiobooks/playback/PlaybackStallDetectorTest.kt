package com.example.myapplication.audiobooks.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStallDetectorTest {
    private val detector = PlaybackStallDetector(bufferingStallMs = 12_000, readyStallMs = 5_000, cooldownMs = 20_000)

    private fun tick(
        now: Long,
        position: Long,
        mediaId: String? = "a",
        wantsPlay: Boolean = true,
        buffering: Boolean = false,
        ready: Boolean = true,
    ) = detector.onTick(now, mediaId, wantsPlay, buffering, ready, position)

    private fun playFor(seconds: Int) {
        for (s in 0..seconds) tick(now = s * 1_000L, position = s * 1_000L)
    }

    @Test
    fun movingPositionIsNeverAStall() {
        playFor(60)
        assertFalse(tick(now = 61_000, position = 61_000))
    }

    @Test
    fun frozenPositionWhileBufferingFiresAfterTheLimit() {
        playFor(10)
        assertFalse(tick(now = 14_000, position = 10_000, buffering = true, ready = false))
        assertFalse(tick(now = 21_000, position = 10_000, buffering = true, ready = false))
        assertTrue(tick(now = 23_000, position = 10_000, buffering = true, ready = false))
    }

    @Test
    fun readyButSilentFiresSooner() {
        playFor(10)
        assertFalse(tick(now = 13_000, position = 10_000))
        assertTrue(tick(now = 16_000, position = 10_000))
    }

    @Test
    fun cooldownKeepsItFromReopeningInALoop() {
        playFor(10)
        assertTrue(tick(now = 16_000, position = 10_000))
        assertFalse(tick(now = 22_000, position = 10_000))
        assertFalse(tick(now = 30_000, position = 10_000))
        assertTrue(tick(now = 37_000, position = 10_000))
    }

    @Test
    fun pauseResetsTheClock() {
        playFor(10)
        assertFalse(tick(now = 40_000, position = 10_000, wantsPlay = false))
        assertFalse(tick(now = 42_000, position = 10_000))
        assertTrue(tick(now = 46_000, position = 10_000))
    }

    @Test
    fun aBookThatNeverStartedIsLeftToTheSourceChain() {
        assertFalse(tick(now = 0, position = 0, buffering = true, ready = false))
        assertFalse(tick(now = 30_000, position = 0, buffering = true, ready = false))
    }

    @Test
    fun newTrackStartsOverWithoutAStall() {
        playFor(10)
        assertFalse(tick(now = 30_000, position = 0, mediaId = "b", buffering = true, ready = false))
        assertFalse(tick(now = 60_000, position = 0, mediaId = "b", buffering = true, ready = false))
    }

    @Test
    fun endedOrIdleIsNotAStall() {
        playFor(10)
        assertFalse(tick(now = 40_000, position = 10_000, buffering = false, ready = false))
    }
}
