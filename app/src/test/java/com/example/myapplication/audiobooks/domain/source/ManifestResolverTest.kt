package com.example.myapplication.audiobooks.domain.source

import com.example.myapplication.audiobooks.domain.model.AudioTrack
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.audiobooks.domain.model.VariantId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManifestResolverTest {
    @Test fun uriRoundTripsVariantWithSeparatorsAndUnicode() {
        val variant = VariantId("abs:my-server/Путь:книга")
        val uri = TrackUriCodec.encode(variant, 12)
        assertEquals(TrackUriCodec.TrackRef(variant, 12), TrackUriCodec.decode(uri))
        assertNull(TrackUriCodec.decode("vetro-audio://track/not-base64/-1"))
        assertNull(TrackUriCodec.decode("https://example.com/book.mp3"))
    }

    @Test fun expiryAndInvalidationFetchFreshManifest() = runBlocking {
        val variant = VariantId("local:book")
        var now = 100_000L
        var calls = 0
        val source = object : ManifestSource {
            override fun supports(variant: VariantId) = variant.value.startsWith("local:")
            override suspend fun refresh(variant: VariantId): MediaManifest {
                calls++
                return MediaManifest(variant, listOf(AudioTrack(0, "https://example.org/v$calls.mp3")), emptyList(), now, now + 60_000)
            }
        }
        val resolver = ManifestResolver(listOf(source)) { now }
        assertEquals("https://example.org/v1.mp3", resolver.resolve(variant, 0).url)
        assertEquals("https://example.org/v1.mp3", resolver.resolve(variant, 0).url)
        now += 31_000
        assertEquals("https://example.org/v2.mp3", resolver.resolve(variant, 0).url)
        resolver.invalidate(variant)
        assertEquals("https://example.org/v3.mp3", resolver.resolve(variant, 0).url)
        assertEquals(3, calls)
    }
}
