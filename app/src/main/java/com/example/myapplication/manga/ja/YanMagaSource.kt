package com.example.myapplication.manga.ja

import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaDetails
import com.example.myapplication.manga.domain.MangaItem
import com.example.myapplication.manga.domain.MangaPage
import com.example.myapplication.manga.domain.MangaSearchPage
import com.example.myapplication.manga.domain.MangaSourceId
import com.example.myapplication.manga.domain.PageDecode
import com.example.myapplication.manga.domain.VetroMangaSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * ヤンマガWeb (Kodansha, Young Magazine). Главы читаются в SpeedBinb: страницу нужно запросить у
 * контент-сервера по подписанной cookie и собрать из кусков ([SpeedBinb.layout]).
 *
 * Берутся только главы, которые сайт открывает любому посетителю без входа: пометка «無料» и
 * отсутствие окна регистрации. Платные и «бесплатные после регистрации» главы в выдачу не попадают.
 */
class YanMagaSource(
    private val http: JaHttp,
    private val random: Random = Random.Default,
) : VetroMangaSource {

    override val id = MangaSourceId("ja-yanmaga")
    override val name = "ヤンマガWeb"
    override val language = "ja"

    override suspend fun search(query: String, page: Int): MangaSearchPage {
        if (page > 1 || query.isBlank()) return MangaSearchPage(emptyList())
        val html = try {
            http.text("$BASE/search?q=${URLEncoder.encode(query, "UTF-8")}")
        } catch (e: JaHttpException) {
            if (e.code == 404) return MangaSearchPage(emptyList()) else throw e
        }
        return MangaSearchPage(YanMagaParser.parseSearch(html).map { it.toItem(id) })
    }

    override suspend fun details(manga: MangaItem): MangaDetails = MangaDetails(item = manga)

    override suspend fun chapters(manga: MangaItem): List<MangaChapter> {
        val series = http.text("$BASE/comics/${manga.key}")
        val episodes = LinkedHashMap<String, YanMagaParser.Episode>()
        YanMagaParser.parseEpisodes(series).forEach { episodes.putIfAbsent(it.id, it) }
        // На странице серии - первые и последние главы; остальное подгружается отдельным запросом.
        YanMagaParser.parseMore(series)?.let { more ->
            val script = http.text(
                "$BASE${more.path}?offset=${more.offset}&limit=${more.limit}&sort=${more.sort}",
                headers = mapOf("Accept" to "text/javascript", "Referer" to "$BASE/comics/${manga.key}"),
            )
            YanMagaParser.parseEpisodesScript(script).forEach { episodes.putIfAbsent(it.id, it) }
        }
        return episodes.values.map { episode ->
            MangaChapter(
                sourceId = id,
                mangaKey = manga.key,
                key = episode.id,
                number = JaTitles.chapterNumber(episode.title),
                title = episode.title,
                language = "ja",
                scanlator = name,
                publishedAt = episode.publishedAtMillis,
                paid = !episode.openToAnyone,
            )
        }
    }

    override suspend fun pages(chapter: MangaChapter): List<MangaPage> {
        // Ссылка на главу ведёт редиректом на читалку; в её адресе - идентификатор содержимого (cid).
        val viewer = http.exchange("$BASE/comics/${chapter.mangaKey}/${chapter.key}")
        val cid = YanMagaParser.cidOf(viewer.finalUrl) ?: return emptyList()
        val infoPath = YanMagaParser.infoPath(viewer.body)
            ?: "/viewer/bibGetCntntInfo?random_identification=${chapter.key}&type=comics"
        val key = SpeedBinb.makeKey(cid, random)
        val separator = if ('?' in infoPath) '&' else '?'
        val info = http.exchange(
            "$BASE$infoPath${separator}cid=${enc(cid)}&k=${enc(key)}&dmytime=${System.currentTimeMillis()}",
            headers = mapOf("Referer" to viewer.finalUrl),
        )
        val content = YanMagaParser.parseInfo(info.body) ?: return emptyList()
        val ctbl = SpeedBinb.decryptStrings(cid, key, content.ctbl) ?: return emptyList()
        val ptbl = SpeedBinb.decryptStrings(cid, key, content.ptbl) ?: return emptyList()
        // Доступ к картинкам даёт подписанная cookie из ответа на описание главы.
        val cookie = info.cookies.filter { it.startsWith("CloudFront-") }.joinToString("; ")
        if (cookie.isEmpty()) return emptyList()
        val base = content.server.trimEnd('/') + "/"
        val headers = mapOf("Cookie" to cookie)
        val listing = http.text("${base}content", headers)
        val sources = YanMagaParser.parseContent(listing)
        val decode = PageDecode.SpeedBinb(ctbl, ptbl)
        return sources.mapIndexed { index, src ->
            MangaPage(index = index, url = "${base}img/$src?q=1", headers = headers, decode = decode)
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val BASE = "https://yanmaga.jp"
    }
}

/** Разбор разметки ヤンマガWeb и ответов читалки. Отдельно от сети, чтобы проверять на образцах. */
object YanMagaParser {

    data class SearchHit(val key: String, val title: String, val author: String?, val coverUrl: String?) {
        fun toItem(source: MangaSourceId) = MangaItem(
            sourceId = source,
            key = key,
            title = title,
            coverUrl = coverUrl,
            languages = listOf("ja"),
            author = author,
        )
    }

    /** [openToAnyone]: помечена «無料» и не требует регистрации. */
    data class Episode(val id: String, val title: String, val publishedAtMillis: Long, val openToAnyone: Boolean)

    /** Параметры кнопки «もっと見る»: откуда и сколько глав подгрузить. */
    data class More(val path: String, val offset: Int, val limit: Int, val sort: String)

    /** Описание главы от читалки: сервер содержимого и зашифрованные таблицы раскладки. */
    data class Info(val server: String, val ctbl: String, val ptbl: String)

    private val SERIES_LINK = Regex("^/comics/([^/?#]+)$")
    private val NOT_SERIES = setOf("authors", "series", "new", "ranking", "search")
    private val CID = Regex("[?&]cid=([^&#]+)")
    private val DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd")
    private val TOKYO = ZoneId.of("Asia/Tokyo")
    private val IMG = Regex("""<t-img\s[^>]*?src="([^"]+)"""")

    fun parseSearch(html: String): List<SearchHit> {
        val hits = LinkedHashMap<String, SearchHit>()
        for (link in Jsoup.parse(html, "https://yanmaga.jp").select("a[href^=/comics/]")) {
            val key = SERIES_LINK.matchEntire(link.attr("href"))?.groupValues?.get(1) ?: continue
            if (key in NOT_SERIES) continue
            val lines = link.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
            val title = lines.firstOrNull() ?: continue
            val author = lines.getOrNull(1)
            val cover = link.selectFirst("img[src]")?.attr("abs:src")?.takeIf { it.startsWith("http") }
            hits.putIfAbsent(key, SearchHit(key, title, author, cover))
        }
        return hits.values.toList()
    }

    fun parseEpisodes(html: String): List<Episode> =
        Jsoup.parse(html, "https://yanmaga.jp").select("li.mod-episode-item").mapNotNull { item ->
            val href = item.selectFirst("a.mod-episode-link[href]")?.attr("href") ?: return@mapNotNull null
            val id = href.substringBefore('?').substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = item.selectFirst("p.mod-episode-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.attr("data-episode-title").trim()
            val free = item.selectFirst(".mod-episode-point--free") != null
            // Окно регистрации открывается у глав, которые без входа не читаются.
            val gated = item.hasClass("js-modal")
            Episode(id, title, parseDate(item.selectFirst("time.mod-episode-date")?.text()), free && !gated)
        }

    fun parseMore(html: String): More? {
        val button = Jsoup.parse(html).selectFirst("[data-path][data-offset][data-limit]") ?: return null
        val offset = button.attr("data-offset").toIntOrNull() ?: return null
        val limit = button.attr("data-limit").toIntOrNull() ?: return null
        val path = button.attr("data-path").takeIf { it.startsWith("/") } ?: return null
        return More(path, offset, limit, button.attr("data-sort").ifEmpty { "older" })
    }

    /** Ответ подгрузки - скрипт `insertAdjacentHTML('beforeend', "<li ...>")`: достаём HTML из строкового литерала. */
    fun parseEpisodesScript(script: String): List<Episode> {
        val result = ArrayList<Episode>()
        var from = 0
        while (true) {
            val call = script.indexOf("insertAdjacentHTML", from)
            if (call < 0) break
            val open = script.indexOf('"', script.indexOf(',', call))
            if (open < 0) break
            var i = open + 1
            while (i < script.length && script[i] != '"') i += if (script[i] == '\\') 2 else 1
            if (i >= script.length) break
            val literal = script.substring(open, i + 1)
            val html = runCatching { Json.parseToJsonElement(literal).jsonPrimitive.content }.getOrNull()
            if (html != null) result += parseEpisodes(html)
            from = i + 1
        }
        return result
    }

    /** cid читалки лежит в адресе, на который ведёт ссылка на главу: `.../viewer/comics/...?cid=...`. */
    fun cidOf(url: String): String? {
        if ("/viewer/" !in url) return null
        return CID.find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotEmpty() }
    }

    /** Адрес запроса описания главы из страницы читалки. */
    fun infoPath(viewerHtml: String): String? =
        Jsoup.parse(viewerHtml).selectFirst("[data-ptbinb]")?.attr("data-ptbinb")?.takeIf { it.startsWith("/") }

    fun parseInfo(json: String): Info? {
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        if (root["result"]?.jsonPrimitive?.intOrNull != 1) return null
        val item = ((root["items"] as? JsonArray)?.firstOrNull() as? JsonObject) ?: return null
        fun text(name: String) = (item[name] as? JsonPrimitive)?.contentOrNull
        return Info(
            server = text("ContentsServer") ?: return null,
            ctbl = text("ctbl") ?: return null,
            ptbl = text("ptbl") ?: return null,
        )
    }

    /** Имена файлов страниц в порядке чтения из `ttx` ответа `content`. */
    fun parseContent(json: String): List<String> {
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
        if (root["result"]?.jsonPrimitive?.intOrNull != 1) return emptyList()
        val ttx = (root["ttx"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        return IMG.findAll(ttx).map { it.groupValues[1] }.toList()
    }

    private fun parseDate(text: String?): Long = runCatching {
        LocalDate.parse(text?.trim().orEmpty(), DATE).atStartOfDay(TOKYO).toInstant().toEpochMilli()
    }.getOrDefault(0L)
}
