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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeRatingStoreTest {

    /** DataStore в памяти: файловый на Windows-JVM не умеет переименовать tmp поверх файла. */
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    private val key = PlaybackEpisodeKey(season = 2, episode = 5)

    @Test
    fun `rating is stored times ten and read back as ten point value`() = runBlocking {
        val store = EpisodeRatingStore(MemoryDataStore())

        store.setRating("anime-1", season = 2, episode = 5, rating = 8.4f)

        val saved = store.ratingsFlow("anime-1").first()[key]
        assertEquals(84, saved?.stored)
        assertEquals(8.4f, saved?.display ?: -1f, 0.001f)
        assertNull(store.ratingsFlow("anime-2").first()[key])
    }

    @Test
    fun `clearing a rating keeps a zero record so the removal can sync`() = runBlocking {
        val store = EpisodeRatingStore(MemoryDataStore())
        store.setRating("anime-1", 2, 5, 7f)

        store.setRating("anime-1", 2, 5, 0f)

        val cleared = store.ratingsFlow("anime-1").first()[key]
        assertEquals(0, cleared?.stored)
        assertEquals(0f, cleared?.display ?: -1f, 0f)
    }

    @Test
    fun `clearing a rating that never existed writes nothing`() = runBlocking {
        val store = EpisodeRatingStore(MemoryDataStore())

        store.setRating("anime-1", 2, 5, 0f)

        assertEquals(emptyMap<PlaybackEpisodeKey, EpisodeRating>(), store.ratingsFlow("anime-1").first())
    }

    @Test
    fun `the same rating twice does not move updatedAt`() = runBlocking {
        val store = EpisodeRatingStore(MemoryDataStore())
        store.setRating("anime-1", 2, 5, 6.5f)
        val first = store.snapshotAll(listOf("anime-1")).getValue("anime-1").getValue(key).updatedAt

        Thread.sleep(5)
        store.setRating("anime-1", 2, 5, 6.5f)

        val second = store.snapshotAll(listOf("anime-1")).getValue("anime-1").getValue(key).updatedAt
        assertEquals(first, second)
    }

    @Test
    fun `remote rating wins only when it is newer than the local one`() = runBlocking {
        val store = EpisodeRatingStore(MemoryDataStore())
        store.setRating("anime-1", 2, 5, 4f)
        val localAt = store.snapshotAll(listOf("anime-1")).getValue("anime-1").getValue(key).updatedAt

        val stale = mapOf("anime-1" to mapOf(key to EpisodeRating(stored = 90, updatedAt = localAt - 1_000)))
        assertEquals(0, store.mergeRemote(stale))
        assertEquals(40, store.ratingsFlow("anime-1").first()[key]?.stored)

        val fresh = mapOf("anime-1" to mapOf(key to EpisodeRating(stored = 90, updatedAt = localAt + 1_000)))
        assertEquals(1, store.mergeRemote(fresh))
        assertEquals(90, store.ratingsFlow("anime-1").first()[key]?.stored)
    }

    @Test
    fun `remote rating for an unknown title is applied`() = runBlocking {
        val store = EpisodeRatingStore(MemoryDataStore())

        val remote = mapOf("anime-9" to mapOf(key to EpisodeRating(stored = 55, updatedAt = 1_000)))

        assertEquals(1, store.mergeRemote(remote))
        assertEquals(55, store.ratingsFlow("anime-9").first()[key]?.stored)
    }
}
