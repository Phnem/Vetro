package com.example.myapplication.media.source

import com.example.myapplication.media.downloadableCandidates
import com.example.myapplication.media.fitCandidatesToBudget
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadCapabilityTest {

    @Test
    fun `playable candidate can explicitly forbid download`() {
        val streamOnly = VetroVideo(
            url = "https://media.example/title.m3u8",
            label = "Auto",
            downloadAllowed = false,
        )
        val ownedFile = VetroVideo(
            url = "https://cloud.example/title.mp4",
            label = "Auto",
            downloadAllowed = true,
        )

        assertFalse(streamOnly.downloadAllowed)
        assertTrue(ownedFile.downloadAllowed)
        assertThrows(IllegalArgumentException::class.java) {
            downloadableCandidates(streamOnly, listOf(ownedFile))
        }
        assertEquals(listOf(ownedFile), downloadableCandidates(ownedFile, listOf(streamOnly)))
    }

    @Test
    fun `background persistence removes credentials but keeps encrypted lookup reference`() {
        val video = VetroVideo(
            url = "https://cloud.example/title.mp4",
            label = "Auto",
            headers = mapOf("Authorization" to "Basic secret", "User-Agent" to "Vetro"),
            credentialRef = PlaybackCredentialRef("webdav:0123456789abcdef01234567"),
        )

        val persisted = video.withoutPersistedSecrets()

        assertFalse(persisted.headers.containsKey("Authorization"))
        assertEquals("Vetro", persisted.headers["User-Agent"])
        assertEquals(
            PlaybackCredentialRef("webdav:0123456789abcdef01234567"),
            persisted.credentialRef,
        )

        val signed = VetroVideo(
            url = "https://cloud.example/title.mp4?X-Amz-Signature=secret",
            label = "Auto",
            downloadAllowed = true,
        )
        assertThrows(IllegalArgumentException::class.java) {
            downloadableCandidates(signed, emptyList())
        }

        val signedReferer = VetroVideo(
            url = "https://cloud.example/title.mp4",
            label = "Auto",
            headers = mapOf("Referer" to "https://portal.example/watch?token=secret"),
            downloadAllowed = true,
        )
        assertThrows(IllegalArgumentException::class.java) {
            downloadableCandidates(signedReferer, emptyList())
        }
        assertFalse(signedReferer.withoutPersistedSecrets().headers.containsKey("Referer"))
    }

    @Test
    fun `fallback candidates are trimmed from the tail to fit the work input budget`() {
        // Как у Kodik: два десятка потоков с Referer — вместе больше потолка WorkManager (10 КБ).
        val kodik = (1..23).map { i ->
            VetroVideo(
                url = "https://cloud.kodik-storage.com/useruploads/${"x".repeat(80)}/$i/720.mp4:hls:manifest.m3u8",
                label = "Kodik $i · 720p",
                headers = mapOf("Referer" to "https://kodik.info/seria/$i/${"y".repeat(60)}/720p", "User-Agent" to "Mozilla/5.0"),
                downloadAllowed = true,
            )
        }
        val json = Json { encodeDefaults = true }
        val encode: (List<VetroVideo>) -> String = { json.encodeToString(it) }
        assertTrue(encode(kodik).length > 10_240)

        val fitted = fitCandidatesToBudget(kodik, 6_000, encode)

        assertTrue(fitted.isNotEmpty())
        assertTrue(encode(fitted).toByteArray().size <= 6_000)
        assertEquals(kodik.take(fitted.size), fitted) // хвост, а не лучшие
    }
}
