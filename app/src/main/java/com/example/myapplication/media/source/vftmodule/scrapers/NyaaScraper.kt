package com.example.myapplication.media.source.vftmodule.scrapers

import com.example.myapplication.media.source.vftmodule.VftReleaseParser
import com.example.myapplication.media.source.vftmodule.VftTorrentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder

/**
 * Парсер Nyaa.si для поиска аниме-раздач (включая сабы, дубляж и оригинальный японский/английский звук).
 */
class NyaaScraper(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://nyaa.si",
) {
    suspend fun search(query: String): List<VftTorrentItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        // c=0_0 - все категории, f=0 - без фильтра
        val rssUrl = "$baseUrl/?page=rss&q=$encoded&c=0_0&f=0"

        val request = Request.Builder()
            .url(rssUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/rss+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val response = runCatching { client.newCall(request).execute() }.getOrNull()
        val xml = response?.use { resp ->
            if (resp.isSuccessful) resp.body?.string().orEmpty() else null
        }

        if (!xml.isNullOrBlank()) {
            val items = parseRss(xml)
            if (items.isNotEmpty()) return@withContext items
        }

        // Фолбэк на HTML
        val htmlUrl = "$baseUrl/?f=0&c=0_0&q=$encoded"
        val htmlRequest = Request.Builder()
            .url(htmlUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        val htmlResponse = runCatching { client.newCall(htmlRequest).execute() }.getOrNull() ?: return@withContext emptyList()
        val html = htmlResponse.use { resp ->
            if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
        }

        if (html.isNotBlank()) parseHtml(html) else emptyList()
    }

    private fun parseRss(xml: String): List<VftTorrentItem> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val items = mutableListOf<VftTorrentItem>()

        for (item in doc.select("item")) {
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            if (title.isBlank()) continue

            val infoHash = item.selectFirst("nyaa\\:infoHash, infoHash")?.text()?.trim()
            val seeders = item.selectFirst("nyaa\\:seeders, seeders")?.text()?.trim()?.toIntOrNull() ?: 0
            val leechers = item.selectFirst("nyaa\\:leechers, leechers")?.text()?.trim()?.toIntOrNull() ?: 0
            val sizeStr = item.selectFirst("nyaa\\:size, size")?.text()?.trim().orEmpty()
            val sizeBytes = parseSizeBytes(sizeStr)

            val magnetUrl = if (!infoHash.isNullOrBlank()) {
                "magnet:?xt=urn:btih:${infoHash.lowercase()}&dn=${URLEncoder.encode(title, "UTF-8")}"
            } else {
                item.selectFirst("link")?.text()?.takeIf { it.startsWith("magnet:") } ?: continue
            }

            val torrentUrl = item.selectFirst("link")?.text()?.takeIf { it.startsWith("http") }

            val parsedInfo = VftReleaseParser.parse(title)

            items.add(
                VftTorrentItem(
                    title = title,
                    magnetUrl = magnetUrl,
                    torrentUrl = torrentUrl,
                    infoHash = (infoHash ?: extractInfoHash(magnetUrl) ?: "").lowercase(),
                    sizeBytes = sizeBytes,
                    seeders = seeders,
                    leechers = leechers,
                    tracker = "Nyaa",
                    parsedInfo = parsedInfo,
                )
            )
        }

        return items
    }

    private fun parseHtml(html: String): List<VftTorrentItem> {
        val doc = Jsoup.parse(html, baseUrl)
        val rows = doc.select("table.torrent-list tbody tr")
        val items = mutableListOf<VftTorrentItem>()

        for (row in rows) {
            val titleLink = row.selectFirst("td:nth-child(2) a:not(.comments)") ?: continue
            val title = titleLink.text().trim()
            if (title.isBlank()) continue

            val magnetLink = row.selectFirst("a[href^=magnet:]")?.attr("href") ?: continue
            val infoHash = extractInfoHash(magnetLink) ?: continue

            val sizeStr = row.selectFirst("td:nth-child(4)")?.text()?.trim().orEmpty()
            val sizeBytes = parseSizeBytes(sizeStr)

            val seeders = row.selectFirst("td:nth-child(6)")?.text()?.trim()?.toIntOrNull() ?: 0
            val leechers = row.selectFirst("td:nth-child(7)")?.text()?.trim()?.toIntOrNull() ?: 0

            val parsedInfo = VftReleaseParser.parse(title)

            items.add(
                VftTorrentItem(
                    title = title,
                    magnetUrl = magnetLink,
                    torrentUrl = null,
                    infoHash = infoHash.lowercase(),
                    sizeBytes = sizeBytes,
                    seeders = seeders,
                    leechers = leechers,
                    tracker = "Nyaa",
                    parsedInfo = parsedInfo,
                )
            )
        }

        return items
    }

    private fun extractInfoHash(magnet: String): String? {
        val match = BTIH_REGEX.find(magnet) ?: return null
        return match.groupValues[1]
    }

    private fun parseSizeBytes(text: String): Long {
        val match = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*([KMGTkmgt]?[iI]?[Bb])""").find(text) ?: return 0L
        val num = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0L
        val unit = match.groupValues[2].uppercase()

        return when {
            unit.startsWith("G") -> (num * 1024 * 1024 * 1024).toLong()
            unit.startsWith("M") -> (num * 1024 * 1024).toLong()
            unit.startsWith("K") -> (num * 1024).toLong()
            unit.startsWith("T") -> (num * 1024L * 1024 * 1024 * 1024).toLong()
            else -> num.toLong()
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val BTIH_REGEX = Regex("""urn:btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})""", RegexOption.IGNORE_CASE)
    }
}
