package com.example.myapplication.media.source.vfmodule

import android.util.Log
import com.example.myapplication.media.source.VetroVideo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

/**
 * Экстрактор потоков VeoVeo для VFmodule.
 */
class VeoVeoExtractor(
    private val client: HttpClient,
) {
    suspend fun extractFromIframe(
        iframeUrl: String,
        season: Int? = null,
        episode: Int? = null,
    ): List<VetroVideo> {
        val normalizedIframe = VfConstants.normalizeUrl(iframeUrl)
        val domainOrigin = VfConstants.originFor(normalizedIframe)

        val html = runCatching {
            client.get(normalizedIframe) {
                header("User-Agent", VfConstants.CHROME_USER_AGENT)
                header("Referer", domainOrigin)
            }.bodyAsText()
        }.onFailure {
            Log.w(TAG, "Failed to load VeoVeo iframe $normalizedIframe: ${it.message}")
        }.getOrNull() ?: return emptyList()

        return parseVeoVeoHtml(html, normalizedIframe, domainOrigin)
    }

    internal fun parseVeoVeoHtml(
        html: String,
        iframeUrl: String,
        domainOrigin: String,
    ): List<VetroVideo> {
        val headers = mapOf(
            "User-Agent" to VfConstants.CHROME_USER_AGENT,
            "Referer" to iframeUrl,
            "Origin" to domainOrigin,
        )

        val videos = mutableListOf<VetroVideo>()

        // 1. Поиск hlsUrl
        val hlsMatch = Regex("""['"]hlsUrl['"]\s*:\s*['"]([^'"]+)['"]""").find(html)
            ?: Regex("""hlsUrl\s*:\s*['"]([^'"]+)['"]""").find(html)
        if (hlsMatch != null) {
            val url = VfConstants.normalizeUrl(hlsMatch.groupValues[1], domainOrigin)
            videos.add(
                VfConstants.createVideo(
                    url = url,
                    quality = "auto",
                    headers = headers,
                    providerKey = "veoveo",
                )
            )
        }

        // 2. Поиск dashUrl
        val dashMatch = Regex("""['"]dashUrl['"]\s*:\s*['"]([^'"]+)['"]""").find(html)
            ?: Regex("""dashUrl\s*:\s*['"]([^'"]+)['"]""").find(html)
        if (dashMatch != null) {
            val url = VfConstants.normalizeUrl(dashMatch.groupValues[1], domainOrigin)
            videos.add(
                VfConstants.createVideo(
                    url = url,
                    quality = "auto",
                    headers = headers,
                    providerKey = "veoveo",
                )
            )
        }

        // 3. Поиск file: "..."
        val fileMatch = Regex("""['"]file['"]\s*:\s*['"]([^'"]+)['"]""").find(html)
            ?: Regex("""file\s*:\s*['"]([^'"]+)['"]""").find(html)
        if (fileMatch != null) {
            val fileRaw = fileMatch.groupValues[1].replace("\\/", "/")
            val parsed = VfConstants.parsePlayerJsFile(fileRaw, headers, "veoveo")
            if (parsed.isNotEmpty()) {
                videos.addAll(parsed)
            } else {
                val url = VfConstants.normalizeUrl(fileRaw, domainOrigin)
                videos.add(
                    VfConstants.createVideo(
                        url = url,
                        quality = "auto",
                        headers = headers,
                        providerKey = "veoveo",
                    )
                )
            }
        }

        // 4. Поиск прямой m3u8 ссылки
        if (videos.isEmpty()) {
            val m3u8Match = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""").find(html)
            if (m3u8Match != null) {
                val url = m3u8Match.value.replace("\\/", "/")
                videos.add(
                    VfConstants.createVideo(
                        url = url,
                        quality = "auto",
                        headers = headers,
                        providerKey = "veoveo",
                    )
                )
            }
        }

        return videos.distinctBy { it.url }
    }

    companion object {
        const val TAG = "VeoVeoExtractor"
    }
}
