package com.example.myapplication.media.progress

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodePlaybackProgressTest {
    @Test
    fun watched_at_eighty_five_percent() {
        val duration = 1_000_000L
        assertFalse(isEpisodeWatched(positionMs = 849_999L, durationMs = duration))
        assertTrue(isEpisodeWatched(positionMs = 850_000L, durationMs = duration))
    }

    @Test
    fun watched_when_only_credits_tail_remains() {
        // A short episode isolates the credits-tail rule from the independent 85% rule.
        val duration = 10 * 60_000L
        assertFalse(isEpisodeWatched(positionMs = duration - 105_001L, durationMs = duration))
        assertTrue(isEpisodeWatched(positionMs = duration - 105_000L, durationMs = duration))
    }

    @Test
    fun short_clip_is_not_watched_just_because_the_tail_is_short() {
        // Заглушка источника или трейлер на 90 секунд: «до конца меньше 105 с» верно с первой секунды.
        assertFalse(isEpisodeWatched(positionMs = 1_000L, durationMs = 90_000L))
        assertFalse(isEpisodeWatched(positionMs = 5_000L, durationMs = 5 * 60_000L))
        assertTrue(isEpisodeWatched(positionMs = 80_000L, durationMs = 90_000L))
    }

    @Test
    fun unknown_duration_is_never_watched() {
        assertFalse(isEpisodeWatched(positionMs = 10_000L, durationMs = 0L))
    }
}
