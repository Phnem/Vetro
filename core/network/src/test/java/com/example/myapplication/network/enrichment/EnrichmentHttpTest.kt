package com.example.myapplication.network.enrichment

import com.example.myapplication.network.LookupResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentHttpTest {

    private var now = 1_000_000_000L
    private val cacheDir = Files.createTempDirectory("enrich").toFile()

    private fun http(vararg responses: Pair<HttpStatusCode, String>): Pair<EnrichmentHttp, () -> Int> {
        var calls = 0
        val engine = MockEngine {
            val (status, body) = responses[minOf(calls, responses.lastIndex)]
            calls++
            respond(body, status)
        }
        val cache = EnrichmentCache(cacheDir, nowMs = { now })
        return EnrichmentHttp(HttpClient(engine), cache, ProviderGate { now }) to { calls }
    }

    @Test
    fun `fresh cache answers without network, stale is refreshed`() = runBlocking {
        val (http, calls) = http(HttpStatusCode.OK to "first", HttpStatusCode.OK to "second")
        val policy = CachePolicy("k", ttlMs = 1_000)
        assertEquals(LookupResult.Found("first"), http.text("P", "https://x/1", policy = policy))
        assertEquals(LookupResult.Found("first"), http.text("P", "https://x/1", policy = policy))
        assertEquals(1, calls())
        now += 2_000
        assertEquals(LookupResult.Found("second"), http.text("P", "https://x/1", policy = policy))
        assertEquals(2, calls())
    }

    @Test
    fun `404 is remembered as no match`() = runBlocking {
        val (http, calls) = http(HttpStatusCode.NotFound to "")
        val policy = CachePolicy("missing", ttlMs = 10_000, negativeTtlMs = 5_000)
        assertEquals(LookupResult.NoMatch, http.text("P", "https://x/2", policy = policy))
        assertEquals(LookupResult.NoMatch, http.text("P", "https://x/2", policy = policy))
        assertEquals(1, calls())
    }

    @Test
    fun `quota exhaustion pauses the provider until tomorrow, stale body is served`() = runBlocking {
        val (http, calls) = http(HttpStatusCode.OK to "old", HttpStatusCode.TooManyRequests to "slow down")
        val policy = CachePolicy("q", ttlMs = 1_000)
        http.text("P", "https://x/3", policy = policy)
        now += 2_000
        // Квота кончилась — отдаём старый ответ, провайдер выключен.
        assertEquals(LookupResult.Found("old"), http.text("P", "https://x/3", policy = policy))
        assertEquals(2, calls())
        assertEquals(LookupResult.Found("old"), http.text("P", "https://x/3", policy = policy))
        assertEquals(2, calls()) // сеть больше не трогаем
        assertTrue(!http.gate.isOpen("P"))
        now += 25 * CacheTtl.HOUR
        assertTrue(http.gate.isOpen("P"))
    }

    @Test
    fun `omdb style 200 with limit message counts as quota`() = runBlocking {
        val (http, _) = http(HttpStatusCode.OK to """{"Response":"False","Error":"Request limit reached!"}""")
        val r = http.text("OMDb", "https://x/4")
        assertTrue(r is LookupResult.Failure)
        assertTrue(!http.gate.isOpen("OMDb"))
    }

    @Test
    fun `single transient failure does not pause, repeated ones do`() = runBlocking {
        val (http, _) = http(HttpStatusCode.ServiceUnavailable to "down")
        http.text("P", "https://x/5")
        assertTrue(http.gate.isOpen("P"))
        http.text("P", "https://x/5")
        assertTrue(!http.gate.isOpen("P"))
    }

    @Test
    fun `wrong user password does not close the provider`() = runBlocking {
        val (http, calls) = http(HttpStatusCode.Unauthorized to "{\"message\":\"invalid\"}", HttpStatusCode.OK to "token")
        val bad = http.text("U", "https://x/login", userCredentials = true)
        assertTrue(bad is LookupResult.Failure)
        assertEquals(401, ((bad as LookupResult.Failure).cause as EnrichmentHttpException).status)
        // Сразу после исправления пароля вход проходит — провайдер не выключен до завтра.
        assertEquals(LookupResult.Found("token"), http.text("U", "https://x/login", userCredentials = true))
        assertEquals(2, calls())
    }
}
