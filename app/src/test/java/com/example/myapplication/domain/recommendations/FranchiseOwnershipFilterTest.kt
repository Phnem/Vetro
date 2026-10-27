package com.example.myapplication.domain.recommendations

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.FranchiseLink
import com.example.myapplication.network.FranchiseLinks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FranchiseOwnershipFilterTest {

    private val now = 1_800_000_000_000L

    /** Цепочка сезонов 1 → 2 → 3 → 4 → 5, плюс отдельная франшиза 100 без связей. MAL id = AniList id + 1000. */
    private val graph: Map<Int, FranchiseLinks> = buildMap {
        for (id in 1..5) {
            val edges = buildList {
                if (id > 1) add(FranchiseLink(id - 1, id - 1 + 1000, "PREQUEL"))
                if (id < 5) add(FranchiseLink(id + 1, id + 1 + 1000, "SEQUEL"))
            }
            put(id, FranchiseLinks(id, id + 1000, edges))
        }
        put(100, FranchiseLinks(100, 1100, emptyList()))
    }

    private class MemoryCache : FranchiseLinksCache {
        val byId = HashMap<Int, FranchiseLinks>()
        override suspend fun byAnilistId(id: Int, nowMillis: Long) = byId[id]
        override suspend fun byMalId(id: Int, nowMillis: Long) = byId.values.firstOrNull { it.malId == id }
        override suspend fun putAll(links: List<FranchiseLinks>, nowMillis: Long) {
            links.forEach { byId[it.anilistId] = it }
        }
    }

    private class Calls {
        var byAnilist = 0
        var byMal = 0
    }

    private fun filter(
        cache: MemoryCache = MemoryCache(),
        calls: Calls = Calls(),
        failing: Boolean = false,
    ) = FranchiseOwnershipFilter(
        fetchByAnilistIds = { ids ->
            calls.byAnilist++
            if (failing) Result.failure(java.io.IOException("offline")) else Result.success(ids.mapNotNull(graph::get))
        },
        fetchByMalIds = { ids ->
            calls.byMal++
            if (failing) Result.failure(java.io.IOException("offline"))
            else Result.success(graph.values.filter { it.malId in ids })
        },
        cache = cache,
        nowMillis = { now },
    )

    private fun owned(anilistId: Int, type: MediaType = MediaType.ANIME) = Anime(
        id = "lib$anilistId",
        title = "Library $anilistId",
        episodes = 12,
        rating = 8f,
        imageFileName = null,
        orderIndex = 0,
        dateAdded = now,
        mediaType = type,
        anilistId = anilistId,
    )

    private fun candidate(id: Int, source: String = "AniList") = PoolEntry(
        result = ApiSearchResult(
            title = "Candidate $id",
            altTitle = null,
            posterUrl = "p",
            episodes = 12,
            description = "",
            type = "TV",
            genres = emptyList(),
            rating = 80,
            source = source,
            categoryType = "ANIME",
            externalId = id.toString(),
        ),
        seedTitle = "seed",
        seedRating = 8f,
    )

    private fun ids(pool: List<PoolEntry>) = pool.map { it.result.externalId!!.toInt() }

    @Test
    fun `the next season of an owned title is dropped`() = runBlocking {
        val result = filter().dropOwned(listOf(candidate(2)), listOf(owned(1)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a season several steps away is found through the chain`() = runBlocking {
        val result = filter().dropOwned(listOf(candidate(4)), listOf(owned(1)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an unrelated franchise stays`() = runBlocking {
        val result = filter().dropOwned(listOf(candidate(100), candidate(3)), listOf(owned(1)))
        assertEquals(listOf(100), ids(result))
    }

    @Test
    fun `a Shikimori candidate known only by MAL id is matched too`() = runBlocking {
        val shikimori = candidate(1003, source = "Shikimori")
        val calls = Calls()
        val result = filter(calls = calls).dropOwned(listOf(shikimori, candidate(100)), listOf(owned(1)))
        assertEquals(listOf(100), ids(result))
        assertTrue(calls.byMal > 0)
    }

    @Test
    fun `a failing network never hides recommendations`() = runBlocking {
        val pool = listOf(candidate(2), candidate(100))
        assertEquals(pool, filter(failing = true).dropOwned(pool, listOf(owned(1))))
    }

    @Test
    fun `cached links are not requested again`() = runBlocking {
        val cache = MemoryCache()
        val calls = Calls()
        val pool = listOf(candidate(4), candidate(100))
        filter(cache, calls).dropOwned(pool, listOf(owned(1)))
        val firstRun = calls.byAnilist
        filter(cache, calls).dropOwned(pool, listOf(owned(1)))
        assertEquals(firstRun, calls.byAnilist)
    }

    @Test
    fun `manga in the collection does not hide anime`() = runBlocking {
        val pool = listOf(candidate(2))
        assertEquals(pool, filter().dropOwned(pool, listOf(owned(1, MediaType.MANGA))))
    }

    @Test
    fun `title stem catches the same title in another format`() {
        val library = listOf(
            owned(1).copy(title = "Hunter x Hunter", titleEn = "Hunter x Hunter"),
            owned(2).copy(title = "JoJo's Bizarre Adventure", titleEn = "JoJo's Bizarre Adventure"),
            owned(3).copy(title = "Frieren", titleEn = "Frieren"),
            owned(4).copy(title = "86", titleEn = "86"),
        )
        val pool = listOf(
            candidate(11).let { it.copy(result = it.result.copy(title = "Hunter x Hunter (2011)")) },
            candidate(12).let { it.copy(result = it.result.copy(title = "JoJo's Bizarre Adventure (TV)")) },
            candidate(13).let { it.copy(result = it.result.copy(title = "Frieren Season 2")) },
            candidate(14).let { it.copy(result = it.result.copy(title = "86 Season 2")) },
            candidate(15).let { it.copy(result = it.result.copy(title = "Hunter Killer")) },
        )
        // Основа «86» короче трёх символов и не используется: иначе совпали бы разные тайтлы.
        assertEquals(listOf(14, 15), ids(RecommendationFilter().filter(pool, library)))
    }
}
