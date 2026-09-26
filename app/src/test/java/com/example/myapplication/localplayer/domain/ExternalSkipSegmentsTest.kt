package com.example.myapplication.localplayer.domain

import com.example.myapplication.network.enrichment.AnimeSkipEpisode
import com.example.myapplication.network.enrichment.EnrichmentSource
import com.example.myapplication.network.enrichment.ExternalSkipKind
import com.example.myapplication.network.enrichment.ExternalSkipSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalSkipSegmentsTest {

    private fun seg(kind: ExternalSkipKind, start: Long, end: Long, ref: Long? = null, confidence: Double? = null) =
        ExternalSkipSegment(kind, start, end, ref, confidence, null, EnrichmentSource.ANIME_SKIP)

    private fun version(number: Int, base: Long, vararg segments: ExternalSkipSegment) =
        AnimeSkipEpisode(1, number, null, base, segments.toList())

    @Test
    fun `anime-skip picks the version whose length matches the video`() {
        val tv = version(3, 1_420_000L, seg(ExternalSkipKind.OPENING, 60_000L, 150_000L, 1_420_000L))
        val bd = version(3, 1_440_000L, seg(ExternalSkipKind.OPENING, 90_000L, 180_000L, 1_440_000L))
        val other = version(4, 1_440_000L, seg(ExternalSkipKind.OPENING, 1L, 2L, 1_440_000L))

        val pick = ExternalSkipMatching.animeSkip(listOf(tv, bd, other), episode = 3, durationMs = 1_441_000L)!!
        assertEquals(1_440_000L, pick.referenceDurationMs)
        assertEquals(listOf(SkipSegment(90_000L, 180_000L, SkipKind.OPENING)), pick.segments)
        assertEquals("Anime-Skip", pick.origin)
        // Ни одна версия не совпадает по длине (другой монтаж) — ничего не применяем.
        assertNull(ExternalSkipMatching.animeSkip(listOf(tv, bd), episode = 3, durationMs = 1_300_000L))
    }

    @Test
    fun `introdb keeps confident segments that fit and drops post credits`() {
        val list = listOf(
            seg(ExternalSkipKind.OPENING, 10_000L, 70_000L, confidence = 0.9),
            seg(ExternalSkipKind.RECAP, 0L, 9_000L, confidence = 0.3),
            seg(ExternalSkipKind.ENDING, 3_431_000L, 3_500_000L, confidence = 1.0),
            seg(ExternalSkipKind.POST_CREDITS, 3_500_000L, 3_520_000L, confidence = 1.0),
        )
        val pick = ExternalSkipMatching.introDb(list, durationMs = 3_480_000L)!!
        assertEquals(
            listOf(
                SkipSegment(10_000L, 70_000L, SkipKind.OPENING),
                SkipSegment(3_431_000L, 3_480_000L, SkipKind.ENDING),
            ),
            pick.segments,
        )
        // Видео намного короче размеченного — эндинг из другой сборки не применяется.
        assertEquals(
            listOf(SkipSegment(10_000L, 70_000L, SkipKind.OPENING)),
            ExternalSkipMatching.introDb(list, durationMs = 3_000_000L)!!.segments,
        )
    }

    private class FakeExternal(
        val supplement: ExternalSkipSelection? = null,
        val fallback: ExternalSkipSelection? = null,
    ) : ExternalSkipLookup {
        var supplementCalls = 0
        var fallbackCalls = 0
        override suspend fun supplement(request: SkipSegmentRequest) = supplement.also { supplementCalls++ }
        override suspend fun fallback(request: SkipSegmentRequest) = fallback.also { fallbackCalls++ }
    }

    @Test
    fun `aniskip stays primary and gains recap and preview without overlaps`() = runBlocking {
        val external = FakeExternal(
            supplement = ExternalSkipSelection(
                listOf(
                    SkipSegment(0L, 20_000L, SkipKind.RECAP),
                    SkipSegment(35_000L, 45_000L, SkipKind.PREVIEW),
                    SkipSegment(1_420_000L, 1_440_000L, SkipKind.PREVIEW),
                ),
                1_440_000L,
                "Anime-Skip",
            ),
        )
        val resolver = SkipSegmentResolver(
            aniSkip = AniSkipLookup { _, _, _, _ ->
                AniSkipSelection(listOf(SkipSegment(30_000L, 120_000L, SkipKind.OPENING)), 1_440_000L)
            },
            external = external,
        )
        val result = resolver.resolve(SkipSegmentRequest(1, 2, 3, 1_440_000L))
        assertEquals(
            listOf(
                SkipSegment(0L, 20_000L, SkipKind.RECAP),
                SkipSegment(30_000L, 120_000L, SkipKind.OPENING),
                SkipSegment(1_420_000L, 1_440_000L, SkipKind.PREVIEW),
            ),
            result.segments,
        )
        assertEquals("AniSkip+Anime-Skip", result.origin)
        assertEquals(0, external.fallbackCalls)
    }

    @Test
    fun `external fallback when aniskip is empty or ids are missing`() = runBlocking {
        val external = FakeExternal(
            fallback = ExternalSkipSelection(listOf(SkipSegment(5_000L, 60_000L, SkipKind.OPENING)), null, "IntroDB"),
        )
        var aniSkipCalls = 0
        val resolver = SkipSegmentResolver(
            aniSkip = AniSkipLookup { _, _, _, _ -> aniSkipCalls++; AniSkipSelection(emptyList(), null) },
            external = external,
        )
        val movie = resolver.resolve(SkipSegmentRequest(null, null, null, 7_000_000L, imdbId = "tt1375666", isMovie = true))
        assertEquals("IntroDB", movie.origin)
        assertEquals(0, aniSkipCalls)
        assertEquals(1, external.fallbackCalls)
    }

    @Test
    fun `external failure never breaks aniskip result`() = runBlocking {
        val resolver = SkipSegmentResolver(
            aniSkip = AniSkipLookup { _, _, _, _ ->
                AniSkipSelection(listOf(SkipSegment(30_000L, 120_000L, SkipKind.OPENING)), 1_440_000L)
            },
            external = object : ExternalSkipLookup {
                override suspend fun supplement(request: SkipSegmentRequest): ExternalSkipSelection? = error("down")
                override suspend fun fallback(request: SkipSegmentRequest): ExternalSkipSelection? = error("down")
            },
        )
        val result = resolver.resolve(SkipSegmentRequest(1, 2, 3, 1_440_000L))
        assertEquals("AniSkip", result.origin)
        assertEquals(1, result.segments.size)
    }
}
