package com.example.myapplication.media.source.vfmodule

import android.util.Log
import com.example.myapplication.network.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Агрегатор плееров стриминговых балансеров для VFmodule.
 * Запрашивает зеркала kinobox, а при их недоступности напрямую опрашивает рабочие API балансеров
 * (HDVB, Alloha/Aladin, Collaps) и кэширует результаты на время сессии.
 */
class KinoboxPlayersClient(
    private val client: HttpClient,
) {
    private val json = AppJson
    private val cache = ConcurrentHashMap<String, List<PlayerHit>>()
    private val deadMirrors = ConcurrentHashMap.newKeySet<String>()
    private val mutex = Mutex()

    data class PlayerHit(
        val type: String,
        val iframeUrl: String,
        val translation: String? = null,
        val success: Boolean = true,
    )

    /**
     * Поиск плееров по ID Кинопоиска, IMDb, TMDB или названию тайтла.
     */
    suspend fun fetchPlayers(
        kinopoiskId: Int? = null,
        imdbId: String? = null,
        tmdbId: Int? = null,
        title: String? = null,
    ): List<PlayerHit> {
        if (kinopoiskId == null && imdbId.isNullOrBlank() && tmdbId == null && title.isNullOrBlank()) {
            return emptyList()
        }

        val cacheKey = "${kinopoiskId ?: ""}|${imdbId?.trim().orEmpty()}|${tmdbId ?: ""}|${title?.trim()?.lowercase().orEmpty()}"
        cache[cacheKey]?.let { return it }

        return mutex.withLock {
            cache[cacheKey]?.let { return@withLock it }
            val resolved = fetchPlayersUncached(kinopoiskId, imdbId, tmdbId, title)
            if (resolved.isNotEmpty()) {
                cache[cacheKey] = resolved
            }
            resolved
        }
    }

    private suspend fun fetchPlayersUncached(
        kinopoiskId: Int?,
        imdbId: String?,
        tmdbId: Int?,
        title: String?,
    ): List<PlayerHit> {
        // 1. Пробуем зеркала агрегатора (если они ещё не помечены как упавшие по SSL/протоколу)
        for (mirror in MIRRORS) {
            if (mirror in deadMirrors) continue
            val hits = runCatching {
                val response = client.get(mirror) {
                    kinopoiskId?.let { parameter("kinopoisk", it) }
                    imdbId?.let { parameter("imdb", it) }
                    tmdbId?.let { parameter("tmdb", it) }
                    title?.takeIf { kinopoiskId == null && imdbId.isNullOrBlank() && tmdbId == null }?.let { parameter("title", it) }
                    header("User-Agent", VfConstants.CHROME_USER_AGENT)
                    header("Accept", "application/json")
                }.bodyAsText()

                parsePlayersJson(response)
            }.onFailure {
                deadMirrors.add(mirror)
                Log.w(TAG, "Mirror $mirror failed: ${it.message}")
            }.getOrNull()

            if (!hits.isNullOrEmpty()) {
                return hits
            }
        }

        // 2. Прямой опрос рабочих балансеров (HDVB, Alloha/Aladin, Collaps)
        val directHits = mutableListOf<PlayerHit>()
        var resolvedKp = kinopoiskId
        var resolvedImdb = imdbId?.takeIf { it.isNotBlank() }

        // 2a. Alloha / Aladin API (также возвращает связку kp + imdb по одному из ID или названию)
        runCatching {
            val allohaUrl = if (resolvedKp != null || resolvedImdb != null) {
                "${AllohaExtractor.API_V2_BASE}/movies/search"
            } else if (!title.isNullOrBlank()) {
                "${AllohaExtractor.API_V2_BASE}/movies/name/list"
            } else {
                null
            }
            if (allohaUrl != null) {
                val body = client.get(allohaUrl) {
                    if (resolvedKp != null || resolvedImdb != null) {
                        resolvedKp?.let { parameter("kp", it) }
                        resolvedImdb?.let { parameter("imdb", it) }
                    } else {
                        parameter("name", title)
                    }
                    header("Authorization", "Bearer ${AllohaExtractor.DEFAULT_TOKEN}")
                    header("User-Agent", VfConstants.CHROME_USER_AGENT)
                    header("Accept", "application/json")
                }.bodyAsText()

                val root = json.parseToJsonElement(body) as? JsonObject
                val dataObj = when (val d = root?.get("data")) {
                    is JsonObject -> d
                    is JsonArray -> d.firstOrNull() as? JsonObject
                    else -> null
                }
                if (dataObj != null) {
                    val idsObj = dataObj["ids"] as? JsonObject
                    val kp = idsObj?.get("kp")?.jsonPrimitive?.intOrNull
                        ?: dataObj["id_kp"]?.jsonPrimitive?.intOrNull
                        ?: resolvedKp
                    val imdb = idsObj?.get("imdb")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("tt") }
                        ?: dataObj["id_imdb"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("tt") }
                        ?: resolvedImdb
                    if (kp != null && kp > 0) resolvedKp = kp
                    if (!imdb.isNullOrBlank()) resolvedImdb = imdb
                }
            }
        }.onFailure {
            Log.w(TAG, "Direct Alloha lookup failed: ${it.message}")
        }

        if (resolvedKp != null || resolvedImdb != null) {
            val allohaIframe = buildString {
                append("${AllohaExtractor.PLAYER_BASE}/?token=${AllohaExtractor.DEFAULT_TOKEN}")
                resolvedKp?.let { append("&kp=").append(it) }
                resolvedImdb?.let { append("&imdb=").append(it) }
            }
            directHits.add(
                PlayerHit(
                    type = "alloha",
                    iframeUrl = allohaIframe,
                )
            )
        }

        // 2b. HDVB Direct API (apivb.com)
        runCatching {
            if (resolvedKp != null || !title.isNullOrBlank()) {
                val hdvbBody = client.get(HDVB_API_URL) {
                    parameter("token", HDVB_TOKEN)
                    if (resolvedKp != null) {
                        parameter("id_kp", resolvedKp)
                    } else {
                        parameter("title", title)
                    }
                    header("User-Agent", VfConstants.CHROME_USER_AGENT)
                    header("Referer", HdvbExtractor.DEFAULT_REFERER)
                    header("Accept", "application/json")
                }.bodyAsText()

                val array = json.parseToJsonElement(hdvbBody) as? JsonArray
                array?.mapNotNull { it as? JsonObject }?.forEach { item ->
                    val iframe = item["iframe_url"]?.jsonPrimitive?.contentOrNull
                        ?.replace("\\/", "/")
                        ?.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    val translator = item["translator"]?.jsonPrimitive?.contentOrNull
                    val kpFromHdvb = item["kinopoisk_id"]?.jsonPrimitive?.intOrNull
                    if (resolvedKp == null && kpFromHdvb != null && kpFromHdvb > 0) {
                        resolvedKp = kpFromHdvb
                    }
                    directHits.add(
                        PlayerHit(
                            type = "hdvb",
                            iframeUrl = VfConstants.normalizeUrl(iframe),
                            translation = translator,
                        )
                    )
                }
            }
        }.onFailure {
            Log.w(TAG, "Direct HDVB lookup failed: ${it.message}")
        }

        // 2c. Collaps Direct Embed / API (api.luxembd.ws / api.bhcesh.me)
        when {
            resolvedKp != null -> {
                directHits.add(
                    PlayerHit(
                        type = "collaps",
                        iframeUrl = "$COLLAPS_EMBED_BASE/kp/$resolvedKp",
                    )
                )
            }
            resolvedImdb != null -> {
                directHits.add(
                    PlayerHit(
                        type = "collaps",
                        iframeUrl = "$COLLAPS_EMBED_BASE/imdb/$resolvedImdb",
                    )
                )
            }
            !title.isNullOrBlank() -> {
                runCatching {
                    val collapsBody = client.get(COLLAPS_LIST_API) {
                        parameter("token", COLLAPS_TOKEN)
                        parameter("name", title)
                        header("User-Agent", VfConstants.CHROME_USER_AGENT)
                        header("Accept", "application/json")
                    }.bodyAsText()
                    val root = json.parseToJsonElement(collapsBody) as? JsonObject
                    val first = (root?.get("results") as? JsonArray)?.firstOrNull() as? JsonObject
                    val iframe = first?.get("iframe_url")?.jsonPrimitive?.contentOrNull?.replace("\\/", "/")
                    val movieId = first?.get("id")?.jsonPrimitive?.intOrNull
                    val url = when {
                        !iframe.isNullOrBlank() -> VfConstants.normalizeUrl(iframe)
                        movieId != null -> "$COLLAPS_EMBED_BASE/movie/$movieId"
                        else -> null
                    }
                    if (!url.isNullOrBlank()) {
                        directHits.add(PlayerHit(type = "collaps", iframeUrl = url))
                    }
                }.onFailure {
                    Log.w(TAG, "Direct Collaps lookup failed: ${it.message}")
                }
            }
        }

        return directHits.distinctBy { "${it.type}:${it.iframeUrl}" }
    }

    internal fun parsePlayersJson(responseBody: String): List<PlayerHit> {
        val root = runCatching {
            json.parseToJsonElement(responseBody)
        }.getOrNull() ?: return emptyList()

        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"]?.let { if (it is JsonArray) it else null }
                ?: root["players"]?.let { if (it is JsonArray) it else null }
                ?: JsonArray(listOf(root))
            else -> return emptyList()
        }

        return list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = (obj["type"] ?: obj["source"] ?: obj["name"])?.jsonPrimitive?.contentOrNull
                ?.lowercase()
                ?: return@mapNotNull null
            val iframe = (obj["iframeUrl"] ?: obj["iframe"] ?: obj["url"])?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val translation = (obj["translation"] ?: obj["voice"] ?: obj["audio"])?.let {
                if (it is JsonObject) {
                    it["title"]?.jsonPrimitive?.contentOrNull ?: it["name"]?.jsonPrimitive?.contentOrNull
                } else {
                    it.jsonPrimitive.contentOrNull
                }
            }
            val success = obj["success"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true

            PlayerHit(
                type = normalizeType(type),
                iframeUrl = VfConstants.normalizeUrl(iframe),
                translation = translation,
                success = success,
            )
        }.filter { it.iframeUrl.isNotBlank() && it.success }
    }

    private fun normalizeType(raw: String): String {
        return when (raw.trim().lowercase()) {
            "rhs", "redheadsound" -> "redheadsound"
            "videocdn", "videoseed" -> "videoseed"
            "anilib", "animelib" -> "animelib"
            "vk", "vkvideo", "vk_video" -> "vkvideo"
            "collapse", "collaps" -> "collaps"
            else -> raw.trim().lowercase()
        }
    }

    companion object {
        const val TAG = "KinoboxPlayersClient"
        const val HDVB_API_URL = "https://apivb.com/api/videos.json"
        const val HDVB_TOKEN = "5e2fe4c70bafd9a7414c4f170ee1b192"
        const val COLLAPS_EMBED_BASE = "https://api.luxembd.ws/embed"
        const val COLLAPS_LIST_API = "https://api.bhcesh.me/list"
        const val COLLAPS_TOKEN = "eedefb541aeba871dcfc756e6b31c02e"
        val MIRRORS = listOf(
            "https://fbphdplay.top/api/players",
            "https://api.kinobox.tv/api/players",
        )
    }
}
