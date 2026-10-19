package com.example.myapplication.media.source.vfmodule

import android.util.Log
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.network.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Экстрактор потоков балансера Collaps для VFmodule.
 */
class CollapsExtractor(
    private val client: HttpClient,
) {
    private val json = AppJson

    suspend fun extract(
        iframeUrl: String,
        season: Int? = null,
        episode: Int? = null,
    ): List<VetroVideo> {
        val normalizedIframe = VfConstants.normalizeUrl(iframeUrl)
        val domainOrigin = VfConstants.originFor(normalizedIframe)

        val html = runCatching {
            client.get(normalizedIframe) {
                header("User-Agent", VfConstants.CHROME_USER_AGENT)
                header("Referer", DEFAULT_REFERER)
                header("Origin", DEFAULT_ORIGIN)
            }.bodyAsText()
        }.onFailure {
            Log.w(TAG, "Failed to load Collaps iframe $normalizedIframe: ${it.message}")
        }.getOrNull() ?: return emptyList()

        return parseCollapsHtml(html, normalizedIframe, domainOrigin, season, episode)
    }

    internal fun parseCollapsHtml(
        html: String,
        iframeUrl: String,
        domainOrigin: String,
        season: Int? = null,
        episode: Int? = null,
    ): List<VetroVideo> {
        val headers = mapOf(
            "User-Agent" to VfConstants.CHROME_USER_AGENT,
            "Referer" to "$domainOrigin/",
            "Origin" to DEFAULT_ORIGIN,
        )

        // 0. Если в makePlayer({...}) есть массив seasons:[...] — разбираем конкретный сезон и серию
        val seasonVideos = parseSeasonsArray(html, domainOrigin, headers, season, episode)
        if (seasonVideos.isNotEmpty()) {
            return seasonVideos
        }

        val videos = mutableListOf<VetroVideo>()

        // 1. Поиск file: "...", hls: "...", open: "..." внутри makePlayer(...)
        val fileRegex = Regex("""['"]?(?:file|open|playlist|hls)['"]?\s*:\s*['"]([^'"]+)['"]""")
        fileRegex.findAll(html).forEach { match ->
            val raw = match.groupValues[1].replace("\\/", "/")
            if (raw.isNotBlank()) {
                val parsed = VfConstants.parsePlayerJsFile(raw, headers, "collaps")
                if (parsed.isNotEmpty()) {
                    videos.addAll(parsed)
                } else if (raw.startsWith("http") || raw.startsWith("//")) {
                    val url = VfConstants.normalizeUrl(raw, domainOrigin)
                    videos.add(
                        VfConstants.createVideo(
                            url = url,
                            quality = if (url.contains(".m3u8")) "auto" else "1080p",
                            headers = headers,
                            providerKey = "collaps",
                        )
                    )
                }
            }
        }

        // 2. Поиск прямой m3u8 ссылки
        if (videos.isEmpty()) {
            val m3u8Regex = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""")
            m3u8Regex.findAll(html).forEach { match ->
                val url = match.value.replace("\\/", "/")
                videos.add(
                    VfConstants.createVideo(
                        url = url,
                        quality = "auto",
                        headers = headers,
                        providerKey = "collaps",
                    )
                )
            }
        }

        return videos.distinctBy { it.url }
            .sortedByDescending { it.resolutionValue() }
    }

    private fun parseSeasonsArray(
        html: String,
        domainOrigin: String,
        headers: Map<String, String>,
        season: Int?,
        episode: Int?,
    ): List<VetroVideo> {
        val markerMatch = Regex("""['"]?seasons['"]?\s*:\s*\[""").find(html) ?: return emptyList()
        val arrayStart = markerMatch.range.last
        val arrayText = extractBalancedBracket(html, arrayStart) ?: return emptyList()

        val seasonsArray = runCatching {
            json.parseToJsonElement(arrayText) as? JsonArray
        }.getOrNull() ?: return emptyList()

        val targetSeason = season ?: 1
        val targetEpisode = episode ?: 1

        val seasonObj = seasonsArray.mapNotNull { it as? JsonObject }.firstOrNull { sObj ->
            val sNum = sObj["season"]?.jsonPrimitive?.intOrNull
                ?: sObj["season"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            sNum == targetSeason
        } ?: (seasonsArray.lastOrNull() as? JsonObject) ?: return emptyList()

        val episodesArray = seasonObj["episodes"] as? JsonArray ?: return emptyList()
        val episodeObj = episodesArray.mapNotNull { it as? JsonObject }.firstOrNull { eObj ->
            val epNum = eObj["episode"]?.jsonPrimitive?.intOrNull
                ?: eObj["episode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            epNum == targetEpisode
        } ?: (episodesArray.firstOrNull() as? JsonObject) ?: return emptyList()

        val hlsRaw = (episodeObj["hls"] ?: episodeObj["file"] ?: episodeObj["dash"])
            ?.jsonPrimitive?.contentOrNull
            ?.replace("\\/", "/")
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val audioNames = ((episodeObj["audio"] as? JsonObject)?.get("names") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val label = if (audioNames.isNotEmpty()) {
            "auto (${audioNames.take(2).joinToString(", ")})"
        } else {
            "auto"
        }

        val url = VfConstants.normalizeUrl(hlsRaw, domainOrigin)
        return listOf(
            VfConstants.createVideo(
                url = url,
                quality = label,
                headers = headers,
                providerKey = "collaps",
            )
        )
    }

    private fun extractBalancedBracket(text: String, openIndex: Int): String? {
        if (openIndex < 0 || openIndex >= text.length || text[openIndex] != '[') return null
        var depth = 0
        var inQuotes = false
        var escape = false
        for (i in openIndex until text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (!inQuotes) {
                if (c == '[') depth++
                else if (c == ']') {
                    depth--
                    if (depth == 0) return text.substring(openIndex, i + 1)
                }
            }
        }
        return null
    }

    companion object {
        const val TAG = "CollapsExtractor"
        const val DEFAULT_ORIGIN = "https://kinokrad.my"
        const val DEFAULT_REFERER = "https://kinokrad.my/"
    }
}
