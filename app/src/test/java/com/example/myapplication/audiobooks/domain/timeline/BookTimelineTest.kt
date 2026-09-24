package com.example.myapplication.audiobooks.domain.timeline

import com.example.myapplication.audiobooks.domain.model.AudioTrack
import com.example.myapplication.audiobooks.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTimelineTest {
    @Test fun boundariesAndChapterWithinOneFile() {
        val timeline = BookTimeline(
            tracks = listOf(track(0, 1_000), track(1, 2_000), track(2, 1_000)),
            chapters = listOf(Chapter(0, "First", 0, 1_500), Chapter(1, "Second", 1_500, 2_500)),
        )
        assertEquals(4_000L, timeline.totalMs)
        assertEquals(1_500L, timeline.toGlobal(1, 500))
        assertEquals(1 to 0L, timeline.toTrack(1_000))
        assertEquals(2 to 1_000L, timeline.toTrack(99_000))
        assertEquals("Second", timeline.chapterAt(1_500)?.title)
        assertEquals(0.375f, timeline.progress(1_500)!!, 0.0001f)
        assertEquals(1_250L, timeline.remainingMs(1_500, 2f))
        assertFalse(timeline.hasEstimates)
    }

    @Test fun unknownDurationIsNotReportedAsFakeProgress() {
        val timeline = BookTimeline(listOf(track(0, 1_000), AudioTrack(1, "content://unknown"), track(2, 1_000)), emptyList())
        assertNull(timeline.totalMs)
        assertNull(timeline.progress(500))
        assertNull(timeline.remainingMs(500, 1f))
        assertEquals(500L, timeline.toGlobal(0, 500))
        assertNull(timeline.toGlobal(2, 100))
        assertEquals(1 to 0L, timeline.toTrack(1_000))
        assertNull(timeline.toTrack(1_001))
    }

    @Test fun sizeAndBitrateProvideMarkedEstimate() {
        val timeline = BookTimeline(listOf(AudioTrack(0, "file://estimated", sizeBytes = 8_000L)), emptyList(), estimatedBitrateKbps = 64)
        assertEquals(1_000L, timeline.totalMs)
        assertTrue(timeline.hasEstimates)
    }

    private fun track(index: Int, durationMs: Long) = AudioTrack(index, "content://track/$index", durationMs = durationMs)
}
