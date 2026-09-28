package com.example.myapplication.media.remote

import com.example.myapplication.media.remote.proxy.CastProxyServer
import com.example.myapplication.media.remote.proxy.ProxyKind
import com.example.myapplication.media.remote.proxy.ProxySubtitle
import com.example.myapplication.media.remote.proxy.ProxyUpstream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Прокси целиком: настоящий сокет, источник — MockWebServer. */
class CastProxyServerTest {
    private val upstream = MockWebServer()
    private val client = OkHttpClient()
    private val proxy = CastProxyServer(client)
    private var base = ""
    private val file = ByteArray(100_000) { (it % 251).toByte() }
    private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()

    private val key = ByteArray(16) { (it * 7).toByte() }
    private fun segment(i: Int) = ByteArray(1_000 + i * 10) { (i * 31 + it).toByte() }
    private fun encrypt(plain: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(plain)

    @Before
    fun setUp() {
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty()
                if (request.getHeader("Referer") != "https://site.example/") return MockResponse().setResponseCode(403)
                return when {
                    path.startsWith("/video.mp4") -> {
                        val range = request.getHeader("Range")
                        if (range != null) {
                            val (from, to) = range.removePrefix("bytes=").split('-').let { it[0].toInt() to (it[1].toIntOrNull() ?: (file.size - 1)) }
                            MockResponse().setResponseCode(206)
                                .setHeader("Content-Range", "bytes $from-$to/${file.size}")
                                .setHeader("Content-Type", "video/mp4")
                                .setBody(Buffer().write(file.copyOfRange(from, to + 1)))
                        } else MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(file))
                    }
                    path == "/hls/master.m3u8" -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720\nv720/index.m3u8\n",
                    )
                    path == "/hls/v720/index.m3u8" -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.ts\n#EXTINF:6.0,\nseg1.ts\n" +
                            "#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\n#EXTINF:6.0,\nseg2.ts\n#EXT-X-ENDLIST\n",
                    )
                    path == "/hls/v720/seg0.ts" -> MockResponse().setBody(Buffer().write(segment(0)))
                    path == "/hls/v720/seg1.ts" -> MockResponse().setBody(Buffer().write(segment(1)))
                    // Третий сегмент зашифрован; IV по умолчанию — номер сегмента (2).
                    path == "/hls/v720/seg2.ts" -> MockResponse().setBody(Buffer().write(encrypt(segment(2), ByteArray(16).also { it[15] = 2 })))
                    path == "/hls/key.bin" -> MockResponse().setBody(Buffer().write(key))
                    path == "/subs.srt" -> MockResponse().setBody("1\n00:00:01,000 --> 00:00:02,000\nПривет\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        upstream.start()
        val port = proxy.ensureStarted()
        base = CastProxyServer.localBase(InetAddress.getLoopbackAddress(), port)
        proxy.publicBase = base
    }

    @After
    fun tearDown() {
        proxy.closeAll()
        upstream.shutdown()
    }

    private val headers = mapOf("Referer" to "https://site.example/", "Cookie" to "session=secret")

    private fun get(path: String, range: String? = null) = client.newCall(
        Request.Builder().url(if (path.startsWith("http")) path else base + path).apply { range?.let { header("Range", it) } }.build(),
    ).execute()

    @Test
    fun `range requests are forwarded and answered 206 with the source headers upstream`() {
        val token = proxy.openSession(ProxyUpstream(upstream.url("/video.mp4").toString(), headers), ProxyKind.FILE)
        get(proxy.mainPath(token, ProxyKind.FILE, "mp4"), "bytes=1000-1999").use { r ->
            assertEquals(206, r.code)
            assertEquals("bytes 1000-1999/${file.size}", r.header("Content-Range"))
            assertEquals("bytes", r.header("Accept-Ranges"))
            assertEquals("*", r.header("Access-Control-Allow-Origin"))
            assertArrayEquals(file.copyOfRange(1000, 2000), r.body!!.bytes())
        }
        assertEquals("session=secret", requests.last().getHeader("Cookie"))
    }

