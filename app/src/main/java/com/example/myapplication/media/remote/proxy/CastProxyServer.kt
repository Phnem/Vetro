package com.example.myapplication.media.remote.proxy

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Откуда прокси берёт поток: адрес источника и его заголовки (Referer, Cookie, Authorization…). */
data class ProxyUpstream(
    val url: String,
    val headers: Map<String, String>,
    /** Заголовки для конкретного адреса (область учётных данных источника); null — [headers] везде. */
    val headersFor: ((String) -> Map<String, String>)? = null,
) {
    fun headersFor(target: String): Map<String, String> = headersFor?.invoke(target) ?: headers
}

/** Что отдаёт сессия как главный ресурс. */
enum class ProxyKind {
    /** Файл (MP4 и т. п.): байты с поддержкой Range. */
    FILE,
    /** HLS: плейлисты переписываются, сегменты идут через прокси. */
    HLS,
    /** HLS, склеенный в один поток (для приёмников без HLS). */
    HLS_CONCAT,
}

/** Субтитры сессии: удалённые (со своими заголовками источника) или локальный файл. */
data class ProxySubtitle(val source: String, val isVttSource: Boolean)

/**
 * VETRO LOCAL CAST PROXY. Телевизор получает простой адрес в локальной сети
 * `http://<IP телефона>:<порт>/s/<токен>/…`, а прокси ходит к источнику с нужными заголовками.
 *
 * Безопасность: не открытый медиасервер. Токен сессии — 128 бит случайности; сессия живёт, пока
 * идёт трансляция (и не дольше [SESSION_TTL_MS] без обращений); отдаются только главный ресурс и
 * адреса, которые прокси сам вписал в переписанные плейлисты (реестр сессии) — произвольный URL
 * запросить нельзя; только GET/HEAD/OPTIONS; клиенты — только из частных сетей; без листингов.
 * В журнал не попадают ни адреса источника, ни заголовки, ни токены.
 */
class CastProxyServer(private val http: OkHttpClient) {

    private class Session(
        val token: String,
        @Volatile var upstream: ProxyUpstream,
        val kind: ProxyKind,
        val subtitles: List<ProxySubtitle>,
        val refresh: (() -> ProxyUpstream?)?,
    ) {
        val registry = ConcurrentHashMap<String, Pair<String, Boolean>>()
        val reverse = ConcurrentHashMap<String, String>()
        val nextId = AtomicLong(1)
        @Volatile var lastAccess = System.currentTimeMillis()
        val keys = ConcurrentHashMap<String, ByteArray>()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "vetro-cast-proxy").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null

