package com.example.myapplication.media.source.vfmodule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniboomExtractorTest {

    private val sampleAniboomHtml = """
        <!DOCTYPE html>
        <html>
        <body>
            <video id="video" data-parameters="{&quot;hls&quot;:&quot;{\&quot;src\&quot;:\&quot;https://aniboom.one/hls/master.m3u8\&quot;}&quot;,&quot;mp4&quot;:&quot;https://aniboom.one/video/1080.mp4&quot;}"></video>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun parseAniboomHtml_extractsHlsAndMp4Streams() {
        val extractor = AniboomExtractor(HttpClient(MockEngine { respond("") }))
        val videos = extractor.parseAniboomHtml(
            sampleAniboomHtml,
            "https://aniboom.one/embed/abcdef",
        )

        assertEquals(2, videos.size)
        assertTrue(videos.any { it.isHlsOrDash && it.url == "https://aniboom.one/hls/master.m3u8" })
        assertTrue(videos.any { !it.isHlsOrDash && it.url == "https://aniboom.one/video/1080.mp4" })
        assertEquals("https://aniboom.one/", videos.first().headers["Referer"])
    }
}
