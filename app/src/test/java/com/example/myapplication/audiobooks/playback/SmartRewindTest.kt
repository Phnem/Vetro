package com.example.myapplication.audiobooks.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRewindTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `a short pause does not move the position`() {
        assertEquals(0L, SmartRewind.rewindMs(0))
        assertEquals(0L, SmartRewind.rewindMs(SmartRewind.MIN_PAUSE_MS - 1))
        assertEquals(100_000L, SmartRewind.resumePositionMs(100_000L, 10_000L))
    }

    @Test
    fun `the longer the pause the further it steps back`() {
        assertEquals(5_000L, SmartRewind.rewindMs(SmartRewind.MIN_PAUSE_MS))
        assertEquals(5_000L, SmartRewind.rewindMs(minute))
        assertEquals(10_000L, SmartRewind.rewindMs(5 * minute))
        assertEquals(15_000L, SmartRewind.rewindMs(30 * minute))
        assertEquals(20_000L, SmartRewind.rewindMs(3 * hour))
        assertEquals(30_000L, SmartRewind.rewindMs(8 * hour))
    }

    @Test
    fun `the rewind never shrinks with the pause and stays under the cap`() {
        var previous = 0L
        var pause = 0L
        while (pause < 100 * hour) {
            val rewind = SmartRewind.rewindMs(pause)
            assertTrue("pause=$pause rewind=$rewind previous=$previous", rewind >= previous)
            assertTrue(rewind <= SmartRewind.MAX_REWIND_MS)
            previous = rewind
            pause += 7 * minute
        }
    }

    @Test
    fun `the position stays inside the file`() {
        assertEquals(0L, SmartRewind.resumePositionMs(12_000L, 9 * hour))
        assertEquals(70_000L, SmartRewind.resumePositionMs(100_000L, 9 * hour))
    }
}
