package com.example.myapplication.media.source.vfmodule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerJsExtractorTest {

    private val samplePlayerHtml = """
        <!DOCTYPE html>
        <html>
        <body>
            <script>
                var player = new Playerjs({
                    id: "player",
                    file: "[1080p]https://cdn.example.com/1080.m3u8,[720p]https://cdn.example.com/720.m3u8"
                });
            </script>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun parsePlayerHtml_extractsQualityStreams() {
        val extractor = PlayerJsExtractor(HttpClient(MockEngine { respond("") }))
        val videos = extractor.parsePlayerHtml(
            samplePlayerHtml,
            "https://cdn.example.com/embed/123",
            "https://cdn.example.com",
            "vibix",
        )

        assertEquals(2, videos.size)
        assertEquals("1080p", videos[0].label)
        assertEquals("720p", videos[1].label)
        assertEquals("vibix", videos[0].providerKey)
    }
}