    /** Порт, на котором слушает прокси (запускается по первому требованию). */
    @Synchronized
    fun ensureStarted(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0), 32)
        server = socket
        pool.execute { acceptLoop(socket) }
        Log.i(TAG, "proxy listening on port ${socket.localPort}")
        return socket.localPort
    }

    /**
     * Новая сессия. [refresh] — как получить свежий адрес, если источник начал отвечать 403/410
     * (истёкшая подписанная ссылка). Возвращает токен.
     */
    fun openSession(
        upstream: ProxyUpstream,
        kind: ProxyKind,
        subtitles: List<ProxySubtitle> = emptyList(),
        refresh: (() -> ProxyUpstream?)? = null,
    ): String {
        dropStale()
        val bytes = ByteArray(16).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        sessions[token] = Session(token, upstream, kind, subtitles, refresh)
        Log.i(TAG, "session opened kind=$kind subtitles=${subtitles.size}")
        return token
    }

    fun closeSession(token: String) {
        if (sessions.remove(token) != null) Log.i(TAG, "session closed")
        if (sessions.isEmpty()) stop()
    }

    fun closeAll() {
        sessions.clear()
        stop()
    }

    @Synchronized
    private fun stop() {
        runCatching { server?.close() }
        server = null
    }

    fun mainPath(token: String, kind: ProxyKind, extension: String): String = when (kind) {
        ProxyKind.HLS_CONCAT -> "/s/$token/c/0.$extension"
        else -> "/s/$token/main.$extension"
    }

    fun concatPath(token: String, fromMs: Long, extension: String) = "/s/$token/c/$fromMs.$extension"

    fun subtitlePath(token: String, index: Int, extension: String) = "/s/$token/sub/$index.$extension"

    private fun dropStale() {
        val now = System.currentTimeMillis()
        sessions.values.removeAll { now - it.lastAccess > SESSION_TTL_MS }
    }

    // ---------- Сеть ----------

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: IOException) { break }
            val remote = client.inetAddress
            if (!(remote.isSiteLocalAddress || remote.isLoopbackAddress || remote.isLinkLocalAddress)) {
                runCatching { client.close() }
                continue
            }
            pool.execute { handle(client) }
        }
    }

    private class HttpRequest(val method: String, val path: String, val query: String?, val headers: Map<String, String>)

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val request = runCatching { readRequest(input) }.getOrNull() ?: return
            try {
                route(request, output)
            } catch (_: SocketException) {
                // Приёмник закрыл соединение (перемотка, стоп) — это нормально.
            } catch (e: IOException) {
                Log.i(TAG, "request aborted: ${e.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.javaClass.simpleName}")
                runCatching { writeStatus(output, 502, "Bad Gateway") }
            }
        }
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (++total > MAX_HEADER_BYTES) return null
            if (b == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) break
                lines += text
                line.setLength(0)
            } else line.append(b.toChar())
        }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val target = parts[1]
        val headers = lines.drop(1).mapNotNull { h ->
            val i = h.indexOf(':')
            if (i <= 0) null else h.substring(0, i).trim().lowercase() to h.substring(i + 1).trim()
        }.toMap()
        return HttpRequest(parts[0].uppercase(), target.substringBefore('?'), target.substringAfter('?', "").ifEmpty { null }, headers)
    }

    private fun route(request: HttpRequest, out: OutputStream) {
        if (request.method == "OPTIONS") {
            // Предзапрос CORS веб-приёмника Cast (Range — не «простой» заголовок).
            writeHead(out, 204, "No Content", corsHeaders() + ("Content-Length" to "0"))
            return
        }
        if (request.method != "GET" && request.method != "HEAD") { writeStatus(out, 405, "Method Not Allowed"); return }
        val segments = request.path.trim('/').split('/')
        if (segments.size < 3 || segments[0] != "s") { writeStatus(out, 404, "Not Found"); return }
        val session = sessions[segments[1]] ?: run { writeStatus(out, 404, "Not Found"); return }
        session.lastAccess = System.currentTimeMillis()
        val head = request.method == "HEAD"
        val name = segments[2]
        when {
            name.startsWith("main") -> when (session.kind) {
                ProxyKind.HLS -> servePlaylistOrBytes(session, session.upstream.url, true, request, out, head, allowRefresh = true)
                ProxyKind.FILE -> serveBytes(session, session.upstream.url, request, out, head, allowRefresh = true)
                ProxyKind.HLS_CONCAT -> serveConcat(session, 0L, out, head)
            }
            name == "r" && segments.size >= 4 -> {
                val id = segments[3].substringBefore('.')
                val (url, isPlaylist) = session.registry[id] ?: run { writeStatus(out, 404, "Not Found"); return }
                servePlaylistOrBytes(session, url, isPlaylist, request, out, head, allowRefresh = false)
            }
            name == "c" && segments.size >= 4 -> serveConcat(session, segments[3].substringBefore('.').toLongOrNull() ?: 0L, out, head)
            name == "sub" && segments.size >= 4 -> {
                val index = segments[3].substringBefore('.').toIntOrNull()
                val wantVtt = segments[3].endsWith(".vtt")
                val sub = index?.let(session.subtitles::getOrNull) ?: run { writeStatus(out, 404, "Not Found"); return }
                serveSubtitle(session, sub, wantVtt, out, head)
            }
            else -> writeStatus(out, 404, "Not Found")
        }
    }

    // ---------- Ресурсы ----------

    private fun servePlaylistOrBytes(
        session: Session,
        url: String,
        expectPlaylist: Boolean,
        request: HttpRequest,
        out: OutputStream,
        head: Boolean,
        allowRefresh: Boolean,
    ) {
        if (!expectPlaylist && !url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
            serveBytes(session, url, request, out, head, allowRefresh)
            return
        }
        val response = fetch(session, url, null, allowRefresh) ?: run { writeStatus(out, 502, "Bad Gateway"); return }
        response.use { r ->
            if (!r.isSuccessful) { writeStatus(out, r.code, "Upstream Error"); return }
            val body = r.body?.string().orEmpty()
            if (!HlsPlaylist.isPlaylist(body)) {
                // Не плейлист (сегмент без расширения) — отдаём как есть.
                val bytes = body.toByteArray(Charsets.ISO_8859_1)
                writeHead(out, 200, "OK", corsHeaders() + mapOf("Content-Type" to (r.header("Content-Type") ?: "application/octet-stream"), "Content-Length" to bytes.size.toString()))
                if (!head) out.write(bytes)
                return
            }
            val finalUrl = r.request.url.toString()
            val rewritten = HlsPlaylist.rewrite(body, finalUrl) { absolute, isPlaylist -> register(session, absolute, isPlaylist) }
            val bytes = rewritten.toByteArray(Charsets.UTF_8)
            writeHead(out, 200, "OK", corsHeaders() + mapOf(
                "Content-Type" to "application/vnd.apple.mpegurl",
                "Content-Length" to bytes.size.toString(),
                "Cache-Control" to "no-cache",
            ))
            if (!head) out.write(bytes)
        }
    }

    /** Адрес прокси для ссылки из плейлиста; одна и та же ссылка — один и тот же адрес. */
    private fun register(session: Session, absoluteUrl: String, isPlaylist: Boolean): String {
        val id = session.reverse.getOrPut(absoluteUrl) {
            val newId = session.nextId.getAndIncrement().toString(36)
            session.registry[newId] = absoluteUrl to isPlaylist
            newId
        }
        val ext = when {
            isPlaylist -> ".m3u8"
            else -> absoluteUrl.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").take(5).takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""
        }
        return "/s/${session.token}/r/$id$ext".let { path -> publicBase?.let { it + path } ?: path }
    }

    /**
     * База для абсолютных адресов в переписанных плейлистах (`http://IP:порт`). Относительный путь
     * от корня тоже законен, но часть телевизоров его не разрешает — даём полный.
     */
    @Volatile var publicBase: String? = null

    private fun serveBytes(session: Session, url: String, request: HttpRequest, out: OutputStream, head: Boolean, allowRefresh: Boolean) {
        val range = request.headers["range"]
        val response = fetch(session, url, range, allowRefresh) ?: run { writeStatus(out, 502, "Bad Gateway"); return }
        response.use { r ->
            if (!r.isSuccessful) { writeStatus(out, r.code, "Upstream Error"); return }
            val headers = LinkedHashMap<String, String>()
            headers += corsHeaders()
            headers["Content-Type"] = r.header("Content-Type")?.takeUnless { it.startsWith("text/html") } ?: guessType(url)
            r.header("Content-Length")?.let { headers["Content-Length"] = it }
            r.header("Content-Range")?.let { headers["Content-Range"] = it }
            headers["Accept-Ranges"] = "bytes"
            headers["transferMode.dlna.org"] = "Streaming"
            headers["contentFeatures.dlna.org"] = DLNA_SEEKABLE
            writeHead(out, r.code, if (r.code == 206) "Partial Content" else "OK", headers)
            if (!head) r.body?.byteStream()?.use { it.copyTo(out, BUFFER) }
        }
    }

    private fun serveSubtitle(session: Session, sub: ProxySubtitle, wantVtt: Boolean, out: OutputStream, head: Boolean) {
        val text = if (sub.source.startsWith("http://") || sub.source.startsWith("https://")) {
            fetch(session, sub.source, null, false)?.use { r -> if (r.isSuccessful) r.body?.string() else null }
        } else {
            runCatching { File(sub.source.removePrefix("file://")).readText() }.getOrNull()
        } ?: run { writeStatus(out, 404, "Not Found"); return }
        val isVtt = SubtitleFormats.isVtt(text)
        val converted = when {
            wantVtt && !isVtt -> SubtitleFormats.srtToVtt(text)
            !wantVtt && isVtt -> SubtitleFormats.vttToSrt(text)
            else -> text
        }
        val bytes = converted.toByteArray(Charsets.UTF_8)
        writeHead(out, 200, "OK", corsHeaders() + mapOf(
            "Content-Type" to if (wantVtt) "text/vtt; charset=utf-8" else "application/x-subrip; charset=utf-8",
            "Content-Length" to bytes.size.toString(),
        ))
        if (!head) out.write(bytes)
    }

    /**
     * HLS одним потоком: сегменты подряд с сегмента, где [fromMs]; fMP4 — с init-сегментом
     * впереди; AES-128 расшифровывается здесь (ключ — через тот же источник с его заголовками).
     * Длина заранее неизвестна — поток до закрытия соединения.
     */
    private fun serveConcat(session: Session, fromMs: Long, out: OutputStream, head: Boolean) {
        val playlist = loadMediaPlaylist(session) ?: run { writeStatus(out, 502, "Bad Gateway"); return }
        if (playlist.segments.any { it.key != null && it.key.method != "AES-128" }) {
            writeStatus(out, 415, "Unsupported Media Type")
            return
        }
        val mime = if (playlist.isFragmentedMp4) "video/mp4" else "video/mp2t"
        writeHead(out, 200, "OK", corsHeaders() + mapOf(
            "Content-Type" to mime,
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to DLNA_STREAM,
            "Connection" to "close",
        ))
        if (head) return
        val start = playlist.indexAt(fromMs)
        Log.i(TAG, "concat from segment $start/${playlist.segments.size}")
        playlist.initUrl?.let { init -> copyUpstream(session, init, playlist.initByteRange, null, out) }
        for (segment in playlist.segments.drop(start)) {
            copyUpstream(session, segment.url, segment.byteRange, segment, out)
        }
        out.flush()
    }

    private fun loadMediaPlaylist(session: Session): HlsPlaylist.MediaPlaylist? {
        var url = session.upstream.url
        repeat(3) {
            val (body, finalUrl) = fetch(session, url, null, allowRefresh = true)?.use { r ->
                if (!r.isSuccessful) return null
                r.body?.string().orEmpty() to r.request.url.toString()
            } ?: return null
            val variant = HlsPlaylist.pickVariant(HlsPlaylist.variants(body, finalUrl))
            if (variant == null) return HlsPlaylist.mediaPlaylist(body, finalUrl).takeIf { it.segments.isNotEmpty() }
            url = variant.url
        }
        return null
    }

    private fun copyUpstream(session: Session, url: String, byteRange: LongRange?, segment: HlsPlaylist.Segment?, out: OutputStream) {
        val range = byteRange?.let { "bytes=${it.first}-${it.last}" }
        val response = fetch(session, url, range, allowRefresh = false) ?: throw IOException("segment unavailable")
        response.use { r ->
            if (!r.isSuccessful) throw IOException("segment HTTP ${r.code}")
            val raw = r.body?.byteStream() ?: return
            val key = segment?.key
            val stream = if (key != null) decrypting(session, raw, key, segment.sequence) else raw
            stream.use { it.copyTo(out, BUFFER) }
        }
    }

    private fun decrypting(session: Session, input: InputStream, key: HlsPlaylist.Key, sequence: Long): InputStream {
        val keyUrl = key.url ?: throw IOException("key without URI")
        val keyBytes = session.keys.getOrPut(keyUrl) {
            fetch(session, keyUrl, null, false)?.use { r -> if (r.isSuccessful) r.body?.bytes() else null }
                ?.takeIf { it.size == 16 } ?: throw IOException("key unavailable")
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(key.iv ?: HlsPlaylist.sequenceIv(sequence)))
        return CipherInputStream(input, cipher)
    }

    // ---------- Источник ----------

    /** Запрос к источнику с заголовками сессии; 401/403/404/410 на главном ресурсе — одна попытка обновить ссылку. */
    private fun fetch(session: Session, url: String, range: String?, allowRefresh: Boolean): Response? {
        fun call(target: String, headers: Map<String, String>): Response? = runCatching {
            val builder = Request.Builder().url(target)
            headers.forEach { (k, v) -> builder.header(k, v) }
            range?.let { builder.header("Range", it) }
            http.newCall(builder.build()).execute()
        }.getOrNull()

        val response = call(url, session.upstream.headersFor(url)) ?: return null
        if (allowRefresh && response.code in REFRESHABLE && session.refresh != null) {
            response.close()
            val fresh = runCatching { session.refresh?.invoke() }.getOrNull() ?: return call(url, session.upstream.headersFor(url))
            Log.i(TAG, "upstream refreshed after HTTP ${response.code}")
            session.upstream = fresh
            session.registry.clear()
            session.reverse.clear()
            return call(fresh.url, fresh.headersFor(fresh.url))
        }
        return response
    }

    private fun guessType(url: String): String = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "ts" -> "video/mp2t"
        "m4s", "fmp4" -> "video/iso.segment"
        "aac" -> "audio/aac"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "vtt" -> "text/vtt"
        "key" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    private fun corsHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Headers" to "Range, Content-Type",
        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
        "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges",
    )

    private fun writeStatus(out: OutputStream, code: Int, reason: String) {
        writeHead(out, code, reason, corsHeaders() + ("Content-Length" to "0"))
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        if (headers.keys.none { it.equals("Connection", true) }) sb.append("Connection: close\r\n")
        sb.append("Server: Vetro\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    companion object {
        private const val TAG = "VetroCastProxy"
        private const val SESSION_TTL_MS = 6 * 3600_000L
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val BUFFER = 64 * 1024
        private val REFRESHABLE = setOf(401, 403, 404, 410)
        /** DLNA: поток перематывается запросами Range. */
        const val DLNA_SEEKABLE = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        /** DLNA: сплошной поток без перемотки байтами. */
        const val DLNA_STREAM = "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        fun localBase(address: InetAddress, port: Int) = "http://${address.hostAddress}:$port"
    }
}
