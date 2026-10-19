package com.example.myapplication.updates

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.domain.seasons.SeasonEpisodesEntry
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.tmdb.SeriesEpisodeState
import com.example.myapplication.network.tmdb.SeriesStatus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock

class SeriesEpisodeCheckUseCaseTest {

    @Test
    fun `legacy known count normalizes silently then next released episode creates update`() = runBlocking {
        val store = FakeSeriesEpisodeStore(series(episodes = 12, tmdbId = 10))
        val source = FakeSeriesEpisodeSource(resolvedTmdbId = 10, releasedEpisodes = 7)
        val useCase = SeriesEpisodeCheckUseCase(store, source, Clock.systemUTC())

        val first = useCase.detectAndStore()

        assertTrue(first.isEmpty())
        assertEquals(7, store.anime.episodes)
        assertTrue(store.normalized)
        assertTrue(store.storedUpdates.isEmpty())

        source.releasedEpisodes = 8
        val second = useCase.detectAndStore()

        assertEquals(listOf(AnimeUpdate("series", "Dark", 7, 8, "TMDB")), second)
        assertEquals(8, store.anime.episodes)
        assertEquals(second, store.storedUpdates)
    }

    @Test
    fun `normalized series with unchanged released count stays untouched`() = runBlocking {
        val store = FakeSeriesEpisodeStore(series(episodes = 7, tmdbId = 10), normalized = true)
        val source = FakeSeriesEpisodeSource(resolvedTmdbId = 10, releasedEpisodes = 7)

        val updates = SeriesEpisodeCheckUseCase(store, source, Clock.systemUTC()).detectAndStore()

        assertTrue(updates.isEmpty())
        assertEquals(7, store.anime.episodes)
        assertTrue(store.storedUpdates.isEmpty())
    }

    @Test
    fun `new series is born normalized and reports first released growth`() = runBlocking {
        val store = FakeSeriesEpisodeStore(series(episodes = 1, tmdbId = 10), normalized = true)
        val source = FakeSeriesEpisodeSource(resolvedTmdbId = 10, releasedEpisodes = 2)

        val updates = SeriesEpisodeCheckUseCase(store, source, Clock.systemUTC()).detectAndStore()

        assertEquals(listOf(AnimeUpdate("series", "Dark", 1, 2, "TMDB")), updates)
        assertEquals(2, store.anime.episodes)
    }

    @Test
    fun `replacement tmdb id resolved after stale id is persisted`() = runBlocking {
        val store = FakeSeriesEpisodeStore(series(episodes = 7, tmdbId = 999), normalized = true)
        val source = FakeSeriesEpisodeSource(resolvedTmdbId = 10, releasedEpisodes = 8)

        SeriesEpisodeCheckUseCase(store, source, Clock.systemUTC()).detectAndStore()

        assertEquals(10, store.anime.tmdbId)
        assertEquals(8, store.anime.episodes)
    }

    @Test
    fun `episode state failure neither normalizes nor changes episodes`() = runBlocking {
        val store = FakeSeriesEpisodeStore(series(episodes = 12, tmdbId = 10))
        val source = FakeSeriesEpisodeSource(resolvedTmdbId = 10, releasedEpisodes = 7).apply {
            failEpisodeState = true
        }

        val updates = SeriesEpisodeCheckUseCase(store, source, Clock.systemUTC()).detectAndStore()

        assertTrue(updates.isEmpty())
        assertEquals(12, store.anime.episodes)
        assertTrue(!store.normalized)
    }

    @Test
    fun `series event merges into shared feed without deleting anime event`() = runBlocking {
        val store = FakeSeriesEpisodeStore(series(episodes = 7, tmdbId = 10), normalized = true)
        val animeEvent = AnimeUpdate("anime", "Frieren", 27, 28, "AniList")
        store.storedUpdates = listOf(animeEvent)
        val source = FakeSeriesEpisodeSource(resolvedTmdbId = 10, releasedEpisodes = 8)

        val fresh = SeriesEpisodeCheckUseCase(store, source, Clock.systemUTC()).detectAndStore()

        assertEquals(listOf(fresh.single(), animeEvent), store.storedUpdates)
    }

