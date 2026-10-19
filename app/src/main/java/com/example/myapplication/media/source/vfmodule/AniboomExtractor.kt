package com.example.myapplication.media.source.vfmodule

import android.util.Log
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.network.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Экстрактор видеохостинга AniBoom для VFmodule.
 */
class AniboomExtractor(
    private val client: HttpClient,
) {
    private val json = AppJson

    suspend fun extract(embedUrl: String): List<VetroVideo> {
        val normalizedUrl = VfConstants.normalizeUrl(embedUrl)

        val html = runCatching {
            client.get(normalizedUrl) {
                header("User-Agent", VfConstants.CHROME_USER_AGENT)
                header("Referer", "https://aniboom.one/")
            }.bodyAsText()
        }.onFailure {
            Log.w(TAG, "Aniboom fetch failed for $normalizedUrl: ${it.message}")
        }.getOrNull() ?: return emptyList()

        return parseAniboomHtml(html, normalizedUrl)
    }

    internal fun parseAniboomHtml(html: String, embedUrl: String): List<VetroVideo> {
        val headers = mapOf(
            "User-Agent" to VfConstants.CHROME_USER_AGENT,
            "Referer" to "https://aniboom.one/",
            "Origin" to "https://aniboom.one",
        )

        val videos = mutableListOf<VetroVideo>()

        // 1. Поиск data-parameters в теге <video id="video"> или любом другом элементе
        val doc = Jsoup.parse(html, embedUrl)
        val rawParameters = doc.selectFirst("video#video")?.attr("data-parameters")
            ?: doc.selectFirst("[data-parameters]")?.attr("data-parameters")
            ?: Regex("""data-parameters=["']([^"']+)["']""").find(html)?.groupValues?.get(1)

        if (!rawParameters.isNullOrBlank()) {
            val unescaped = Parser.unescapeEntities(rawParameters, false)
            val dataObj = runCatching {
                json.parseToJsonElement(unescaped).jsonObject
            }.getOrNull()

            if (dataObj != null) {
                fun extractStream(key: String): String? {
                    val rawVal = dataObj[key]?.jsonPrimitive?.contentOrNull ?: return null
                    return runCatching {
                        json.parseToJsonElement(rawVal).jsonObject["src"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull() ?: rawVal
                }

                val hls = extractStream("hls")
                if (!hls.isNullOrBlank()) {
                    videos.add(
                        VfConstants.createVideo(
                            url = VfConstants.normalizeUrl(hls),
                            quality = "auto",
                            headers = headers,
                            providerKey = "aniboom",
                        )
                    )
                }

                val dash = extractStream("dash")
                if (!dash.isNullOrBlank()) {
                    videos.add(
                        VfConstants.createVideo(
                            url = VfConstants.normalizeUrl(dash),
                            quality = "auto",
                            headers = headers,
                            providerKey = "aniboom",
                        )
                    )
                }

                val mp4 = extractStream("mp4")
                if (!mp4.isNullOrBlank()) {
                    videos.add(
                        VfConstants.createVideo(
                            url = VfConstants.normalizeUrl(mp4),
                            quality = "1080p",
                            headers = headers,
                            providerKey = "aniboom",
                        )
                    )
                }
            }
        }

        // 2. Фолбэк: поиск master.m3u8 в разметке
        if (videos.isEmpty()) {
            val m3u8Match = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""").find(html)
            if (m3u8Match != null) {
                videos.add(
                    VfConstants.createVideo(
                        url = m3u8Match.value.replace("\\/", "/"),
                        quality = "auto",
                        headers = headers,
                        providerKey = "aniboom",
                    )
                )
            }
        }

        return videos.distinctBy { it.url }
    }

    companion object {
        const val TAG = "AniboomExtractor"
    }
}
