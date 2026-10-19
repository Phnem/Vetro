package com.example.myapplication.media.source.vfmodule

import android.util.Log
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.network.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

/**
 * Экстрактор потоков HDVB для VFmodule.
 */
class HdvbExtractor(
    private val client: HttpClient,
) {
    private val json = AppJson

    data class IframeParams(
        val href: String,
        val key: String,
        val file: String,
        val csrfToken: String,
        val playlistUrl: String,
        val domainOrigin: String,
        val iframeUrl: String,
    )

    suspend fun extract(
        iframeUrl: String,
        season: Int? = null,
        episode: Int? = null,
    ): List<VetroVideo> {
        val normalizedIframe = VfConstants.normalizeUrl(iframeUrl)
        val domainOrigin = VfConstants.originFor(normalizedIframe)

        // 1. Загружаем HTML iframe
        val html = runCatching {
            client.get(normalizedIframe) {
                header("User-Agent", VfConstants.CHROME_USER_AGENT)
                header("Referer", DEFAULT_REFERER)
            }.bodyAsText()
        }.onFailure {
            Log.w(TAG, "Failed to load HDVB iframe $normalizedIframe: ${it.message}")
        }.getOrNull() ?: return emptyList()

        // 2. Парсим параметры из HTML: href, key, file, csrf-token
        val params = parseIframeHtml(html, normalizedIframe)
            ?: return parsePlaylistResponse(html, domainOrigin, normalizedIframe)

        val effectiveCsrf = params.csrfToken.ifBlank { params.key }

        // 3. Шлём POST на ссылку плейлиста с заголовком x-csrf-token и подставленными Origin и Referer
        val playlistResponse = postPlaylist(
            url = params.playlistUrl,
            params = params,
            csrfToken = effectiveCsrf,
            domainOrigin = domainOrigin,
        ) ?: return emptyList()

        // 4. Если это сериал и вернулся JSON-массив сезонов/серий — выбираем нужный сезон и серию
        val seriesVideos = extractSeriesPlaylistIfPresent(
            responseBody = playlistResponse,
            params = params,
            csrfToken = effectiveCsrf,
            domainOrigin = domainOrigin,
            season = season,
            episode = episode,
        )
        if (seriesVideos.isNotEmpty()) {
            return seriesVideos
        }

        // 5. Разбор полученного плейлиста (.m3u8 или JSON со ссылкой на m3u8 / вложенным токеном)
        val direct = parsePlaylistResponse(playlistResponse, domainOrigin, "$domainOrigin/")
        if (direct.isNotEmpty()) {
            val firstUrl = direct.first().url
            if (!firstUrl.contains(".m3u8") && firstUrl.contains("/~") && !params.href.startsWith("http")) {
                val tokenFile = firstUrl.substringAfterLast("/").removePrefix("/playlist/").removeSuffix(".txt")
                val secondUrl = "https://vid11.${params.href}/playlist/$tokenFile.txt"
                val secondResp = postPlaylist(secondUrl, params, effectiveCsrf, domainOrigin)
                if (!secondResp.isNullOrBlank()) {
                    val resolved = parsePlaylistResponse(secondResp, domainOrigin, "$domainOrigin/")
                    if (resolved.isNotEmpty()) return resolved
                }
            }
            return direct
        }

        return emptyList()
    }

    private suspend fun postPlaylist(
        url: String,
        params: IframeParams,
        csrfToken: String,
        domainOrigin: String,
    ): String? = runCatching {
        if (url.contains("/playlist/") && url.endsWith(".txt")) {
            client.post(url) {
                header("User-Agent", VfConstants.CHROME_USER_AGENT)
                header("Accept", "*/*")
                header("x-csrf-token", csrfToken)
                header("Origin", domainOrigin)
                header("Referer", "$domainOrigin/")
                setBody("")
            }.bodyAsText()
        } else {
            client.submitForm(
                url = url,
                formParameters = parameters {
                    append("href", params.href)
                    append("key", params.key)
                    append("file", params.file)
                },
            ) {
                header("User-Agent", VfConstants.CHROME_USER_AGENT)
                header("x-csrf-token", csrfToken)
                header("X-Requested-With", "XMLHttpRequest")
                header("Origin", domainOrigin)
                header("Referer", params.iframeUrl)
            }.bodyAsText()
        }
    }.onFailure {
        Log.w(TAG, "HDVB playlist POST failed ($url): ${it.message}")
    }.getOrNull()

    private suspend fun extractSeriesPlaylistIfPresent(
        responseBody: String,
        params: IframeParams,
        csrfToken: String,
        domainOrigin: String,
        season: Int?,
        episode: Int?,
    ): List<VetroVideo> {
        val trimmed = responseBody.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val array = runCatching { json.parseToJsonElement(trimmed) as? JsonArray }.getOrNull()
            ?: return emptyList()

        // Проверяем, что это структура сезонов HDVB (элементы содержат "folder")
        val firstObj = array.firstOrNull() as? JsonObject ?: return emptyList()
        if (!firstObj.containsKey("folder")) return emptyList()

        val targetSeason = season ?: 1
        val targetEpisode = episode ?: 1

        val seasonObj = array.mapNotNull { it as? JsonObject }.firstOrNull { sObj ->
            val sId = sObj["id"]?.jsonPrimitive?.contentOrNull?.trim()
            val sNum = sObj["season"]?.jsonPrimitive?.intOrNull
                ?: sObj["season_number"]?.jsonPrimitive?.intOrNull
                ?: sId?.toIntOrNull()
            sNum == targetSeason
        } ?: firstObj

        val episodesFolder = seasonObj["folder"] as? JsonArray ?: return emptyList()
        val episodeObj = episodesFolder.mapNotNull { it as? JsonObject }.firstOrNull { eObj ->
            val epNum = eObj["episode"]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull()
                ?: eObj["id"]?.jsonPrimitive?.contentOrNull?.substringAfterLast("-")?.toIntOrNull()
            epNum == targetEpisode
        } ?: (episodesFolder.firstOrNull() as? JsonObject) ?: return emptyList()

        data class VoiceFile(val file: String, val title: String?)

        val voiceFiles = mutableListOf<VoiceFile>()
        val voicesFolder = episodeObj["folder"] as? JsonArray
        if (voicesFolder != null && voicesFolder.isNotEmpty()) {
            voicesFolder.mapNotNull { it as? JsonObject }.forEach { vObj ->
                val f = vObj["file"]?.jsonPrimitive?.contentOrNull?.trim()
                val t = vObj["title"]?.jsonPrimitive?.contentOrNull?.trim()
                if (!f.isNullOrBlank()) {
                    voiceFiles.add(VoiceFile(f, t))
                }
            }
        } else {
            val directFile = episodeObj["file"]?.jsonPrimitive?.contentOrNull?.trim()
            val directTitle = episodeObj["title"]?.jsonPrimitive?.contentOrNull?.trim()
            if (!directFile.isNullOrBlank()) {
                voiceFiles.add(VoiceFile(directFile, directTitle))
            }
        }

        if (voiceFiles.isEmpty()) return emptyList()

        val playbackHeaders = mapOf(
            "User-Agent" to VfConstants.CHROME_USER_AGENT,
            "Referer" to "$domainOrigin/",
            "Origin" to domainOrigin,
        )

        val results = mutableListOf<VetroVideo>()
        for (vf in voiceFiles.take(4)) {
            val cleanFile = vf.file
                .replace("\\/", "/")
                .replace("\\", "")
                .removePrefix("/playlist/")
                .removeSuffix(".txt")

            if (cleanFile.startsWith("http") && cleanFile.contains(".m3u8")) {
                results.add(
                    VfConstants.createVideo(
                        url = cleanFile,
                        quality = if (!vf.title.isNullOrBlank()) "auto (${vf.title})" else "auto",
                        headers = playbackHeaders,
                        providerKey = "hdvb",
                    )
                )
                continue
            }

            val epUrl = if (!params.href.startsWith("http") && params.href.isNotBlank()) {
                "https://vid11.${params.href}/playlist/$cleanFile.txt"
            } else {
                "$domainOrigin/playlist/$cleanFile.txt"
            }

            val m3u8Body = postPlaylist(epUrl, params, csrfToken, domainOrigin)?.trim().orEmpty()
            if (m3u8Body.startsWith("http") && m3u8Body.contains(".m3u8")) {
                results.add(
                    VfConstants.createVideo(
                        url = m3u8Body,
                        quality = if (!vf.title.isNullOrBlank()) "auto (${vf.title})" else "auto",
                        headers = playbackHeaders,
                        providerKey = "hdvb",
                    )
                )
            } else {
                val parsed = parsePlaylistResponse(m3u8Body, domainOrigin, "$domainOrigin/")
                results.addAll(parsed)
            }
        }

        return results.distinctBy { it.url }
    }

    internal fun parseIframeHtml(html: String, iframeUrl: String): IframeParams? {
        val doc = Jsoup.parse(html, iframeUrl)
        val domainOrigin = VfConstants.originFor(iframeUrl)

        // href, key, file из скрипта плеера (playerConfigs = {...}) или параметров window
        val href = Regex("""['"]?href['"]?\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("\\", "")
            ?: iframeUrl

        val key = (
            Regex("""['"]?key['"]?\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""\bvar\s+key\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: ""
            ).replace("\\/", "/").replace("\\", "")

        val file = (
            Regex("""['"]?file['"]?\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""\bvar\s+file\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: ""
            ).replace("\\/", "/").replace("\\", "")

        // CSRF-token из meta-тега, JS или поля key в playerConfigs
        val csrfToken = (
            doc.selectFirst("meta[name=csrf-token]")?.attr("content")
                ?: Regex("""['"]x-csrf-token['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""csrf-token['"]\s*content=['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""csrf_token\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: key
            ).replace("\\/", "/").replace("\\", "")

        if (csrfToken.isBlank() && key.isBlank() && file.isBlank()) {
            Log.w(TAG, "HDVB parameters missing in iframe $iframeUrl")
            return null
        }

        // Если это прямой ответ JSON {"file": "https://...m3u8"}, а не HTML с playerConfigs
        if (html.trim().startsWith("{") && file.startsWith("http") && file.contains(".m3u8") && key.isBlank()) {
            return null
        }

        val playlistUrl = if (
            !href.startsWith("http") &&
            href.contains(".") &&
            (file.startsWith("/playlist/") || file.startsWith("~"))
        ) {
            val cleanFile = file.removePrefix("/playlist/").removeSuffix(".txt")
            "https://vid11.$href/playlist/$cleanFile.txt"
        } else {
            val playlistEndpoint = Regex("""url\s*:\s*['"](/[^'"]*playlist[^'"]*)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""['"](/playlist[^'"]*)['"]""").find(html)?.groupValues?.get(1)
                ?: "/playlist"
            if (playlistEndpoint.startsWith("http")) {
                playlistEndpoint
            } else {
                "$domainOrigin$playlistEndpoint"
            }
        }

        return IframeParams(
            href = href,
            key = key,
            file = file,
            csrfToken = csrfToken,
            playlistUrl = playlistUrl,
            domainOrigin = domainOrigin,
            iframeUrl = iframeUrl,
        )
    }

    internal fun parsePlaylistResponse(
        responseBody: String,
        domainOrigin: String,
        referer: String,
    ): List<VetroVideo> {
        val trimmed = responseBody.trim()
        val headers = mapOf(
            "User-Agent" to VfConstants.CHROME_USER_AGENT,
            "Referer" to referer,
            "Origin" to domainOrigin,
        )

        // Вариант 1: тело само является .m3u8 ссылкой или содержит .m3u8
        if (trimmed.startsWith("http") && trimmed.contains(".m3u8")) {
            return listOf(
                VfConstants.createVideo(
                    url = trimmed,
                    quality = "auto",
                    headers = headers,
                    providerKey = "hdvb",
                )
            )
        }

        // Вариант 2: JSON-ответ со списком или полем file / url
        val jsonElement = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        if (jsonElement is JsonObject) {
            val fileUrl = (jsonElement["file"] ?: jsonElement["url"] ?: jsonElement["playlist"])
                ?.jsonPrimitive?.contentOrNull
            if (!fileUrl.isNullOrBlank()) {
                val normalizedUrl = VfConstants.normalizeUrl(fileUrl, domainOrigin)
                return listOf(
                    VfConstants.createVideo(
                        url = normalizedUrl,
                        quality = "auto",
                        headers = headers,
                        providerKey = "hdvb",
                    )
                )
            }
        }

        // Вариант 3: JSON массив с качествами / файлами
        if (jsonElement is JsonArray) {
            val videos = jsonElement.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val url = (obj["url"] ?: obj["file"])?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val quality = obj["title"]?.jsonPrimitive?.contentOrNull
                    ?: obj["label"]?.jsonPrimitive?.contentOrNull
                    ?: obj["quality"]?.jsonPrimitive?.contentOrNull
                    ?: "auto"
                val normalizedUrl = VfConstants.normalizeUrl(url, domainOrigin)
                VfConstants.createVideo(
                    url = normalizedUrl,
                    quality = quality,
                    headers = headers,
                    providerKey = "hdvb",
                )
            }
            if (videos.isNotEmpty()) return videos
        }

        // Вариант 4: Regex-поиск .m3u8 ссылки внутри ответа
        val m3u8Match = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""").find(trimmed)
        if (m3u8Match != null) {
            val url = m3u8Match.value.replace("\\/", "/")
            return listOf(
                VfConstants.createVideo(
                    url = url,
                    quality = "auto",
                    headers = headers,
                    providerKey = "hdvb",
                )
            )
        }

        return emptyList()
    }

    companion object {
        const val TAG = "HdvbExtractor"
        const val DEFAULT_REFERER = "https://movielab.one/"
    }
}
