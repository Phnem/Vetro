package com.example.myapplication.media.source.vfmodule

import android.util.Log
import com.example.myapplication.media.source.VetroVideo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

/**
 * Экстрактор видеохостинга Sibnet для VFmodule.
 */
class SibnetExtractor(
    private val client: HttpClient,
) {
    suspend fun extract(urlOrVideoId: String): List<VetroVideo> {
        val videoId = extractVideoId(urlOrVideoId) ?: urlOrVideoId.trim()
        if (videoId.isBlank()) return emptyList()

        val embedUrl = if (urlOrVideoId.startsWith("http")) {
            urlOrVideoId
        } else {
            "https://video.sibnet.ru/shell.php?videoid=$videoId"
        }

        val html = runCatching {
            client.get(embedUrl) {
                header("User-Agent", VfConstants.CHROME_USER_AGENT)
                header("Referer", "https://video.sibnet.ru/")
            }.bodyAsText()
        }.onFailure {
            Log.w(TAG, "Sibnet fetch failed for $embedUrl: ${it.message}")
        }.getOrNull() ?: return emptyList()

        return parseSibnetHtml(html, embedUrl)
    }

    internal fun parseSibnetHtml(html: String, embedUrl: String): List<VetroVideo> {
        val headers = mapOf(
            "User-Agent" to VfConstants.CHROME_USER_AGENT,
            "Referer" to embedUrl,
        )

        // 1. Поиск player.src([{src: "/v/...", type: "video/mp4"}])
        val srcMatch = Regex("""player\.src\(\[\s*\{\s*src:\s*["']([^"']+)["']""").find(html)
            ?: Regex("""src:\s*["'](/v/[^"']+)["']""").find(html)
            ?: Regex("""["'](/v/[a-zA-Z0-9_\-./]+\.mp4[^"']*)["']""").find(html)
            ?: Regex("""src:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(html)

        if (srcMatch != null) {
            val rawPath = srcMatch.groupValues[1]
            val videoUrl = if (rawPath.startsWith("http")) {
                rawPath
            } else {
                "https://video.sibnet.ru$rawPath"
            }

            return listOf(
                VfConstants.createVideo(
                    url = videoUrl,
                    quality = "480p",
                    headers = headers,
                    providerKey = "sibnet",
                )
            )
        }

        return emptyList()
    }

    private fun extractVideoId(raw: String): String? {
        val videoidParam = Regex("""videoid=(\d+)""").find(raw)?.groupValues?.get(1)
        if (videoidParam != null) return videoidParam

        val videoPath = Regex("""video(\d+)""").find(raw)?.groupValues?.get(1)
        if (videoPath != null) return videoPath

        if (raw.all { it.isDigit() }) return raw
        return null
    }

    companion object {
        const val TAG = "SibnetExtractor"
    }
}
