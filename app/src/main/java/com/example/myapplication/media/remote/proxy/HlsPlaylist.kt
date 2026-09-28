package com.example.myapplication.media.remote.proxy

import java.net.URI

/**
 * Плейлисты HLS для локального прокси: всё, на что ссылается плейлист (варианты, сегменты,
 * ключи, init-сегменты, альтернативные дорожки), переписывается на адреса прокси, чтобы ТВ ходил
 * только через телефон — с нужными источнику заголовками. Чистые функции, проверяются тестами.
 */
object HlsPlaylist {

    fun isPlaylist(body: String): Boolean = body.trimStart(Char(0xFEFF), ' ', '\n', '\r').startsWith("#EXTM3U")

    /**
     * Переписывает [text] (загружен с [baseUrl], уже после редиректов). Каждая ссылка — через
     * [register]: он кладёт абсолютный адрес источника в реестр сессии и возвращает адрес прокси.
     */
    fun rewrite(text: String, baseUrl: String, register: (absoluteUrl: String, isPlaylist: Boolean) -> String): String {
        val out = StringBuilder(text.length + 256)
        var nextIsPlaylist = false
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> out.append(line)
                line.startsWith("#") -> {
                    val tag = line.substringBefore(':')
                    if (tag == "#EXT-X-STREAM-INF") nextIsPlaylist = true
                    out.append(rewriteUriAttribute(line, baseUrl, isPlaylist = tag in PLAYLIST_URI_TAGS, register))
                }
                else -> {
                    out.append(register(resolve(baseUrl, line.trim()), nextIsPlaylist))
                    nextIsPlaylist = false
                }
            }
            out.append('\n')
        }
        return out.toString()
    }

    private val PLAYLIST_URI_TAGS = setOf("#EXT-X-MEDIA", "#EXT-X-I-FRAME-STREAM-INF", "#EXT-X-RENDITION-REPORT")
    private val URI_ATTR = Regex("""URI="([^"]*)"""")

    private fun rewriteUriAttribute(line: String, baseUrl: String, isPlaylist: Boolean, register: (String, Boolean) -> String): String {
        val match = URI_ATTR.find(line) ?: return line
        val value = match.groupValues[1]
        // «data:» и «skd:» (FairPlay) — не адреса, трогать нечего.
        if (value.startsWith("data:") || value.startsWith("skd:")) return line
        val proxied = register(resolve(baseUrl, value), isPlaylist)
        return line.replaceRange(match.range, "URI=\"$proxied\"")
    }

    fun resolve(baseUrl: String, reference: String): String =
        runCatching { URI(baseUrl).resolve(URI(reference.replace(" ", "%20"))).toString() }.getOrElse { reference }

    // ---------- Разбор для склейки в один поток ----------

    data class Variant(val url: String, val bandwidth: Long, val height: Int?)

    fun variants(text: String, baseUrl: String): List<Variant> {
        val out = ArrayList<Variant>()
        var pending: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) pending = line
            else if (pending != null && line.isNotEmpty() && !line.startsWith("#")) {
                val bw = Regex("""(?:^|[,:])BANDWIDTH=(\d+)""").find(pending)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val height = Regex("""RESOLUTION=\d+x(\d+)""").find(pending)?.groupValues?.get(1)?.toIntOrNull()
                out += Variant(resolve(baseUrl, line), bw, height)
                pending = null
            }
        }
        return out
    }

    /** Лучший вариант для ТВ: самый высокий до 1080p (4K-потоки многие приёмники не тянут). */
    fun pickVariant(variants: List<Variant>): Variant? =
        variants.filter { (it.height ?: 0) <= 1080 }.maxByOrNull { it.bandwidth }
            ?: variants.minByOrNull { it.bandwidth }

    data class Key(val method: String, val url: String?, val iv: ByteArray?)

    data class Segment(val url: String, val durationMs: Long, val startMs: Long, val sequence: Long, val key: Key?, val byteRange: LongRange?)

    data class MediaPlaylist(val segments: List<Segment>, val initUrl: String?, val initByteRange: LongRange?, val endList: Boolean) {
        val totalMs: Long get() = segments.lastOrNull()?.let { it.startMs + it.durationMs } ?: 0L
        val isFragmentedMp4: Boolean get() = initUrl != null

        /** Сегмент, в котором [positionMs]: с него начинается склеенный поток. */
        fun indexAt(positionMs: Long): Int {
            if (segments.isEmpty()) return 0
            val i = segments.indexOfLast { it.startMs <= positionMs }
            return i.coerceIn(0, segments.lastIndex)
        }
    }

    fun mediaPlaylist(text: String, baseUrl: String): MediaPlaylist {
        val segments = ArrayList<Segment>()
        var duration = 0L
        var start = 0L
        var sequence = 0L
        var key: Key? = null
        var initUrl: String? = null
        var initRange: LongRange? = null
        var range: LongRange? = null
        var lastRangeEnd = 0L
        var endList = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull() ?: 0L
                line.startsWith("#EXTINF:") -> duration = ((line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0) * 1000).toLong()
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    range = byteRange(line.substringAfter(':'), lastRangeEnd)
                    lastRangeEnd = range.last + 1
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = Regex("""METHOD=([A-Z0-9-]+)""").find(line)?.groupValues?.get(1) ?: "NONE"
                    val uri = URI_ATTR.find(line)?.groupValues?.get(1)?.let { resolve(baseUrl, it) }
                    val iv = Regex("""IV=0[xX]([0-9a-fA-F]+)""").find(line)?.groupValues?.get(1)?.let(::hexToBytes)
                    key = if (method == "NONE") null else Key(method, uri, iv)
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    initUrl = URI_ATTR.find(line)?.groupValues?.get(1)?.let { resolve(baseUrl, it) }
                    initRange = Regex("""BYTERANGE="([^"]+)"""").find(line)?.groupValues?.get(1)?.let { byteRange(it, 0L) }
                }
                line == "#EXT-X-ENDLIST" -> endList = true
                line.isNotEmpty() && !line.startsWith("#") -> {
                    segments += Segment(resolve(baseUrl, line), duration, start, sequence, key, range)
                    start += duration
                    sequence++
                    duration = 0L
                    range = null
                }
            }
        }
        return MediaPlaylist(segments, initUrl, initRange, endList)
    }

    /** «длина@смещение» или «длина» (продолжение предыдущего диапазона). */
    private fun byteRange(spec: String, defaultOffset: Long): LongRange {
        val length = spec.substringBefore('@').trim().toLongOrNull() ?: 0L
        val offset = spec.substringAfter('@', "").trim().toLongOrNull() ?: defaultOffset
        return offset until offset + length
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.padStart(32, '0').takeLast(32)
        return ByteArray(16) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** IV по умолчанию для AES-128 — номер сегмента big-endian в 16 байтах. */
    fun sequenceIv(sequence: Long): ByteArray = ByteArray(16).also { iv ->
        for (i in 0 until 8) iv[15 - i] = (sequence ushr (8 * i)).toByte()
    }
}
