package com.example.myapplication.media.remote

import android.util.Log
import com.example.myapplication.media.remote.proxy.CastProxyServer
import com.example.myapplication.media.remote.proxy.HlsPlaylist
import com.example.myapplication.media.remote.proxy.ProxyKind
import com.example.myapplication.media.remote.proxy.ProxySubtitle
import com.example.myapplication.media.remote.proxy.ProxyUpstream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Как доставить медиа на конкретный транспорт. Не проксирует без причины: публичная ссылка без
 * заголовков, которую приёмник умеет открыть, уходит напрямую; заголовки источника, CORS
 * веб-приёмника или HLS для приёмника без HLS — через локальный прокси.
 */
class RemoteMediaPreparer(
    private val proxy: CastProxyServer,
    private val http: OkHttpClient,
) {
    /** Токен прокси-сессии последней подготовки — закрывается при следующей или отключении. */
    @Volatile var activeToken: String? = null
        private set

    suspend fun prepare(
        media: RemoteMedia,
        service: RemoteService,
        refresh: (() -> ProxyUpstream?)? = null,
    ): PreparedMedia = withContext(Dispatchers.IO) {
        val source = sourceFormat(media.url, media.mimeType)
        val decision = decide(source, service.protocol, service.capabilities, needsHeaders(media.headers))
        Log.i(TAG, "prepare ${service.protocol} source=$source delivery=${decision.delivery} format=${decision.format}")
        closeActive()
        val address = LanNetwork.addressFor(service.host)
        val needsProxy = decision.delivery != DeliveryMode.DIRECT || media.subtitles.isNotEmpty()
        if (needsProxy && address == null) throw RemotePlaybackException(RemoteError.NoLocalNetwork)

        val subtitleExt = if (service.protocol == RemoteProtocol.GOOGLE_CAST) "vtt" else "srt"
        val proxySubs = media.subtitles.filter { canConvert(it.mimeType) }.map {
            ProxySubtitle(it.url, it.mimeType.contains("vtt"))
        }
        val token = if (needsProxy) {
            val port = proxy.ensureStarted()
            proxy.publicBase = CastProxyServer.localBase(address!!, port)
            proxy.openSession(
                upstream = ProxyUpstream(media.url, media.headers, media.headersFor),
                kind = when (decision.delivery) {
                    DeliveryMode.PROXY_CONCAT -> ProxyKind.HLS_CONCAT
                    DeliveryMode.PROXY -> if (source == RemoteFormat.HLS) ProxyKind.HLS else ProxyKind.FILE
                    DeliveryMode.DIRECT -> ProxyKind.FILE
                },
                subtitles = proxySubs,
                refresh = refresh,
            ).also { activeToken = it }
        } else null
        val base = token?.let { proxy.publicBase }

        val subtitles = media.subtitles.filter { canConvert(it.mimeType) }.mapIndexed { i, s ->
            s.copy(url = base + proxy.subtitlePath(token!!, i, subtitleExt), mimeType = if (subtitleExt == "vtt") "text/vtt" else "text/srt")
        }

        when (decision.delivery) {
            DeliveryMode.DIRECT -> PreparedMedia(media, media.url, decision.mime, decision.format, DeliveryMode.DIRECT, subtitles)
            DeliveryMode.PROXY -> {
                val ext = when (source) { RemoteFormat.HLS -> "m3u8"; RemoteFormat.DASH -> "mpd"; else -> extensionOf(media.url) ?: "mp4" }
                PreparedMedia(media, base + proxy.mainPath(token!!, ProxyKind.FILE, ext), decision.mime, decision.format, DeliveryMode.PROXY, subtitles)
            }
            DeliveryMode.PROXY_CONCAT -> {
                // Какой поток получится (TS или fMP4) и с какого сегмента он начнётся — по плейлисту.
                val playlist = mediaPlaylist(media) ?: throw RemotePlaybackException(RemoteError.StreamFailed)
                val fmp4 = playlist.isFragmentedMp4
                if (fmp4 && service.capabilities.supports(RemoteFormat.MP4) == false) throw RemotePlaybackException(RemoteError.UnsupportedFormat)
                if (!fmp4 && service.capabilities.supports(RemoteFormat.MPEG_TS) == false) throw RemotePlaybackException(RemoteError.UnsupportedFormat)
                val offset = playlist.segments.getOrNull(playlist.indexAt(media.startPositionMs))?.startMs ?: 0L
                val ext = if (fmp4) "mp4" else "ts"
                PreparedMedia(
                    media = media,
                    url = base + proxy.concatPath(token!!, media.startPositionMs, ext),
                    mimeType = if (fmp4) "video/mp4" else "video/mpeg",
                    format = if (fmp4) RemoteFormat.MP4 else RemoteFormat.MPEG_TS,
                    delivery = DeliveryMode.PROXY_CONCAT,
                    subtitles = subtitles,
                    streamOffsetMs = offset,
                )
            }
        }
    }

    fun closeActive() {
        activeToken?.let(proxy::closeSession)
        activeToken = null
    }

    private fun mediaPlaylist(media: RemoteMedia): HlsPlaylist.MediaPlaylist? {
        var url = media.url
        repeat(3) {
            val (body, finalUrl) = runCatching {
                val builder = Request.Builder().url(url)
                (media.headersFor?.invoke(url) ?: media.headers).forEach { (k, v) -> builder.header(k, v) }
                http.newCall(builder.build()).execute().use { r ->
                    if (!r.isSuccessful) return null
                    r.body?.string().orEmpty() to r.request.url.toString()
                }
            }.getOrNull() ?: return null
            val variant = HlsPlaylist.pickVariant(HlsPlaylist.variants(body, finalUrl))
                ?: return HlsPlaylist.mediaPlaylist(body, finalUrl).takeIf { it.segments.isNotEmpty() }
            url = variant.url
        }
        return null
    }

    companion object {
        private const val TAG = "RemoteMedia"

        data class Decision(val delivery: DeliveryMode, val format: RemoteFormat, val mime: String)

        /** Чистое решение «как доставить» — проверяется тестами. */
        fun decide(source: RemoteFormat, protocol: RemoteProtocol, caps: RemoteCapabilities, needsHeaders: Boolean): Decision {
            val mime = mimeOf(source)
            return when (protocol) {
                RemoteProtocol.GOOGLE_CAST -> when (source) {
                    // Веб-приёмнику нужен CORS на плейлистах и сегментах — у CDN его обычно нет.
                    RemoteFormat.HLS -> Decision(DeliveryMode.PROXY, source, mime)
                    // DASH прокси не переписывает: только открытая ссылка.
                    RemoteFormat.DASH -> if (needsHeaders) throw RemotePlaybackException(RemoteError.UnsupportedFormat)
                        else Decision(DeliveryMode.DIRECT, source, mime)
                    RemoteFormat.MKV -> throw RemotePlaybackException(RemoteError.UnsupportedFormat)
                    else -> Decision(if (needsHeaders) DeliveryMode.PROXY else DeliveryMode.DIRECT, source, mime)
                }
                RemoteProtocol.DLNA -> when (source) {
                    RemoteFormat.DASH -> throw RemotePlaybackException(RemoteError.UnsupportedFormat)
                    RemoteFormat.HLS -> when (caps.supports(RemoteFormat.HLS)) {
                        true -> Decision(if (needsHeaders) DeliveryMode.PROXY else DeliveryMode.DIRECT, RemoteFormat.HLS, mime)
                        // HLS по DLNA понимают немногие: склеиваем сегменты в один поток.
                        else -> Decision(DeliveryMode.PROXY_CONCAT, RemoteFormat.MPEG_TS, "video/mpeg")
                    }
                    else -> {
                        if (caps.supports(source) == false) throw RemotePlaybackException(RemoteError.UnsupportedFormat)
                        Decision(if (needsHeaders) DeliveryMode.PROXY else DeliveryMode.DIRECT, source, mime)
                    }
                }
            }
        }

        fun sourceFormat(url: String, mimeType: String?): RemoteFormat {
            val mime = mimeType?.lowercase().orEmpty()
            val path = url.substringBefore('?').lowercase()
            return when {
                "mpegurl" in mime || path.endsWith(".m3u8") || ".m3u8" in url.lowercase() -> RemoteFormat.HLS
                "dash" in mime || path.endsWith(".mpd") -> RemoteFormat.DASH
                path.endsWith(".mkv") || "matroska" in mime -> RemoteFormat.MKV
                path.endsWith(".webm") || "webm" in mime -> RemoteFormat.WEBM
                path.endsWith(".ts") || "mp2t" in mime -> RemoteFormat.MPEG_TS
                else -> RemoteFormat.MP4
            }
        }

        fun mimeOf(format: RemoteFormat) = when (format) {
            RemoteFormat.HLS -> "application/x-mpegURL"
            RemoteFormat.DASH -> "application/dash+xml"
            RemoteFormat.MPEG_TS -> "video/mpeg"
            RemoteFormat.WEBM -> "video/webm"
            RemoteFormat.MKV -> "video/x-matroska"
            RemoteFormat.MP4 -> "video/mp4"
        }

        /** Заголовки, без которых источник ссылку не отдаст (User-Agent телевизор пришлёт свой). */
        fun needsHeaders(headers: Map<String, String>): Boolean =
            headers.keys.any { it.lowercase() !in setOf("user-agent", "accept", "accept-language") }

        private fun canConvert(mime: String) = mime.contains("vtt") || mime.contains("srt") || mime.contains("subrip")

        private fun extensionOf(url: String) = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").takeIf { it.length in 2..4 }
    }
}
