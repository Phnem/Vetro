package com.example.myapplication.data.models

import com.example.myapplication.ui.home.episodesFromSlider
import com.example.myapplication.ui.home.episodesToSlider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterTest {
    private val thisYear = LibraryFilter.currentYear()

    private fun anime(
        episodes: Int = 12,
        rating: Float = 7f,
        year: Int? = 2015,
        status: WatchStatus? = null,
        mediaType: MediaType = MediaType.ANIME,
    ) = Anime(
        id = "a",
        title = "T",
        episodes = episodes,
        rating = rating,
        imageFileName = null,
        orderIndex = 0,
        dateAdded = 0,
        mediaType = mediaType,
        watchStatus = status,
        releaseYear = year,
    )

    @Test
    fun `empty filter matches everything including unknown year and unrated`() {
        val filter = LibraryFilter()

        assertFalse(filter.isActive)
        assertTrue(filter.matchesRanges(anime(year = null, rating = 0f)))
    }

    @Test
    fun `ranges stretched to the slider edges normalize to no restriction`() {
        val stretched = LibraryFilter(
            yearRange = LibraryFilter.YEAR_MIN..thisYear,
            ratingRange = 0f..10f,
            episodesRange = 1..2000,
        ).normalized()

        assertNull(stretched.yearRange)
        assertNull(stretched.ratingRange)
        assertNull(stretched.episodesRange)
        assertFalse(stretched.isActive)
    }

    @Test
    fun `narrowed year skips unknown and zero years`() {
        val filter = LibraryFilter(yearRange = 2010..2020).normalized()

        assertTrue(filter.matchesRanges(anime(year = 2015)))
        assertFalse(filter.matchesRanges(anime(year = 2005)))
        assertFalse(filter.matchesRanges(anime(year = null)))
        assertFalse(filter.matchesRanges(anime(year = 0)))
    }

    @Test
    fun `lower year edge at the minimum does not drop old titles`() {
        val filter = LibraryFilter(yearRange = LibraryFilter.YEAR_MIN..2000).normalized()

        assertTrue(filter.matchesRanges(anime(year = 1937)))
        assertFalse(filter.matchesRanges(anime(year = 2001)))
    }

    @Test
    fun `upper episodes edge at the maximum means two thousand and more`() {
        val filter = LibraryFilter(episodesRange = 100..LibraryFilter.EPISODES_MAX).normalized()

        assertTrue(filter.matchesRanges(anime(episodes = 2500)))
        assertFalse(filter.matchesRanges(anime(episodes = 99)))
    }

    @Test
    fun `rating range compares by the stored tenth`() {
        val filter = LibraryFilter(ratingRange = 7.3f..8.1f).normalized()

        assertTrue(filter.matchesRanges(anime(rating = 7.3f)))
        assertTrue(filter.matchesRanges(anime(rating = 8.1f)))
        assertFalse(filter.matchesRanges(anime(rating = 7.2f)))
        assertFalse(filter.matchesRanges(anime(rating = 0f)))
    }

    @Test
    fun `status filter uses the progress based default when the user chose nothing`() {
        val watching = LibraryFilter(statuses = setOf(WatchStatus.WATCHING))
        val completed = LibraryFilter(statuses = setOf(WatchStatus.COMPLETED))

        assertTrue(watching.matchesStatus(anime(), auto = WatchStatus.WATCHING))
        assertFalse(watching.matchesStatus(anime(), auto = WatchStatus.COMPLETED))
        assertTrue(completed.matchesStatus(anime(), auto = WatchStatus.COMPLETED))
        // Выбор пользователя важнее прогресса.
        assertFalse(watching.matchesStatus(anime(status = WatchStatus.DROPPED), auto = WatchStatus.WATCHING))
        assertTrue(LibraryFilter(statuses = setOf(WatchStatus.DROPPED)).matchesStatus(anime(status = WatchStatus.DROPPED), auto = WatchStatus.WATCHING))
        // Нет фильтра по статусу — подходит любой.
        assertTrue(LibraryFilter().matchesStatus(anime(), auto = null))
    }

    @Test
    fun `without a choice and without progress data the status reads as completed`() {
        assertEquals(WatchStatus.COMPLETED, anime().effectiveWatchStatus(auto = null))
        assertEquals(WatchStatus.WATCHING, anime().effectiveWatchStatus(auto = WatchStatus.WATCHING))
        assertEquals(WatchStatus.ON_HOLD, anime(status = WatchStatus.ON_HOLD).effectiveWatchStatus(auto = WatchStatus.WATCHING))
    }

    @Test
    fun `persisted status parsing is lenient`() {
        assertEquals(WatchStatus.ON_HOLD, WatchStatus.fromPersisted("on_hold"))
        assertNull(WatchStatus.fromPersisted(null))
        assertNull(WatchStatus.fromPersisted(""))
        assertNull(WatchStatus.fromPersisted("whatever"))
    }

    @Test
    fun `episodes slider reaches both ends and round-trips typical counts`() {
        assertEquals(1, episodesFromSlider(0f))
        assertEquals(2000, episodesFromSlider(1f))
        listOf(1, 12, 24, 26, 50, 100, 500, 2000).forEach { count ->
            assertEquals(count, episodesFromSlider(episodesToSlider(count)))
        }
    }

    @Test
    fun `episodes slider keeps typical short series in a usable stretch of the track`() {
        // Линейная шкала уложила бы 24 серии в первые 1,2% хода; здесь это заметный кусок.
        assertTrue(episodesToSlider(24) > 0.2f)
    }
}
