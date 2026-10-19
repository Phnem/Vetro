package com.example.myapplication.media.source.vfmodule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SibnetExtractorTest {

    private val sampleSibnetHtml = """
        <!DOCTYPE html>
        <html>
        <body>
            <script>
                player.src([{src: "/v/abcdef123456.mp4", type: "video/mp4"}]);
            </script>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun parseSibnetHtml_extractsMp4DirectStream() {
        val extractor = SibnetExtractor(HttpClient(MockEngine { respond("") }))
        val videos = extractor.parseSibnetHtml(
            sampleSibnetHtml,
            "https://video.sibnet.ru/shell.php?videoid=123456",
        )

        assertEquals(1, videos.size)
        val video = videos.first()
        assertEquals("https://video.sibnet.ru/v/abcdef123456.mp4", video.url)
        assertFalse(video.isHlsOrDash)
        assertEquals("480p", video.label)
        assertEquals("https://video.sibnet.ru/shell.php?videoid=123456", video.headers["Referer"])
    }
}
