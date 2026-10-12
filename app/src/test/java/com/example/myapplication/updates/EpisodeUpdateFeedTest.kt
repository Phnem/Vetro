package com.example.myapplication.updates

import com.example.myapplication.data.models.AnimeUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeUpdateFeedTest {

    private fun update(from: Int, to: Int) =
        AnimeUpdate(animeId = "a", title = "Title", currentEpisodes = from, newEpisodes = to, source = "AniList")

    @Test
    fun `anime release is published as a notification`() = runBlocking {
        var stored = emptyList<AnimeUpdate>()

        val fresh = publishEpisodeUpdates(
            detected = listOf(update(from = 2, to = 3)),
            getExisting = { emptyList() },
            setUpdates = { stored = it },
        )

        assertEquals(listOf(update(from = 2, to = 3)), fresh)
        assertEquals(fresh, stored)
    }

    @Test
    fun `series still apply the released count`() = runBlocking {
        val applied = mutableListOf<AnimeUpdate>()

        publishEpisodeUpdates(
            detected = listOf(update(from = 2, to = 3)),
            getExisting = { emptyList() },
            applyEpisodes = { applied += it },
            setUpdates = {},
        )

        assertEquals(listOf(update(from = 2, to = 3)), applied)
    }

    @Test
    fun `a notification the user already read does not come back while the counter stays put`() = runBlocking {
        var stored: List<AnimeUpdate>? = null

        val fresh = publishEpisodeUpdates(
            detected = listOf(update(from = 2, to = 3)),
            getExisting = { emptyList() },
            getAcknowledged = { mapOf("a" to 3) },
            setUpdates = { stored = it },
        )

        assertTrue(fresh.isEmpty())
        assertEquals(emptyList<AnimeUpdate>(), stored)
    }

    @Test
    fun `an episode newer than the acknowledged one is announced again`() = runBlocking {
        val fresh = publishEpisodeUpdates(
            detected = listOf(update(from = 2, to = 4)),
            getExisting = { emptyList() },
            getAcknowledged = { mapOf("a" to 3) },
            setUpdates = {},
        )

        assertEquals(listOf(update(from = 2, to = 4)), fresh)
    }
}
