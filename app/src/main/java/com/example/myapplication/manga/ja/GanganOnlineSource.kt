package com.example.myapplication.manga.ja

import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaDetails
import com.example.myapplication.manga.domain.MangaItem
import com.example.myapplication.manga.domain.MangaPage
import com.example.myapplication.manga.domain.MangaSearchPage
import com.example.myapplication.manga.domain.MangaSourceId
import com.example.myapplication.manga.domain.VetroMangaSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.util.concurrent.atomic.AtomicReference

/**
 * ガンガンONLINE (Square Enix). Сайт на Next.js: данные страницы лежат в `__NEXT_DATA__`, картинки
 * отдаются прямыми подписанными ссылками без перестановки блоков.
 *
 * Поиск у сайта не фильтрует (запрос игнорируется и приходит весь каталог), поэтому каталог
 * загружается один раз за сессию и ищется локально.
 */
class GanganOnlineSource(private val http: JaHttp) : VetroMangaSource {

    override val id = MangaSourceId("ja-gangan")
    override val name = "ガンガンONLINE"
    override val language = "ja"

    private val catalog = AtomicReference<List<MangaItem>?>(null)

    override suspend fun search(query: String, page: Int): MangaSearchPage {
        if (page > 1 || query.isBlank()) return MangaSearchPage(emptyList())
        val all = catalog.get() ?: GanganParser.parseCatalog(http.text("$BASE/search"), id).also { catalog.set(it) }
        val needle = JaTitles.normalize(query)
        return MangaSearchPage(all.filter { JaTitles.normalize(it.title).contains(needle) })
    }

    override suspend fun details(manga: MangaItem): MangaDetails = MangaDetails(item = manga)

    override suspend fun chapters(manga: MangaItem): List<MangaChapter> {
        val json = GanganParser.nextData(http.text("$BASE/title/${manga.key}")) ?: return emptyList()
        return GanganParser.parseChapters(json).map { c ->
            MangaChapter(
                sourceId = id,
                mangaKey = manga.key,
                key = c.id,
                number = c.number,
                title = c.title,
                language = "ja",
                scanlator = name,
            )
        }
    }

    override suspend fun pages(chapter: MangaChapter): List<MangaPage> {
        val html = try {
            http.text("$BASE/title/${chapter.mangaKey}/chapter/${chapter.key}")
        } catch (e: JaHttpException) {
            if (e.code in 400..499) return emptyList() else throw e
        }
        val json = GanganParser.nextData(html) ?: return emptyList()
        val headers = mapOf("Referer" to "$BASE/")
        return GanganParser.parsePages(json).mapIndexed { i, path -> MangaPage(i, "$BASE$path", headers) }
    }

    private companion object {
        const val BASE = "https://www.ganganonline.com"
    }
}

object GanganParser {

    data class Chapter(val id: String, val title: String, val number: Double?)

    fun nextData(html: String): JsonObject? {
        val raw = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun JsonObject.pageProps(): JsonObject? =
        (this["props"] as? JsonObject)?.get("pageProps") as? JsonObject

    fun parseCatalog(html: String, source: MangaSourceId): List<MangaItem> {
        val props = nextData(html)?.pageProps() ?: return emptyList()
        val sections = ((props["data"] as? JsonObject)?.get("sections") as? JsonArray) ?: return emptyList()
        val seen = HashSet<String>()
        return sections.flatMap { section ->
            ((section as? JsonObject)?.get("titleLinks") as? JsonArray) ?: JsonArray(emptyList())
        }.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["titleId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            MangaItem(
                sourceId = source,
                key = id,
                title = name,
                coverUrl = o["imageUrl"]?.jsonPrimitive?.contentOrNull?.let { "https://www.ganganonline.com$it" },
                languages = listOf("ja"),
                author = o["author"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    /**
     * Главы тайтла. В списке бывают главы "только в приложении" (ссылка на установку, поле
     * `appLaunchUrl`) и ещё не вышедшие (`status`) - их веб не отдаёт, в выдачу они не идут.
     */
    fun parseChapters(data: JsonObject): List<Chapter> {
        val props = data.pageProps() ?: return emptyList()
        val chapters = (((props["data"] as? JsonObject)?.get("default") as? JsonObject)?.get("chapters") as? JsonArray)
            ?: return emptyList()
        return chapters.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o["appLaunchUrl"] != null || o["status"] != null) return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val main = o["mainText"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val sub = o["subText"]?.jsonPrimitive?.contentOrNull.orEmpty()
            Chapter(id = id, title = listOf(main, sub).filter { it.isNotBlank() }.joinToString(" "), number = chapterNumber(main))
        }
    }

    /** "第49鐘-1" -> 49.1; "第48鐘" -> 48; "第0鐘-2" -> 0.2. Часть после дефиса - десятичная доля. */
    fun chapterNumber(main: String): Double? {
        val m = Regex("第\\s*(\\d+)\\s*[^\\d\\s-]*\\s*(?:-\\s*(\\d+))?").find(main) ?: return JaTitles.chapterNumber(main)
        val whole = m.groupValues[1].toDoubleOrNull() ?: return null
        val part = m.groupValues[2].toIntOrNull() ?: return whole
        return whole + part / 10.0
    }

    fun parsePages(data: JsonObject): List<String> {
        val pages = (((data.pageProps()?.get("data")) as? JsonObject)?.get("pages") as? JsonArray) ?: return emptyList()
        return pages.mapNotNull { el ->
            // Рекламные вставки (`linkImage`) - не страницы манги.
            ((el as? JsonObject)?.get("image") as? JsonObject)?.get("imageUrl")?.jsonPrimitive?.contentOrNull
        }
    }
}
