package com.example.myapplication.domain.recommendations

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.movie.TmdbIdQuery
import com.example.myapplication.network.movie.TmdbIdResolution
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryTmdbIdBackfillTest {

    private val now = 1_800_000_000_000L

    private fun item(
        id: String,
        title: String = id,
        type: MediaType = MediaType.MOVIE,
        tmdbId: Int? = null,
        year: Int? = 2014,
        status: WatchStatus? = null,
        titleEn: String? = null,
    ) = Anime(
        id = id,
        title = title,
        episodes = 1,
        rating = 8f,
        imageFileName = null,
        orderIndex = 0,
        dateAdded = now,
        mediaType = type,
        tmdbId = tmdbId,
        releaseYear = year,
        watchStatus = status,
        titleEn = titleEn,
    )

    private class MemoryLedger : TmdbIdMissLedger {
        val misses = HashMap<String, Pair<String, Long>>()
        override suspend fun isRecentMiss(animeId: String, fingerprint: String, nowMillis: Long): Boolean {
            val miss = misses[animeId] ?: return false
            return miss.first == fingerprint && nowMillis - miss.second < LibraryTmdbIdBackfill.MISS_TTL_MILLIS
        }

        override suspend fun recordMiss(animeId: String, fingerprint: String, nowMillis: Long, reason: String) {
            misses[animeId] = fingerprint to nowMillis
        }
    }

    private fun backfill(
        ledger: MemoryLedger = MemoryLedger(),
        persisted: MutableMap<String, Int> = HashMap(),
        clock: () -> Long = { now },
        resolve: suspend (TmdbIdQuery) -> TmdbIdResolution,
    ) = LibraryTmdbIdBackfill(resolve, { id, tmdb -> persisted[id] = tmdb }, ledger, clock, pauseMillis = 0)

    private fun matched(id: Int) = TmdbIdResolution.Matched(id, 1.0, "t", 2014)

    @Test
    fun `fills missing id and persists it`() = runBlocking {
        val persisted = HashMap<String, Int>()
        val result = backfill(persisted = persisted) { matched(157336) }.run(listOf(item("a", "Interstellar")))
        assertEquals(157336, result.single().tmdbId)
        assertEquals(157336, persisted["a"])
    }

    @Test
    fun `query carries title variants year and type`() = runBlocking {
        val queries = mutableListOf<TmdbIdQuery>()
        backfill { queries += it; TmdbIdResolution.NoMatch }
            .run(listOf(item("m", "Фильм", titleEn = "Movie", type = MediaType.MOVIE, year = 2001), item("s", type = MediaType.SERIES)))
        assertEquals(listOf("Movie", "Фильм"), queries[0].titles)
        assertEquals(2001, queries[0].year)
        assertEquals(AppContentType.MOVIE, queries[0].contentType)
        assertEquals(AppContentType.SERIES, queries[1].contentType)
    }

    @Test
    fun `skips anime books dropped and titles that already have an id`() = runBlocking {
        var calls = 0
        val library = listOf(
            item("anime", type = MediaType.ANIME),
            item("known", tmdbId = 5),
            item("dropped", status = WatchStatus.DROPPED),
        )
        backfill { calls++; matched(1) }.run(library)
        assertEquals(0, calls)
    }

    @Test
    fun `misses and ambiguous titles are remembered and not searched again`() = runBlocking {
        val ledger = MemoryLedger()
        var calls = 0
        val library = listOf(item("none"), item("twin"))
        val engine = backfill(ledger = ledger) { query ->
            calls++
            if (query.titles.first() == "none") TmdbIdResolution.NoMatch else TmdbIdResolution.Ambiguous
        }
        val first = engine.run(library)
        engine.run(library)
        assertEquals(2, calls)
        assertNull(first.first { it.id == "none" }.tmdbId)
        assertNull(first.first { it.id == "twin" }.tmdbId)
    }

    @Test
    fun `a changed title invalidates the remembered miss`() = runBlocking {
        val ledger = MemoryLedger()
        var calls = 0
        val engine = backfill(ledger = ledger) { calls++; TmdbIdResolution.NoMatch }
        engine.run(listOf(item("x", "Old title")))
        engine.run(listOf(item("x", "New title")))
        assertEquals(2, calls)
    }

    @Test
    fun `an old miss is retried after the ttl`() = runBlocking {
        val ledger = MemoryLedger()
        var clock = now
        var calls = 0
        val engine = backfill(ledger = ledger, clock = { clock }) { calls++; TmdbIdResolution.NoMatch }
        engine.run(listOf(item("x")))
        clock += LibraryTmdbIdBackfill.MISS_TTL_MILLIS + 1
        engine.run(listOf(item("x")))
        assertEquals(2, calls)
    }

    @Test
    fun `unavailable stops the run and records nothing`() = runBlocking {
        val ledger = MemoryLedger()
        var calls = 0
        backfill(ledger = ledger) { calls++; TmdbIdResolution.Unavailable }
            .run(listOf(item("a"), item("b"), item("c")))
        assertEquals(1, calls)
        assertEquals(0, ledger.misses.size)
    }

    @Test
    fun `a failing lookup does not break the run`() = runBlocking {
        val library = listOf(item("a"))
        val result = backfill { error("boom") }.run(library)
        assertEquals(library, result)
    }

    @Test
    fun `at most the limit of titles is looked up per run`() = runBlocking {
        var calls = 0
        backfill { calls++; TmdbIdResolution.NoMatch }
            .run((1..30).map { item("t$it") }, limit = 12)
        assertEquals(12, calls)
    }
}
