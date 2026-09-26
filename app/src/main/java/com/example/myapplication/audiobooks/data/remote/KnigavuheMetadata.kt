package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.network.TokenBucketRateLimiter
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Knigavuhe как источник метаданных (research/sources/knigavuhe.md): обложка, автор, чтец, цикл.
 * Звук отсюда не берём — у лицензионных книг там лишь фрагмент ЛитРеса. Нужен, чтобы у книг витрины,
 * которых нет у звуковых источников, всё равно была настоящая обложка, а не плашка с названием.
 */
class KnigavuheMetadata(
    private val http: OkHttpClient,
    private val rate: TokenBucketRateLimiter,
) {
    data class Hit(
        val title: String,
        val authors: List<String>,
        val narrators: List<String>,
        val coverUrl: String?,
        val series: String?,
    )

    suspend fun search(query: String): List<Hit> = withContext(Dispatchers.IO) {
        rate.acquire()
        val url = "$BASE/search/?q=${URLEncoder.encode(query.trim(), "UTF-8")}"
        // С мобильным UA сайт уводит на m.-версию с другой разметкой; десктопная стабильнее.
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val html = try {
            http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        } catch (_: IOException) {
            ""
        }
        parseSearch(html)
    }

    internal companion object {
        const val BASE = "https://knigavuhe.org"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"

        fun parseSearch(html: String): List<Hit> =
            Jsoup.parse(html, BASE).select("#books_list .bookkitem").mapNotNull { item ->
                val title = item.selectFirst("a.bookkitem_name")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                Hit(
                    title = title,
                    authors = item.select(".bookkitem_author a").map { it.text().trim() },
                    narrators = item.select("a[href^=/reader/]").map { it.text().trim() }.distinct(),
                    // В выдаче миниатюра 154×220 (`1-1.jpg`); полная обложка того же каталога — `1.jpg`.
                    coverUrl = item.selectFirst("img.bookkitem_cover_img")?.absUrl("src")?.takeIf { it.isNotBlank() }
                        ?.replace("/1-1.jpg", "/1.jpg"),
                    series = item.selectFirst("a[href^=/series/]")?.text()?.trim(),
                )
            }
    }
}
