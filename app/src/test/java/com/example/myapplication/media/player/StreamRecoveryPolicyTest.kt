package com.example.myapplication.media.player

import com.example.myapplication.media.source.VetroVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRecoveryPolicyTest {

    @Test
    fun `watchdog emits exactly once after eight continuous seconds`() {
        val watchdog = ContinuousBufferingWatchdog(thresholdMs = 8_000L)

        assertFalse(watchdog.sample(nowMs = 1_000L, buffering = true))
        assertFalse(watchdog.sample(nowMs = 8_999L, buffering = true))
        assertTrue(watchdog.sample(nowMs = 9_000L, buffering = true))
        assertFalse(watchdog.sample(nowMs = 20_000L, buffering = true))

        assertFalse(watchdog.sample(nowMs = 21_000L, buffering = false))
        assertFalse(watchdog.sample(nowMs = 22_000L, buffering = true))
        assertTrue(watchdog.sample(nowMs = 30_000L, buffering = true))
    }

    @Test
    fun `watchdog never retries the bad url`() {
        assertEquals(
            StreamRecoveryAction.SWITCH_CANDIDATE,
            policy.decide(input(trigger = StreamRecoveryTrigger.WATCHDOG, hasFallback = true)),
        )
        assertEquals(
            StreamRecoveryAction.RERESOLVE,
            policy.decide(input(trigger = StreamRecoveryTrigger.WATCHDOG, hasFallback = false)),
        )
    }

    @Test
    fun `buffer bands allow only one same url rebuild when safe`() {
        for (bufferedMs in listOf(8_000L, 15_000L, 20_001L)) {
            assertEquals(
                StreamRecoveryAction.RETRY_CURRENT,
                policy.decide(input(bufferedMs = bufferedMs)),
            )
            assertEquals(
                StreamRecoveryAction.SWITCH_CANDIDATE,
                policy.decide(input(bufferedMs = bufferedMs, retryAlreadyUsed = true)),
            )
        }
        assertEquals(
            StreamRecoveryAction.SWITCH_CANDIDATE,
            policy.decide(input(bufferedMs = 7_999L)),
        )
    }

    @Test
    fun `http statuses select resolve fallback or buffer aware retry`() {
        assertEquals(
            StreamRecoveryAction.RERESOLVE,
            policy.decide(input(httpStatus = 403)),
        )
        for (status in listOf(404, 410)) {
            assertEquals(
                StreamRecoveryAction.SWITCH_CANDIDATE,
                policy.decide(input(httpStatus = status)),
            )
        }
        for (status in listOf(429, 503)) {
            assertEquals(
                StreamRecoveryAction.SWITCH_CANDIDATE,
                policy.decide(input(httpStatus = status, bufferedMs = 7_999L)),
            )
            assertEquals(
                StreamRecoveryAction.RETRY_CURRENT,
                policy.decide(input(httpStatus = status, bufferedMs = 8_000L)),
            )
        }
    }

    @Test
    fun `fallback ranking prefers another host without quality increase then lower same host`() {
        val current = video("https://slow.example/1080.m3u8", 1080)
        val otherHostSame = video("https://fast.example/1080.m3u8", 1080)
        val otherHostLower = video("https://backup.example/720.m3u8", 720)
        val sameHostLower = video("https://slow.example/720.m3u8", 720)
        val otherHostHigher = video("https://fast.example/1440.m3u8", 1440)

        assertEquals(
            listOf(otherHostSame, otherHostLower, sameHostLower, otherHostHigher),
            rankRecoveryCandidates(
                current = current,
                candidates = listOf(otherHostHigher, sameHostLower, otherHostLower, otherHostSame),
                failedUrls = emptySet(),
            ),
        )
    }

    @Test
    fun `fallback ranking excludes failed current and same host non lower urls`() {
        val current = video("https://slow.example/current.m3u8", 720)
        val failed = video("https://fast.example/failed.m3u8", 720)
        val sameHostSame = video("https://slow.example/equal.m3u8", 720)
        val viable = video("https://backup.example/480.m3u8", 480)

        assertEquals(
            listOf(viable),
            rankRecoveryCandidates(
                current,
                listOf(current, failed, sameHostSame, viable),
                failedUrls = setOf(failed.url),
            ),
        )
    }

    @Test
    fun `zero is a valid pending recovery position and wins over stored progress`() {
        assertEquals(0L, selectResumePosition(0L, 42_000L))
        assertEquals(42_000L, selectResumePosition(null, 42_000L))
        assertEquals(null, selectResumePosition(null, null))
    }

    @Test
    fun `preferred video carries the previously chosen studio across episodes`() {
        val aniLibria = video("https://anilibria.example/e2.m3u8", 1080, sourceName = "AniLibria")
        val kodik = video("https://kodik.example/e2.m3u8", 1080, sourceName = "Kodik")

        assertEquals(
            aniLibria,
            selectPreferredVideo(listOf(kodik, aniLibria), preferredSourceName = "AniLibria"),
        )
    }

    @Test
    fun `preferred video falls back to the resolver default when the studio is gone`() {
        val kodik = video("https://kodik.example/e2.m3u8", 1080, sourceName = "Kodik")
        val jutSu = video("https://jutsu.example/e2.m3u8", 1080, sourceName = "JutSu")

        assertEquals(
            kodik,
            selectPreferredVideo(listOf(kodik, jutSu), preferredSourceName = "AniLibria"),
        )
        assertEquals(
            kodik,
            selectPreferredVideo(listOf(kodik, jutSu), preferredSourceName = null),
        )
        assertEquals(null, selectPreferredVideo(emptyList(), preferredSourceName = "AniLibria"))
    }

    private fun input(
        trigger: StreamRecoveryTrigger = StreamRecoveryTrigger.PLAYER_ERROR,
        bufferedMs: Long = 12_000L,
        retryAlreadyUsed: Boolean = false,
        httpStatus: Int? = null,
        hasFallback: Boolean = true,
    ) = StreamRecoveryInput(
        trigger = trigger,
        bufferedDurationMs = bufferedMs,
        retryAlreadyUsed = retryAlreadyUsed,
        httpStatus = httpStatus,
        hasFallback = hasFallback,
    )

    @Test
    fun `fallback ranking keeps the dub before host and quality`() {
        val current = video("https://a.example/ep.m3u8", 1080, sourceName = "AniDUB")
        val otherDub = video("https://b.example/ep.m3u8", 1080, sourceName = "AniLibria")
        val sameDubOtherPlayer = video("https://c.example/ep.m3u8", 720, sourceName = "Kodik · AniDUB")
        val english = video("https://d.example/ep.m3u8", 1080, sourceName = "HiAnime Sub")

        val ranked = rankRecoveryCandidates(current, listOf(english, otherDub, sameDubOtherPlayer), emptySet())

        // Та же студия у другого плеера — первой, даже с худшим качеством.
        assertEquals(sameDubOtherPlayer, ranked.first())
        assertEquals(3, ranked.size)
    }

    @Test
    fun `dub similarity levels`() {
        assertEquals(3, dubSimilarity("AniLibria", "anilibria"))
        assertEquals(2, dubSimilarity("AniDUB", "Kodik · AniDUB"))
        assertEquals(1, dubSimilarity("Студийная Банда", "Кубик в Кубе"))
        assertEquals(0, dubSimilarity("Студийная Банда", "Crunchyroll"))
        assertEquals(0, dubSimilarity(null, "AniDUB"))
    }

    private fun video(url: String, resolution: Int, sourceName: String? = null) = VetroVideo(
        url = url,
        label = "${resolution}p",
        resolution = resolution,
        sourceName = sourceName,
    )

    private companion object {
        val policy = StreamRecoveryPolicy()
    }
}
