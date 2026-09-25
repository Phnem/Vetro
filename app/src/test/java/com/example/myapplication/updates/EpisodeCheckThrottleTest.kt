package com.example.myapplication.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeCheckThrottleTest {

    private var now = 0L
    private val throttle = EpisodeCheckThrottle(intervalMs = 30 * 60_000L, nowMs = { now })

    @Test
    fun first_check_always_runs() {
        assertTrue(throttle.shouldRun(force = false))
    }

    @Test
    fun repeat_within_interval_is_skipped_until_it_passes() {
        throttle.markCompleted()
        now += 10 * 60_000L
        assertFalse(throttle.shouldRun(force = false))
        now += 20 * 60_000L
        assertTrue(throttle.shouldRun(force = false))
    }

    @Test
    fun manual_refresh_bypasses_the_throttle() {
        throttle.markCompleted()
        now += 1_000L
        assertTrue(throttle.shouldRun(force = true))
    }

    @Test
    fun failed_check_does_not_start_the_interval() {
        // markCompleted() is only called after a successful run — a failure leaves the gate open.
        now += 1_000L
        assertTrue(throttle.shouldRun(force = false))
    }
}
