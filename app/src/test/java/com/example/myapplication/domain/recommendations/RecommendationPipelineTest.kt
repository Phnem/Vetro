package com.example.myapplication.domain.recommendations

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.ExternalIds
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_800_000_000_000L

private fun lib(
    id: String,
    title: String = id,
    rating: Float = 8f,
    tags: List<String> = emptyList(),
    type: MediaType = MediaType.ANIME,
    anilistId: Int? = id.hashCode().let { if (type == MediaType.ANIME) kotlin.math.abs(it) else null },
    tmdbId: Int? = if (type == MediaType.ANIME) null else kotlin.math.abs(id.hashCode()),
    status: WatchStatus? = null,
    favorite: Boolean = false,
    added: Long = NOW,
) = Anime(
    id = id,
    title = title,
    episodes = 12,
    rating = rating,
    imageFileName = null,
    orderIndex = 0,
    dateAdded = added,
    isFavorite = favorite,
    tags = tags.toImmutableList(),
    anilistId = anilistId,
    mediaType = type,
    tmdbId = tmdbId,
    watchStatus = status,
)

private fun entry(
    title: String,
    type: ContentType = ContentType.ANIME,
    source: String = "AniList",
    externalId: String? = null,
    seedKey: String = "seed-a",
    seedTitle: String = "Seed A",
    seedRating: Float = 8f,
    linkWeight: Int? = 20,
    genres: List<String> = emptyList(),
    rating: Int? = 80,
    year: Int? = null,
    tmdb: Int? = null,
    votes: Int? = null,
    signal: SignalKind = SignalKind.RECOMMENDATION,
    rank: Int? = 0,
) = PoolEntry(
    result = ApiSearchResult(
        title = title,
        altTitle = null,
        posterUrl = "https://img/$title.jpg",
        episodes = 12,
        description = "",
        type = "",
        genres = genres,
        rating = rating,
        source = source,
        categoryType = if (type == ContentType.ANIME) "ANIME" else "MOVIE",
        externalId = externalId,
        seasonYear = year,
        externalIds = ExternalIds(tmdb = tmdb),
        linkWeight = linkWeight,
        voteCount = votes,
    ),
    seedTitle = seedTitle,
    seedRating = seedRating,
    contentType = type,
    seedKey = seedKey,
    signal = signal,
    rank = rank,
)

class SeedSelectorTest {

    @Test
    fun `dropped and disliked titles are never seeds`() {
        val library = listOf(
            lib("good", rating = 9f),
            lib("dropped", rating = 0f, status = WatchStatus.DROPPED),
            lib("disliked", rating = 3f),
        )
        val seeds = SeedSelector.select(library, ContentType.ANIME, NOW)
        assertEquals(listOf("good"), seeds.map { it.title })
    }

    @Test
    fun `seeds are diverse instead of six copies of one genre`() {
        val action = (1..8).map { lib("action$it", rating = 9f, tags = listOf("Action", "Shounen")) }
        val others = listOf(
            lib("romance", rating = 8f, tags = listOf("Romance", "Drama")),
            lib("scifi", rating = 8f, tags = listOf("Sci-Fi", "Mecha")),
        )
        val seeds = SeedSelector.select(action + others, ContentType.ANIME, NOW, limit = 6).map { it.title }
        assertTrue(seeds.contains("romance"))
        assertTrue(seeds.contains("scifi"))
        assertEquals(6, seeds.size)
    }

    @Test
    fun `seed count scales between six and ten`() {
        assertEquals(6, SeedSelector.limitFor(3))
        assertEquals(8, SeedSelector.limitFor(20))
        assertEquals(10, SeedSelector.limitFor(500))
    }

    @Test
    fun `movies and series seeds need a tmdb id and never mix types`() {
        val library = listOf(
            lib("film", type = MediaType.MOVIE, tmdbId = 550),
            lib("show", type = MediaType.SERIES, tmdbId = 550),
            lib("no-id", type = MediaType.MOVIE, tmdbId = null),
        )
        val movies = SeedSelector.select(library, ContentType.MOVIE, NOW)
        val shows = SeedSelector.select(library, ContentType.TV, NOW)
        assertEquals(listOf("MOVIE:tmdb:550"), movies.map { it.key })
        assertEquals(listOf("TV:tmdb:550"), shows.map { it.key })
    }
}

