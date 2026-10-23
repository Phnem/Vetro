package com.example.myapplication.ui.details

import com.example.myapplication.data.models.Anime
import com.example.myapplication.media.progress.TitleTrackingPreferences
import com.example.myapplication.network.AnimeDetails
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DetailsTrackingTest {
    private val anime = Anime("title", "Title", episodes = 12, rating = 6f,
        imageFileName = null, orderIndex = 0, dateAdded = 0)
    private val details = AnimeDetails("Title", null, "", "ANIME", "FINISHED", 12, 12,
        null, emptyList(), 90, null, "test")

    @Test fun `manual rating overrides catalogue rating while auto retains existing priority`() {
        assertEquals("90/100", buildDetailFacts(anime, details, true).first { it.key == "rating" }.value)
        assertEquals("8.3/10", buildDetailFacts(anime, details, true, userRatingOverride = 8.25f)
            .first { it.key == "rating" }.value)
    }

    @Test fun `date pill formats full and incomplete ranges`() {
        val start = LocalDate.of(2026, 10, 10).toEpochDay()
        val finish = LocalDate.of(2026, 10, 20).toEpochDay()
        assertEquals("Auto", watchDatesLabel(TitleTrackingPreferences()))
        assertEquals("Auto", watchDatesLabel(TitleTrackingPreferences(autoStartedOn = start)))
        assertEquals("10.10.26–…", watchDatesLabel(TitleTrackingPreferences(manualDates = true, manualStartedOn = start)))
        assertEquals("10.10.26–20.10.26", watchDatesLabel(TitleTrackingPreferences(autoStartedOn = start, autoFinishedOn = finish)))
        assertEquals("10.10.26–20.10.26", watchDatesLabel(TitleTrackingPreferences(manualDates = true,
            manualStartedOn = start, manualFinishedOn = finish)))
    }
}
