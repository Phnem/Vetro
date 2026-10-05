package com.example.myapplication.media.source.movieseries.custom

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSourceSettingsServiceTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `a pasted definition installs and appears in the list`() = runBlocking {
        val store = FakeStore()
        val service = service(store) { respond("", HttpStatusCode.OK) }

        val outcome = service.installFromText(VETRO_MANIFEST)

        assertTrue(outcome is CustomSourceOutcome.Installed)
        val summary = service.summaries().single()
        assertEquals("My catalog", summary.displayName)
        assertEquals("Vetro", summary.kindLabel)
        assertTrue(summary.enabled)
    }

    @Test
    fun `a definition fetched from a link records where it came from`() = runBlocking {
        val store = FakeStore()
        val service = service(store) { respond(VETRO_MANIFEST, HttpStatusCode.OK, jsonHeaders) }

        service.installFromUrl("https://cfg.example/source.json")

        assertEquals("https://cfg.example/source.json", service.summaries().single().sourceUrl)
    }

    @Test
    fun `a plain http link is refused before any request is made`() = runBlocking {
        var called = false
        val service = service(FakeStore()) {
            called = true
            respond(VETRO_MANIFEST, HttpStatusCode.OK, jsonHeaders)
        }

        val outcome = service.installFromUrl("http://insecure.example/m.json")

        // The definition decides what Vetro will talk to, so it must not arrive over plain HTTP
        // where anyone on the path could rewrite it.
        assertTrue(reason(outcome).contains("https"))
        assertFalse("Nothing should be fetched over plain http", called)
    }

    @Test
    fun `a link that is not a url is refused`() = runBlocking {
        val service = service(FakeStore()) { respond("", HttpStatusCode.OK) }

        assertTrue(reason(service.installFromUrl("not a link")).contains("valid link"))
    }

    @Test
    fun `an unreachable link says so instead of reporting a parse error`() = runBlocking {
        val service = service(FakeStore()) { respond("", HttpStatusCode.NotFound) }

        assertTrue(reason(service.installFromUrl("https://cfg.example/m.json")).contains("HTTP 404"))
    }

    @Test
    fun `an invalid definition is refused with the validator's reason and nothing is stored`() =
        runBlocking {
            val store = FakeStore()
            val service = service(store) { respond("", HttpStatusCode.OK) }
            val badVersion = VETRO_MANIFEST.replace("\"manifestVersion\": 1", "\"manifestVersion\": 9")

            val outcome = service.installFromText(badVersion)

            assertTrue(reason(outcome).contains("manifestVersion"))
            assertTrue(store.all().isEmpty())
        }

    @Test
    fun `installing the same source again updates it rather than duplicating`() = runBlocking {
        val store = FakeStore()
        val service = service(store) { respond("", HttpStatusCode.OK) }

        service.installFromText(VETRO_MANIFEST)
        service.installFromText(VETRO_MANIFEST.replace("My catalog", "Renamed catalog"))

        assertEquals(listOf("Renamed catalog"), service.summaries().map { it.displayName })
    }

    @Test
    fun `a source can be disabled and removed`() = runBlocking {
        val store = FakeStore()
        val service = service(store) { respond("", HttpStatusCode.OK) }
        service.installFromText(VETRO_MANIFEST)
        val key = service.summaries().single().key

        service.setEnabled(key, false)
        assertFalse(service.summaries().single().enabled)

        service.remove(key)
        assertTrue(service.summaries().isEmpty())
    }

    @Test
    fun `a disabled source builds no provider at all`() = runBlocking {
        val store = FakeStore()
        val service = service(store) { respond("", HttpStatusCode.OK) }
        service.installFromText(VETRO_MANIFEST)
        val registry = CustomSourceRegistry(store, HttpClient(MockEngine { respond("", HttpStatusCode.OK) }))

        assertEquals(1, registry.providers().size)

        service.setEnabled(service.summaries().single().key, false)

        // Switched off in settings means it cannot reach the network even by accident.
        assertTrue(registry.providers().isEmpty())
    }

    @Test
    fun `refreshing a hand-pasted source explains that it has no link`() = runBlocking {
        val store = FakeStore()
        val service = service(store) { respond("", HttpStatusCode.OK) }
        service.installFromText(VETRO_MANIFEST)

        val outcome = service.refresh(service.summaries().single().key)

        assertTrue(reason(outcome).contains("no link"))
    }

    @Test
    fun `refreshing an unknown source does not crash`() = runBlocking {
        val service = service(FakeStore()) { respond("", HttpStatusCode.OK) }

        assertTrue(reason(service.refresh("missing")).contains("no longer installed"))
    }

    @Test
    fun `an array bundle installs manifests, queues packages for review and names what it refused`() = runBlocking {
        val store = FakeStore()
        val service = service(store) { respond("", HttpStatusCode.OK) }
        val broken = """{ "format": 2, "id": "broken.pkg", "version": "1.0.0" }"""

        val outcome = service.installFromText("[$VETRO_MANIFEST, ${readmePackage()}, $broken]")

        assertTrue("Expected a bundle, got $outcome", outcome is CustomSourceOutcome.Bundle)
        outcome as CustomSourceOutcome.Bundle
        assertEquals(1, outcome.installed)
        assertEquals(listOf("example.films"), outcome.previews.map { it.id })
        assertEquals(1, outcome.problems.size)
        assertTrue(outcome.problems.single(), outcome.problems.single().startsWith("broken.pkg: "))
        // Пакет ставится только после подтверждения — сейчас в списке лишь манифест.
        assertEquals(listOf("My catalog"), service.summaries().map { it.displayName })
    }

    @Test
    fun `a named bundle object is read through its sources list`() = runBlocking {
        val service = service(FakeStore()) { respond("", HttpStatusCode.OK) }

        val outcome = service.installFromText("""{ "name": "My picks", "sources": [$VETRO_MANIFEST] }""")

        assertEquals(1, (outcome as CustomSourceOutcome.Bundle).installed)
    }

    @Test
    fun `a plain list of addon links installs each one`() = runBlocking {
        val store = FakeStore()
        val service = service(store) { respond(VETRO_MANIFEST, HttpStatusCode.OK, jsonHeaders) }

        val outcome = service.installFromText(
            """
            # My addons
            https://cfg.example/a.json   # first
            https://cfg.example/b.json
            """.trimIndent(),
        )

        assertEquals(2, (outcome as CustomSourceOutcome.Bundle).installed)
    }

    @Test
    fun `a single definition is still installed on its own`() = runBlocking {
        val outcome = service(FakeStore()) { respond("", HttpStatusCode.OK) }.installFromText(VETRO_MANIFEST)
        assertTrue(outcome is CustomSourceOutcome.Installed)
    }

    private fun readmePackage(): String =
        requireNotNull(javaClass.classLoader?.getResource("sdk/readme/min_package.json")).readText()

    private fun reason(outcome: CustomSourceOutcome): String {
        assertTrue("Expected a rejection, got $outcome", outcome is CustomSourceOutcome.Rejected)
        return (outcome as CustomSourceOutcome.Rejected).reason
    }

    private fun service(
        store: InstalledSourceStore,
        respondWith: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
            io.ktor.client.request.HttpRequestData,
        ) -> io.ktor.client.request.HttpResponseData,
    ) = CustomSourceSettingsService(
        store = store,
        installer = CustomSourceInstaller(),
        client = HttpClient(MockEngine(respondWith)),
    )

    private class FakeStore : InstalledSourceStore {
        private val entries = mutableListOf<InstalledSource>()

        override suspend fun all(): List<InstalledSource> = entries.toList()

        override suspend fun install(source: InstalledSource): InstalledSource {
            entries.removeAll { it.key == source.key }
            entries += source
            return source
        }

        override suspend fun setEnabled(key: String, enabled: Boolean) {
            entries.replaceAll { if (it.key == key) it.copy(enabled = enabled) else it }
        }

        override suspend fun remove(key: String) {
            entries.removeAll { it.key == key }
        }
    }

    private companion object {
        const val VETRO_MANIFEST = """
            {
              "manifestVersion": 1,
              "id": "my-catalog",
              "name": "My catalog",
              "baseUrl": "https://media.example.org",
              "capabilities": ["MOVIE", "TMDB_ID"],
              "movie": { "path": "/api/movie/{tmdbId}" },
              "response": { "streams": "/streams", "url": "/src" }
            }
        """
    }
}
