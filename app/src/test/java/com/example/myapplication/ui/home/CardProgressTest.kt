package com.example.myapplication.ui.home

import com.example.myapplication.data.models.AiringProgress
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.media.progress.PlaybackEpisodeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Статус «по умолчанию» читается с той же полоски прогресса, что показывает карточка. */
class CardProgressTest {

    private fun info(aired: Int, total: Int?, kind: CardProgressKind = CardProgressKind.WATCHING) =
        AiringCardInfo(seasonNumber = null, airedEpisodes = aired, totalEpisodes = total, kind = kind)

    @Test
    fun `no progress at all means completed`() {
        assertEquals(WatchStatus.COMPLETED, (null as AiringCardInfo?).autoWatchStatus())
    }

    @Test
    fun `a title in the middle of its season is being watched`() {
        assertEquals(WatchStatus.WATCHING, info(aired = 6, total = 12).autoWatchStatus())
    }

    @Test
    fun `a finished season is completed`() {
        assertEquals(WatchStatus.COMPLETED, info(aired = 12, total = 12).autoWatchStatus())
    }

    @Test
    fun `an unannounced total keeps the title in progress`() {
        assertEquals(WatchStatus.WATCHING, info(aired = 3, total = null).autoWatchStatus())
    }

    @Test
    fun `a manga with unread chapters is being read`() {
        assertEquals(WatchStatus.WATCHING, info(aired = 40, total = 120, kind = CardProgressKind.READING).autoWatchStatus())
    }

    @Test
    fun `no sources give no card progress`() {
        assertNull(computeCardProgress(layout = null, storedEpisodes = 12, watched = null, airing = null, reading = null))
    }

    @Test
    fun `watched episodes against the stored count give a watching bar`() {
        val progress = computeCardProgress(
            layout = null,
            storedEpisodes = 12,
            watched = PlaybackEpisodeKey(season = 1, episode = 5),
            airing = null,
            reading = null,
        )
        assertEquals(CardProgressKind.WATCHING, progress?.kind)
        assertEquals(WatchStatus.WATCHING, progress.autoWatchStatus())
    }

    @Test
    fun `a title that is airing and caught up reads as being watched`() {
        val airing = AiringProgress(animeId = "a", seasonNumber = 1, airedEpisodes = 4, totalEpisodes = 12, updatedAt = 0L)
        val progress = computeCardProgress(
            layout = null,
            storedEpisodes = 4,
            watched = PlaybackEpisodeKey(season = 1, episode = 4),
            airing = airing,
            reading = null,
        )
        assertEquals(CardProgressKind.AIRING, progress?.kind)
        assertEquals(WatchStatus.WATCHING, progress.autoWatchStatus())
    }
}
