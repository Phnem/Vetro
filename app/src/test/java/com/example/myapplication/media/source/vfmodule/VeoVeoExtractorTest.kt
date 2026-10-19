package com.example.myapplication.media.source.vfmodule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VeoVeoExtractorTest {

    private val sampleIframeHtml = """
        <html>
        <head>
            <script>
                var options = {
                    hlsUrl: "https://veoveo.example/video/master.m3u8",
                    dashUrl: "https://veoveo.example/video/manifest.mpd"
                };
            </script>
        </head>
        </html>
    """.trimIndent()

    @Test
    fun parseVeoVeoHtml_extractsHlsAndDash() {
        val extractor = VeoVeoExtractor(HttpClient(MockEngine { respond("") }))
        val videos = extractor.parseVeoVeoHtml(
            sampleIframeHtml,
            "https://veoveo.example/embed/1",
            "https://veoveo.example",
        )

        assertEquals(2, videos.size)
        assertTrue(videos.any { it.isHlsOrDash && it.url.contains(".m3u8") })
        assertTrue(videos.any { it.isHlsOrDash && it.url.contains(".mpd") })
    }
}
