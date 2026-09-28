package com.example.myapplication.media.remote.dlna

import com.example.myapplication.media.remote.RemoteDeviceKind
import com.example.myapplication.media.remote.RemoteFormat
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * UPnP AV (DLNA) без тяжёлых стеков: разбор ответа SSDP, описания устройства и SOAP-ответов,
 * сборка SOAP-запросов и DIDL-Lite. Чистые функции — проверяются тестами.
 */
object DlnaProtocol {
    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

    fun mSearch(searchTarget: String, mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mx\r\n" +
            "ST: $searchTarget\r\n" +
            "USER-AGENT: Android/UPnP/1.1 Vetro/3\r\n\r\n"

    data class SsdpResponse(val location: String, val usn: String?, val st: String?)

    fun parseSsdp(packet: String): SsdpResponse? {
        val lines = packet.split("\r\n", "\n")
        val first = lines.firstOrNull()?.uppercase() ?: return null
        if (!first.startsWith("HTTP/1.1 200") && !first.startsWith("NOTIFY")) return null
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim().uppercase() to l.substring(i + 1).trim()
        }.toMap()
        if (headers["NTS"]?.contains("byebye") == true) return null
        val location = headers["LOCATION"]?.takeIf { it.startsWith("http") } ?: return null
        return SsdpResponse(location, headers["USN"], headers["ST"] ?: headers["NT"])
    }

    data class Description(
        val udn: String,
        val friendlyName: String,
        val manufacturer: String?,
        val modelName: String?,
        val deviceType: String,
        /** serviceType → абсолютный controlURL. */
        val controlUrls: Map<String, String>,
    ) {
        val avTransport: String? get() = controlUrls.entries.firstOrNull { it.key.startsWith("urn:schemas-upnp-org:service:AVTransport:") }?.value
        val renderingControl: String? get() = controlUrls.entries.firstOrNull { it.key.startsWith("urn:schemas-upnp-org:service:RenderingControl:") }?.value
        val connectionManager: String? get() = controlUrls.entries.firstOrNull { it.key.startsWith("urn:schemas-upnp-org:service:ConnectionManager:") }?.value
    }

    /** Описание устройства; ищет MediaRenderer и во вложенных устройствах. */
    fun parseDescription(xml: String, location: String): Description? {
        val doc = parse(xml) ?: return null
        val base = doc.documentElement.childText("URLBase")?.takeIf { it.isNotBlank() } ?: location
        val devices = doc.getElementsByTagNameNS("*", "device")
        for (i in 0 until devices.length) {
            val device = devices.item(i) as Element
            val type = device.childText("deviceType").orEmpty()
            if (!type.contains("MediaRenderer")) continue
            val services = HashMap<String, String>()
            val serviceNodes = device.getElementsByTagNameNS("*", "service")
            for (j in 0 until serviceNodes.length) {
                val s = serviceNodes.item(j) as Element
                val serviceType = s.childText("serviceType") ?: continue
                val control = s.childText("controlURL") ?: continue
                services[serviceType] = resolve(base, control)
            }
            val udn = device.childText("UDN") ?: continue
            return Description(
                udn = udn,
                friendlyName = device.childText("friendlyName")?.takeIf { it.isNotBlank() } ?: "Media Renderer",
                manufacturer = device.childText("manufacturer"),
                modelName = device.childText("modelName"),
                deviceType = type,
                controlUrls = services,
            )
        }
        return null
    }

    /** Форматы из GetProtocolInfo (Sink): «http-get:*:video/mp4:*,…». */
    fun formatsFromSink(sink: String): Set<RemoteFormat> {
        val mimes = sink.split(',').mapNotNull { it.split(':').getOrNull(2)?.trim()?.lowercase() }
        return buildSet {
            for (m in mimes) when {
                m == "video/mp4" || m == "video/x-m4v" || m == "video/3gpp" -> add(RemoteFormat.MP4)
                m == "video/mpeg" || m == "video/mp2t" || m == "video/vnd.dlna.mpeg-tts" || m == "video/x-mpeg2" -> add(RemoteFormat.MPEG_TS)
                m.contains("mpegurl") -> add(RemoteFormat.HLS)
                m == "video/x-matroska" || m == "video/x-mkv" || m == "video/mkv" -> add(RemoteFormat.MKV)
                m == "video/webm" -> add(RemoteFormat.WEBM)
                m == "application/dash+xml" -> add(RemoteFormat.DASH)
            }
        }
    }

