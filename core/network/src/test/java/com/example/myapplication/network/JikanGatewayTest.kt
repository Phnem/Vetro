package com.example.myapplication.network

import com.example.myapplication.network.enrichment.ProviderGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JikanGatewayTest {

    private var now = 1_000_000L
    private var calls = 0

    private fun gateway(vararg answers: HttpStatusCode): JikanGateway {
        val engine = MockEngine {
            val status = answers[minOf(calls, answers.lastIndex)]
            calls++
            respond(if (status == HttpStatusCode.OK) """{"data":[{"mal_id":1}]}""" else "<html>error</html>", status)
        }
        return JikanGateway(
            HttpClient(engine) { install(HttpTimeout) },
            gate = ProviderGate { now },
            rate = TokenBucketRateLimiter(maxTokens = 100.0, refillTokensPerSecond = 100.0),
        )
    }

    @Test
    fun `answer is parsed and keeps the gate open`() = runBlocking {
        val jikan = gateway(HttpStatusCode.OK)
        assertNotNull(jikan.get(listOf("anime"), mapOf("q" to "x"))?.get("data"))
        assertTrue(jikan.isOpen)
    }

    @Test
    fun `repeated 504 closes Jikan and later calls skip the network`() = runBlocking {
        val jikan = gateway(HttpStatusCode.GatewayTimeout)
        assertNull(jikan.get(listOf("anime", "1")))
        assertTrue("одиночный сбой — не повод выключать", jikan.isOpen)
        assertNull(jikan.get(listOf("anime", "1")))
        assertFalse(jikan.isOpen)
        val before = calls
        repeat(5) { assertNull(jikan.get(listOf("anime", "1"))) }
        assertEquals("закрыт — без запросов", before, calls)
        // Через минуту — пробуем снова.
        now += 61_000
        assertTrue(jikan.isOpen)
        jikan.get(listOf("anime", "1"))
        assertEquals(before + 1, calls)
    }

    @Test
    fun `429 is a short pause, not a daily quota`() = runBlocking {
        val jikan = gateway(HttpStatusCode.TooManyRequests)
        jikan.get(listOf("anime"))
        jikan.get(listOf("anime"))
        assertFalse(jikan.isOpen)
        now += 61_000
        assertTrue("посекундный лимит Jikan не закрывает его до полуночи", jikan.isOpen)
    }

    @Test
    fun `404 is an answer, not a failure`() = runBlocking {
        val jikan = gateway(HttpStatusCode.NotFound)
        repeat(3) { assertNull(jikan.get(listOf("anime", "999999"))) }
        assertTrue(jikan.isOpen)
        assertEquals(3, calls)
    }

    @Test
    fun `an error page instead of JSON counts as a failure`() = runBlocking {
        val engine = MockEngine { calls++; respond("<html>maintenance</html>", HttpStatusCode.OK) }
        val jikan = JikanGateway(HttpClient(engine) { install(HttpTimeout) }, ProviderGate { now }, TokenBucketRateLimiter(100.0, 100.0))
        assertNull(jikan.get(listOf("anime")))
        assertNull(jikan.get(listOf("anime")))
        assertFalse(jikan.isOpen)
    }

    @Test
    fun `upstream error inside a 200 body is a failure`() = runBlocking {
        // Живой ответ Jikan с телефона: HTTP 200, внутри — таймаут MAL.
        val body = """{"status":500,"type":"UpstreamException","message":"Request to MyAnimeList.net timed out (10 seconds)."}"""
        val engine = MockEngine { calls++; respond(body, HttpStatusCode.OK) }
        val jikan = JikanGateway(HttpClient(engine) { install(HttpTimeout) }, ProviderGate { now }, TokenBucketRateLimiter(100.0, 100.0))
        assertNull(jikan.get(listOf("anime", "52991")))
        assertNull(jikan.get(listOf("anime", "52991")))
        assertFalse(jikan.isOpen)
        val notFound = MockEngine { respond("""{"status":404,"type":"BadResponseException"}""", HttpStatusCode.OK) }
        val other = JikanGateway(HttpClient(notFound) { install(HttpTimeout) }, ProviderGate { now }, TokenBucketRateLimiter(100.0, 100.0))
        repeat(3) { assertNull(other.get(listOf("anime", "1"))) }
        assertTrue(other.isOpen)
    }

    @Test
    fun `cached upstream error is re-asked once without the cache`() = runBlocking {
        val cacheHeaders = mutableListOf<String?>()
        val engine = MockEngine { request ->
            cacheHeaders += request.headers["Cache-Control"]
            if (cacheHeaders.size == 1) respond("""{"status":500,"type":"UpstreamException"}""", HttpStatusCode.OK)
            else respond("""{"data":{"mal_id":52991,"title":"Sousou no Frieren"}}""", HttpStatusCode.OK)
        }
        val jikan = JikanGateway(HttpClient(engine) { install(HttpTimeout) }, ProviderGate { now }, TokenBucketRateLimiter(100.0, 100.0))
        assertNotNull(jikan.get(listOf("anime", "52991"))?.get("data"))
        assertEquals(listOf(null, "no-cache"), cacheHeaders)
        assertTrue(jikan.isOpen)
    }
}
