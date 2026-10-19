package com.example.myapplication.media.source.vfmodule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CvhStreamResolverTest {

    private val sampleJson = """
        {
          "hls": "https://cvh.example.com/live/master.m3u8",
          "dash": "https://cvh.example.com/live/manifest.mpd",
          "qualities": {
            "1080": "https://cvh.example.com/1080.mp4",
            "720": "https://cvh.example.com/720.mp4",
            "480": "https://cvh.example.com/480.mp4"
          }
        }
    """.trimIndent()

    @Test
    fun parseCvhResponse_extractsAllStreamsAndQualities() {
        val resolver = CvhStreamResolver(HttpClient(MockEngine { respond("") }))
        val videos = resolver.parseCvhResponse(sampleJson)

        assertEquals(5, videos.size)
        assertTrue(videos.any { it.isHlsOrDash && it.label == "auto" })
        assertTrue(videos.any { it.label == "1080p" })
        assertTrue(videos.any { it.label == "720p" })
        assertTrue(videos.any { it.label == "480p" })
    }

    @Test
    fun resolveByVkId_requestsWithChromeUserAgentAndOrigin() = runBlocking {
        var recordedUa = ""
        var recordedOrigin = ""

        val mockEngine = MockEngine { request ->
            recordedUa = request.headers["User-Agent"].orEmpty()
            recordedOrigin = request.headers["Origin"].orEmpty()
            respond(
                content = sampleJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val client = HttpClient(mockEngine)
        val resolver = CvhStreamResolver(client)
        val videos = resolver.resolveByVkId("456239017")

        assertTrue(videos.isNotEmpty())
        assertTrue(recordedUa.contains("Chrome"))
        assertEquals("https://cdnvideohub.com", recordedOrigin)
    }
}
