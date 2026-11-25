package com.example.myapplication.media.source

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.movieseries.MovieSeriesStreamingProvider
import com.example.myapplication.media.source.movieseries.ProviderCapability
import com.example.myapplication.media.source.movieseries.ProviderId
import com.example.myapplication.media.source.movieseries.ProviderHealth
import com.example.myapplication.media.source.movieseries.ProviderHealthRegistry
import com.example.myapplication.media.source.movieseries.ProviderResolution
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieSeriesSourceCascadeTest {

    @Test
    fun `failing source configuration or lookup does not hide successful sibling`() = runBlocking {
        val failing = fakeSource("WebDAV") { error("server unavailable") }
        val direct = fakeSource("Direct HTTPS") {
            ProviderResolution.Found(listOf(workingHoster))
        }

        val outcome = resolveMovieSeriesSources(seriesRequest(), listOf(failing, direct), 1_000)

        assertEquals(listOf(workingVideoUrl), outcome.playableUrls())
    }

    @Test
    fun `not found sibling does not hide a later success`() = runBlocking {
        val missing = fakeSource("WebDAV") { ProviderResolution.NotFound }
        val found = fakeSource("Jellyfin") { ProviderResolution.Found(listOf(workingHoster)) }

        val outcome = resolveMovieSeriesSources(seriesRequest(), listOf(missing, found), 1_000)

        assertEquals(listOf(workingVideoUrl), outcome.playableUrls())
    }

    @Test
    fun `every provider answering not found reports no match rather than failure`() = runBlocking {
        val outcome = resolveMovieSeriesSources(
            seriesRequest(),
            listOf(
                fakeSource("WebDAV") { ProviderResolution.NotFound },
                fakeSource("Jellyfin") { ProviderResolution.NotFound },
            ),
            1_000,
        )

        assertEquals(PlaybackResolution.NoMatch, outcome)
    }

    @Test
    fun `provider level errors surface as failure not as a clean no match`() = runBlocking {
        val outcome = resolveMovieSeriesSources(
            seriesRequest(),
            listOf(
                fakeSource("WebDAV") { ProviderResolution.TemporaryError("HTTP 503") },
                fakeSource("Jellyfin") { ProviderResolution.RateLimited(1_000) },
            ),
            1_000,
        )

        assertEquals(PlaybackResolution.Failure, outcome)
    }

    @Test
    fun `unconfigured providers alone report not configured`() = runBlocking {
        val outcome = resolveMovieSeriesSources(
            seriesRequest(),
            listOf(
                fakeSource("WebDAV") { ProviderResolution.NotConfigured },
                fakeSource("Emby") { ProviderResolution.NotConfigured },
            ),
            1_000,
        )

        assertEquals(PlaybackResolution.NotConfigured(MediaType.SERIES), outcome)
    }

    @Test
    fun `provider without the media capability is never called`() = runBlocking {
        var called = false
        val movieOnly = fakeSource("Movies only", capabilities = setOf(ProviderCapability.MOVIE)) {
            called = true
            ProviderResolution.Found(listOf(workingHoster))
        }

        val outcome = resolveMovieSeriesSources(seriesRequest(), listOf(movieOnly), 1_000)

        assertFalse("A series request must not reach a movie-only provider", called)
        assertEquals(PlaybackResolution.NotConfigured(MediaType.SERIES), outcome)
    }

    @Test
    fun `provider of the other language is never called`() = runBlocking {
        var called = false
        val ruOnly = fakeSource(
            "RU only",
            capabilities = setOf(ProviderCapability.SERIES, ProviderCapability.RU),
        ) {
            called = true
            ProviderResolution.Found(listOf(workingHoster))
        }

        resolveMovieSeriesSources(seriesRequest(), listOf(ruOnly), 1_000)

        assertFalse("An EN request must not reach an RU-only provider", called)
    }

    @Test
    fun `cancelling the caller cancels the cascade`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = launch {
            resolveMovieSeriesSources(
                seriesRequest(),
                listOf(fakeSource("WebDAV") { started.complete(Unit); awaitCancellation() }),
                60_000,
            )
        }
        started.await()

        withTimeout(2_000) { job.cancelAndJoin() }

        assertTrue("The caller must stay cancelled", job.isCancelled)
    }

    @Test
    fun `a provider cancelled from inside is a failure, not an endless wait`() = runBlocking {
        val outcome = withTimeout(2_000) {
            resolveMovieSeriesSources(
                seriesRequest(),
                listOf(fakeSource("WebDAV") { throw CancellationException("sub-call cancelled") }),
                1_000,
            )
        }

        assertTrue(outcome !is PlaybackResolution.Found)
    }

    @Test
    fun `a parked provider is skipped without paying its timeout`() = runBlocking {
        var called = false
        val parked = fakeSource("Dead") {
            called = true
            ProviderResolution.Found(listOf(workingHoster))
        }
        val registry = FakeHealth(
            mapOf(parked.id to ProviderHealth(temporarilyDisabledUntil = 5_000))
        )

        val outcome = resolveMovieSeriesSources(
            seriesRequest(),
            listOf(parked),
            timeoutMs = 1_000,
            health = registry,
            now = { 1_000 },
        )

        assertFalse("A parked provider must not be contacted", called)
        assertEquals(PlaybackResolution.Failure, outcome)
    }

    @Test
    fun `a healthy sibling still runs while another provider is parked`() = runBlocking {
        val parked = fakeSource("Dead") { ProviderResolution.Found(listOf(workingHoster)) }
        val healthy = fakeSource("Alive") { ProviderResolution.Found(listOf(workingHoster)) }
        val registry = FakeHealth(
            mapOf(parked.id to ProviderHealth(temporarilyDisabledUntil = 5_000))
        )

        val outcome = resolveMovieSeriesSources(
            seriesRequest(),
            listOf(parked, healthy),
            timeoutMs = 1_000,
            health = registry,
            now = { 1_000 },
        )

        assertEquals(listOf(workingVideoUrl), outcome.playableUrls())
    }

    @Test
    fun `parking expires and the provider is contacted again`() = runBlocking {
        var called = false
        val recovering = fakeSource("Recovering") {
            called = true
            ProviderResolution.Found(listOf(workingHoster))
        }
        val registry = FakeHealth(
            mapOf(recovering.id to ProviderHealth(temporarilyDisabledUntil = 5_000))
        )

        resolveMovieSeriesSources(
            seriesRequest(),
            listOf(recovering),
            timeoutMs = 1_000,
            health = registry,
            now = { 6_000 },
        )

        assertTrue("Once the pause expires the provider must be retried", called)
    }

    @Test
    fun `a timed out attempt is still recorded against provider health`() = runBlocking {
        val slow = fakeSource("Slow") {
            kotlinx.coroutines.delay(5_000)
            ProviderResolution.NotFound
        }
        val registry = FakeHealth(emptyMap())

        resolveMovieSeriesSources(seriesRequest(), listOf(slow), timeoutMs = 50, health = registry)

        assertEquals(1, registry.recorded.size)
        assertTrue(registry.recorded.single().second is ProviderResolution.TemporaryError)
    }

    private class FakeHealth(
        private val initial: Map<ProviderId, ProviderHealth>,
    ) : ProviderHealthRegistry {
        val recorded = mutableListOf<Pair<ProviderId, ProviderResolution>>()

        override fun healthOf(providerId: ProviderId): ProviderHealth =
            initial[providerId] ?: ProviderHealth()

        override suspend fun record(
            providerId: ProviderId,
            outcome: ProviderResolution,
            elapsedMs: Long,
        ) {
            recorded += providerId to outcome
        }
    }

    private val workingVideoUrl = "https://owned.example/house-s01e02.mp4"

    private val workingHoster = VetroHoster(
        name = "Direct HTTPS",
        videos = listOf(VetroVideo(url = workingVideoUrl, label = "Auto")),
    )

    /**
     * Compares by stream URL rather than whole objects: the cascade now attributes each video to the
     * provider that produced it, so asserting object identity would just re-state the plumbing.
     */
    private fun PlaybackResolution.playableUrls(): List<String> =
        (this as? PlaybackResolution.Found)
            ?.hosters
            ?.flatMap { hoster -> hoster.videos.orEmpty().map(VetroVideo::url) }
            .orEmpty()

    private fun fakeSource(
        name: String,
        capabilities: Set<ProviderCapability> = setOf(
            ProviderCapability.MOVIE,
            ProviderCapability.SERIES,
        ),
        resolve: suspend () -> ProviderResolution,
    ) = object : MovieSeriesStreamingProvider {
        override val id: ProviderId = ProviderId(name)
        override val displayName: String = name
        override val capabilities: Set<ProviderCapability> = capabilities
        override suspend fun resolve(request: PlaybackRequest): ProviderResolution = resolve()
    }

    private fun seriesRequest() = PlaybackRequest(
        anime = Anime(
            id = "house",
            title = "Doctor House",
            episodes = 1,
            rating = 0f,
            imageFileName = null,
            orderIndex = 0,
            dateAdded = 0,
            mediaType = MediaType.SERIES,
        ),
        episodeNumber = 2,
        language = AppLanguage.EN,
    )
}
