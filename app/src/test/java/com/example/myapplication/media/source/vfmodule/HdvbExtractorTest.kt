package com.example.myapplication.media.source.vfmodule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdvbExtractorTest {

    private val sampleIframeHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="csrf-token" content="mock_csrf_token_xyz">
        </head>
        <body>
            <script>
                var href = "https://vid169.site/embed/12345";
                var key = "test_sec_key_456";
                var file = "encrypted_or_plain_file_ref";
                var url = "/playlist";
            </script>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun parseIframeHtml_extractsCsrfTokenKeyFileAndHref() {
        val extractor = HdvbExtractor(HttpClient(MockEngine { respond("") }))
        val params = extractor.parseIframeHtml(sampleIframeHtml, "https://vid169.site/embed/12345")

        assertNotNull(params)
        assertEquals("mock_csrf_token_xyz", params?.csrfToken)
        assertEquals("test_sec_key_456", params?.key)
        assertEquals("encrypted_or_plain_file_ref", params?.file)
        assertEquals("https://vid169.site/playlist", params?.playlistUrl)
    }

    @Test
    fun extract_postsWithCsrfHeaderAndOrigin() = runBlocking {
        var recordedCsrf = ""
        var recordedOrigin = ""
        var recordedReferer = ""

        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath.contains("embed")) {
                respond(sampleIframeHtml)
            } else {
                recordedCsrf = request.headers["x-csrf-token"].orEmpty()
                recordedOrigin = request.headers["Origin"].orEmpty()
                recordedReferer = request.headers["Referer"].orEmpty()
                respond(
                    content = """{"file": "https://vid169.site/hls/12345/index.m3u8"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }

        val client = HttpClient(mockEngine)
        val extractor = HdvbExtractor(client)
        val videos = extractor.extract("https://vid169.site/embed/12345")

        assertEquals(1, videos.size)
        assertEquals("mock_csrf_token_xyz", recordedCsrf)
        assertEquals("https://vid169.site", recordedOrigin)
        assertEquals("https://vid169.site/embed/12345", recordedReferer)
        assertTrue(videos.first().isHlsOrDash)
        assertEquals("https://vid169.site/hls/12345/index.m3u8", videos.first().url)
    }
}
