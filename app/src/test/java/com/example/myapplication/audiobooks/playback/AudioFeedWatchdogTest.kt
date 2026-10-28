package com.example.myapplication.audiobooks.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFeedWatchdogTest {
    private val watchdog = AudioFeedWatchdog(starvedLimitMs = 2_500, cooldownMs = 15_000)

    private fun check(now: Long, wantsPlay: Boolean = true, ready: Boolean = true) =
        watchdog.silentForMs(now, wantsPlay, ready)

    @Test
    fun steadyFeedIsNeverDead() {
        for (t in 0..60_000 step 100) watchdog.onFed(t.toLong())
        assertEquals(-1L, check(60_100))
    }

    @Test
    fun feedThatStopsFiresAfterTheLimitWithTheSilentTime() {
        watchdog.onFed(10_000)
        assertEquals(-1L, check(12_000))
        assertEquals(3_000L, check(13_000))
    }

    @Test
    fun neverFedOutputIsLeftToTheOtherDetectors() {
        assertEquals(-1L, check(60_000))
    }

    @Test
    fun pauseOrEndOfStreamIsNotDeath() {
        watchdog.onFed(10_000)
        watchdog.onIdle()
        assertEquals(-1L, check(60_000))
    }

    @Test
    fun notPlayingOrNotReadyIsNotDeath() {
        watchdog.onFed(10_000)
        assertEquals(-1L, check(60_000, wantsPlay = false))
        assertEquals(-1L, check(60_000, ready = false))
        assertEquals(50_000L, check(60_000))
    }

    @Test
    fun cooldownThenFiresAgainIfTheNewTrackIsDeadToo() {
        watchdog.onFed(10_000)
        assertEquals(5_000L, check(15_000))
        assertEquals(-1L, check(20_000))
        assertEquals(-1L, check(29_000))
        assertEquals(15_000L, check(30_000))
    }

    @Test
    fun healthyTrackAfterTheFixStaysQuiet() {
        watchdog.onFed(10_000)
        assertEquals(5_000L, check(15_000))
        watchdog.onIdle()
        for (t in 15_500..40_000 step 100) watchdog.onFed(t.toLong())
        assertEquals(-1L, check(40_100))
    }
}
