package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.audiobooks.data.remote.web.SiteText
import com.example.myapplication.audiobooks.data.remote.web.SitePlaylist
import com.example.myapplication.audiobooks.data.remote.web.SiteTrack
import com.example.myapplication.audiobooks.data.remote.web.SourceHttp
import com.example.myapplication.audiobooks.data.remote.web.WebAudiobookSource
import com.example.myapplication.audiobooks.data.remote.web.mapOk
import com.example.myapplication.audiobooks.data.remote.web.parseOk
import com.example.myapplication.audiobooks.domain.source.Availability
import com.example.myapplication.audiobooks.domain.source.BookLanguage
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookDetails
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceId
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.audiobooks.domain.source.SourceShelf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jsoup.Jsoup

/**
 * Yakniga: открытый GraphQL сайта (тот же, что у его веб-плеера). Поиск, детали и главы с длинами —
 * JSON, звук — прямые MP3 `/files/…/chapter_N.mp3` с Range, без подписи: манифест не протухает.
 */
class YaknigaSource(web: SourceHttp, nowMs: () -> Long = System::currentTimeMillis) : WebAudiobookSource(web, nowMs) {
    override val id = YaknigaParser.SOURCE
    override val displayName = "Yakniga"
    override val languages = setOf(BookLanguage.RU)
    override val infrastructureGroup = "yakniga.org"
    override val shelves = listOf(SourceShelf("top", "Популярное на Yakniga", "Что слушают чаще всего"))

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> {
        if (page > 0) return SourceResult.Ok(emptyList())
        return graphql(YaknigaParser.SEARCH, buildJsonObject { put("term", query.trim()) })
            .mapOk(YaknigaParser::parseSearch)
    }

    override suspend fun shelf(id: String, page: Int): SourceResult<List<SourceBook>> = when {
        id == "top" -> graphql(
            YaknigaParser.COLLECTION,
            buildJsonObject {
                put("query", buildJsonObject { put("by_search_top", "desc") })
                put("page", page + 1)
                put("perPage", 24)
            },
        ).mapOk(YaknigaParser::parseCollection)
        else -> super.shelf(id, page)
    }

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> =
        book(ref.key).mapOk { it.details }

    override suspend fun playlist(key: String): SourceResult<SitePlaylist> =
        book(key).mapOk { SitePlaylist(it.tracks) }

    private suspend fun book(key: String): SourceResult<YaknigaParser.ParsedBook> =
        graphql(YaknigaParser.BOOK, buildJsonObject { put("id", key) }).parseOk { json ->
            YaknigaParser.parseBook(json, key)?.let { SourceResult.Ok(it) }
                ?: SourceResult.Restricted(Availability.RIGHTS_HOLDER)
        }

    private suspend fun graphql(query: String, variables: JsonObject): SourceResult<String> {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        return web.postJson("${YaknigaParser.BASE}/graphql", body.toString())
    }
}

internal object YaknigaParser {
    val SOURCE = SourceId("yakniga")
    const val BASE = "https://yakniga.org"

    const val SEARCH = "query(\$term: String!){ search(term: \$term) { __typename ... on Book { " +
        "id title authorName cover duration copyrightBlock readers { name } } } }"
    const val COLLECTION = "query(\$query: JSON, \$perPage: Int, \$page: Int){ books(query: \$query, perPage: \$perPage, " +
        "page: \$page) { collection { id title authorName cover duration copyrightBlock readers { name } } } }"
    const val BOOK = "query(\$id: ID!){ book(id: \$id) { id title authorName cover duration description copyrightBlock " +
        "likesCount dislikesCount readers { name } authors { name } genres { collection { name } } series { name } " +
        "chapters { collection { name duration fileUrl } } } }"

    data class ParsedBook(val details: SourceBookDetails, val tracks: List<SiteTrack>)

    private val json = Json { ignoreUnknownKeys = true }

    fun parseSearch(body: String): List<SourceBook> =
        data(body)?.get("search").array().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            if (o.str("__typename") != "Book") null else card(o)
        }

    fun parseCollection(body: String): List<SourceBook> =
        data(body)?.obj("books")?.get("collection").array().mapNotNull { (it as? JsonObject)?.let(::card) }

    /** Книга без звука (duration 0 — у сайта только фрагмент) или заблокированная — не книга для плеера. */
    fun parseBook(body: String, key: String): ParsedBook? {
        val o = data(body)?.obj("book") ?: return null
        val card = card(o) ?: return null
        val tracks = o.obj("chapters")?.get("collection").array().mapNotNull { c ->
            val ch = c as? JsonObject ?: return@mapNotNull null
            val path = ch.str("fileUrl") ?: return@mapNotNull null
            SiteTrack(ch.str("name"), absolute(path), ch.long("duration"))
        }
        if (tracks.isEmpty()) return null
        val likes = o.long("likesCount") ?: 0
        val dislikes = o.long("dislikesCount") ?: 0
        val book = card.copy(
            ref = SourceBookRef(SOURCE, key),
            authors = o.get("authors").array().mapNotNull { (it as? JsonObject)?.str("name") }.ifEmpty { card.authors },
            genres = o.obj("genres")?.get("collection").array().mapNotNull { (it as? JsonObject)?.str("name") },
        )
        return ParsedBook(
            details = SourceBookDetails(
                book = book,
                description = o.str("description")?.let { Jsoup.parse(it).text() }?.takeIf { it.isNotBlank() },
                chapterDurationsSec = tracks.map { it.durationSec },
                // Лайки/дизлайки → оценка 0–5, как у остальных источников; без голосов — нет оценки.
                rating = if (likes + dislikes > 0) 5.0 * likes / (likes + dislikes) else null,
                series = o.obj("series")?.str("name"),
                authorShelfId = book.authors.firstOrNull()?.let { "author:$it" },
            ),
            tracks = tracks,
        )
    }

    private fun card(o: JsonObject): SourceBook? {
        val id = o.str("id") ?: return null
        val duration = o.long("duration") ?: 0
        if (duration <= 0 || o.bool("copyrightBlock") == true) return null
        return SourceBook(
            ref = SourceBookRef(SOURCE, id),
            title = SiteText.stripNarration(o.str("title")?.trim() ?: return null),
            authors = listOfNotNull(o.str("authorName")?.trim()?.takeIf { it.isNotEmpty() }),
            narrators = o.get("readers").array().mapNotNull { (it as? JsonObject)?.str("name")?.trim() }.distinct(),
            coverUrl = o.str("cover")?.let(::absolute),
            durationSec = duration,
        )
    }

    private fun absolute(path: String) = if (path.startsWith("http")) path else BASE + path

    private fun data(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject["data"] as? JsonObject }.getOrNull()

    private fun JsonElement?.array(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
    private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject
    private fun JsonObject.str(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.long(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull
}
