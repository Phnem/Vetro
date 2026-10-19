package com.example.myapplication.media.source.vfmodule

import android.util.Log
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.network.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Низкоуровневый резолвер потоков CDNVideoHub (CVH) для VFmodule.
 */
class CvhStreamResolver(
    private val client: HttpClient,
) {
    private val json = AppJson

    /**
     * Поиск VK ID (идентификатора видео в CDNVideoHub) по Kinopoisk ID, сезону и серии.
     */
    suspend fun lookupVkIdByKinopoisk(
        kinopoiskId: Int,
        season: Int? = null,
        episode: Int? = null,
    ): String? = runCatching {
        val response = client.get("$PLAPI_BASE/playlist") {
            parameter("pub", 12)
            parameter("aggr", "kp")
            parameter("id", kinopoiskId)
            header("User-Agent", VfConstants.CHROME_USER_AGENT)
            header("Accept", "application/json")
            header("Referer", "http://lostfilm5.org")
        }.bodyAsText()

        val root = json.parseToJsonElement(response) as? JsonObject ?: return@runCatching null
        val items = root["items"] as? JsonArray ?: return@runCatching null
        val objects = items.mapNotNull { it as? JsonObject }
        val matched = if (season != null && episode != null) {
            objects.firstOrNull { item ->
                val s = item["season"]?.jsonPrimitive?.intOrNull
                    ?: item["season"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val e = item["episode"]?.jsonPrimitive?.intOrNull
                    ?: item["episode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                s == season && e == episode
            } ?: objects.firstOrNull()
        } else {
            objects.firstOrNull()
        }
        matched?.get("vkId")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.onFailure {
        Log.w(TAG, "CVH playlist lookup failed for kp=$kinopoiskId: ${it.message}")
    }.getOrNull()

    suspend fun resolveByVkId(vkId: String): List<VetroVideo> {
        val cleanVkId = vkId.trim().removePrefix("vk:").removePrefix("cvh:")
        if (cleanVkId.isBlank()) return emptyList()

        val videos = mutableListOf<VetroVideo>()

        // 1. Запрос к plapi.cdnvideohub.com
        val plapiVideos = queryPlapi(cleanVkId)
        videos.addAll(plapiVideos)

        // 2. Если plapi не вернул потоков, пробуем альтернативный эндпоинт …/streams/vk
        if (videos.isEmpty()) {
            val streamsVideos = queryStreamsVk(cleanVkId)
            videos.addAll(streamsVideos)
        }

        return videos.distinctBy { it.url }
            .sortedByDescending { it.resolutionValue() }
    }

    private suspend fun queryPlapi(vkId: String): List<VetroVideo> = runCatching {
        val url = "$PLAPI_BASE/video/$vkId"
        val response = client.get(url) {
            header("User-Agent", VfConstants.CHROME_USER_AGENT)
            header("Accept", "application/json, text/plain, */*")
            header("Origin", "https://cdnvideohub.com")
            header("Referer", "https://cdnvideohub.com/")
        }.bodyAsText()

        parseCvhResponse(response)
    }.onFailure {
        Log.w(TAG, "Plapi query failed for vkId $vkId: ${it.message}")
    }.getOrElse { emptyList() }

    private suspend fun queryStreamsVk(vkId: String): List<VetroVideo> {
        return runCatching {
            for (base in STREAMS_VK_BASES) {
                val url = "$base/streams/vk?vkId=$vkId"
                val response = runCatching {
                    client.get(url) {
                        header("User-Agent", VfConstants.CHROME_USER_AGENT)
                        header("Accept", "application/json, text/plain, */*")
                        header("Referer", "$base/")
                    }.bodyAsText()
                }.getOrNull() ?: continue

                val parsed = parseCvhResponse(response)
                if (parsed.isNotEmpty()) {
                    return@runCatching parsed
                }
            }
            emptyList<VetroVideo>()
        }.onFailure {
            Log.w(TAG, "Streams/vk query failed for vkId $vkId: ${it.message}")
        }.getOrElse { emptyList() }
    }

    internal fun parseCvhResponse(responseBody: String): List<VetroVideo> {
        if (responseBody.isBlank()) return emptyList()

        val root = runCatching {
            json.parseToJsonElement(responseBody)
        }.getOrNull() as? JsonObject ?: return emptyList()

        val videos = mutableListOf<VetroVideo>()
        val headers = mapOf(
            "User-Agent" to VfConstants.CHROME_USER_AGENT,
            "Referer" to "https://cdnvideohub.com/",
            "Origin" to "https://cdnvideohub.com",
        )

        val sourcesObj = root["sources"] as? JsonObject

        // 1. Разбор HLS плейлиста (hlsUrl / hls / master / playlist)
        val hlsUrl = (
            root["hlsUrl"] ?: root["hls"] ?: root["master"] ?: root["m3u8"]
                ?: sourcesObj?.get("hlsUrl") ?: sourcesObj?.get("hls")
            )?.let {
            if (it is JsonObject) it["url"]?.jsonPrimitive?.contentOrNull else it.jsonPrimitive.contentOrNull
        }
        if (!hlsUrl.isNullOrBlank()) {
            videos.add(
                VfConstants.createVideo(
                    url = VfConstants.normalizeUrl(hlsUrl),
                    quality = "auto",
                    headers = headers,
                    providerKey = "cvh",
                )
            )
        }

        // 2. Разбор DASH манифеста
        val dashUrl = (root["dash"] ?: root["mpd"])?.let {
            if (it is JsonObject) it["url"]?.jsonPrimitive?.contentOrNull else it.jsonPrimitive.contentOrNull
        }
        if (!dashUrl.isNullOrBlank()) {
            videos.add(
                VfConstants.createVideo(
                    url = VfConstants.normalizeUrl(dashUrl),
                    quality = "auto",
                    headers = headers,
                    providerKey = "cvh",
                )
            )
        }

        // 3. Разбор конкретных качеств (qualities / streams / files)
        val qualitiesObj = (root["qualities"] ?: root["streams"] ?: root["files"]) as? JsonObject
        qualitiesObj?.forEach { (resKey, resVal) ->
            val streamUrl = when (resVal) {
                is JsonObject -> (resVal["url"] ?: resVal["src"] ?: resVal["file"])?.jsonPrimitive?.contentOrNull
                else -> resVal.jsonPrimitive.contentOrNull
            }
            if (!streamUrl.isNullOrBlank()) {
                val cleanQuality = normalizeQualityName(resKey)
                videos.add(
                    VfConstants.createVideo(
                        url = VfConstants.normalizeUrl(streamUrl),
                        quality = cleanQuality,
                        headers = headers,
                        providerKey = "cvh",
                    )
                )
            }
        }

        // 4. Разбор массива streams
        val streamsArray = (root["streams"] ?: root["sources"]) as? JsonArray
        streamsArray?.forEach { elem ->
            val obj = elem as? JsonObject ?: return@forEach
            val url = (obj["url"] ?: obj["src"] ?: obj["file"])?.jsonPrimitive?.contentOrNull ?: return@forEach
            val res = obj["resolution"]?.jsonPrimitive?.contentOrNull
                ?: obj["quality"]?.jsonPrimitive?.contentOrNull
                ?: obj["label"]?.jsonPrimitive?.contentOrNull
                ?: "auto"
            videos.add(
                VfConstants.createVideo(
                    url = VfConstants.normalizeUrl(url),
                    quality = normalizeQualityName(res),
                    headers = headers,
                    providerKey = "cvh",
                )
            )
        }

        return videos
    }

    private fun normalizeQualityName(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.isNotBlank()) "${digits}p" else raw
    }

    companion object {
        const val TAG = "CvhStreamResolver"
        const val PLAPI_BASE = "https://plapi.cdnvideohub.com/api/v1/player/sv"
        val STREAMS_VK_BASES = listOf(
            "https://cdnvideohub.com",
            "https://api.cdnvideohub.com",
            "https://plapi.cdnvideohub.com",
        )
    }
}
