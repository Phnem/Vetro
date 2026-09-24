package com.example.myapplication.audiobooks.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningTimeCountdownTest {
    @Test fun countsOnlyWhilePlayingAndFadesAtTheEnd() {
        val timer = ListeningTimeCountdown(20_000L)
        assertEquals(15_000L, timer.advance(5_000L, playing = true))
        assertEquals(15_000L, timer.advance(60_000L, playing = false))
        assertEquals(10_000L, timer.advance(5_000L, playing = true))
        assertEquals(1f, timer.volume)
        assertEquals(5_000L, timer.advance(5_000L, playing = true))
        assertEquals(0.25f, timer.volume)
        assertEquals(0L, timer.advance(7_000L, playing = true))
    }
}
