package com.example.myapplication.media.source.vfmodule

import com.example.myapplication.network.ids.CrossIds
import com.example.myapplication.network.ids.ResolveQuery
import com.example.myapplication.network.ids.ResolvedTitle
import com.example.myapplication.network.ids.TitleKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VfTitleIdContributorTest {

    @Test
    fun `contribute extracts missing kinopoisk id from balancer player urls`() = runBlocking {
        val mockEngine = MockEngine { request ->
            respond(
                content = """
                    [
                        {
                            "type": "Alloha",
                            "iframeUrl": "https://alloha.tv/iframe/?kp=435&imdb=tt0111161",
                            "translation": "Дубляж",
                            "success": true
                        }
                    ]
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine)
        val contributor = VfTitleIdContributor(
            allohaExtractor = AllohaExtractor(client),
            playersClient = KinoboxPlayersClient(client),
        )

        val query = ResolveQuery(
            kind = TitleKind.MOVIE,
            title = "The Shawshank Redemption",
            known = CrossIds(imdb = "tt0111161"),
        )
        val soFar = ResolvedTitle(
            ids = CrossIds(imdb = "tt0111161", tmdbMovie = 278),
            titleRu = "Побег из Шоушенка",
            descriptionRu = "Описание",
            posterUrl = "https://example.com/poster.jpg",
        )

        val contribution = contributor.contribute(query, soFar)
        assertNotNull(contribution)
        assertEquals(435, contribution?.ids?.kinopoisk)
        assertEquals("tt0111161", contribution?.ids?.imdb)
    }

    @Test
    fun `contribute returns null when all ids and metadata are already complete`() = runBlocking {
        var networkCalled = false
        val mockEngine = MockEngine {
            networkCalled = true
            respond("", HttpStatusCode.OK)
        }
        val client = HttpClient(mockEngine)
        val contributor = VfTitleIdContributor(
            allohaExtractor = AllohaExtractor(client),
            playersClient = KinoboxPlayersClient(client),
        )

        val query = ResolveQuery(kind = TitleKind.MOVIE, title = "Complete Movie")
        val soFar = ResolvedTitle(
            ids = CrossIds(kinopoisk = 435, imdb = "tt0111161", tmdbMovie = 278),
            titleRu = "Побег из Шоушенка",
            descriptionRu = "Полное описание",
            posterUrl = "https://example.com/poster.jpg",
        )

        val contribution = contributor.contribute(query, soFar)
        assertNull(contribution)
        assertEquals(false, networkCalled)
    }

    @Test
    fun `contribute enriches from Alloha API when token is provided`() = runBlocking {
        val mockEngine = MockEngine {
            respond(
                content = """
                    {
                        "status": "success",
                        "data": {
                            "id_kp": 326,
                            "id_imdb": "tt0110912",
                            "id_tmdb": 680,
                            "name": "Криминальное чтиво",
                            "original_name": "Pulp Fiction",
                            "description": "Двое бандитов Винсент Вега и Джулс Винфилд...",
                            "poster": "https://alloha.tv/posters/326.jpg"
                        }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine)
        val contributor = VfTitleIdContributor(
            allohaExtractor = AllohaExtractor(client),
            playersClient = KinoboxPlayersClient(client),
            allohaTokenProvider = { "test-token" },
        )

        val query = ResolveQuery(kind = TitleKind.MOVIE, title = "Pulp Fiction")
        val soFar = ResolvedTitle(ids = CrossIds(imdb = "tt0110912"))

        val contribution = contributor.contribute(query, soFar)
        assertNotNull(contribution)
        assertEquals(326, contribution?.ids?.kinopoisk)
        assertEquals(680, contribution?.ids?.tmdbMovie)
        assertEquals("Криминальное чтиво", contribution?.titleRu)
        assertEquals("Pulp Fiction", contribution?.titleEn)
        assertEquals("https://alloha.tv/posters/326.jpg", contribution?.posterUrl)
    }
}