class CandidateMergerTest {

    @Test
    fun `movie 123 and tv 123 are different entities`() {
        val pool = listOf(
            entry("Same", ContentType.MOVIE, "TMDB", "123", year = 2020, tmdb = 123),
            entry("Same", ContentType.TV, "TMDB", "123", year = 2020, tmdb = 123),
        )
        val groups = CandidateMerger.group(pool)
        assertEquals(2, groups.size)
        assertEquals(setOf("MOVIE:tmdb:123", "TV:tmdb:123"), groups.map { it.key }.toSet())
    }

    @Test
    fun `same tmdb id from two sources merges and keeps both pieces of evidence`() {
        val pool = listOf(
            entry("Dune", ContentType.MOVIE, "TMDB", "438631", seedKey = "s1", year = 2021, tmdb = 438631),
            entry("Dune Part One", ContentType.MOVIE, "Trakt", "99", seedKey = "s2", year = 2021, tmdb = 438631),
        )
        val groups = CandidateMerger.group(pool)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().entries.size)
    }

    @Test
    fun `remake with the same title but another year stays separate`() {
        val pool = listOf(
            entry("Dune", ContentType.MOVIE, "TMDB", "841", year = 1984, tmdb = 841),
            entry("Dune", ContentType.MOVIE, "Trakt", "7", year = 2021, tmdb = null),
        )
        assertEquals(2, CandidateMerger.group(pool).size)
    }

    @Test
    fun `anime duplicates across sources merge by title`() {
        val pool = listOf(
            entry("Cowboy Bebop", source = "AniList", externalId = "1"),
            entry("Cowboy Bebop", source = "Shikimori", externalId = "1", seedKey = "s2"),
        )
        assertEquals(1, CandidateMerger.group(pool).size)
    }
}

class NormalizedScorerTest {

    private fun scorer(
        affinity: Map<String, Float> = emptyMap(),
        confidence: Float = 1f,
        trust: Map<String, Float> = emptyMap(),
    ) = NormalizedScorer(affinity, { it }, preferredSource = "AniList", seedTrust = trust, confidence = confidence)

    @Test
    fun `scores stay in the unit interval`() {
        val pool = (1..30).map { entry("T$it", genres = listOf("Action"), rating = (it * 3) % 100) }
        val ranked = scorer(mapOf("Action" to 1f)).scoreAndRank(pool, 50)
        assertTrue(ranked.all { it.score in 0f..1f })
    }

    @Test
    fun `a title recommended by two different seeds beats one recommended by one`() {
        val pool = listOf(
            entry("Twice", seedKey = "a", seedTitle = "A"),
            entry("Twice", seedKey = "b", seedTitle = "B", source = "Shikimori"),
            entry("Once", seedKey = "a", seedTitle = "A"),
        )
        val ranked = scorer().scoreAndRank(pool, 10)
        assertEquals("Twice", ranked.first().title)
    }

    @Test
    fun `two APIs for the same seed are one confirmation not two`() {
        val pool = listOf(
            entry("Doubled", seedKey = "a", seedTitle = "A", source = "AniList"),
            entry("Doubled", seedKey = "a", seedTitle = "A", source = "Shikimori"),
            entry("Single", seedKey = "a", seedTitle = "A"),
        )
        val scores = scorer().scoreAndRank(pool, 10).associate { it.title to it.score }
        assertEquals(scores.getValue("Single"), scores.getValue("Doubled"), 1e-4f)
    }

    @Test
    fun `stronger edge votes raise the score`() {
        val pool = listOf(
            entry("Strong", linkWeight = 40),
            entry("Weak", linkWeight = 1),
        )
        val ranked = scorer().scoreAndRank(pool, 10)
        assertEquals("Strong", ranked.first().title)
    }

    @Test
    fun `missing components do not count as zero`() {
        // Нет жанров и нет оценки: остаётся только связь с сидом, и скор не проваливается в ноль.
        val ranked = scorer(mapOf("Action" to 1f)).scoreAndRank(listOf(entry("Bare", rating = null)), 5)
        assertTrue(ranked.single().score > 0.2f)
    }

