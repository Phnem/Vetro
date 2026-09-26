package com.example.myapplication.media.subtitles

import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.PersonalMediaServerConfig
import com.example.myapplication.media.source.PersonalMediaServerProvider
import com.example.myapplication.media.source.PlaybackIdentity
import com.example.myapplication.media.source.PlaybackSourceConfigStore
import com.example.myapplication.media.source.UserAccountConfig
import com.example.myapplication.media.source.UserAccountKind
import com.example.myapplication.media.source.WebDavConfig
import com.example.myapplication.network.TokenBucketRateLimiter
import com.example.myapplication.network.enrichment.EnrichmentHttp
import com.example.myapplication.network.enrichment.OpenSubtitlesClient
import com.example.myapplication.network.enrichment.SubtitleCandidate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSubtitleServiceTest {

    private fun candidate(
        id: Long,
        lang: String,
        downloads: Int,
        trusted: Boolean = false,
        hi: Boolean = false,
        machine: Boolean = false,
    ) = SubtitleCandidate(id, lang, "rel$id", null, downloads, null, hi, machine, false, trusted)

    @Test
    fun `ranking puts ui language first, prefers trusted human subtitles`() {
        val offers = SubtitleRanking.rank(
            listOf(
                candidate(1, "en", 900),
                candidate(2, "ru", 50, machine = true),
                candidate(3, "ru", 10),
                candidate(4, "ru", 5, trusted = true),
                candidate(5, "ru", 999, hi = true),
                candidate(6, "de", 5000),
            ),
            listOf("ru", "en"),
        )
        assertEquals(listOf(4L, 3L, 5L, 1L), offers.map { it.fileId })
        // Машинный перевод — только если живого нет.
        assertEquals(listOf(7L), SubtitleRanking.rank(listOf(candidate(7, "ru", 1, machine = true)), listOf("ru")).map { it.fileId })
    }

    private class Store(var account: UserAccountConfig?) : PlaybackSourceConfigStore {
        override fun webDav(): WebDavConfig? = null
        override fun saveWebDav(config: WebDavConfig) = Unit
        override fun clearWebDav() = Unit
        override fun personalServer(provider: PersonalMediaServerProvider): PersonalMediaServerConfig? = null
        override fun savePersonalServer(provider: PersonalMediaServerProvider, config: PersonalMediaServerConfig) = Unit
        override fun clearPersonalServer(provider: PersonalMediaServerProvider) = Unit
        override fun account(kind: UserAccountKind): UserAccountConfig? = account
    }

    @Test
    fun `download is cached by file id, expired token triggers one relogin, quota is reported`() = runBlocking {
        val calls = mutableListOf<String>()
        var downloadStatus = listOf(HttpStatusCode.Unauthorized, HttpStatusCode.OK)
        var downloadCalls = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            calls += path
            when {
                path.endsWith("/login") -> respond("""{"token":"t${calls.count { it.endsWith("/login") }}","user":{"allowed_downloads":5}}""", HttpStatusCode.OK)
                path.endsWith("/download") -> {
                    val status = downloadStatus[minOf(downloadCalls++, downloadStatus.lastIndex)]
                    respond(if (status == HttpStatusCode.OK) """{"link":"https://dl/x.srt","remaining":4}""" else "{}", status)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = OpenSubtitlesClient(
            EnrichmentHttp(HttpClient(engine)),
            TokenBucketRateLimiter(maxTokens = 100.0, refillTokensPerSecond = 100.0),
            apiKey = { "key" },
        )
        val dir = Files.createTempDirectory("subs").toFile()
        var fetched = 0
        val service = ExternalSubtitleService(
            client = client,
            accounts = Store(UserAccountConfig("me", "pw")),
            dir = dir,
            fetchBytes = { fetched++; "1\n00:00:01,000 --> 00:00:02,000\nПривет\n".toByteArray() },
        )
        val offer = SubtitleOffer(42L, "ru", "WEB", false)

        val first = service.load(offer)
        assertTrue(first is SubtitleLoad.Loaded)
        val track = (first as SubtitleLoad.Loaded).track
        assertEquals("opensubtitles:42", track.id)
        assertEquals("application/x-subrip", track.mimeType)
        assertTrue(track.isUserAdded)
        // 401 на скачивании → повторный вход → успех.
        assertEquals(2, calls.count { it.endsWith("/login") })

        // Второй раз — из файла, без сети и без расхода квоты.
        val before = calls.size
        assertTrue(service.load(offer) is SubtitleLoad.Loaded)
        assertEquals(before, calls.size)
        assertEquals(1, fetched)

        downloadStatus = listOf(HttpStatusCode.NotAcceptable)
        downloadCalls = 0
        assertEquals(SubtitleLoad.QuotaExhausted, service.load(offer.copy(fileId = 43L)))
    }

    @Test
    fun `no identity or no account gives no offers`() = runBlocking {
        val client = OpenSubtitlesClient(
            EnrichmentHttp(HttpClient(MockEngine { respond("", HttpStatusCode.InternalServerError) })),
            TokenBucketRateLimiter(maxTokens = 10.0, refillTokensPerSecond = 10.0),
            apiKey = { "key" },
        )
        val service = ExternalSubtitleService(client, Store(null), Files.createTempDirectory("s").toFile(), { null })
        val identity = PlaybackIdentity(libraryId = "1", title = "x", mediaType = MediaType.MOVIE, imdbId = "tt1375666")
        assertEquals(emptyList<SubtitleOffer>(), service.offers(identity, null, null, listOf("ru")))
        assertEquals(false, service.isAvailable)
    }

    @Test
    fun `label names the language in itself and trims the release`() {
        assertEquals("Русский · WEB-DL", subtitleLabel(SubtitleOffer(1, "ru", "WEB-DL", false)))
        assertEquals("English · Inception.2010.1080p.… · SDH", subtitleLabel(SubtitleOffer(1, "en", "Inception.2010.1080p.BluRay.x264", true)))
    }
}
