package com.example.myapplication.media.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSaveGateTest {

    @Test
    fun saves_every_interval_only_while_playing() {
        val gate = ProgressSaveGate(intervalMs = 10_000)
        val saves = (0..60).count { second -> gate.shouldSave(playing = true, nowMs = second * 1_000L) }
        // t = 0, 10, 20, 30, 40, 50, 60 c
        assertEquals(7, saves)
    }

    @Test
    fun never_saves_while_paused() {
        val gate = ProgressSaveGate(intervalMs = 10_000)
        val saves = (0..60).count { second -> gate.shouldSave(playing = false, nowMs = second * 1_000L) }
        assertEquals(0, saves)
    }

    @Test
    fun saves_immediately_on_pause() {
        val gate = ProgressSaveGate(intervalMs = 10_000)
        assertTrue(gate.shouldSave(playing = true, nowMs = 0))
        assertFalse(gate.shouldSave(playing = true, nowMs = 3_000))
        assertTrue(gate.shouldSave(playing = false, nowMs = 4_000))
        assertFalse(gate.shouldSave(playing = false, nowMs = 20_000))
    }
}