    fun sinkHasVideo(sink: String): Boolean = sink.contains("video/", ignoreCase = true)

    fun sinkHasSubtitles(sink: String): Boolean =
        listOf("text/srt", "application/x-subrip", "smi/caption", "text/vtt").any { sink.contains(it, ignoreCase = true) }

    fun kindOf(description: Description, sink: String?): RemoteDeviceKind {
        val text = listOfNotNull(description.friendlyName, description.manufacturer, description.modelName).joinToString(" ").lowercase()
        return when {
            sink != null && sink.isNotBlank() && !sinkHasVideo(sink) -> RemoteDeviceKind.SPEAKER
            listOf("box", "shield", "kodi", "stick", "fire tv", "player").any { it in text } -> RemoteDeviceKind.TV_BOX
            listOf("tv", "samsung", "lg ", "lge", "sony", "bravia", "philips", "panasonic", "hisense", "tcl", "sharp", "toshiba", "webos", "tizen").any { it in text } -> RemoteDeviceKind.TV
            else -> RemoteDeviceKind.RENDERER
        }
    }

    // ---------- SOAP ----------

    fun soapEnvelope(serviceType: String, action: String, args: List<Pair<String, String>>): String {
        val body = args.joinToString("") { (k, v) -> "<$k>${escape(v)}</$k>" }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$serviceType\">$body</u:$action></s:Body></s:Envelope>"
    }

    /** Значения из ответа SOAP по именам элементов; ошибка UPnP — как «UPnPError:<код>». */
    fun parseSoap(xml: String): Map<String, String> {
        val doc = parse(xml) ?: return emptyMap()
        val out = HashMap<String, String>()
        val all = doc.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val e = all.item(i) as Element
            val name = e.localName ?: e.tagName.substringAfter(':')
            if (e.childNodes.length <= 1) out[name] = e.textContent.orEmpty()
        }
        out["errorCode"]?.let { out["UPnPError"] = it }
        return out
    }

    /**
     * DIDL-Lite для SetAVTransportURI. protocolInfo с DLNA.ORG_OP=01 — приёмник может перематывать
     * байтами; для склеенного потока — 00. Субтитры — расширением Samsung (sec:CaptionInfoEx) и
     * вторым res: другие приёмники лишнее проигнорируют.
     */
    fun didl(title: String, url: String, mime: String, seekable: Boolean, subtitleUrl: String?, artworkUrl: String?): String {
        val op = if (seekable) "01" else "00"
        val info = "http-get:*:$mime:DLNA.ORG_OP=$op;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        val sb = StringBuilder()
        sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        sb.append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:sec=\"http://www.sec.co.kr/\">")
        sb.append("<item id=\"vetro-0\" parentID=\"0\" restricted=\"1\">")
        sb.append("<dc:title>").append(escape(title)).append("</dc:title>")
        sb.append("<upnp:class>object.item.videoItem</upnp:class>")
        artworkUrl?.let { sb.append("<upnp:albumArtURI>").append(escape(it)).append("</upnp:albumArtURI>") }
        subtitleUrl?.let { sb.append("<sec:CaptionInfoEx sec:type=\"srt\">").append(escape(it)).append("</sec:CaptionInfoEx>") }
        sb.append("<res protocolInfo=\"").append(escape(info)).append("\">").append(escape(url)).append("</res>")
        subtitleUrl?.let { sb.append("<res protocolInfo=\"http-get:*:text/srt:*\">").append(escape(it)).append("</res>") }
        sb.append("</item></DIDL-Lite>")
        return sb.toString()
    }

    /** «1:02:03» / «01:02:03.500» / «NOT_IMPLEMENTED» → мс. */
    fun parseTime(value: String?): Long? {
        val v = value?.trim() ?: return null
        val parts = v.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return h * 3_600_000 + m * 60_000 + (s * 1000).toLong()
    }

    fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
    }

    fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun resolve(base: String, relative: String): String =
        runCatching { URI(base).resolve(relative.trim()).toString() }.getOrElse { relative }

    private fun parse(xml: String) = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Защита от XXE: описание приходит от любого устройства в сети.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml.trimStart(Char(0xFEFF)))))
    }.getOrNull()

    private fun Element.childText(name: String): String? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i) as? Element ?: continue
            if ((n.localName ?: n.tagName) == name) return n.textContent?.trim()
        }
        return null
    }
}
