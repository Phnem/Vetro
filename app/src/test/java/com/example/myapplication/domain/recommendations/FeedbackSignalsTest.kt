package com.example.myapplication.domain.recommendations

import com.example.myapplication.network.ApiSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun item(
    title: String,
    genres: List<String> = emptyList(),
    seedTitle: String? = null,
    key: String = title.lowercase(),
) = RecommendationItem(
    key = key,
    title = title,
    coverUrl = "https://img/x.jpg",
    genres = genres,
    source = "AniList",
    seedTitle = seedTitle,
)

private fun poolEntry(title: String, seedTitle: String) = PoolEntry(
    result = ApiSearchResult(
        title = title,
        altTitle = null,
        posterUrl = "https://img/x.jpg",
        episodes = 12,
        description = "",
        type = "ANIME",
        genres = emptyList(),
        rating = 80,
        source = "AniList",
        categoryType = "ANIME",
        externalId = null,
    ),
    seedTitle = seedTitle,
    seedRating = 8f,
)

class FeedbackSignalsTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `approved genres go up and disapproved go down`() {
        val entries = listOf(
            item("A", genres = listOf("Action")).toFeedbackEntry(FeedbackVerdict.APPROVED, now),
            item("B", genres = listOf("Romance")).toFeedbackEntry(FeedbackVerdict.DISAPPROVED, now),
        )
        val weights = FeedbackSignals.genreWeights(entries, { it }, now)
        assertEquals(FeedbackSignals.GENRE_WEIGHT, weights.getValue("Action"), 1e-4f)
        assertEquals(-FeedbackSignals.GENRE_WEIGHT, weights.getValue("Romance"), 1e-4f)
    }

    @Test
    fun `feedback weight halves after the half life`() {
        val old = item("A", genres = listOf("Action"))
            .toFeedbackEntry(FeedbackVerdict.APPROVED, now - FeedbackSignals.HALF_LIFE_MILLIS)
        val weights = FeedbackSignals.genreWeights(listOf(old), { it }, now)
        assertEquals(FeedbackSignals.GENRE_WEIGHT / 2f, weights.getValue("Action"), 1e-3f)
    }

    @Test
    fun `unmapped genres contribute nothing`() {
        val entries = listOf(item("A", genres = listOf("Unknown")).toFeedbackEntry(FeedbackVerdict.APPROVED, now))
        assertTrue(FeedbackSignals.genreWeights(entries, { null }, now).isEmpty())
    }

    @Test
    fun `seed trust is smoothed so one swipe does not decide`() {
        val one = listOf(item("A", seedTitle = "S").toFeedbackEntry(FeedbackVerdict.APPROVED, now))
        val many = (1..8).map { item("A$it", seedTitle = "S").toFeedbackEntry(FeedbackVerdict.APPROVED, now) }
        val trustOne = FeedbackSignals.seedTrust(one).getValue("S")
        val trustMany = FeedbackSignals.seedTrust(many).getValue("S")
        assertTrue(trustOne in 0.1f..0.5f)
        assertTrue(trustMany > trustOne)
        assertTrue(trustMany < 1f)
    }

    @Test
    fun `seed trust turns negative when recommendations are rejected`() {
        val entries = listOf(
            item("A", seedTitle = "S").toFeedbackEntry(FeedbackVerdict.DISAPPROVED, now),
            item("B", seedTitle = "S").toFeedbackEntry(FeedbackVerdict.DISAPPROVED, now),
        )
        assertTrue(FeedbackSignals.seedTrust(entries).getValue("S") < 0f)
    }

    @Test
    fun `index matches judged card by key or by normalized title`() {
        val judged = FeedbackIndex(listOf(item("Cowboy Bebop").toFeedbackEntry(FeedbackVerdict.DISAPPROVED, now)))
        assertTrue(judged.isJudged(item("Cowboy Bebop", key = "anilist:1")))
        assertTrue(judged.isJudged(item("Other", key = "cowboy bebop")))
        assertFalse(judged.isJudged(item("Trigun")))
    }

    @Test
    fun `filter drops candidates the user already judged`() {
        val judged = FeedbackIndex(listOf(item("Trigun").toFeedbackEntry(FeedbackVerdict.APPROVED, now)))
        val pool = listOf(poolEntry("Trigun", "S"), poolEntry("Monster", "S"))
        val kept = RecommendationFilter().filter(pool, emptyList(), judged)
        assertEquals(listOf("Monster"), kept.map { it.result.title })
    }

    @Test
    fun `swipe feedback alone builds a taste vector for an empty library`() {
        val extra = mapOf("action" to FeedbackSignals.GENRE_WEIGHT, "romance" to -FeedbackSignals.GENRE_WEIGHT)
        val affinity = GenreAffinityCalculator().calculate(emptyList(), extra)
        assertEquals(1f, affinity.getValue("action"), 1e-4f)
        assertEquals(-1f, affinity.getValue("romance"), 1e-4f)
    }

    @Test
    fun `candidates from a trusted seed outrank those from a rejected seed`() {
        val scorer = RecommendationScorer(
            affinity = emptyMap(),
            genreToTagId = { it },
            seedTrust = mapOf("Loved" to 0.8f, "Hated" to -0.8f),
        )
        val ranked = scorer.scoreAndRank(
            listOf(poolEntry("From Hated", "Hated"), poolEntry("From Loved", "Loved")),
            limit = 10,
        )
        assertEquals(listOf("From Loved", "From Hated"), ranked.map { it.title })
    }
}
