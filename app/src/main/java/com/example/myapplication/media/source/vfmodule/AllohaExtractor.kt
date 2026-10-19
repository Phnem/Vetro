package com.example.myapplication.media.source.vfmodule

import android.util.Log
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.network.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Экстрактор потоков Alloha (Aladin) для VFmodule.
 */
class AllohaExtractor(
    private val client: HttpClient,
) {
    private val json = AppJson

    suspend fun extractFromIframe(
        iframeUrl: String,
        season: Int? = null,
        episode: Int? = null,
    ): List<VetroVideo> {
        var targetIframe = VfConstants.normalizeUrl(iframeUrl)
        if (season != null && episode != null && targetIframe.contains("token=") && !targetIframe.contains("season=")) {
            targetIframe = "$targetIframe&season=$season&episode=$episode"
        }
        val domainOrigin = VfConstants.originFor(targetIframe)

        val html = runCatching {
            client.get(targetIframe) {
                header("User-Agent", VfConstants.CHROME_USER_AGENT)
                header("Referer", "$domainOrigin/")
            }.bodyAsText()
        }.onFailure {
            Log.w(TAG, "Failed to load Alloha iframe $targetIframe: ${it.message}")
        }.getOrNull() ?: return emptyList()

        // 1. Стандартный разбор HTML (для прямых ссылок и тестовых стабов)
        val directVideos = parseAllohaHtml(html, targetIframe, domainOrigin)
        if (directVideos.isNotEmpty()) {
            return directVideos
        }

        // 2. Разбор Aladin / scalp-as.stloadi.live через viewporti + Borth + POST /bnsi/movies/{actId}
        return extractAladinBnsi(html, targetIframe, domainOrigin)
    }

    private suspend fun extractAladinBnsi(
        html: String,
        iframeUrl: String,
        domainOrigin: String,
    ): List<VetroVideo> {
        val viewporti = Regex("""name=['"]viewporti['"]\s+content=['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""content=['"]([^'"]+)['"]\s+name=['"]viewporti['"]""").find(html)?.groupValues?.get(1)
            ?: return emptyList()

        val fileListRaw = Regex("""fileList\s*=\s*JSON\.parse\('([^']+)'\)""").find(html)?.groupValues?.get(1)
            ?: return emptyList()

        val fileListObj = runCatching {
            json.parseToJsonElement(fileListRaw) as? JsonObject
        }.getOrNull() ?: return emptyList()

        val activeObj = fileListObj["active"] as? JsonObject ?: return emptyList()
        val actId = activeObj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val trId = activeObj["id_translation"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "66"

        val token = Regex("""[?&]token=([^&#]+)""").find(iframeUrl)?.groupValues?.get(1)
            ?: DEFAULT_TOKEN

        val borth = computeBorth(viewporti)
        val postUrl = "$domainOrigin/bnsi/movies/$actId"

        val bnsiBody = runCatching {
            client.post(postUrl) {
                header("User-Agent", VfConstants.CHROME_USER_AGENT)
                header("Accept", "*/*")
                header("Origin", domainOrigin)
                header("Referer", iframeUrl)
                header("X-Requested-With", "XMLHttpRequest")
                header("Borth", borth)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("token=$token&av1=false&autoplay=0&audio=$trId")
            }.bodyAsText()
        }.onFailure {
            Log.w(TAG, "Alloha bnsi POST failed ($postUrl): ${it.message}")
        }.getOrNull() ?: return emptyList()

        return parseBnsiResponse(bnsiBody, domainOrigin)
    }

    internal fun parseBnsiResponse(
        responseBody: String,
        domainOrigin: String,
    ): List<VetroVideo> {
        val root = runCatching { json.parseToJsonElement(responseBody) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val hlsSource = root["hlsSource"] as? JsonArray ?: return emptyList()

        val playbackHeaders = mapOf(
            "User-Agent" to VfConstants.CHROME_USER_AGENT,
            "Origin" to domainOrigin,
            "Referer" to "$domainOrigin/",
        )

        val videos = mutableListOf<VetroVideo>()
        for (item in hlsSource) {
            val obj = item as? JsonObject ?: continue
            val qualityMap = obj["quality"] as? JsonObject ?: continue
            for ((resKey, urlElem) in qualityMap) {
                val rawUrls = urlElem.jsonPrimitive.contentOrNull
                    ?.replace("\\/", "/")
                    ?.trim()
                    ?: continue
                val primaryUrl = rawUrls.substringBefore(" or ").trim()
                if (primaryUrl.isBlank()) continue
                val cleanQuality = if (resKey.endsWith("p")) resKey else "${resKey}p"
                videos.add(
                    VfConstants.createVideo(
                        url = VfConstants.normalizeUrl(primaryUrl, domainOrigin),
                        quality = cleanQuality,
                        headers = playbackHeaders,
                        providerKey = "alloha",
                    )
                )
            }
        }

        return videos.distinctBy { it.url }
            .sortedByDescending { it.resolutionValue() }
    }

    suspend fun queryApi(
        token: String = DEFAULT_TOKEN,
        kinopoiskId: Int? = null,
        imdbId: String? = null,
        title: String? = null,
    ): JsonObject? = runCatching {
        val hasIds = kinopoiskId != null || !imdbId.isNullOrBlank()
        val response = if (hasIds) {
            client.get("$API_V2_BASE/movies/search") {
                kinopoiskId?.let { parameter("kp", it) }
                imdbId?.takeIf { it.isNotBlank() }?.let { parameter("imdb", it) }
                header("Authorization", "Bearer $token")
                header("User-Agent", VfConstants.CHROME_USER_AGENT)
                header("Accept", "application/json")
            }.bodyAsText()
        } else if (!title.isNullOrBlank()) {
            client.get("$API_V2_BASE/movies/name/list") {
                parameter("name", title)
                header("Authorization", "Bearer $token")
                header("User-Agent", VfConstants.CHROME_USER_AGENT)
                header("Accept", "application/json")
            }.bodyAsText()
        } else {
            return@runCatching null
        }

        val parsed = json.parseToJsonElement(response) as? JsonObject ?: return@runCatching null
        val dataElem = parsed["data"]
        if (dataElem is JsonArray && dataElem.isNotEmpty()) {
            val firstObj = dataElem.firstOrNull() as? JsonObject ?: return@runCatching parsed
            JsonObject(parsed + ("data" to firstObj))
        } else {
            parsed
        }
    }.onFailure {
        Log.w(TAG, "Alloha API request failed: ${it.message}")
    }.getOrNull()

    internal fun parseAllohaHtml(
        html: String,
        iframeUrl: String,
        domainOrigin: String,
    ): List<VetroVideo> {
        val headers = mapOf(
            "User-Agent" to VfConstants.CHROME_USER_AGENT,
            "Referer" to iframeUrl,
            "Origin" to domainOrigin,
        )

        // 1. Поиск блока files: { "1080": "...", "720": "...", "480": "..." }
        val filesMatch = Regex("""['"]files['"]\s*:\s*\{([^}]+)\}""").find(html)
            ?: Regex("""files\s*:\s*\{([^}]+)\}""").find(html)

        if (filesMatch != null) {
            val content = filesMatch.groupValues[1]
            val pairRegex = Regex("""['"]?(\d+p?)['"]?\s*:\s*['"]([^'"]+)['"]""")
            val videos = pairRegex.findAll(content).mapNotNull { match ->
                val rawQuality = match.groupValues[1]
                val url = match.groupValues[2].replace("\\/", "/")
                val cleanQuality = if (rawQuality.endsWith("p")) rawQuality else "${rawQuality}p"
                val normalizedUrl = VfConstants.normalizeUrl(url, domainOrigin)
                VfConstants.createVideo(
                    url = normalizedUrl,
                    quality = cleanQuality,
                    headers = headers,
                    providerKey = "alloha",
                )
            }.toList()

            if (videos.isNotEmpty()) {
                return videos.sortedByDescending { it.resolutionValue() }
            }
        }

        // 2. Поиск file: "..." или playlist
        val fileMatch = Regex("""['"]file['"]\s*:\s*['"]([^'"]+)['"]""").find(html)
            ?: Regex("""file\s*:\s*['"]([^'"]+)['"]""").find(html)

        if (fileMatch != null) {
            val fileRaw = fileMatch.groupValues[1].replace("\\/", "/")
            if (fileRaw.isNotBlank()) {
                val parsed = VfConstants.parsePlayerJsFile(fileRaw, headers, "alloha")
                if (parsed.isNotEmpty()) return parsed

                val normalizedUrl = VfConstants.normalizeUrl(fileRaw, domainOrigin)
                return listOf(
                    VfConstants.createVideo(
                        url = normalizedUrl,
                        quality = "auto",
                        headers = headers,
                        providerKey = "alloha",
                    )
                )
            }
        }

        // 3. Regex-поиск m3u8
        val m3u8Match = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""").find(html)
        if (m3u8Match != null) {
            val url = m3u8Match.value.replace("\\/", "/")
            return listOf(
                VfConstants.createVideo(
                    url = url,
                    quality = "auto",
                    headers = headers,
                    providerKey = "alloha",
                )
            )
        }

        return emptyList()
    }

    companion object {
        const val TAG = "AllohaExtractor"
        const val DEFAULT_TOKEN = "22c8122334d050de1bfc97bd08aa5e"
        const val API_V2_BASE = "https://apbugall.org/v2"
        const val PLAYER_BASE = "https://scalp-as.stloadi.live"

        private val FINGERPRINT_HASH: String by lazy {
            MessageDigest.getInstance("SHA-256")
                .digest("fingerprint-alloha".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        internal fun computeBorth(viewporti: String): String {
            val zy = viewporti.filterIndexed { index, _ -> index % 2 == 0 }
            val zz = StringBuilder(zy.length)
            for (i in zy.indices step 2) {
                if (i + 1 < zy.length) {
                    zz.append(zy[i + 1]).append(zy[i])
                } else {
                    zz.append(zy[i])
                }
            }
            val z9 = StringBuilder(zz.length)
            for (c in zz) {
                val mapped = if (c in '0'..'9') {
                    val d = c - '0'
                    ('0'.code + ((d + 7) % 10)).toChar()
                } else {
                    c
                }
                z9.append(mapped)
            }
            return "$FINGERPRINT_HASH|$z9"
        }
    }
}
