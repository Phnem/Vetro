package com.example.myapplication.media.source.vfmodule

import android.util.Log
import com.example.myapplication.media.source.VetroVideo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

/**
 * Экстрактор видеохостинга VK Video (vk.com / vkvideo.ru) для VFmodule.
 */
class VkVideoExtractor(
    private val client: HttpClient,
) {
    suspend fun extract(embedUrl: String): List<VetroVideo> {
        val normalizedUrl = VfConstants.normalizeUrl(embedUrl)

        val html = runCatching {
            client.get(normalizedUrl) {
                header("User-Agent", VfConstants.CHROME_USER_AGENT)
                header("Referer", "https://vk.com/")
            }.bodyAsText()
        }.onFailure {
            Log.w(TAG, "VK Video fetch failed for $normalizedUrl: ${it.message}")
        }.getOrNull() ?: return emptyList()

        return parseVkHtml(html, normalizedUrl)
    }

    internal fun parseVkHtml(html: String, embedUrl: String): List<VetroVideo> {
        val headers = mapOf(
            "User-Agent" to VfConstants.CHROME_USER_AGENT,
            "Referer" to embedUrl,
        )

        val videos = mutableListOf<VetroVideo>()

        // 1. Поиск качеств в JSON/переменных плеера: "url1080": "...", "url720": "...", и т.д.
        val qualityKeys = listOf(
            "2160" to "2160p",
            "1440" to "1440p",
            "1080" to "1080p",
            "720" to "720p",
            "480" to "480p",
            "360" to "360p",
            "240" to "240p",
        )

        for ((key, label) in qualityKeys) {
            val regex = Regex("""['"]url$key['"]\s*:\s*['"]([^'"]+)['"]""")
            val match = regex.find(html)
            if (match != null) {
                val url = match.groupValues[1].replace("\\/", "/")
                videos.add(
                    VfConstants.createVideo(
                        url = url,
                        quality = label,
                        headers = headers,
                        providerKey = "vkvideo",
                    )
                )
            }
        }

        // 2. Поиск HLS потока ("hls": "...")
        val hlsRegex = Regex("""['"]hls(?:_live)?['"]\s*:\s*['"]([^'"]+)['"]""")
        val hlsMatch = hlsRegex.find(html)
        if (hlsMatch != null) {
            val url = hlsMatch.groupValues[1].replace("\\/", "/")
            videos.add(
                VfConstants.createVideo(
                    url = url,
                    quality = "auto",
                    headers = headers,
                    providerKey = "vkvideo",
                )
            )
        }

        // 3. Фолбэк: <source src="..." type="video/mp4">
        if (videos.isEmpty()) {
            val sourceRegex = Regex("""<source[^>]+src=['"]([^'"]+)['"]""")
            sourceRegex.findAll(html).forEach { match ->
                val src = match.groupValues[1].replace("\\/", "/")
                videos.add(
                    VfConstants.createVideo(
                        url = src,
                        quality = "720p",
                        headers = headers,
                        providerKey = "vkvideo",
                    )
                )
            }
        }

        return videos.distinctBy { it.url }
            .sortedByDescending { it.resolutionValue() }
    }

    companion object {
        const val TAG = "VkVideoExtractor"
    }
}
