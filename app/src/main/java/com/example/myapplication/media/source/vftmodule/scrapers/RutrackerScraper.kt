package com.example.myapplication.media.source.vftmodule.scrapers

import com.example.myapplication.media.source.vftmodule.VftReleaseParser
import com.example.myapplication.media.source.vftmodule.VftTorrentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Парсер трекера RuTracker (rutracker.org / зеркала).
 * Ищет раздачи фильмов, сериалов и аниме по разделам видео.
 */
class RutrackerScraper(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://rutracker.org",
) {
    suspend fun search(query: String): List<VftTorrentItem> = withContext(Dispatchers.IO) {
        val searchUrl = "$baseUrl/forum/tracker.php"

        // RuTracker часто требует кодировку Windows-1251 для русских букв в форме поиска
        val encodedQueryCp1251 = runCatching {
            URLEncoder.encode(query.trim(), "windows-1251")
        }.getOrDefault(URLEncoder.encode(query.trim(), "UTF-8"))

        val formBody = FormBody.Builder()
            .add("nm", query.trim())
            // Категории: 1 (Кино, Видео и ТВ), 2 (Сериалы), 7 (Аниме)
            .add("o", "10") // сортировка по сидам
            .add("s", "2") // по убыванию
            .build()

        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Referer", searchUrl)
            .post(formBody)
            .build()

        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return@withContext emptyList()
        val html = response.use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            // Читаем тело как windows-1251 если charset не указан
            val bytes = resp.body?.bytes() ?: return@withContext emptyList()
            try {
                String(bytes, charset("windows-1251"))
            } catch (e: Exception) {
                String(bytes, Charsets.UTF_8)
            }
        }

        if (html.isBlank()) return@withContext emptyList()

        parseHtml(html)
    }

    private fun parseHtml(html: String): List<VftTorrentItem> {
        val doc = Jsoup.parse(html, baseUrl)
        val rows = doc.select("table#tor-tbl tbody tr")
        val items = mutableListOf<VftTorrentItem>()

        for (row in rows) {
            val titleLink = row.selectFirst("a.tLink, a.tt-text, a[href^=viewtopic.php]") ?: continue
            val title = titleLink.text().trim()
            if (title.isBlank()) continue

            val topicId = Regex("""t=(\d+)""").find(titleLink.attr("href"))?.groupValues?.get(1) ?: continue

            val sizeLink = row.selectFirst("a.f-dl, a[href^=dl.php]")
            val sizeStr = sizeLink?.text()?.trim() ?: row.select("td:matches((?i)\\b(?:MB|GB|ГБ|МБ|КБ|KB)\\b)").firstOrNull()?.text().orEmpty()
            val sizeBytes = parseSizeBytes(sizeStr)

            val seeders = row.selectFirst("b.seedmed, span.seedmed, td.seed")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val leechers = row.selectFirst("td.leech, td.leechmed")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0

            val dlUrl = "$baseUrl/forum/dl.php?t=$topicId"
            // Поскольку прямой magnet на rutracker генерируется через infoHash или dl.php,
            // сформируем ссылку на скачивание .torrent
            val parsedInfo = VftReleaseParser.parse(title)

            // Для magnet используем placeholder с topicId или хэшем если удастся найти
            val magnetUrl = "magnet:?xt=urn:btih:rutracker_$topicId&dn=${URLEncoder.encode(title, "UTF-8")}"

            items.add(
                VftTorrentItem(
                    title = title,
                    magnetUrl = magnetUrl,
                    torrentUrl = dlUrl,
                    infoHash = "rutracker_$topicId",
                    sizeBytes = sizeBytes,
                    seeders = seeders,
                    leechers = leechers,
                    tracker = "RuTracker",
                    parsedInfo = parsedInfo,
                )
            )
        }

        return items
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
    }
}
