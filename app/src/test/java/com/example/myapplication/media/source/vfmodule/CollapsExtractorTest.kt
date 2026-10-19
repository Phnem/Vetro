package com.example.myapplication.media.source.vfmodule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollapsExtractorTest {

    private val sampleCollapsHtml = """
        <html>
        <body>
            <script>
                makePlayer({
                    file: "[1080p]https://stream.collaps.org/1080.m3u8,[720p]https://stream.collaps.org/720.m3u8"
                });
            </script>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun parseCollapsHtml_extractsQualityBrackets() {
        val extractor = CollapsExtractor(HttpClient(MockEngine { respond("") }))
        val videos = extractor.parseCollapsHtml(
            sampleCollapsHtml,
            "https://api.collaps.org/embed/movie/123",
            "https://api.collaps.org",
        )

        assertEquals(2, videos.size)
        assertEquals("1080p", videos[0].label)
        assertEquals("720p", videos[1].label)
        assertTrue(videos.all { it.isHlsOrDash })
    }
}