    @Test
    fun `ensureSeasonsResolved falls back to TVmaze when TMDB fails`() = runBlocking {
        val house = series(episodes = 176, tmdbId = 1408)
        val store = FakeSeriesEpisodeStore(house, normalized = true)
        val fallbackEntry = SeasonEpisodesEntry(
            animeId = house.id,
            seasons = (1..8).map { SeasonInfo(seasonNumber = it, episodes = 22, source = "TVmaze") },
            complete = true,
            resolvedAt = 1000L,
        )
        val source = FakeSeriesEpisodeSource(resolvedTmdbId = 1408, releasedEpisodes = 176).apply {
            failEpisodeState = true
            fallback = fallbackEntry
        }

        SeriesEpisodeCheckUseCase(store, source, Clock.systemUTC()).ensureSeasonsResolved(house)

        assertNotNull(store.seasonsEntry)
        assertEquals(8, store.seasonsEntry?.seasons?.size)
        assertEquals("TVmaze", store.seasonsEntry?.seasons?.first()?.source)
    }

    private fun series(episodes: Int, tmdbId: Int?) = Anime(
        id = "series",
        title = "Dark",
        episodes = episodes,
        rating = 8f,
        imageFileName = null,
        orderIndex = 0,
        dateAdded = 0,
        tags = persistentListOf(),
        categoryType = "SERIES",
        mediaType = MediaType.SERIES,
        tmdbId = tmdbId,
    )
}

private class FakeSeriesEpisodeStore(
    initial: Anime,
    var normalized: Boolean = false,
) : SeriesEpisodeStore {
    var anime = initial
    var storedUpdates: List<AnimeUpdate> = emptyList()
    var seasonsEntry: SeasonEpisodesEntry? = null

    override fun getAllSeries(): List<Anime> = listOf(anime)
    override fun isNormalized(animeId: String): Boolean = normalized
    override fun getUpdates(): List<AnimeUpdate> = storedUpdates
    override fun getSeasons(animeId: String): SeasonEpisodesEntry? = seasonsEntry?.takeIf { it.animeId == animeId }

    override suspend fun normalize(anime: Anime, releasedEpisodes: Int) {
        this.anime = anime.copy(episodes = releasedEpisodes)
        normalized = true
    }

    override suspend fun setTmdbId(animeId: String, tmdbId: Int) {
        anime = anime.copy(tmdbId = tmdbId)
    }

    override suspend fun applyEpisodes(animeId: String, releasedEpisodes: Int) {
        anime = anime.copy(episodes = releasedEpisodes)
    }

    override suspend fun setUpdates(updates: List<AnimeUpdate>) {
        this.storedUpdates = updates
    }

    override suspend fun setSeasons(entry: SeasonEpisodesEntry) {
        this.seasonsEntry = entry
    }
}

private class FakeSeriesEpisodeSource(
    private val resolvedTmdbId: Int?,
    var releasedEpisodes: Int,
) : SeriesEpisodeSource {
    var failEpisodeState: Boolean = false
    var fallback: SeasonEpisodesEntry? = null

    override suspend fun resolveTmdbId(anime: Anime): Int? = resolvedTmdbId

    override suspend fun episodeState(tmdbId: Int, clock: Clock): LookupResult<SeriesEpisodeState> =
        if (failEpisodeState) {
            LookupResult.Failure(IllegalStateException("offline"), retryable = true)
        } else LookupResult.Found(
            SeriesEpisodeState(
                releasedEpisodes = releasedEpisodes,
                knownEpisodes = 12,
                status = SeriesStatus.ONGOING,
            )
        )

    override suspend fun fallbackSeasons(anime: Anime, clock: Clock): SeasonEpisodesEntry? = fallback
}
