package com.example.myapplication.media.source.vfmodule

import android.util.Log
import com.example.myapplication.media.source.VetroVideo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

/**
 * Универсальный экстрактор плееров на базе Playerjs / JavaScript-конфигов для VFmodule.
 */
class PlayerJsExtractor(
    private val client: HttpClient,
) {
    suspend fun extract(
        iframeUrl: String,
        providerKey: String,
        customReferer: String? = null,
    ): List<VetroVideo> {
        val normalizedIframe = VfConstants.normalizeUrl(iframeUrl)
        val domainOrigin = VfConstants.originFor(normalizedIframe)
        val referer = customReferer ?: domainOrigin

        val html = runCatching {
            client.get(normalizedIframe) {
                header("User-Agent", VfConstants.CHROME_USER_AGENT)
                header("Referer", referer)
                header("Origin", domainOrigin)
            }.bodyAsText()
        }.onFailure {
            Log.w(TAG, "Failed to load player iframe $normalizedIframe ($providerKey): ${it.message}")
        }.getOrNull() ?: return emptyList()

        return parsePlayerHtml(html, normalizedIframe, domainOrigin, providerKey)
    }

    internal fun parsePlayerHtml(
        html: String,
        iframeUrl: String,
        domainOrigin: String,
        providerKey: String,
    ): List<VetroVideo> {
        val headers = mapOf(
            "User-Agent" to VfConstants.CHROME_USER_AGENT,
            "Referer" to iframeUrl,
            "Origin" to domainOrigin,
        )

        val videos = mutableListOf<VetroVideo>()

        // 1. Поиск file: "..." (Playerjs / Clappr / Flowplayer)
        val fileRegex = Regex("""(?:['"]file['"]|file)\s*:\s*['"]([^'"]+)['"]""")
        fileRegex.findAll(html).forEach { match ->
            val fileRaw = match.groupValues[1].replace("\\/", "/")
            if (fileRaw.isNotBlank()) {
                val parsed = VfConstants.parsePlayerJsFile(fileRaw, headers, providerKey)
                if (parsed.isNotEmpty()) {
                    videos.addAll(parsed)
                } else if (fileRaw.startsWith("http") || fileRaw.startsWith("//")) {
                    val url = VfConstants.normalizeUrl(fileRaw, domainOrigin)
                    videos.add(
                        VfConstants.createVideo(
                            url = url,
                            quality = if (url.contains(".m3u8")) "auto" else "1080p",
                            headers = headers,
                            providerKey = providerKey,
                        )
                    )
                }
            }
        }

        // 2. Поиск <source src="..." type="..."> или <video src="...">
        val sourceRegex = Regex("""<(?:source|video)[^>]+src=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        sourceRegex.findAll(html).forEach { match ->
            val src = match.groupValues[1].replace("\\/", "/")
            if (src.isNotBlank() && (src.contains(".m3u8") || src.contains(".mp4"))) {
                val url = VfConstants.normalizeUrl(src, domainOrigin)
                videos.add(
                    VfConstants.createVideo(
                        url = url,
                        quality = if (url.contains(".m3u8")) "auto" else "1080p",
                        headers = headers,
                        providerKey = providerKey,
                    )
                )
            }
        }

        // 3. Поиск прямых ссылок .m3u8 в JavaScript коде
        if (videos.isEmpty()) {
            val m3u8Regex = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""")
            m3u8Regex.findAll(html).forEach { match ->
                val url = match.value.replace("\\/", "/")
                videos.add(
                    VfConstants.createVideo(
                        url = url,
                        quality = "auto",
                        headers = headers,
                        providerKey = providerKey,
                    )
                )
            }
        }

        return videos.distinctBy { it.url }
            .sortedByDescending { it.resolutionValue() }
    }

    companion object {
        const val TAG = "PlayerJsExtractor"
    }
}
