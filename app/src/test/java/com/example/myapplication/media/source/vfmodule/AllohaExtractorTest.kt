package com.example.myapplication.media.source.vfmodule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllohaExtractorTest {

    private val sampleIframeHtml = """
        <html>
        <body>
            <script>
                var player = {
                    files: {
                        "1080": "https://stream.alloha.tv/1080.m3u8",
                        "720": "https://stream.alloha.tv/720.m3u8",
                        "480": "https://stream.alloha.tv/480.m3u8"
                    }
                };
            </script>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun parseAllohaHtml_extractsQualityStreams() {
        val extractor = AllohaExtractor(HttpClient(MockEngine { respond("") }))
        val videos = extractor.parseAllohaHtml(
            sampleIframeHtml,
            "https://alloha.tv/embed/123",
            "https://alloha.tv",
        )

        assertEquals(3, videos.size)
        assertEquals("1080p", videos[0].label)
        assertEquals("720p", videos[1].label)
        assertEquals("480p", videos[2].label)
        assertTrue(videos.all { it.isHlsOrDash })
    }
}
