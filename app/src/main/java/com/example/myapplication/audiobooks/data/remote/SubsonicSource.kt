package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.audiobooks.data.remote.web.SitePlaylist
import com.example.myapplication.audiobooks.data.remote.web.SiteTrack
import com.example.myapplication.audiobooks.data.remote.web.SourceHttp
import com.example.myapplication.audiobooks.data.remote.web.WebAudiobookSource
import com.example.myapplication.audiobooks.data.remote.web.parseOk
import com.example.myapplication.audiobooks.domain.source.BookLanguage
import com.example.myapplication.audiobooks.domain.source.FailureKind
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookDetails
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceId
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.media.source.UserAccountConfig
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Свой сервер Subsonic / OpenSubsonic (Navidrome, Airsonic, Gonic, Nextcloud Music…): книги — альбомы
 * медиатеки пользователя, главы — треки альбома. Поиск — `search3`, книга — `getAlbum`, звук —
 * `stream` (Range без перекодирования). Не подключён — источник молча ничего не находит.
 *
 * Вход — токеном `md5(пароль + соль)`: сам пароль в запросы не попадает, но токен едет в ссылках на
 * звук и обложку — так устроен протокол. Ссылки живут только на устройстве.
 */
class SubsonicSource(
    web: SourceHttp,
    private val account: () -> UserAccountConfig?,
    nowMs: () -> Long = System::currentTimeMillis,
) : WebAudiobookSource(web, nowMs) {
    override val id = SubsonicParser.SOURCE
    override val displayName = "Subsonic"
    // Своя медиатека — на любом языке; книга на чужом языке просто не совпадёт с запросом.
    override val languages = setOf(BookLanguage.RU, BookLanguage.EN)
    override val infrastructureGroup = "subsonic"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> {
        val api = api() ?: return SourceResult.Ok(emptyList())
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return SourceResult.Ok(emptyList())
        val url = api.url(
            "search3",
            "query" to q,
            "albumCount" to "$ROWS",
            "albumOffset" to "${page * ROWS}",
            "artistCount" to "0",
            "songCount" to "0",
        )
        return web.get(url).parseOk { body -> SubsonicParser.parseSearch(body, api).asResult() }
    }

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> =
        album(ref.key) { it.details }

    override suspend fun playlist(key: String): SourceResult<SitePlaylist> =
        album(key) { SitePlaylist(it.tracks) }

    private suspend fun <T : Any> album(key: String, pick: (SubsonicParser.ParsedAlbum) -> T): SourceResult<T> {
        val api = api() ?: return SourceResult.Failed(FailureKind.BLOCKED)
        return web.get(api.url("getAlbum", "id" to key)).parseOk { body ->
            SubsonicParser.parseAlbum(body, key, api)?.let { SourceResult.Ok(pick(it)) } ?: SourceResult.Failed(FailureKind.PARSE)
        }
    }

    private fun api(): SubsonicApi? = account()?.let(::SubsonicApi)

    private fun <T : Any> T?.asResult(): SourceResult<T> = this?.let { SourceResult.Ok(it) } ?: SourceResult.Failed(FailureKind.PARSE)

    private companion object {
        const val ROWS = 30
    }
}

/** Адреса запросов Subsonic с параметрами входа. Новая соль на каждый экземпляр — как велит протокол. */
class SubsonicApi(private val account: UserAccountConfig, salt: String = newSalt()) {
    private val base = account.baseUrl.trim().trimEnd('/') + "/rest/"
    private val auth = listOf(
        "u" to account.username.trim(),
        "t" to md5(account.password + salt),
        "s" to salt,
        "v" to API_VERSION,
        "c" to CLIENT,
        "f" to "json",
    )

    fun url(method: String, vararg params: Pair<String, String>): String =
        base + method + ".view?" + (auth + params).joinToString("&") { (k, v) -> k + "=" + enc(v) }

    fun stream(id: String): String = url("stream", "id" to id)

    fun cover(id: String): String = url("getCoverArt", "id" to id, "size" to "600")

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT = "Vetro"

        private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

        private fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun newSalt(): String {
            val bytes = ByteArray(8).also(SecureRandom()::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

internal object SubsonicParser {
    val SOURCE = SourceId("subsonic")

    data class ParsedAlbum(val details: SourceBookDetails, val tracks: List<SiteTrack>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Тело `subsonic-response`, если сервер ответил `ok`; ошибка входа и прочие — null. */
    fun okBody(body: String): JsonObject? {
        val root = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return null
        val response = root["subsonic-response"] as? JsonObject ?: return null
        return response.takeIf { it.text("status") == "ok" }
    }

    fun parseSearch(body: String, api: SubsonicApi): List<SourceBook>? {
        val response = okBody(body) ?: return null
        val albums = ((response["searchResult3"] as? JsonObject)?.get("album") as? JsonArray).orEmpty()
        return albums.mapNotNull { (it as? JsonObject)?.let { o -> book(o, api) } }
    }

    fun parseAlbum(body: String, key: String, api: SubsonicApi): ParsedAlbum? {
        val album = okBody(body)?.get("album") as? JsonObject ?: return null
        val songs = (album["song"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .sortedWith(compareBy({ it.int("discNumber") ?: 0 }, { it.int("track") ?: Int.MAX_VALUE }, { it.text("path") ?: it.text("title") }))
        val tracks = songs.mapNotNull { s ->
            val id = s.text("id") ?: return@mapNotNull null
            SiteTrack(title = s.text("title"), url = api.stream(id), durationSec = s.int("duration")?.toLong()?.takeIf { it > 0 })
        }
        if (tracks.isEmpty()) return null
        val book = book(album, api, key) ?: return null
        return ParsedAlbum(
            details = SourceBookDetails(
                book = book.copy(durationSec = tracks.sumOf { it.durationSec ?: 0 }.takeIf { it > 0 } ?: book.durationSec),
                description = null,
                chapterDurationsSec = tracks.map { it.durationSec },
            ),
            tracks = tracks,
        )
    }

    private fun book(o: JsonObject, api: SubsonicApi, key: String? = null): SourceBook? {
        val id = key ?: o.text("id") ?: return null
        return SourceBook(
            ref = SourceBookRef(SOURCE, id),
            title = o.text("name") ?: o.text("title") ?: return null,
            authors = listOfNotNull(o.text("artist")),
            narrators = emptyList(),
            coverUrl = o.text("coverArt")?.let(api::cover),
            durationSec = o.int("duration")?.toLong()?.takeIf { it > 0 },
            genres = listOfNotNull(o.text("genre")),
            year = o.int("year"),
        )
    }

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
}