    @Test
    fun `genre taste separates otherwise equal candidates`() {
        val pool = listOf(
            entry("Liked", genres = listOf("Action")),
            entry("Disliked", genres = listOf("Romance")),
        )
        val ranked = scorer(mapOf("Action" to 1f, "Romance" to -1f)).scoreAndRank(pool, 10)
        assertEquals(listOf("Liked", "Disliked"), ranked.map { it.title })
    }

    @Test
    fun `few votes pull a high rating toward the mean`() {
        val pool = listOf(
            entry("Obscure", ContentType.MOVIE, "TMDB", "1", rating = 100, votes = 3, tmdb = 1, year = 2000),
            entry("Proven", ContentType.MOVIE, "TMDB", "2", rating = 85, votes = 20_000, tmdb = 2, year = 2001),
        )
        val ranked = scorer().scoreAndRank(pool, 10)
        assertEquals("Proven", ranked.first().title)
    }

    @Test
    fun `trending candidates without seeds are ranked by quality alone`() {
        val pool = listOf(
            entry("Hit", rating = 95, signal = SignalKind.TRENDING, seedKey = "", seedTitle = ""),
            entry("Meh", rating = 55, signal = SignalKind.TRENDING, seedKey = "", seedTitle = ""),
        )
        val ranked = scorer(confidence = 0f).scoreAndRank(pool, 10)
        assertEquals("Hit", ranked.first().title)
    }

    @Test
    fun `evidence and content type are kept on the item`() {
        val pool = listOf(
            entry("X", ContentType.TV, "TMDB", "5", tmdb = 5, year = 2015, seedKey = "a"),
            entry("X", ContentType.TV, "Trakt", "9", tmdb = 5, year = 2015, seedKey = "b"),
        )
        val item = scorer().scoreAndRank(pool, 5).single()
        assertEquals(ContentType.TV, item.contentType)
        assertEquals(5, item.tmdbId)
        assertEquals("SERIES", item.categoryType)
        assertEquals(2, item.evidence.size)
        assertEquals("TV:tmdb:5", item.key)
    }
}

class DiversityRerankerTest {

    private fun item(title: String, score: Float, vararg genres: String) = RecommendationItem(
        key = title, title = title, coverUrl = "u", genres = genres.toList(), source = "AniList", score = score,
    )

    @Test
    fun `mmr prefers a different genre over a near duplicate`() {
        val items = listOf(item("A1", 0.90f, "Action"), item("A2", 0.88f, "Action"), item("B", 0.80f, "Drama"))
        val picked = DiversityReranker.mmr(items, limit = 2).map { it.title }
        assertEquals(listOf("A1", "B"), picked)
    }

    @Test
    fun `mmr keeps the best item first and never invents items`() {
        val items = (1..10).map { item("T$it", 1f - it * 0.05f, "Action") }
        val picked = DiversityReranker.mmr(items, limit = 5)
        assertEquals("T1", picked.first().title)
        assertEquals(5, picked.size)
        assertTrue(picked.all { p -> items.any { it.key == p.key } })
    }
}

class SourceCircuitBreakerTest {

    @Test
    fun `opens after repeated failures and recovers after the cooldown`() {
        var now = 0L
        val breaker = SourceCircuitBreaker(failureThreshold = 3, cooldownMillis = 1_000, clock = { now })
        repeat(2) { breaker.recordFailure("trakt") }
        assertFalse(breaker.isOpen("trakt"))
        breaker.recordFailure("trakt")
        assertTrue(breaker.isOpen("trakt"))
        now = 1_001
        assertFalse(breaker.isOpen("trakt"))
        breaker.recordFailure("trakt") // полуоткрытое состояние: один отказ открывает снова
        assertTrue(breaker.isOpen("trakt"))
    }

    @Test
    fun `success resets the counter and sources are independent`() {
        val breaker = SourceCircuitBreaker(failureThreshold = 2)
        breaker.recordFailure("a")
        breaker.recordSuccess("a")
        breaker.recordFailure("a")
        assertFalse(breaker.isOpen("a"))
        repeat(2) { breaker.recordFailure("b") }
        assertTrue(breaker.isOpen("b"))
        assertFalse(breaker.isOpen("a"))
    }
}

class TasteProfileTest {

    private val calculator = GenreAffinityCalculator()

