package com.example.myapplication.media.progress

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodePlaybackStoreMigrationTest {

    /** DataStore в памяти: файловый на Windows-JVM не умеет переименовать tmp поверх файла. */
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun dataStore(name: String): DataStore<Preferences> = MemoryDataStore()

    @Test
    fun `progress and quality move out of settings once, other settings stay`() = runBlocking {
        val settings = dataStore("settings")
        val playback = dataStore("playback")

        // Прогресс, записанный прежней версией в настройки.
        EpisodePlaybackStore(settings).apply {
            saveProgress("anime-1", season = 1, episode = 3, positionMs = 600_000, durationMs = 1_400_000)
            savePreferredQuality("anime-1", 1080)
        }
        val themeKey = stringPreferencesKey("theme")
        settings.edit { it[themeKey] = "DARK" }

        val store = EpisodePlaybackStore(playback, legacySettings = settings)
        val progress = store.episodeFlow("anime-1", 1, 3).first()
        assertEquals(600_000L, progress?.positionMs)
        assertEquals(1080, store.preferredQualityFlow("anime-1").first())

        val left = settings.data.first().asMap().keys.map { it.name }
        assertEquals(listOf("theme"), left)
        assertTrue(playback.data.first().asMap().keys.any { it.name.startsWith("episode_progress_") })
    }

    @Test
    fun `new writes go to the playback store only`() = runBlocking {
        val settings = dataStore("settings")
        val playback = dataStore("playback")
        val store = EpisodePlaybackStore(playback, legacySettings = settings)

        store.saveProgress("anime-2", season = 2, episode = 1, positionMs = 90_000, durationMs = 1_200_000)

        assertTrue(settings.data.first().asMap().isEmpty())
        assertEquals(90_000L, store.episodeFlow("anime-2", 2, 1).first()?.positionMs)
        assertNull(store.episodeFlow("anime-2", 2, 2).first())
    }
}
