package com.example.myapplication.ui.details

import com.example.myapplication.domain.enrichment.title.NextRelease
import com.example.myapplication.domain.enrichment.title.ReleaseConfidence
import com.example.myapplication.domain.enrichment.title.ReleaseTrack
import com.example.myapplication.network.enrichment.EnrichmentSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseTextTest {
    private val now = Instant.parse("2026-09-26T12:00:00Z")

    private fun release(at: Instant?, confidence: ReleaseConfidence, date: LocalDate? = null) =
        NextRelease(ReleaseTrack.ORIGINAL, 1, 5, at, date, confidence, EnrichmentSource.ANILIST)

    @Test
    fun countdown() {
        val at = now.plus(Duration.ofDays(4).plusHours(16).plusMinutes(33))
        assertEquals("через 4 дн 16 ч 33 мин 00 с", releaseText(release(at, ReleaseConfidence.EXACT), now, ru = true))
        assertEquals("in 4d 16h 33m 00s", releaseText(release(at, ReleaseConfidence.EXACT), now, ru = false))
        assertEquals("через 5 мин 07 с", releaseText(release(now.plusSeconds(307), ReleaseConfidence.EXACT), now, ru = true))
    }

    @Test
    fun estimatedDateAndPast() {
        val at = now.plus(Duration.ofHours(3))
        assertEquals("≈ через 3 ч 0 мин 00 с", releaseText(release(at, ReleaseConfidence.ESTIMATED), now, ru = true))
        assertEquals("3 окт.", releaseText(release(null, ReleaseConfidence.DATE_ONLY, LocalDate.parse("2026-10-03")), now, ru = true))
        assertEquals("Oct 3", releaseText(release(null, ReleaseConfidence.DATE_ONLY, LocalDate.parse("2026-10-03")), now, ru = false))
        assertEquals("Выходит сейчас", releaseText(release(now.minusSeconds(1), ReleaseConfidence.EXACT), now, ru = true))
    }
}