    @Test
    fun `recent taste outweighs old taste of the same strength`() {
        val year = GenreAffinityCalculator.HALF_LIFE_MILLIS
        val library = listOf(
            lib("old", rating = 9f, tags = listOf("Drama"), added = NOW - 3 * year),
            lib("new", rating = 9f, tags = listOf("Action"), added = NOW),
        )
        val affinity = calculator.calculate(library, nowMillis = NOW)
        assertTrue(affinity.getValue("Action") > affinity.getValue("Drama"))
        assertTrue("old taste is not erased", affinity.getValue("Drama") > 0f)
    }

    @Test
    fun `dropped without a rating is a moderate negative signal`() {
        val library = listOf(
            lib("liked", rating = 9f, tags = listOf("Action")),
            lib("dropped", rating = 0f, tags = listOf("Romance"), status = WatchStatus.DROPPED),
        )
        val affinity = calculator.calculate(library, nowMillis = NOW)
        assertTrue(affinity.getValue("Romance") < 0f)
        assertTrue("one drop does not ban the genre outright", affinity.getValue("Romance") > -1f)
    }

    @Test
    fun `without a clock the legacy profile is unchanged`() {
        val library = listOf(lib("x", rating = 9f, tags = listOf("Action"), status = WatchStatus.DROPPED))
        val legacy = calculator.calculate(library)
        assertEquals(1f, legacy.getValue("Action"), 1e-4f)
    }

    @Test
    fun `mix counts the whole library and ignores manga`() {
        val library = listOf(
            lib("a1"), lib("a2"),
            lib("m1", type = MediaType.MOVIE, tmdbId = null),
            lib("s1", type = MediaType.SERIES),
            lib("manga", type = MediaType.MANGA, anilistId = null, tmdbId = null),
        )
        val mix = ContentMix.of(library)
        assertEquals(ContentMix(anime = 2, movies = 1, series = 1), mix)
        assertNotEquals(0, mix.total)
    }
}

/**
 * Офлайн-прогон (replay) на синтетических коллекциях без сети: полный путь «пулы по типам → квота →
 * колода из 20» для заданных составов библиотеки.
 */
class ReplayCollectionsTest {

    private fun pool(type: ContentType, n: Int) = (1..n).map {
        RecommendationItem(
            key = "${type.name}:$it", title = "$type $it", coverUrl = "u", source = "x",
            score = 1f - it * 0.01f, contentType = type,
        )
    }

    private fun deckFor(mix: ContentMix, previous: Quota? = null): List<RecommendationItem> {
        val pools = ContentType.entries.associateWith { pool(it, 40) }
        val quota = ContentMixResolver.resolve(mix, pools.mapValues { it.value.size }, previous)
        return QuotaAllocator.interleave(pools, quota)
    }

    private fun List<RecommendationItem>.count(type: ContentType) = count { it.contentType == type }

    @Test
    fun `anime only library gives an all anime deck`() {
        val deck = deckFor(ContentMix(anime = 40))
        assertEquals(20, deck.size)
        assertEquals(20, deck.count(ContentType.ANIME))
    }

    @Test
    fun `movies only library gives no anime`() {
        val deck = deckFor(ContentMix(movies = 30))
        assertEquals(0, deck.count(ContentType.ANIME))
        assertEquals(20, deck.count(ContentType.MOVIE))
    }

    @Test
    fun `series heavy library gives mostly series with some movies`() {
        val deck = deckFor(ContentMix(movies = 10, series = 40))
        assertEquals(0, deck.count(ContentType.ANIME))
        assertTrue(deck.count(ContentType.TV) > deck.count(ContentType.MOVIE))
        assertTrue(deck.count(ContentType.MOVIE) >= 1)
    }

    @Test
    fun `balanced library mixes all three types`() {
        val deck = deckFor(ContentMix(anime = 50, movies = 25, series = 25))
        assertEquals(10, deck.count(ContentType.ANIME))
        assertEquals(10, deck.count(ContentType.MOVIE) + deck.count(ContentType.TV))
    }

    @Test
    fun `the same input always produces the same deck`() {
        val mix = ContentMix(anime = 30, movies = 10, series = 10)
        assertEquals(deckFor(mix).map { it.key }, deckFor(mix).map { it.key })
    }

    @Test
    fun `no duplicated cards in a deck`() {
        val deck = deckFor(ContentMix(anime = 50, movies = 25, series = 25))
        assertEquals(deck.size, deck.map { it.key }.toSet().size)
    }
}
