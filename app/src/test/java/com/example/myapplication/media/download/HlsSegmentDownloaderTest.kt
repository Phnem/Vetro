package com.example.myapplication.media.download

import com.example.myapplication.media.source.VetroVideo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HlsSegmentDownloaderTest {

    @Test
    fun `selects requested variant and keeps repeated segments`() {
        // Segments are fetched in parallel: the interceptor runs on several threads.
        val requestedPaths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val packet = ByteArray(188 * 400).also { bytes ->
            bytes.indices.step(188).forEach { bytes[it] = 0x47 }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                requestedPaths += path
                val (contentType, body) = when (path) {
                    "/master.m3u8" -> "application/vnd.apple.mpegurl" to """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720
                        720.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080
                        1080.m3u8
                    """.trimIndent().toByteArray()
                    "/720.m3u8" -> "application/vnd.apple.mpegurl" to """
                        #EXTM3U
                        #EXTINF:4,
                        segment.ts
                        #EXTINF:4,
                        segment.ts
                        #EXT-X-ENDLIST
                    """.trimIndent().toByteArray()
                    "/segment.ts" -> "video/mp2t" to packet
                    else -> error("Unexpected request: $path")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(contentType.toMediaType()))
                    .build()
            }
            .build()
        val destination = File.createTempFile("native-hls", ".mp4").also { it.delete() }

        try {
            HlsSegmentDownloader(client).download(
                video = VetroVideo(
                    url = "https://media.test/master.m3u8",
                    label = "720p",
                    resolution = 720,
                ),
                destination = destination,
            )

            assertTrue(MediaFileValidator.isPlayableVideo(destination))
            assertEquals(
                listOf("/master.m3u8", "/720.m3u8", "/segment.ts", "/segment.ts"),
                requestedPaths,
            )
        } finally {
            destination.delete()
        }
    }

    @Test
    fun `retries a segment whose connection drops and keeps the file intact`() {
        val segmentAttempts = mutableListOf<Int>()
        var failuresLeft = 2
        val packet = ByteArray(188 * 1000).also { bytes ->
            bytes.indices.step(188).forEach { bytes[it] = 0x47 }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                if (path == "/media.m3u8") {
                    return@addInterceptor Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """
                                #EXTM3U
                                #EXTINF:4,
                                segment.ts
                                #EXT-X-ENDLIST
                            """.trimIndent()
                                .toByteArray()
                                .toResponseBody("application/vnd.apple.mpegurl".toMediaType())
                        )
                        .build()
                }
                segmentAttempts += segmentAttempts.size + 1
                if (failuresLeft > 0) {
                    failuresLeft -= 1
                    throw java.io.EOFException("connection closed by peer")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(packet.toResponseBody("video/mp2t".toMediaType()))
                    .build()
            }
            .build()
        val destination = File.createTempFile("retry-hls", ".mp4").also { it.delete() }

        try {
            HlsSegmentDownloader(client).download(
                video = VetroVideo(url = "https://media.test/media.m3u8", label = "720p"),
                destination = destination,
            )

            assertEquals(3, segmentAttempts.size)
            assertTrue(MediaFileValidator.isPlayableVideo(destination))
            // Only the successful attempt reached the output: the two dropped bodies wrote nothing.
            assertEquals(packet.size.toLong(), destination.length())
        } finally {
            destination.delete()
        }
    }

    @Test
    fun `does not retry a segment the server refuses`() {
        var segmentRequests = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                if (path == "/media.m3u8") {
                    return@addInterceptor Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """
                                #EXTM3U
                                #EXTINF:4,
                                segment.ts
                                #EXT-X-ENDLIST
                            """.trimIndent()
                                .toByteArray()
                                .toResponseBody("application/vnd.apple.mpegurl".toMediaType())
                        )
                        .build()
                }
                segmentRequests += 1
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        val destination = File.createTempFile("refused-hls", ".mp4").also { it.delete() }

        try {
            val error = runCatching {
                HlsSegmentDownloader(client).download(
                    video = VetroVideo(url = "https://media.test/media.m3u8", label = "720p"),
                    destination = destination,
                )
            }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertEquals(1, segmentRequests)
        } finally {
            destination.delete()
        }
    }

    @Test
    fun `parallel segments land in playlist order even when they finish out of order`() {
        val segmentCount = 7
        // Each segment is a run of TS packets tagged with its index; earlier segments answer slower.
        fun segment(index: Int) = ByteArray(188 * 100).also { bytes ->
            bytes.indices.step(188).forEach {
                bytes[it] = 0x47
                bytes[it + 1] = index.toByte()
            }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                val (contentType, body) = if (path == "/media.m3u8") {
                    "application/vnd.apple.mpegurl" to buildString {
                        appendLine("#EXTM3U")
                        repeat(segmentCount) {
                            appendLine("#EXTINF:4,")
                            appendLine("s$it.ts")
                        }
                        appendLine("#EXT-X-ENDLIST")
                    }.toByteArray()
                } else {
                    val index = path.removePrefix("/s").removeSuffix(".ts").toInt()
                    Thread.sleep((segmentCount - index) * 15L)
                    "video/mp2t" to segment(index)
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(contentType.toMediaType()))
                    .build()
            }
            .build()
        val destination = File.createTempFile("ordered-hls", ".mp4").also { it.delete() }
        val progress = java.util.Collections.synchronizedList(mutableListOf<Int>())

        try {
            HlsSegmentDownloader(client).download(
                video = VetroVideo(url = "https://media.test/media.m3u8", label = "720p"),
                destination = destination,
                onProgress = { progress += it },
            )

            val written = destination.readBytes()
            val order = written.indices.step(188 * 100).map { written[it + 1].toInt() }
            assertEquals((0 until segmentCount).toList(), order)
            // Progress only moves forward and is reported once per value.
            assertEquals(progress.distinct().sorted(), progress)
            assertEquals(100, progress.last())
        } finally {
            destination.delete()
        }
    }
}
