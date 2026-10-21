package com.example.myapplication.media.source.vftmodule.scrapers

import com.example.myapplication.media.source.vftmodule.VftReleaseParser
import com.example.myapplication.media.source.vftmodule.VftTorrentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Парсер открытого трекера Rutor (rutor.info / rutor.is).
 * Поиск фильмов, сериалов и аниме без регистрации.
 */
class RutorScraper(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://rutor.info",
) {
    suspend fun search(query: String): List<VftTorrentItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = "$baseUrl/search/0/0/0/0/$encoded"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()

        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return@withContext emptyList()
        val html = response.use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            resp.body?.string().orEmpty()
        }

        if (html.isBlank()) return@withContext emptyList()

        parseHtml(html)
    }

    private fun parseHtml(html: String): List<VftTorrentItem> {
        val doc = Jsoup.parse(html, baseUrl)
        val rows = doc.select("tr.gai, tr.tum")
        val items = mutableListOf<VftTorrentItem>()

        for (row in rows) {
            val titleLink = row.selectFirst("a[href^=/torrent/]") ?: continue
            val title = titleLink.text().trim()
            if (title.isBlank()) continue

            val magnetLink = row.selectFirst("a[href^=magnet:]")?.attr("href") ?: continue
            val infoHash = extractInfoHash(magnetLink) ?: continue

            // Размер раздачи (обычно 4-я колонка)
            val sizeText = row.select("td.align-right, td:matches((?i)\\b(?:MB|GB|ГБ|МБ|КБ|KB)\\b)").firstOrNull()?.text().orEmpty()
            val sizeBytes = parseSizeBytes(sizeText)

            // Сиды / личи
            val seeders = row.selectFirst("span.green")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val leechers = row.selectFirst("span.red")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0

            val relativeTorrentUrl = titleLink.attr("href")
            val torrentUrl = if (relativeTorrentUrl.startsWith("/")) "$baseUrl$relativeTorrentUrl" else null

            val parsedInfo = VftReleaseParser.parse(title)

            items.add(
                VftTorrentItem(
                    title = title,
                    magnetUrl = magnetLink,
                    torrentUrl = torrentUrl,
                    infoHash = infoHash.lowercase(),
                    sizeBytes = sizeBytes,
                    seeders = seeders,
                    leechers = leechers,
                    tracker = "Rutor",
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
        val match = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*([KMGTkmgt]?[Bb]|[КМГТкмгт]?[Бб])""").find(text) ?: return 0L
        val num = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0L
        val unit = match.groupValues[2].uppercase()

        return when {
            unit.startsWith("G") || unit.startsWith("Г") -> (num * 1024 * 1024 * 1024).toLong()
            unit.startsWith("M") || unit.startsWith("М") -> (num * 1024 * 1024).toLong()
            unit.startsWith("K") || unit.startsWith("К") -> (num * 1024).toLong()
            unit.startsWith("T") || unit.startsWith("Т") -> (num * 1024L * 1024 * 1024 * 1024).toLong()
            else -> num.toLong()
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val BTIH_REGEX = Regex("""urn:btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})""", RegexOption.IGNORE_CASE)
    }
}
