package com.example.myapplication.network.movie

import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.ExternalIds
import com.example.myapplication.network.LookupResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbIdResolverTest {

    private fun hit(id: Int, title: String, year: Int?, original: String? = null) = ApiSearchResult(
        title = title,
        altTitle = null,
        posterUrl = null,
        episodes = 1,
        description = "",
        type = "MOVIE",
        genres = emptyList(),
        rating = null,
        source = "TMDB",
        categoryType = "MOVIE",
        externalId = id.toString(),
        seasonYear = year,
        originalTitle = original,
        externalIds = ExternalIds(tmdb = id),
    )

    private class FakeGateway(
        var available: Boolean = true,
        val respond: (query: String, type: AppContentType, language: AppLanguage, year: Int?) -> LookupResult<List<ApiSearchResult>>,
    ) : TmdbSearchGateway {
        val calls = mutableListOf<Triple<String, AppContentType, AppLanguage>>()
        override val isAvailable: Boolean get() = available
        override suspend fun search(
            query: String,
            contentType: AppContentType,
            language: AppLanguage,
            year: Int?,
        ): LookupResult<List<ApiSearchResult>> {
            calls += Triple(query, contentType, language)
            return respond(query, contentType, language, year)
        }
    }

    private fun movie(vararg titles: String, year: Int? = null) =
        TmdbIdQuery(titles.toList(), year, AppContentType.MOVIE)

    @Test
    fun `exact title and year resolves`() = runBlocking {
        val gateway = FakeGateway { _, _, _, _ -> LookupResult.Found(listOf(hit(157336, "Interstellar", 2014))) }
        val verdict = TmdbIdResolver(gateway).resolve(movie("Interstellar", year = 2014))
        assertEquals(157336, (verdict as TmdbIdResolution.Matched).tmdbId)
    }

    @Test
    fun `year off by one still resolves with lower confidence`() = runBlocking {
        val gateway = FakeGateway { _, _, _, _ -> LookupResult.Found(listOf(hit(1, "Parasite", 2019))) }
        val verdict = TmdbIdResolver(gateway).resolve(movie("Parasite", year = 2020)) as TmdbIdResolution.Matched
        assertEquals(1, verdict.tmdbId)
        assertTrue(verdict.confidence < 1.0)
    }

    @Test
    fun `year off by two is rejected`() = runBlocking {
        val gateway = FakeGateway { _, _, _, _ -> LookupResult.Found(listOf(hit(1, "Parasite", 2019))) }
        assertEquals(TmdbIdResolution.NoMatch, TmdbIdResolver(gateway).resolve(movie("Parasite", year = 2022)))
    }

    @Test
    fun `same title without year is ambiguous between remakes`() = runBlocking {
        val gateway = FakeGateway { _, _, _, _ ->
            LookupResult.Found(listOf(hit(10, "Dune", 1984), hit(11, "Dune", 2021)))
        }
        assertEquals(TmdbIdResolution.Ambiguous, TmdbIdResolver(gateway).resolve(movie("Dune")))
    }

    @Test
    fun `known year picks the right remake`() = runBlocking {
        val gateway = FakeGateway { _, _, _, _ ->
            LookupResult.Found(listOf(hit(10, "Dune", 1984), hit(11, "Dune", 2021)))
        }
        val verdict = TmdbIdResolver(gateway).resolve(movie("Dune", year = 2021)) as TmdbIdResolution.Matched
        assertEquals(11, verdict.tmdbId)
    }

    @Test
    fun `unknown year needs a near exact title`() = runBlocking {
        val gateway = FakeGateway { _, _, _, _ -> LookupResult.Found(listOf(hit(5, "The Dark Knight Rises", 2012))) }
        assertEquals(TmdbIdResolution.NoMatch, TmdbIdResolver(gateway).resolve(movie("Dark Night")))
    }

    @Test
    fun `any title variant can confirm the match through the original title`() = runBlocking {
        val gateway = FakeGateway { _, _, _, _ ->
            LookupResult.Found(listOf(hit(496243, "Паразиты", 2019, original = "Parasite")))
        }
        val verdict = TmdbIdResolver(gateway).resolve(movie("Parasite", year = 2019))
        assertEquals(496243, (verdict as TmdbIdResolution.Matched).tmdbId)
    }

    @Test
    fun `cyrillic title is searched in the russian locale and type is passed through`() = runBlocking {
        val gateway = FakeGateway { _, _, _, _ -> LookupResult.NoMatch }
        TmdbIdResolver(gateway).resolve(TmdbIdQuery(listOf("Игра престолов"), 2011, AppContentType.SERIES))
        assertTrue(gateway.calls.all { it.second == AppContentType.SERIES && it.third == AppLanguage.RU })
    }

    @Test
    fun `missing key is unavailable and makes no request`() = runBlocking {
        val gateway = FakeGateway(available = false) { _, _, _, _ -> LookupResult.NoMatch }
        assertEquals(TmdbIdResolution.Unavailable, TmdbIdResolver(gateway).resolve(movie("Anything", year = 2000)))
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `network failure is unavailable so it is not remembered as a miss`() = runBlocking {
        val gateway = FakeGateway { _, _, _, _ -> LookupResult.Failure(java.io.IOException("offline"), retryable = true) }
        assertEquals(TmdbIdResolution.Unavailable, TmdbIdResolver(gateway).resolve(movie("Anything", year = 2000)))
    }

    @Test
    fun `empty search result is a plain miss`() = runBlocking {
        val gateway = FakeGateway { _, _, _, _ -> LookupResult.NoMatch }
        assertEquals(TmdbIdResolution.NoMatch, TmdbIdResolver(gateway).resolve(movie("Obscure Home Video", year = 1999)))
    }
}
