package com.example.myapplication.media.remote

import com.example.myapplication.media.remote.dlna.DlnaProtocol
import com.example.myapplication.media.remote.proxy.HlsPlaylist
import com.example.myapplication.media.remote.proxy.SubtitleFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemotePlaybackLogicTest {

    // ---------- HLS ----------

    @Test
    fun `master playlist - every reference is rewritten, relative against the final url`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="ru",URI="audio/ru.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480
            low/index.m3u8?sig=abc
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1920x1080
            https://cdn2.example.com/hi/index.m3u8
        """.trimIndent()
        val seen = ArrayList<Pair<String, Boolean>>()
        val out = HlsPlaylist.rewrite(master, "https://cdn.example.com/show/ep1/master.m3u8") { url, pl ->
            seen += url to pl
            "http://192.168.1.5:4000/s/t/r/${seen.size}"
        }
        assertEquals(
            listOf(
                "https://cdn.example.com/show/ep1/audio/ru.m3u8" to true,
                "https://cdn.example.com/show/ep1/low/index.m3u8?sig=abc" to true,
                "https://cdn2.example.com/hi/index.m3u8" to true,
            ),
            seen,
        )
        assertFalse(out.contains("cdn.example.com"))
        assertTrue(out.contains("URI=\"http://192.168.1.5:4000/s/t/r/1\""))
        assertTrue(out.contains("#EXT-X-STREAM-INF:BANDWIDTH=800000"))
    }

    @Test
    fun `media playlist - segments, keys and init map go through the proxy`() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-KEY:METHOD=AES-128,URI="/keys/k1",IV=0x0000000000000000000000000000000A
            #EXTINF:6.0,
            seg-0.m4s
            #EXTINF:5.5,
            ../other/seg-1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val seen = ArrayList<String>()
        HlsPlaylist.rewrite(media, "https://h.example/a/b/index.m3u8") { url, pl -> assertFalse(pl); seen += url; "P" }
        assertEquals(
            listOf("https://h.example/a/b/init.mp4", "https://h.example/keys/k1", "https://h.example/a/b/seg-0.m4s", "https://h.example/a/other/seg-1.m4s"),
            seen,
        )
        val parsed = HlsPlaylist.mediaPlaylist(media, "https://h.example/a/b/index.m3u8")
        assertEquals(2, parsed.segments.size)
        assertEquals(11_500L, parsed.totalMs)
        assertTrue(parsed.isFragmentedMp4)
        assertEquals(10, parsed.segments[0].key!!.iv!!.last().toInt())
        assertEquals(1, parsed.indexAt(7_000))
        assertEquals(0, parsed.indexAt(0))
        assertTrue(parsed.endList)
    }

    @Test
    fun `variant for a tv - highest up to 1080p`() {
        val v = listOf(
            HlsPlaylist.Variant("a", 800_000, 480),
            HlsPlaylist.Variant("b", 2_400_000, 1080),
            HlsPlaylist.Variant("c", 9_000_000, 2160),
        )
        assertEquals("b", HlsPlaylist.pickVariant(v)!!.url)
        assertEquals(15, HlsPlaylist.sequenceIv(15).last().toInt())
    }

    // ---------- Субтитры ----------

    @Test
    fun `srt and vtt convert both ways without touching the text`() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\nПривет, мир\n\n2\n00:01:02,000 --> 00:01:04,250\nВторая\n"
        val vtt = SubtitleFormats.srtToVtt(srt)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:01.500 --> 00:00:03.000"))
        assertTrue(vtt.contains("Привет, мир"))
        val back = SubtitleFormats.vttToSrt("WEBVTT\n\nNOTE x\n\n00:01.500 --> 00:03.000 align:start\nПривет\n")
        assertTrue(back, back.contains("1\n00:00:01,500 --> 00:00:03,000\nПривет"))
    }

    // ---------- DLNA ----------

    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <URLBase>http://192.168.1.20:9197/</URLBase>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>[TV] Samsung Q80</friendlyName>
            <manufacturer>Samsung Electronics</manufacturer>
            <modelName>QE55Q80</modelName>
            <UDN>uuid:abc-123</UDN>
            <serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/control/AVTransport1</controlURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>upnp/control/RenderingControl1</controlURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/upnp/control/ConnectionManager1</controlURL></service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun `renderer description gives absolute control urls and a tv kind`() {
        val d = DlnaProtocol.parseDescription(description, "http://192.168.1.20:9197/dmr")!!
        assertEquals("uuid:abc-123", d.udn)
        assertEquals("http://192.168.1.20:9197/upnp/control/AVTransport1", d.avTransport)
        assertEquals("http://192.168.1.20:9197/upnp/control/RenderingControl1", d.renderingControl)
        assertEquals(RemoteDeviceKind.TV, DlnaProtocol.kindOf(d, "http-get:*:video/mp4:*"))
        assertEquals(RemoteDeviceKind.SPEAKER, DlnaProtocol.kindOf(d, "http-get:*:audio/mpeg:*"))
    }

    @Test
    fun `xml with a doctype is refused`() {
        assertNull(
            DlnaProtocol.parseDescription(
                "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><root>&x;</root>",
                "http://x/",
            ),
        )
    }

    @Test
    fun `ssdp response, sink formats, soap values and times`() {
        val r = DlnaProtocol.parseSsdp(
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.20:9197/dmr\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:abc-123::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
        )!!
        assertEquals("http://192.168.1.20:9197/dmr", r.location)
        assertNull(DlnaProtocol.parseSsdp("NOTIFY * HTTP/1.1\r\nNTS: ssdp:byebye\r\nLOCATION: http://x/\r\n\r\n"))
        val formats = DlnaProtocol.formatsFromSink(
            "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL,http-get:*:video/vnd.dlna.mpeg-tts:*,http-get:*:audio/mpeg:*",
        )
        assertEquals(setOf(RemoteFormat.MP4, RemoteFormat.MPEG_TS), formats)
        val soap = DlnaProtocol.parseSoap(
            "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                "<u:GetPositionInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><Track>1</Track>" +
                "<TrackDuration>0:24:10</TrackDuration><RelTime>0:03:07.250</RelTime></u:GetPositionInfoResponse></s:Body></s:Envelope>",
        )
        assertEquals(1_450_000L, DlnaProtocol.parseTime(soap["TrackDuration"]))
        assertEquals(187_250L, DlnaProtocol.parseTime(soap["RelTime"]))
        assertNull(DlnaProtocol.parseTime("NOT_IMPLEMENTED"))
        assertEquals("1:02:03", DlnaProtocol.formatTime(3_723_000))
        val fault = DlnaProtocol.parseSoap(
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><detail>" +
                "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>714</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>",
        )
        assertEquals("714", fault["UPnPError"])
    }

    @Test
    fun `didl metadata is escaped and carries subtitles`() {
        val didl = DlnaProtocol.didl("Tom & Jerry <1>", "http://p/s/t/main.mp4?a=1&b=2", "video/mp4", true, "http://p/s/t/sub/0.srt", null)
        assertTrue(didl.contains("Tom &amp; Jerry &lt;1&gt;"))
        assertTrue(didl.contains("a=1&amp;b=2"))
        assertTrue(didl.contains("DLNA.ORG_OP=01"))
        assertTrue(didl.contains("sec:CaptionInfoEx"))
        val envelope = DlnaProtocol.soapEnvelope(DlnaProtocol.AV_TRANSPORT, "SetAVTransportURI", listOf("CurrentURIMetaData" to didl))
        assertTrue(envelope.contains("&lt;DIDL-Lite"))
    }

    // ---------- Доставка ----------

    private val cast = RemoteCapabilities(needsCors = true)

    @Test
    fun `delivery is chosen by protocol, headers and receiver formats`() {
        fun decide(f: RemoteFormat, p: RemoteProtocol, caps: RemoteCapabilities = RemoteCapabilities(), headers: Boolean = false) =
            RemoteMediaPreparer.decide(f, p, caps, headers).delivery
        assertEquals(DeliveryMode.PROXY, decide(RemoteFormat.HLS, RemoteProtocol.GOOGLE_CAST, cast))
        assertEquals(DeliveryMode.DIRECT, decide(RemoteFormat.MP4, RemoteProtocol.GOOGLE_CAST, cast))
        assertEquals(DeliveryMode.PROXY, decide(RemoteFormat.MP4, RemoteProtocol.GOOGLE_CAST, cast, headers = true))
        assertEquals(DeliveryMode.PROXY_CONCAT, decide(RemoteFormat.HLS, RemoteProtocol.DLNA))
        assertEquals(DeliveryMode.DIRECT, decide(RemoteFormat.HLS, RemoteProtocol.DLNA, RemoteCapabilities(formats = setOf(RemoteFormat.HLS))))
        assertEquals(DeliveryMode.PROXY, decide(RemoteFormat.HLS, RemoteProtocol.DLNA, RemoteCapabilities(formats = setOf(RemoteFormat.HLS)), headers = true))
        try {
            decide(RemoteFormat.MKV, RemoteProtocol.DLNA, RemoteCapabilities(formats = setOf(RemoteFormat.MP4)))
            fail("MKV on an MP4-only renderer must be refused")
        } catch (e: RemotePlaybackException) {
            assertEquals(RemoteError.UnsupportedFormat, e.error)
        }
        assertTrue(RemoteMediaPreparer.needsHeaders(mapOf("Referer" to "x")))
        assertFalse(RemoteMediaPreparer.needsHeaders(mapOf("User-Agent" to "x")))
        assertEquals(RemoteFormat.HLS, RemoteMediaPreparer.sourceFormat("https://x/playlist.m3u8?t=1", null))
        assertEquals(RemoteFormat.MP4, RemoteMediaPreparer.sourceFormat("https://x/video", "video/mp4"))
    }

    // ---------- Одно устройство — один пункт ----------

    private fun service(protocol: RemoteProtocol, host: String?, name: String) = RemoteService(
        adapterId = protocol.name, protocol = protocol, serviceId = name + protocol, name = name, kind = RemoteDeviceKind.TV, host = host,
        capabilities = RemoteCapabilities(),
    )

    @Test
    fun `one tv found by cast and dlna is one device with cast first`() {
        val devices = RemotePlaybackManager.mergeServices(
            listOf(
                service(RemoteProtocol.DLNA, "192.168.1.20", "[TV] Samsung Q80"),
                service(RemoteProtocol.GOOGLE_CAST, "192.168.1.20", "Гостиная"),
                service(RemoteProtocol.DLNA, "192.168.1.31", "Kodi"),
            ),
        )
        assertEquals(2, devices.size)
        val tv = devices.first { it.id == "ip:192.168.1.20" }
        assertEquals("Гостиная", tv.name)
        assertEquals(listOf(RemoteProtocol.GOOGLE_CAST, RemoteProtocol.DLNA), tv.services.map { it.protocol })
        assertEquals("Google Cast · DLNA", tv.protocolLabel)
        // Без адреса, но с тем же именем — тоже то же устройство.
        val merged = RemotePlaybackManager.mergeServices(
            listOf(service(RemoteProtocol.DLNA, "10.0.0.2", "Bravia"), service(RemoteProtocol.GOOGLE_CAST, null, "BRAVIA")),
        )
        assertEquals(1, merged.size)
        assertNotNull(merged.single().services.firstOrNull { it.protocol == RemoteProtocol.GOOGLE_CAST })
    }

    @Test
    fun `remote position is extrapolated only while playing`() {
        val playing = RemotePlaybackState(RemoteStatus.PLAYING, positionMs = 10_000, durationMs = 12_000, updatedAtElapsedMs = 1_000)
        assertEquals(11_500L, playing.positionAt(2_500))
        assertEquals(12_000L, playing.positionAt(9_000))
        assertEquals(10_000L, playing.copy(status = RemoteStatus.PAUSED).positionAt(9_000))
    }
}
