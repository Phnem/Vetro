package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.audiobooks.data.remote.web.SourceHttp
import com.example.myapplication.audiobooks.data.remote.web.SiteText
import com.example.myapplication.audiobooks.data.remote.web.mapOk
import com.example.myapplication.audiobooks.domain.model.AudioTrack
import com.example.myapplication.audiobooks.domain.model.Chapter
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.BookLanguage
import com.example.myapplication.audiobooks.domain.source.FailureKind
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookDetails
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceId
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.media.source.UserAccountConfig
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Свой сервер Audiobookshelf: библиотеки типа `book`, поиск — `/api/libraries/<id>/search`, книга —
 * `/api/items/<id>?expanded=1` с настоящей разметкой глав (в том числе внутри одного M4B). Звук — по
 * ссылкам `media.tracks[].contentUrl`, которые сервер отдаёт сам, с заголовком `Authorization`.
 *
 * Вход — `POST /login`; токен держится в памяти и переполучается, когда сервер его отверг или прошло
 * [TOKEN_TTL_MS] (новые версии сервера выдают короткоживущий JWT).
 */
class AudiobookshelfSource(
    private val web: SourceHttp,
    private val account: () -> UserAccountConfig?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AudiobookSource {
    override val id = AudiobookshelfParser.SOURCE
    override val displayName = "Audiobookshelf"
    override val languages = setOf(BookLanguage.RU, BookLanguage.EN)
    override val infrastructureGroup = "audiobookshelf"

    private val lock = Mutex()
    private var session: Session? = null

    private data class Session(val scope: String, val base: String, val token: String, val libraries: List<String>, val at: Long)

    override fun supports(variant: VariantId): Boolean = variant.value.startsWith("${id.value}:")

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> {
        val q = query.trim().takeIf { it.isNotEmpty() && page == 0 } ?: return SourceResult.Ok(emptyList())
        val s = session() ?: return SourceResult.Ok(emptyList())
        val books = mutableListOf<SourceBook>()
        for (library in s.libraries) {
            val url = "${s.base}/api/libraries/$library/search?q=${enc(q)}&limit=$ROWS"
            when (val r = web.get(url, auth(s))) {
                is SourceResult.Ok -> books += AudiobookshelfParser.parseSearch(r.value, s.base, s.token)
                is SourceResult.Failed -> return r.also { invalidateOn(it) }
                is SourceResult.Restricted -> Unit
            }
        }
        return SourceResult.Ok(books)
    }

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> =
        item(ref.key).mapOk { it.details }

    override suspend fun refresh(variant: VariantId): MediaManifest {
        val key = variant.value.removePrefix("${id.value}:")
        val parsed = when (val r = item(key)) {
            is SourceResult.Ok -> r.value
            is SourceResult.Restricted -> throw IOException("audiobookshelf: $key restricted (${r.reason})")
            is SourceResult.Failed -> throw IOException("audiobookshelf: $key failed (${r.kind})", r.cause)
        }
        val s = session() ?: throw IOException("audiobookshelf: not connected")
        val tracks = parsed.tracks.mapIndexed { i, t ->
            AudioTrack(index = i, url = t.url, headers = auth(s), mimeType = t.mimeType, durationMs = t.durationMs)
        }
        if (tracks.isEmpty()) throw IOException("audiobookshelf: $key has no audio")
        val chapters = parsed.chapters.ifEmpty {
            var start = 0L
            parsed.tracks.mapIndexed { i, t ->
                Chapter(i, SiteText.chapterTitle(t.title) ?: "Глава ${i + 1}", start, t.durationMs).also { start += t.durationMs ?: 0 }
            }.takeIf { parsed.tracks.all { it.durationMs != null } }.orEmpty()
        }
        // Токен новых серверов живёт около часа: манифест устаревает раньше, и ссылки пересобираются.
        return MediaManifest(variant, tracks, chapters, resolvedAt = nowMs(), expiresAt = s.at + TOKEN_TTL_MS)
    }

    private suspend fun item(key: String): SourceResult<AudiobookshelfParser.ParsedItem> {
        val s = session() ?: return SourceResult.Failed(FailureKind.BLOCKED)
        return when (val r = web.get("${s.base}/api/items/${enc(key)}?expanded=1", auth(s))) {
            is SourceResult.Ok -> AudiobookshelfParser.parseItem(r.value, key, s.base, s.token)
                ?.let { SourceResult.Ok(it) } ?: SourceResult.Failed(FailureKind.PARSE)
            is SourceResult.Failed -> r.also { invalidateOn(it) }
            is SourceResult.Restricted -> r
        }
    }

    /** Вход и список книжных библиотек; повторно — только при смене учётки или по истечении токена. */
    private suspend fun session(): Session? = lock.withLock {
        val config = account() ?: return@withLock null.also { session = null }
        val base = config.baseUrl.trim().trimEnd('/')
        val scope = "$base|${config.username.trim()}|${config.password.hashCode()}"
        session?.takeIf { it.scope == scope && nowMs() - it.at < TOKEN_TTL_MS }?.let { return@withLock it }
        val body = buildJsonObject {
            put("username", config.username.trim())
            put("password", config.password)
        }.toString()
        val login = web.postJson("$base/login", body, mapOf("x-return-tokens" to "true"))
        val token = (login as? SourceResult.Ok)?.value?.let(AudiobookshelfParser::token) ?: return@withLock null
        val libs = (web.get("$base/api/libraries", mapOf("Authorization" to "Bearer $token")) as? SourceResult.Ok)
            ?.value?.let(AudiobookshelfParser::bookLibraries).orEmpty()
        Session(scope, base, token, libs, nowMs()).also { session = it }
    }

    private suspend fun invalidateOn(failure: SourceResult.Failed) {
        if (failure.kind == FailureKind.BLOCKED) lock.withLock { session = null }
    }

    private fun auth(s: Session) = mapOf("Authorization" to "Bearer ${s.token}")

    private companion object {
        const val ROWS = 30
        const val TOKEN_TTL_MS = 45 * 60 * 1000L

        fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
    }
}

/** Проверка подключения в настройках: вход прошёл — сервер настоящий и учётка верна. */
suspend fun audiobookshelfLogin(web: SourceHttp, config: UserAccountConfig): Boolean {
    val body = buildJsonObject {
        put("username", config.username.trim())
        put("password", config.password)
    }.toString()
    val r = web.postJson(config.baseUrl.trim().trimEnd('/') + "/login", body, mapOf("x-return-tokens" to "true"))
    return (r as? SourceResult.Ok)?.value?.let(AudiobookshelfParser::token) != null
}

internal object AudiobookshelfParser {
    val SOURCE = SourceId("audiobookshelf")

    data class Track(val title: String?, val url: String, val mimeType: String?, val durationMs: Long?)
    data class ParsedItem(val details: SourceBookDetails, val tracks: List<Track>, val chapters: List<Chapter>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Токен из ответа `/login`: `accessToken` новых версий, иначе прежний `token`. */
    fun token(body: String): String? {
        val user = obj(body)?.obj("user") ?: return null
        return user.text("accessToken") ?: user.text("token")
    }

    fun bookLibraries(body: String): List<String> =
        obj(body)?.arr("libraries").orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.text("mediaType") == "book" }
            .mapNotNull { it.text("id") }

    fun parseSearch(body: String, base: String, token: String): List<SourceBook> =
        obj(body)?.arr("book").orEmpty().mapNotNull { (it as? JsonObject)?.obj("libraryItem") }
            .mapNotNull { book(it, base, token) }

    fun parseItem(body: String, key: String, base: String, token: String): ParsedItem? {
        val item = obj(body) ?: return null
        val media = item.obj("media") ?: return null
        val book = book(item, base, token, key) ?: return null
        val tracks = media.arr("tracks").orEmpty().mapNotNull { it as? JsonObject }
            .sortedBy { it.num("index") ?: 0.0 }
            .mapNotNull { t ->
                val url = t.text("contentUrl")?.let { absolute(base, it) } ?: return@mapNotNull null
                Track(t.text("title"), url, t.text("mimeType"), t.num("duration")?.let { (it * 1000).toLong() }?.takeIf { it > 0 })
            }
            .ifEmpty {
                // Серверы, что не отдают tracks, — файлы по ino.
                media.arr("audioFiles").orEmpty().mapNotNull { it as? JsonObject }
                    .filter { it.text("exclude") != "true" }
                    .sortedBy { it.num("index") ?: 0.0 }
                    .mapNotNull { f ->
                        val ino = f.text("ino") ?: return@mapNotNull null
                        Track(
                            f.obj("metadata")?.text("filename"),
                            "$base/api/items/$key/file/$ino",
                            f.text("mimeType"),
                            f.num("duration")?.let { (it * 1000).toLong() }?.takeIf { it > 0 },
                        )
                    }
            }
        if (tracks.isEmpty()) return null
        val chapters = media.arr("chapters").orEmpty().mapNotNull { it as? JsonObject }.mapIndexedNotNull { i, c ->
            val start = c.num("start") ?: return@mapIndexedNotNull null
            val end = c.num("end")
            Chapter(i, c.text("title") ?: "Глава ${i + 1}", (start * 1000).toLong(), end?.let { ((it - start) * 1000).toLong() })
        }
        val meta = media.obj("metadata")
        return ParsedItem(
            details = SourceBookDetails(
                book = book,
                description = meta?.text("description"),
                chapterDurationsSec = if (chapters.isNotEmpty()) chapters.map { c -> c.durationMs?.div(1000) } else tracks.map { t -> t.durationMs?.div(1000) },
                series = meta?.text("seriesName"),
            ),
            tracks = tracks,
            chapters = chapters,
        )
    }

    private fun book(item: JsonObject, base: String, token: String, key: String? = null): SourceBook? {
        val id = key ?: item.text("id") ?: return null
        val media = item.obj("media") ?: return null
        val meta = media.obj("metadata") ?: return null
        return SourceBook(
            ref = SourceBookRef(SOURCE, id),
            title = meta.text("title") ?: return null,
            authors = names(meta.text("authorName")),
            narrators = names(meta.text("narratorName")),
            // Картинка грузится без заголовков — токен идёт параметром (так API разрешает для GET).
            coverUrl = "$base/api/items/$id/cover?token=" + URLEncoder.encode(token, "UTF-8"),
            durationSec = media.num("duration")?.toLong()?.takeIf { it > 0 },
            genres = meta.arr("genres").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.take(3),
            year = meta.text("publishedYear")?.take(4)?.toIntOrNull(),
        )
    }

    private fun names(s: String?): List<String> = s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun absolute(base: String, url: String): String =
        (if (url.startsWith("http://") || url.startsWith("https://")) url else base + "/" + url.trimStart('/')).replace(" ", "%20")

    private fun obj(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
    private fun JsonObject.obj(name: String) = get(name) as? JsonObject
    private fun JsonObject.arr(name: String) = get(name) as? JsonArray
    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    private fun JsonObject.num(name: String): Double? = (get(name) as? JsonPrimitive)?.content?.toDoubleOrNull()
}
