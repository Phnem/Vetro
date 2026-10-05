package com.example.myapplication.manga.ja

import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaDetails
import com.example.myapplication.manga.domain.MangaItem
import com.example.myapplication.manga.domain.MangaPage
import com.example.myapplication.manga.domain.MangaSearchPage
import com.example.myapplication.manga.domain.MangaSourceId
import com.example.myapplication.manga.domain.PageDecode
import com.example.myapplication.manga.domain.VetroMangaSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.Instant

/**
 * КАДОКОМИ (бывший ComicWalker, comic-walker.com) - JSON-API издательства Kadokawa. Через него же
 * идут бесплатные главы Web Ace / Comp Ace / Young Ace UP: на самом web-ace.jp остались только
 * карточки томов, читалка у них общая. Поэтому "Kadocomi" и "Web Ace" - один источник.
 *
 * Картинки бесплатных эпизодов приходят со сложенными по XOR байтами (ключ в ответе просмотрщика);
 * расшифровка - ровно то, что делает сам сайт перед показом ([PageDecode.Xor]).
 */
class ComicWalkerSource(private val http: JaHttp) : VetroMangaSource {

    override val id = MangaSourceId("ja-comicwalker")
    override val name = "カドコミ / Web Ace"
    override val language = "ja"

    override suspend fun search(query: String, page: Int): MangaSearchPage {
        if (page > 1 || query.isBlank()) return MangaSearchPage(emptyList())
        val json = http.json("$BASE/api/search/keywords?keyword=${URLEncoder.encode(query, "UTF-8")}&limit=$SEARCH_LIMIT", HEADERS)
        return MangaSearchPage(ComicWalkerParser.parseSearch(json, id))
    }

    override suspend fun details(manga: MangaItem): MangaDetails = MangaDetails(item = manga)

    override suspend fun chapters(manga: MangaItem): List<MangaChapter> {
        val json = http.json("$BASE/api/contents/details/work?workCode=${manga.key}", HEADERS)
        return ComicWalkerParser.parseEpisodes(json).map { e ->
            MangaChapter(
                sourceId = id,
                mangaKey = manga.key,
                key = e.id,
                number = e.number,
                title = e.title,
                language = "ja",
                scanlator = name,
                pageCount = e.pageCount,
                publishedAt = e.publishedAtMillis,
            )
        }
    }

    override suspend fun pages(chapter: MangaChapter): List<MangaPage> {
        val url = "$BASE/api/contents/viewer?episodeId=${chapter.key}&imageSizeType=${URLEncoder.encode("width:1284", "UTF-8")}"
        val json = try {
            http.json(url, HEADERS)
        } catch (e: JaHttpException) {
            // 4xx - эпизод закрыт (платный или срок бесплатного чтения вышел): страниц нет.
            if (e.code in 400..499) return emptyList() else throw e
        }
        return ComicWalkerParser.parseViewer(json).mapIndexed { i, m ->
            MangaPage(
                index = i,
                url = m.url,
                headers = mapOf("Referer" to "$BASE/"),
                decode = m.xorKey?.let { PageDecode.Xor(it) },
            )
        }
    }

    private companion object {
        const val BASE = "https://comic-walker.com"
        const val SEARCH_LIMIT = 20
        val HEADERS = mapOf("Referer" to "$BASE/")
    }
}

/** Разбор JSON ответов Кадокоми. Отдельно от сети - для проверки на сохранённых образцах. */
object ComicWalkerParser {

    data class Episode(
        val id: String,
        val title: String,
        val number: Double?,
        val pageCount: Int,
        val publishedAtMillis: Long,
    )

    data class Manuscript(val url: String, val xorKey: String?)

    fun parseSearch(json: JsonElement, source: MangaSourceId): List<MangaItem> {
        val results = json.jsonObject["result"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val code = o["code"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val authors = (o["authors"] as? JsonArray).orEmptyArray()
                .mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
                .joinToString(" ")
                .ifBlank { null }
            MangaItem(
                sourceId = source,
                key = code,
                title = title,
                coverUrl = o["thumbnail"]?.jsonPrimitive?.contentOrNull,
                languages = listOf("ja"),
                author = authors,
            )
        }
    }

    /** Читаемые эпизоды тайтла: первые и последние из карточки, без дублей, только активные. */
    fun parseEpisodes(work: JsonElement): List<Episode> {
        val root = work.jsonObject
        val raw = listOf("firstEpisodes", "latestEpisodes").flatMap { key ->
            ((root[key] as? JsonObject)?.get("result") as? JsonArray).orEmptyArray()
        }
        val seen = HashSet<String>()
        return raw.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o["isActive"]?.jsonPrimitive?.booleanOrNull == false) return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val title = listOfNotNull(
                o["title"]?.jsonPrimitive?.contentOrNull,
                o["subTitle"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            ).joinToString(" ")
            val internal = o["internal"] as? JsonObject
            val kind = o["type"]?.jsonPrimitive?.contentOrNull ?: internal?.get("episodetype")?.jsonPrimitive?.contentOrNull
            val number = JaTitles.chapterNumber(title)
                ?: if (kind == "normal") internal?.get("episodeNo")?.jsonPrimitive?.doubleOrNull else null
            Episode(
                id = id,
                title = title,
                number = number,
                pageCount = internal?.get("pageCount")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                publishedAtMillis = runCatching {
                    Instant.parse(o["updateDate"]?.jsonPrimitive?.contentOrNull.orEmpty()).toEpochMilli()
                }.getOrDefault(0L),
            )
        }
    }

    /**
     * Страницы просмотрщика. Режим шифрования кроме "xor" и отсутствия шифра не знаем: такие
     * страницы пропускаем целиком, а не показываем мусор.
     */
    fun parseViewer(viewer: JsonElement): List<Manuscript> {
        val manuscripts = viewer.jsonObject["manuscripts"] as? JsonArray ?: return emptyList()
        val parsed = manuscripts.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = o["drmImageUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            when (o["drmMode"]?.jsonPrimitive?.contentOrNull) {
                "xor" -> o["drmHash"]?.jsonPrimitive?.contentOrNull?.let { Manuscript(url, it) }
                null, "", "none" -> Manuscript(url, null)
                else -> null
            }
        }
        return if (parsed.size == manuscripts.size) parsed else emptyList()
    }

    private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())
}
