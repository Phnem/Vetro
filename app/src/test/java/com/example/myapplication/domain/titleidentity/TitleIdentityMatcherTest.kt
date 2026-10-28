package com.example.myapplication.domain.titleidentity

import org.junit.Assert.*
import org.junit.Test

class TitleIdentityMatcherTest {
    private fun title(name: String, kind: TitleKind = TitleKind.ANIME) = TitleIdentity(listOf(name), kind)
    private val optimistic = TitleIdentityMatcher(scorer = TitlePairScorer { _, _, _, _ -> 0.99999 }, threshold = 0.99)
    @Test fun `movie and tv namespaces never collapse numeric ids`() {
        val a = title("Example", TitleKind.MOVIE).copy(ids = setOf(TitleExternalId(TitleNamespace.TMDB_MOVIE, "550")))
        val b = title("Example", TitleKind.SERIES).copy(ids = setOf(TitleExternalId(TitleNamespace.TMDB_SERIES, "550")))
        assertEquals(TitleIdentityDecision.DIFFERENT_ENTITY, optimistic.compare(a, b).decision)
    }
    @Test fun `id contradiction overrides another matching id`() {
        val a = title("A").copy(ids = setOf(TitleExternalId(TitleNamespace.MAL_ANIME, "1"), TitleExternalId(TitleNamespace.WIKIDATA, "Q1")))
        val b = title("B").copy(ids = setOf(TitleExternalId(TitleNamespace.MAL_ANIME, "2"), TitleExternalId(TitleNamespace.WIKIDATA, "Q1")))
        assertEquals("ID_CONFLICT", optimistic.compare(a, b).reason)
        assertFalse(TitleIdentityPolicies.allowEnrichment(optimistic.compare(a, b)))
    }
    @Test fun `invalid source scope is uncertain`() {
        val a = title("Title").copy(ids = setOf(TitleExternalId(TitleNamespace.MAL_MANGA, "1")))
        assertEquals("INVALID_ID_SCOPE", optimistic.compare(a, title("Tittle")).reason)
    }
    @Test fun `trusted shared id works without title guesses`() {
        val a = title("A").copy(ids = setOf(TitleExternalId(TitleNamespace.MAL_ANIME, "1")))
        val result = optimistic.compare(a, a.copy(titles = listOf("Перевод")))
        assertEquals(TitleIdentityDecision.SAME_ENTITY, result.decision)
        assertTrue(result.verified)
        assertTrue(TitleIdentityPolicies.hideRecommendation(result))
    }
    @Test fun `literary work and recording remain separate`() {
        val a = title("The Hobbit", TitleKind.BOOK_WORK)
        assertEquals(TitleIdentityDecision.DIFFERENT_ENTITY, optimistic.compare(a, a.copy(level = TitleLevel.NARRATION)).decision)
    }
    @Test fun `two recordings cannot match using their shared work id`() {
        val a = title("The Hobbit", TitleKind.BOOK_WORK).copy(level = TitleLevel.NARRATION,
            ids = setOf(TitleExternalId(TitleNamespace.OPENLIBRARY_WORK, "OL123W")))
        assertEquals("UNVALIDATED_ENTITY_LEVEL", optimistic.compare(a, a.copy(author = "Another narrator")).reason)
    }
    @Test fun `two seasons cannot match using their shared series id`() {
        val a = title("Example", TitleKind.SERIES).copy(level = TitleLevel.SEASON,
            ids = setOf(TitleExternalId(TitleNamespace.TMDB_SERIES, "123")))
        assertEquals("UNVALIDATED_ENTITY_LEVEL", optimistic.compare(a, a.copy(titles = listOf("Example Season 2"))).reason)
    }
    @Test fun `unknown identical text does not prove identity`() {
        assertEquals(TitleIdentityDecision.UNCERTAIN, optimistic.compare(title("Unknown title"), title("Unknown title")).decision)
    }
    @Test fun `numeric and roman season changes block model suggestions`() {
        for ((a,b) in listOf("Example Season 1" to "Example Season 2", "Example Part II" to "Example Part III", "Example" to "Example 2"))
            assertEquals(TitleIdentityDecision.UNCERTAIN, optimistic.compare(title(a), title(b)).decision)
    }
    @Test fun `regional remakes and named sequels block model suggestions`() {
        for ((a,b) in listOf("The Office (UK)" to "The Office (US)", "Naruto" to "Naruto Shippuden", "Hellsing" to "Hellsing Ultimate"))
            assertEquals(TitleIdentityDecision.UNCERTAIN, optimistic.compare(title(a), title(b)).decision)
    }
    @Test fun `learned result can rank but cannot hide or write`() {
        val result = optimistic.compare(title("Example title"), title("Example tittle"))
        assertEquals(TitleIdentityDecision.SAME_ENTITY, result.decision)
        assertFalse(result.verified)
        assertFalse(TitleIdentityPolicies.hideRecommendation(result))
        assertFalse(TitleIdentityPolicies.allowEnrichment(result))
        assertTrue(TitleIdentityPolicies.searchScore(result) > 0.9)
    }
    @Test fun `dictionary collisions abstain even with a confident model`() {
        val matcher = TitleIdentityMatcher(TitleAliasLookup { setOf("one", "two") }, TitlePairScorer { _, _, _, _ -> 1.0 }, .99)
        assertEquals("DICTIONARY_AMBIGUOUS", matcher.compare(title("Example"), title("Examplex")).reason)
    }
    @Test fun `same dictionary entity allows translated aliases`() {
        val matcher = TitleIdentityMatcher(TitleAliasLookup { setOf("one") })
        val result = matcher.compare(title("A translated title"), title("Перевод названия"))
        assertTrue(result.verified)
        assertEquals(TitleIdentityDecision.SAME_ENTITY, result.decision)
    }
    @Test fun `unknown book title cannot guess author identity`() {
        assertEquals("BOOK_AUTHOR_MISSING", optimistic.compare(title("Example book", TitleKind.BOOK_WORK), title("Example boook", TitleKind.BOOK_WORK)).reason)
    }
    @Test fun `raw oversized punctuation cannot bypass the feature input guard`() {
        val matcher = TitleIdentityMatcher(scorer = TitlePairScorer { a, b, _, _ ->
            TitleMatchFeatures.compute(a, b)
            0.99999
        }, threshold = 0.99)
        assertEquals(TitleIdentityDecision.UNCERTAIN, matcher.compare(title("(".repeat(5000) + "Example title"), title("Example tittle")).decision)
    }
    @Test fun `dictionary cannot override conflicting book authors`() {
        val matcher = TitleIdentityMatcher(TitleAliasLookup { setOf("one") })
        val a = title("Selected poems", TitleKind.BOOK_WORK).copy(author = "Author A")
        assertEquals("AUTHOR_CONFLICT", matcher.compare(a, a.copy(author = "Author B")).reason)
    }
}