    @Test
    fun `nothing outside the session is reachable`() {
        val token = proxy.openSession(ProxyUpstream(upstream.url("/video.mp4").toString(), headers), ProxyKind.FILE)
        get("/s/0123456789abcdef0123456789abcdef/main.mp4").use { assertEquals(404, it.code) }
        get("/s/$token/r/zz").use { assertEquals(404, it.code) }
        get("/").use { assertEquals(404, it.code) }
        client.newCall(Request.Builder().url("$base/s/$token/main.mp4").post("x".toRequestBody()).build()).execute().use { assertEquals(405, it.code) }
        proxy.closeSession(token)
        val again = proxy.openSession(ProxyUpstream(upstream.url("/video.mp4").toString(), headers), ProxyKind.FILE)
        base = CastProxyServer.localBase(InetAddress.getLoopbackAddress(), proxy.ensureStarted())
        get("/s/$token/main.mp4").use { assertEquals(404, it.code) }
        get(proxy.mainPath(again, ProxyKind.FILE, "mp4"), "bytes=0-9").use { assertEquals(206, it.code) }
    }

    @Test
    fun `hls playlists are rewritten so the tv only talks to the proxy`() {
        val token = proxy.openSession(ProxyUpstream(upstream.url("/hls/master.m3u8").toString(), headers), ProxyKind.HLS)
        val master = get(proxy.mainPath(token, ProxyKind.HLS, "m3u8")).use { r ->
            assertEquals("application/vnd.apple.mpegurl", r.header("Content-Type"))
            r.body!!.string()
        }
        assertFalse(master.contains(upstream.hostName + ":" + upstream.port))
        val variantUrl = master.lines().first { it.startsWith("http") }
        val media = get(variantUrl).use { it.body!!.string() }
        val segmentUrls = media.lines().filter { it.startsWith("http") }
        assertEquals(3, segmentUrls.size)
        assertTrue(media.contains("URI=\"$base/s/$token/r/"))
        get(segmentUrls[1]).use { assertArrayEquals(segment(1), it.body!!.bytes()) }
    }

    @Test
    fun `hls concatenated into one stream from the segment of the start position, aes decrypted`() {
        val token = proxy.openSession(ProxyUpstream(upstream.url("/hls/master.m3u8").toString(), headers), ProxyKind.HLS_CONCAT)
        val bytes = get(proxy.concatPath(token, 7_000, "ts")).use { r ->
            assertEquals("video/mp2t", r.header("Content-Type"))
            r.body!!.bytes()
        }
        val expected = ByteArrayOutputStream().apply { write(segment(1)); write(segment(2)) }.toByteArray()
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `an expired link is refreshed once through the provider`() {
        val token = proxy.openSession(
            ProxyUpstream(upstream.url("/video.mp4").toString(), mapOf("Referer" to "https://stale.example/")),
            ProxyKind.FILE,
            refresh = { ProxyUpstream(upstream.url("/video.mp4").toString(), headers) },
        )
        get(proxy.mainPath(token, ProxyKind.FILE, "mp4"), "bytes=0-99").use { r ->
            assertEquals(206, r.code)
            assertArrayEquals(file.copyOfRange(0, 100), r.body!!.bytes())
        }
    }

    @Test
    fun `subtitles are served converted for the receiver`() {
        val token = proxy.openSession(
            ProxyUpstream(upstream.url("/video.mp4").toString(), headers),
            ProxyKind.FILE,
            subtitles = listOf(ProxySubtitle(upstream.url("/subs.srt").toString(), isVttSource = false)),
        )
        get(proxy.subtitlePath(token, 0, "vtt")).use { r ->
            assertTrue(r.header("Content-Type")!!.startsWith("text/vtt"))
            val text = r.body!!.string()
            assertTrue(text.startsWith("WEBVTT"))
            assertTrue(text.contains("00:00:01.000 --> 00:00:02.000"))
        }
    }
}
