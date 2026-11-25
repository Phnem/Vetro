package com.example.myapplication.manga.source

import com.example.myapplication.network.TokenBucketRateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaDexSourceTest {

    /** MangaDex отдаёт `"links": null` у тайтлов без ссылок на другие каталоги. */
    private val body = """
        {"data":[
          {"id":"a","attributes":{"title":{"en":"Without links"},"links":null}},
          {"id":"b","attributes":{"title":{"en":"With links"},"links":{"al":"42"}}}
        ],"total":2}
    """.trimIndent()

    private fun source() = MangaDexSource(
        client = HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }) {
            // Те же настройки, что у общего клиента приложения (coreNetworkModule).
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        },
        rateLimiter = TokenBucketRateLimiter(10.0, 10.0),
    )

    @Test
    fun `a title with null links does not fail the whole search`() = runBlocking<Unit> {
        assertEquals(listOf("Without links", "With links"), source().search("x", 1).items.map { it.title })
    }

    @Test
    fun `AniList lookup skips a title with null links`() = runBlocking<Unit> {
        assertEquals("b", source().findByAniList(42, listOf("x"))?.key)
    }
}
