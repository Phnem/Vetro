package com.example.myapplication.media.source.sdk

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.media.source.PlaybackRequest
import com.example.myapplication.media.source.movieseries.ProviderResolution
import com.example.myapplication.media.source.movieseries.custom.CustomSourceInstaller
import com.example.myapplication.media.source.movieseries.custom.CustomSourceOutcome
import com.example.myapplication.media.source.movieseries.custom.CustomSourceSettingsService
import com.example.myapplication.media.source.movieseries.custom.InstalledSource
import com.example.myapplication.media.source.movieseries.custom.InstalledSourceDefinition
import com.example.myapplication.media.source.movieseries.custom.InstalledSourceStore
import com.example.myapplication.media.source.movieseries.custom.PackageParse
import com.example.myapplication.network.AppJson
import com.google.crypto.tink.subtle.Ed25519Sign
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPackageTest {

    private val text = javaClass.classLoader!!.getResource("sdk/example_package.json")!!.readText()
    private val root = AppJson.parseToJsonElement(text).jsonObject
    private val pkg = AppJson.decodeFromJsonElement(ProviderPackage.serializer(), root)

    private fun invalid(edit: (JsonObject) -> JsonObject): String {
        val changed = AppJson.decodeFromJsonElement(ProviderPackage.serializer(), edit(root))
        val result = ProviderPackageValidator.validate(changed)
        assertTrue("ожидался отказ", result is PackageValidation.Invalid)
        return (result as PackageValidation.Invalid).reason
    }

    private fun JsonObject.with(key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(this + (key to value))

    private fun JsonObject.withStreamsUrl(url: String): JsonObject {
        val ops = this["operations"]!!.jsonObject
        val streams = ops["streams"]!!.jsonObject
        val req = buildJsonObject { put("url", url) }
        return with("operations", JsonObject(ops + ("streams" to JsonObject(streams + ("request" to req)))))
    }

    // ---------- валидатор ----------

    @Test
    fun `example package is valid`() {
        assertTrue(ProviderPackageValidator.validate(pkg) is PackageValidation.Valid)
    }

    @Test
    fun `sandbox rules reject what could escape`() {
        assertTrue(invalid { it.withStreamsUrl("https://{unitId}.example.com/x") }.contains("host"))
        assertTrue(invalid { it.withStreamsUrl("https://evil.example.org/{unitId}") }.contains("not in allowedHosts"))
        assertTrue(invalid { it.withStreamsUrl("http://api.example.com/{unitId}") }.contains("https"))
        assertTrue(invalid { it.withStreamsUrl("https://user@api.example.com/{unitId}") }.contains("credentials"))
        assertTrue(invalid { it.withStreamsUrl("https://api.example.com/{kinopoiskId}") }.contains("kinopoisk"))
        assertTrue(invalid { it.withStreamsUrl("https://api.example.com/{script}") }.contains("{script}"))
        assertTrue(invalid { it.withStreamsUrl("https://api.example.com/../{unitId}") }.contains(".."))
        assertTrue(invalid { it.with("allowInsecureHttp", JsonPrimitive(true)) }.contains("local address"))
        assertTrue(invalid { it.with("allowedHosts", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("*.example.com")))) }.contains("Invalid host"))
        assertTrue(invalid { it.with("auth", buildJsonObject { put("kind", "QUERY"); put("name", "key") }) }.contains("Query-parameter"))
    }

    @Test
    fun `capabilities and operations must agree`() {
        assertTrue(invalid { r ->
            val ops = r["operations"]!!.jsonObject
            r.with("operations", JsonObject(ops - "units"))
        }.contains("units"))
        assertTrue(invalid { r -> r.with("capabilities", kotlinx.serialization.json.JsonArray(listOf("SEARCH_BY_EXTERNAL_ID", "UNITS", "STREAMS", "PAGES").map(::JsonPrimitive))) }.contains("pages"))
        assertTrue(invalid { it.with("sdk", buildJsonObject { put("min", 3); put("max", 4) }) }.contains("SDK"))
        assertTrue(invalid { it.with("format", JsonPrimitive(1)) }.contains("format"))
        assertTrue(invalid { it.with("version", JsonPrimitive("latest")) }.contains("version"))
    }

    // ---------- целостность ----------

    private fun sign(json: JsonObject, keys: Ed25519Sign.KeyPair): JsonObject {
        val signature = Ed25519Sign(keys.privateKey).sign(PackageIntegrity.canonical(json).toByteArray())
        return json.with("signature", buildJsonObject {
            put("alg", "ed25519")
            put("publicKey", Base64.getEncoder().encodeToString(keys.publicKey))
            put("value", Base64.getEncoder().encodeToString(signature))
        })
    }

    @Test
    fun `signature survives reformatting and catches tampering`() {
        val keys = Ed25519Sign.KeyPair.newKeyPair()
        val signed = sign(root, keys)
        val parsed = AppJson.decodeFromJsonElement(ProviderPackage.serializer(), signed)
        val (origin, problem) = PackageIntegrity.verify(signed, parsed.signature)
        assertNull(problem)
        assertEquals(Base64.getEncoder().encodeToString(keys.publicKey), (origin as PackageOrigin.Signed).publicKey)

        // Другой порядок полей и отступы — те же канонические байты.
        val reordered = AppJson.parseToJsonElement(
            AppJson.encodeToString(JsonObject.serializer(), JsonObject(signed.entries.reversed().associate { it.key to it.value })),
        ).jsonObject
        assertNull(PackageIntegrity.verify(reordered, parsed.signature).second)

        // Подменили хост — подпись не сходится.
        val tampered = signed.with("allowedHosts", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("api.example.com"), JsonPrimitive("evil.example.org"))))
        assertNotNull(PackageIntegrity.verify(tampered, parsed.signature).second)
    }

    @Test
    fun `updates keep the author key and never go back`() {
        val a = Base64.getEncoder().encodeToString(Ed25519Sign.KeyPair.newKeyPair().publicKey)
        val b = Base64.getEncoder().encodeToString(Ed25519Sign.KeyPair.newKeyPair().publicKey)
        val installed = InstalledPackageFacts("1.2.0", a)
        assertNull(PackageIntegrity.updateProblem(installed, "1.3.0", PackageOrigin.Signed(a)))
        assertNotNull(PackageIntegrity.updateProblem(installed, "1.3.0", PackageOrigin.Signed(b)))
        assertNotNull(PackageIntegrity.updateProblem(installed, "1.3.0", PackageOrigin.Unsigned))
        assertNotNull(PackageIntegrity.updateProblem(installed, "1.1.9", PackageOrigin.Signed(a)))
        assertNull(PackageIntegrity.updateProblem(InstalledPackageFacts("1.0.0", null), "1.0.1", PackageOrigin.Signed(b)))
        assertEquals(1, PackageIntegrity.compareVersions("1.10.0", "1.9.9"))
    }

    // ---------- песочница ----------

    private class Server {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += request.url.toString() + " key=" + request.headers["X-Api-Key"]
            val path = request.url.encodedPath
            when {
                path == "/v1/search" -> respond(
                    """{"results":[{"id":"t-9","title":"Other","ids":{"tmdb":"1"}},{"id":"t-1","title":"Show","year":"2019-01-01","ids":{"tmdb":"1396","imdb":"tt0903747"}}]}""",
                    HttpStatusCode.OK,
                )
                path == "/v1/titles/t-1/episodes" -> respond(
                    """{"episodes":[{"id":"e-1","number":1,"season":1},{"id":"e-2","number":2,"season":1},{"id":"e-9","number":2,"season":2}]}""",
                    HttpStatusCode.OK,
                )
                path == "/v1/episodes/e-9/streams" -> respond(
                    """{"streams":[
                      {"url":"https://cdn.example.com/e9/master.m3u8","label":"RU dub","quality":"1080p","audio":"ru",
                       "subs":[{"url":"https://cdn.example.com/e9/en.vtt","lang":"en","format":"vtt"},{"url":"https://tracker.example.net/s.vtt","lang":"de"}]},
                      {"url":"https://evil.example.org/steal.m3u8","label":"x"}
                    ]}""",
                    HttpStatusCode.OK,
                )
                path == "/v1/episodes/redirect/streams" -> respond("", HttpStatusCode.Found, headersOf("Location", "https://evil.example.org/x"))
                path == "/v1/episodes/huge/streams" -> respond("{\"streams\":[\"" + "a".repeat(3_000) + "\"]}", HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    private fun runtime(server: Server, key: String? = "secret", limits: RuntimeLimits = RuntimeLimits()) =
        ProviderPackageRuntime(pkg, HttpClient(server.engine) { followRedirects = false }, { key }, limits)

    @Test
    fun `provider chain resolves an episode by external id and filters foreign hosts`() = runBlocking {
        val server = Server()
        val provider = PackageStreamingProvider(runtime(server))
        val request = PlaybackRequest(
            anime = Anime(id = "1", title = "Show", episodes = 10, rating = 0f, imageFileName = null, orderIndex = 0, dateAdded = 0,
                mediaType = MediaType.SERIES, tmdbId = 1396),
            episodeNumber = 2,
            seasonInfo = SeasonInfo(seasonNumber = 2, episodes = 10, source = "test"),
        )
        val found = provider.resolve(request) as ProviderResolution.Found
        val videos = found.hosters.single().videos.orEmpty()
        assertEquals(listOf("https://cdn.example.com/e9/master.m3u8"), videos.map { it.url })
        assertEquals(1080, videos.single().resolution)
        // Субтитры с чужого хоста выброшены, свои остались.
        assertEquals(listOf("https://cdn.example.com/e9/en.vtt"), videos.single().subtitles.map { it.url })
        assertTrue(server.seen.all { it.endsWith("key=secret") })
        // Совпадение по tmdb в выдаче: «Other» с чужим id не взят.
        assertTrue(server.seen.any { it.contains("/titles/t-1/") })
    }

    @Test
    fun `no key means not configured and no request at all`() = runBlocking {
        val server = Server()
        assertEquals(PackageResult.NotConfigured, runtime(server, key = null).search(null, mapOf(ExternalIdKind.TMDB to "1396")))
        assertTrue(server.seen.isEmpty())
    }

    @Test
    fun `redirects out of the allowlist and oversized bodies are blocked`() = runBlocking {
        val server = Server()
        assertTrue(runtime(server).streams(unitId = "redirect") is PackageResult.Blocked)
        assertTrue(runtime(server, limits = RuntimeLimits(maxResponseBytes = 1_000)).streams(unitId = "huge") is PackageResult.Blocked)
        // Значение с «/» и «?» кодируется и не меняет адрес запроса.
        runtime(server).units("../admin?x=1", UnitKind.EPISODE)
        assertTrue(server.seen.last().contains("/v1/titles/..%2Fadmin%3Fx%3D1/episodes"))
    }

    // ---------- импорт ----------

    private class MemoryStore : InstalledSourceStore {
        val items = mutableListOf<InstalledSource>()
        override suspend fun all() = items.toList()
        override suspend fun install(source: InstalledSource): InstalledSource {
            items.removeAll { it.key == source.key }; items += source; return source
        }
        override suspend fun setEnabled(key: String, enabled: Boolean) = Unit
        override suspend fun remove(key: String) { items.removeAll { it.key == key } }
    }

    @Test
    fun `import shows a review first and pins the author key`() = runBlocking {
        val store = MemoryStore()
        val service = CustomSourceSettingsService(store, CustomSourceInstaller(), HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
        val keys = Ed25519Sign.KeyPair.newKeyPair()
        val signed = AppJson.encodeToString(JsonObject.serializer(), sign(root, keys))

        val review = service.installFromText(signed) as CustomSourceOutcome.ReviewRequired
        assertTrue("до подтверждения ничего не установлено", store.items.isEmpty())
        assertEquals(listOf("api.example.com", "cdn.example.com"), review.preview.hosts)
        assertTrue(review.preview.needsKey)
        assertTrue(service.confirm(review.preview) is CustomSourceOutcome.Installed)
        val stored = store.items.single().definition as InstalledSourceDefinition.Package
        assertEquals(Base64.getEncoder().encodeToString(keys.publicKey), stored.signerKey)
        assertEquals(64, stored.sha256.length)

        // Обновление тем же id, но чужим ключом — отказ ещё до просмотра.
        val foreign = sign(root.with("version", JsonPrimitive("1.1.0")), Ed25519Sign.KeyPair.newKeyPair())
        val rejected = service.installFromText(AppJson.encodeToString(JsonObject.serializer(), foreign))
        assertTrue(rejected is CustomSourceOutcome.Rejected)
        assertTrue((rejected as CustomSourceOutcome.Rejected).reason.contains("different key"))
    }

    @Test
    fun `oversized package is refused before parsing`() {
        val big = text.replace("\"Vetro tests\"", "\"" + "x".repeat(CustomSourceInstaller.MAX_PACKAGE_BYTES) + "\"")
        val parsed = CustomSourceInstaller().fromPackageJson(big)
        assertTrue(parsed is PackageParse.Rejected)
    }
}
