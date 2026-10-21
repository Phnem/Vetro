package com.example.myapplication.media.source.vftmodule.scrapers

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
 * Парсер The Pirate Bay через открытый API apibay.org.
 * Категория 200 = Video (фильмы, сериалы в HD/UHD, оригинальные англоязычные дорожки).
 */
class PirateBayScraper(
    private val client: OkHttpClient,
    private val apiBaseUrl: String = "https://apibay.org",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String): List<VftTorrentItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        // cat=200 - раздел Video
        val url = "$apiBaseUrl/q.php?q=$encoded&cat=200"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return@withContext emptyList()
        val body = response.use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            resp.body?.string().orEmpty()
        }

        if (body.isBlank() || body.contains("No results returned")) return@withContext emptyList()

        val parsedJson = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return@withContext emptyList()
        val items = mutableListOf<VftTorrentItem>()

        for (el in parsedJson) {
            val obj = el.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val infoHash = obj["info_hash"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (name.isBlank() || infoHash.isBlank() || infoHash == "0000000000000000000000000000000000000000") continue

            val sizeBytes = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val seeders = obj["seeders"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val leechers = obj["leechers"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

            val encodedName = URLEncoder.encode(name, "UTF-8")
            val magnetUrl = "magnet:?xt=urn:btih:${infoHash.lowercase()}&dn=$encodedName$TRACKERS"

            val parsedInfo = VftReleaseParser.parse(name)

            items.add(
                VftTorrentItem(
                    title = name,
                    magnetUrl = magnetUrl,
                    torrentUrl = null,
                    infoHash = infoHash.lowercase(),
                    sizeBytes = sizeBytes,
                    seeders = seeders,
                    leechers = leechers,
                    tracker = "ThePirateBay",
                    parsedInfo = parsedInfo,
                )
            )
        }

        items
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val TRACKERS = "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce&tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce&tr=udp%3A%2F%2Ftracker.bittor.pw%3A1337%2Fannounce"
    }
}
