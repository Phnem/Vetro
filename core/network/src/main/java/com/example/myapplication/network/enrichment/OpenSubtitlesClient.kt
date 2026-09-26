package com.example.myapplication.network.enrichment

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.TokenBucketRateLimiter
import com.phnem.vetro.network.BuildConfig
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenSubtitles REST v1. Поиск — с ключом приложения (`Api-Key`); скачивание — ещё и с токеном
 * входа пользователя (`Authorization`): квота скачиваний принадлежит его аккаунту. Ссылка на файл
 * живёт 3 часа — кэшируется сам файл по `file_id`, а не ссылка.
 */
class OpenSubtitlesClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
    private val apiKey: () -> String = { BuildConfig.OPENSUBTITLES_API_KEY },
) {
    // User-Agent с именем приложения, которого требует OpenSubtitles, ставит общий клиент (VetroApp/1.0).
    val isConfigured: Boolean get() = apiKey().isNotBlank()

    /**
     * Фильм — по [imdbId]/[tmdbId] самого фильма; серия — по id сериала + сезон/номер (OpenSubtitles
     * называет это parent). Языки — коды ISO 639-1 через запятую, в алфавитном порядке (так требует API).
     */
    suspend fun search(query: SubtitleQuery): LookupResult<List<SubtitleCandidate>> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        val params = buildList {
            val imdb = query.imdbId?.removePrefix("tt")?.trimStart('0')
            if (query.season != null && query.episode != null) {
                imdb?.let { add("parent_imdb_id=$it") } ?: query.tmdbId?.let { add("parent_tmdb_id=$it") }
                add("season_number=${query.season}")
                add("episode_number=${query.episode}")
            } else {
                imdb?.let { add("imdb_id=$it") } ?: query.tmdbId?.let { add("tmdb_id=$it") }
            }
            if (none { it.contains("imdb_id") || it.contains("tmdb_id") }) return LookupResult.NoMatch
            if (query.languages.isNotEmpty()) add("languages=${query.languages.map(String::lowercase).distinct().sorted().joinToString(",")}")
            add("order_by=download_count")
        }.sorted()
        val path = "subtitles?${params.joinToString("&")}"
        return http.text("OpenSubtitles", "$BASE/$path", rate, policy = CachePolicy("opensubtitles:$path", CacheTtl.DAY)) {
            header("Api-Key", key)
        }.parse { OpenSubtitlesParser.candidates(it).takeIf(List<SubtitleCandidate>::isNotEmpty) }
    }

    /** Вход пользователя: токен для скачивания и, у VIP, свой адрес API. */
    suspend fun login(username: String, password: String): LookupResult<OpenSubtitlesSession> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        return http.text("OpenSubtitles", "$BASE/login", rate, HttpMethod.Post) {
            header("Api-Key", key)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("username", username); put("password", password) }.toString())
        }.parse(OpenSubtitlesParser::session)
    }

    /** Временная ссылка на файл (UTF-8). Расход квоты считается здесь, а не при скачивании файла. */
    suspend fun downloadLink(fileId: Long, session: OpenSubtitlesSession): LookupResult<SubtitleDownload> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        return http.text("OpenSubtitles", "${session.baseUrl ?: BASE}/download", rate, HttpMethod.Post) {
            header("Api-Key", key)
            header("Authorization", "Bearer ${session.token}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("file_id", fileId); put("sub_format", "srt") }.toString())
        }.parse(OpenSubtitlesParser::download)
    }

    private companion object {
        const val BASE = "https://api.opensubtitles.com/api/v1"
    }
}

data class SubtitleQuery(
    val imdbId: String?,
    val tmdbId: Int?,
    val season: Int? = null,
    val episode: Int? = null,
    val languages: List<String>,
)

data class SubtitleCandidate(
    val fileId: Long,
    val language: String,
    val release: String?,
    val fps: Double?,
    val downloadCount: Int,
    val rating: Double?,
    val hearingImpaired: Boolean,
    val machineTranslated: Boolean,
    val aiTranslated: Boolean,
    val fromTrusted: Boolean,
)

data class OpenSubtitlesSession(val token: String, val baseUrl: String?, val allowedDownloads: Int?)

data class SubtitleDownload(val url: String, val fileName: String?, val remaining: Int?)

internal object OpenSubtitlesParser {
    @Serializable
    private data class SearchDto(val data: List<ItemDto> = emptyList())

    @Serializable
    private data class ItemDto(val attributes: AttributesDto? = null)

    @Serializable
    private data class AttributesDto(
        val language: String? = null,
        val release: String? = null,
        val fps: Double? = null,
        val download_count: Int = 0,
        val ratings: Double? = null,
        val hearing_impaired: Boolean = false,
        val machine_translated: Boolean = false,
        val ai_translated: Boolean = false,
        val from_trusted: Boolean = false,
        val files: List<FileDto> = emptyList(),
    )

    @Serializable
    private data class FileDto(val file_id: Long)

    @Serializable
    private data class LoginDto(val token: String, val base_url: String? = null, val user: UserDto? = null)

    @Serializable
    private data class UserDto(val allowed_downloads: Int? = null)

    @Serializable
    private data class DownloadDto(val link: String, val file_name: String? = null, val remaining: Int? = null)

    fun candidates(body: String): List<SubtitleCandidate> =
        EnrichmentJson.decodeFromString(SearchDto.serializer(), body).data.mapNotNull { item ->
            val a = item.attributes ?: return@mapNotNull null
            val file = a.files.firstOrNull() ?: return@mapNotNull null
            SubtitleCandidate(
                fileId = file.file_id,
                language = a.language?.lowercase() ?: return@mapNotNull null,
                release = a.release,
                fps = a.fps?.takeIf { it > 0 },
                downloadCount = a.download_count,
                rating = a.ratings?.takeIf { it > 0 },
                hearingImpaired = a.hearing_impaired,
                machineTranslated = a.machine_translated,
                aiTranslated = a.ai_translated,
                fromTrusted = a.from_trusted,
            )
        }

    fun session(body: String): OpenSubtitlesSession {
        val d = EnrichmentJson.decodeFromString(LoginDto.serializer(), body)
        // У VIP-аккаунтов свой хост: запросы скачивания идут на него.
        val base = d.base_url?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "https://$it" }
            ?.trimEnd('/')?.let { if (it.endsWith("/api/v1")) it else "$it/api/v1" }
        return OpenSubtitlesSession(d.token, base, d.user?.allowed_downloads)
    }

    fun download(body: String): SubtitleDownload {
        val d = EnrichmentJson.decodeFromString(DownloadDto.serializer(), body)
        return SubtitleDownload(d.link, d.file_name, d.remaining)
    }
}
