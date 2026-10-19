package com.example.myapplication.media.source.vfmodule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VkVideoExtractorTest {

    private val sampleVkHtml = """
        <!DOCTYPE html>
        <html>
        <body>
            <script>
                var videoParams = {
                    "url1080": "https:\/\/vkvd.net\/1080.mp4",
                    "url720": "https:\/\/vkvd.net\/720.mp4",
                    "url480": "https:\/\/vkvd.net\/480.mp4",
                    "hls": "https:\/\/vkvd.net\/master.m3u8"
                };
            </script>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun parseVkHtml_extractsMp4QualitiesAndHls() {
        val extractor = VkVideoExtractor(HttpClient(MockEngine { respond("") }))
        val videos = extractor.parseVkHtml(
            sampleVkHtml,
            "https://vk.com/video_ext.php?oid=123&id=456&hash=abc",
        )

        assertEquals(4, videos.size)
        assertTrue(videos.any { it.label == "1080p" && it.url == "https://vkvd.net/1080.mp4" })
        assertTrue(videos.any { it.label == "720p" && it.url == "https://vkvd.net/720.mp4" })
        assertTrue(videos.any { it.label == "480p" && it.url == "https://vkvd.net/480.mp4" })
        assertTrue(videos.any { it.isHlsOrDash && it.url == "https://vkvd.net/master.m3u8" })
    }
}
