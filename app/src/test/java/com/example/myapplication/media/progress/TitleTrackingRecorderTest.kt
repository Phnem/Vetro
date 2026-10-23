package com.example.myapplication.media.progress

import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.domain.seasons.SeasonKind
import org.junit.Assert.*
import org.junit.Test

class TitleTrackingRecorderTest {
    @Test fun `bounds use regular seasons in order and ignore specials`() {
        val seasons = listOf(
            SeasonInfo(2, 12, source = "test"),
            SeasonInfo(1000, 1, source = "test", kind = SeasonKind.Special),
            SeasonInfo(1, 24, source = "test"),
        )
        assertEquals(PlaybackEpisodeKey(1, 1) to PlaybackEpisodeKey(2, 12), episodeBounds(seasons, 24, false))
    }

    @Test fun `latest available episode of ongoing or incomplete season is not final`() {
        assertNull(episodeBounds(listOf(SeasonInfo(2, 5, ongoing = true, source = "test")), 5, false).second)
        assertNull(episodeBounds(listOf(SeasonInfo(2, 5, totalEpisodes = 12, source = "test")), 5, false).second)
        assertNull(episodeBounds(listOf(SeasonInfo(2, 12, source = "test")), 12, true).second)
        assertNull(episodeBounds(listOf(SeasonInfo(1, 12, source = "test"), SeasonInfo(2, 0, source = "test")), 12, false).second)
    }

    @Test fun `fallback requires known episode total and no airing marker`() {
        assertEquals(PlaybackEpisodeKey(1, 1) to PlaybackEpisodeKey(1, 12), episodeBounds(emptyList(), 12, false))
        assertNull(episodeBounds(emptyList(), 0, false).second)
        assertNull(episodeBounds(emptyList(), 5, true).second)
    }
}
