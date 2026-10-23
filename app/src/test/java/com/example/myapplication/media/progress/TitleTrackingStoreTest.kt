package com.example.myapplication.media.progress

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TitleTrackingStoreTest {
    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    private val data = MemoryStore()
    private val ratings = EpisodeRatingStore(data)
    private val tracking = TitleTrackingStore(data, ratings)
    private val id = "title-1"
    private val day = LocalDate.of(2026, 10, 10)

    @Test fun `auto is the default and manual without episode ratings changes nothing`() = runBlocking {
        assertFalse(tracking.setManualRating(id, true))
        val state = tracking.observe(id).first()
        assertFalse(state.preferences.manualRating)
        assertEquals(6.5f, state.rating(6.5f), 0.001f)
        assertNull(state.episodeAverage)
    }

    @Test fun `average counts only rated episodes equally across all seasons`() = runBlocking {
        ratings.setRating(id, 1, 1, 7.1f)
        ratings.setRating(id, 1, 2, 8.2f)
        ratings.setRating(id, 2, 1, 10f)
        ratings.setRating(id, 2, 2, 5f)
        ratings.setRating(id, 2, 2, 0f)
        assertTrue(tracking.setManualRating(id, true))
        assertEquals((7.1f + 8.2f + 10f) / 3f, tracking.observe(id).first().rating(6f), 0.001f)
        ratings.setRating(id, 1, 1, 4.1f)
        assertEquals((4.1f + 8.2f + 10f) / 3f, tracking.observe(id).first().rating(6f), 0.001f)
        tracking.setManualRating(id, false)
        assertEquals(6f, tracking.observe(id).first().rating(6f), 0.001f)
    }

    @Test fun `removing the last rating persists auto and a notice even after rating again`() = runBlocking {
        ratings.setRating(id, 1, 1, 8f)
        tracking.setManualRating(id, true)
        ratings.setRating(id, 1, 1, 0f)
        var state = tracking.observe(id).first()
        assertFalse(state.preferences.manualRating)
        assertTrue(state.preferences.ratingResetNotice)
        assertEquals(6f, state.rating(6f), 0.001f)
        ratings.setRating(id, 1, 2, 9f)
        state = TitleTrackingStore(data, ratings).observe(id).first()
        assertFalse(state.preferences.manualRating)
        tracking.acknowledgeRatingReset(id)
        assertFalse(tracking.observe(id).first().preferences.ratingResetNotice)
    }

    @Test fun `remote deletion also resets manual and remote updates recalculate it`() = runBlocking {
        ratings.setRating(id, 1, 1, 8f)
        tracking.setManualRating(id, true)
        val key = PlaybackEpisodeKey(1, 1)
        val newer = ratings.ratingsFlow(id).first().getValue(key).updatedAt + 1_000
        ratings.mergeRemote(mapOf(id to mapOf(key to EpisodeRating(95, newer))))
        assertEquals(9.5f, tracking.observe(id).first().rating(6f), 0.001f)
        ratings.mergeRemote(mapOf(id to mapOf(key to EpisodeRating(0, newer + 1))))
        assertFalse(tracking.observe(id).first().preferences.manualRating)
        assertTrue(tracking.observe(id).first().preferences.ratingResetNotice)
    }

    @Test fun `title choices and averages do not leak into other titles`() = runBlocking {
        ratings.setRating(id, 1, 1, 9f)
        tracking.setManualRating(id, true)
        val all = tracking.observeTitles(listOf(id, "other")).first()
        assertEquals(9f, all.getValue(id).rating(6f), 0.001f)
        assertEquals(6f, all.getValue("other").rating(6f), 0.001f)
        assertNull(all.getValue("other").episodeAverage)
    }

    @Test fun `auto records first opens only and persists across store recreation`() = runBlocking {
        assertFalse(tracking.recordOpened(id, false, false, day.minusDays(1)))
        assertNull(tracking.observe(id).first().preferences.startedOn)
        assertFalse(tracking.recordOpened(id, true, false, day))
        assertFalse(tracking.recordOpened(id, true, false, day.plusDays(2)))
        assertTrue(tracking.recordOpened(id, false, true, day.plusDays(10)))
        assertFalse(tracking.recordOpened(id, false, true, day.plusDays(15)))
        val preferences = TitleTrackingStore(data, ratings).observe(id).first().preferences
        assertEquals(day, preferences.startedOn)
        assertEquals(day.plusDays(10), preferences.finishedOn)
    }

    @Test fun `single episode can start and finish on the same day`() = runBlocking {
        assertTrue(tracking.recordOpened(id, true, true, day))
        assertEquals(day, tracking.observe(id).first().preferences.startedOn)
        assertEquals(day, tracking.observe(id).first().preferences.finishedOn)
    }

    @Test fun `manual dates block automatic writes and auto restores original interval`() = runBlocking {
        tracking.recordOpened(id, true, false, day)
        val start = day.minusDays(20)
        val finish = day.minusDays(10)
        tracking.saveManualDates(id, start, finish)
        assertFalse(tracking.recordOpened(id, false, true, day.plusDays(3)))
        var preferences = tracking.observe(id).first().preferences
        assertEquals(start, preferences.startedOn)
        assertEquals(finish, preferences.finishedOn)
        tracking.useAutoDates(id)
        preferences = tracking.observe(id).first().preferences
        assertFalse(preferences.manualDates)
        assertEquals(day, preferences.startedOn)
        assertNull(preferences.finishedOn)
        assertTrue(tracking.recordOpened(id, false, true, day.plusDays(5)))
    }

    @Test fun `manual start without finish is supported and inverted dates are rejected`() = runBlocking {
        tracking.saveManualDates(id, day, null)
        assertTrue(tracking.observe(id).first().preferences.manualDates)
        assertNull(tracking.observe(id).first().preferences.finishedOn)
        assertTrue(runCatching { tracking.saveManualDates(id, day, day.minusDays(1)) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(day, tracking.observe(id).first().preferences.startedOn)
        assertNull(tracking.observe(id).first().preferences.finishedOn)
    }

    @Test fun `opening first after final or moving clock backwards cannot create inverted dates`() = runBlocking {
        tracking.recordOpened(id, false, true, day)
        tracking.recordOpened(id, true, false, day.plusDays(1))
        assertNull(tracking.observe(id).first().preferences.startedOn)
        tracking.recordOpened("another", true, false, day)
        assertFalse(tracking.recordOpened("another", false, true, day.minusDays(1)))
        assertNull(tracking.observe("another").first().preferences.finishedOn)
    }
}
