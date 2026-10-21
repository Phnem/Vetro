package com.example.myapplication.media.source.vftmodule.clients

import com.example.myapplication.media.source.vftmodule.VftPreferences
import com.example.myapplication.media.source.vftmodule.VftReleaseParser
import com.example.myapplication.media.source.vftmodule.VftTorrentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Клиент Jackett API (Torznab / JSON).
 * Позволяет искать раздачи сразу по десяткам трекеров, настроенных пользователем в Jackett.
 */
class JackettClient(
    private val client: OkHttpClient,
    private val prefs: VftPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.isJackettEnabled || prefs.jackettUrl.isBlank()) return@withContext false
        val testUrl = "${prefs.jackettUrl}/api/v2.0/indexers/all/results?apikey=${prefs.jackettApiKey}&t=caps"
        val request = Request.Builder().url(testUrl).get().build()
        runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun search(query: String): List<VftTorrentItem> = withContext(Dispatchers.IO) {
        if (!prefs.isJackettEnabled || prefs.jackettUrl.isBlank()) return@withContext emptyList()

        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val apiKey = prefs.jackettApiKey
        val url = "${prefs.jackettUrl}/api/v2.0/indexers/all/results?apikey=$apiKey&Query=$encoded&Category[]=2000&Category[]=5000"

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return@withContext emptyList()
        val body = response.use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            resp.body?.string().orEmpty()
        }

        if (body.isBlank()) return@withContext emptyList()

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@withContext emptyList()
        val resultsArray = root["Results"]?.jsonArray ?: return@withContext emptyList()

        val items = mutableListOf<VftTorrentItem>()
        for (el in resultsArray) {
            val obj = el.jsonObject
            val title = obj["Title"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (title.isBlank()) continue

            val magnetUri = obj["MagnetUri"]?.jsonPrimitive?.content?.takeIf { it.startsWith("magnet:") }
            val link = obj["Link"]?.jsonPrimitive?.content
            val directMagnet = link?.takeIf { it.startsWith("magnet:") }
            val effectiveMagnet = magnetUri ?: directMagnet

            val infoHash = obj["InfoHash"]?.jsonPrimitive?.content?.trim()
                ?: effectiveMagnet?.let { extractInfoHash(it) }
                ?: continue

            val torrentUrl = link?.takeIf { it.startsWith("http") }
            val tracker = obj["Tracker"]?.jsonPrimitive?.content ?: "Jackett"
            val sizeBytes = obj["Size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val seeders = obj["Seeders"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val leechers = obj["Peers"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

            val magnet = effectiveMagnet ?: "magnet:?xt=urn:btih:${infoHash.lowercase()}&dn=${URLEncoder.encode(title, "UTF-8")}"
            val parsedInfo = VftReleaseParser.parse(title)

            items.add(
                VftTorrentItem(
                    title = title,
                    magnetUrl = magnet,
                    torrentUrl = torrentUrl,
                    infoHash = infoHash.lowercase(),
                    sizeBytes = sizeBytes,
                    seeders = seeders,
                    leechers = leechers,
                    tracker = tracker,
                    parsedInfo = parsedInfo,
                )
            )
        }

        items
    }

    private fun extractInfoHash(magnet: String): String? {
        val match = BTIH_REGEX.find(magnet) ?: return null
        return match.groupValues[1]
    }

    companion object {
        private val BTIH_REGEX = Regex("""urn:btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})""", RegexOption.IGNORE_CASE)
    }
}
