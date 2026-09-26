package com.example.myapplication.audiobooks.playback

import com.example.myapplication.audiobooks.domain.model.AudioTrack
import com.example.myapplication.audiobooks.domain.model.Chapter
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.model.WorkId
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookProgressSnapshotTest {

    // Две главы-файла по 10 минут, как у Aknigi24.
    private val timeline = BookTimeline(
        tracks = listOf(AudioTrack(0, "u0", durationMs = 600_000), AudioTrack(1, "u1", durationMs = 600_000)),
        chapters = listOf(Chapter(0, "Глава 1", 0, 600_000), Chapter(1, "Глава 2", 600_000, 600_000)),
    )

    private fun at(track: Int, offset: Long) = AudiobookProgressTracker.snapshot(
        timeline, WorkId.new(), NarrationId.new(), VariantId("x:y"), track, offset, speed = 1.5f,
    )!!

    @Test
    fun `position is stored on the book clock with chapter and offset`() {
        val s = at(track = 1, offset = 42_000)
        assertEquals(642_000L, s.globalMs)
        assertEquals(1, s.chapterIndex)
        assertEquals(42_000L, s.chapterOffsetMs)
        assertEquals(1_200_000L, s.totalMs)
        assertEquals(1.5f, s.speed)
        assertFalse(s.finished)
    }

    @Test
    fun `last minute counts as finished`() {
        assertTrue(at(track = 1, offset = 560_000).finished)
        assertFalse(at(track = 1, offset = 500_000).finished)
    }
}
