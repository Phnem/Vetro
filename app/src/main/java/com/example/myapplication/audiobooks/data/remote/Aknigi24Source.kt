package com.example.myapplication.audiobooks.data.remote

import com.example.myapplication.audiobooks.domain.model.AudioTrack
import com.example.myapplication.audiobooks.domain.model.Chapter
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.Availability
import com.example.myapplication.audiobooks.domain.source.BookLanguage
import com.example.myapplication.audiobooks.domain.source.FailureKind
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookDetails
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.network.TokenBucketRateLimiter
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * RU №1 (tecplan §6, решение Q9). Поиск и детали — HTML, главы — JSON плеера сайта, аудио — прямые
 * MP3 `/book/<id>/chapter/<n>` с Range, без подписи и cookie: манифест не протухает.
 */
class Aknigi24Source(
    private val http: OkHttpClient,
    private val rate: TokenBucketRateLimiter,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AudiobookSource {
    override val id = Aknigi24Parser.SOURCE
    override val displayName = "Aknigi24"
    override val languages = setOf(BookLanguage.RU)
    override val infrastructureGroup = "aknigi24.com"

    override suspend fun search(query: String, page: Int): SourceResult<List<SourceBook>> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val pageParam = if (page > 0) "&page=${page + 1}" else ""
        return fetch("${Aknigi24Parser.BASE}/search?q=$q$pageParam").map(Aknigi24Parser::parseList)
    }

    override suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails> =
        book(ref.key).map { it.details }

    override fun supports(variant: VariantId): Boolean = variant.value.startsWith("${id.value}:")

    override suspend fun refresh(variant: VariantId): MediaManifest {
        val key = variant.value.removePrefix("${id.value}:")
        val parsed = when (val r = book(key)) {
            is SourceResult.Ok -> r.value
            is SourceResult.Restricted -> throw IOException("Aknigi24: $key restricted (${r.reason})")
            is SourceResult.Failed -> throw IOException("Aknigi24: $key failed (${r.kind})", r.cause)
        }
        var start = 0L
        val durations = parsed.details.chapterDurationsSec
        return MediaManifest(
            variant = variant,
            tracks = parsed.chapterUrls.mapIndexed { i, url ->
                AudioTrack(index = i, url = url, mimeType = "audio/mpeg", durationMs = durations[i]?.times(1000))
            },
            chapters = parsed.chapterUrls.indices.map { i ->
                val durationMs = durations[i]?.times(1000)
                Chapter(index = i, title = "Глава ${i + 1}", startMs = start, durationMs = durationMs)
                    .also { start += durationMs ?: 0L }
            },
            resolvedAt = nowMs(),
            expiresAt = null,
        )
    }

    private suspend fun book(key: String): SourceResult<Aknigi24Parser.ParsedBook> =
        when (val r = fetch("${Aknigi24Parser.BASE}/book/$key")) {
            is SourceResult.Ok -> Aknigi24Parser.parseBook(r.value, key)?.let { SourceResult.Ok(it) }
                ?: if (RESTRICTED_MARKERS.any { r.value.contains(it, ignoreCase = true) }) {
                    SourceResult.Restricted(Availability.RIGHTS_HOLDER)
                } else {
                    SourceResult.Failed(FailureKind.PARSE)
                }
            is SourceResult.Restricted -> r
            is SourceResult.Failed -> r
        }

    private suspend fun fetch(url: String): SourceResult<String> = withContext(Dispatchers.IO) {
        rate.acquire()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> SourceResult.Ok(response.body?.string().orEmpty())
                    response.code == 404 || response.code == 410 -> SourceResult.Restricted(Availability.REMOVED)
                    response.code == 429 -> SourceResult.Failed(FailureKind.RATE_LIMITED)
                    response.code == 403 || response.code == 503 -> SourceResult.Failed(FailureKind.BLOCKED)
                    else -> SourceResult.Failed(FailureKind.NETWORK, IOException("HTTP ${response.code}"))
                }
            }
        } catch (e: SocketTimeoutException) {
            SourceResult.Failed(FailureKind.TIMEOUT, e)
        } catch (e: IOException) {
            SourceResult.Failed(FailureKind.NETWORK, e)
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"

        /**
         * Страница есть, плеера нет, и сайт объясняет почему. Голое «правообладател» не годится: ссылка
         * «Правообладателям» стоит в подвале каждой страницы.
         */
        val RESTRICTED_MARKERS = listOf("по просьбе правообладателя", "по требованию правообладателя", "удалена по требованию")
    }
}

private inline fun <T, R> SourceResult<T>.map(transform: (T) -> R): SourceResult<R> = when (this) {
    is SourceResult.Ok -> runCatching { SourceResult.Ok(transform(value)) }
        .getOrElse { SourceResult.Failed(FailureKind.PARSE, it) }
    is SourceResult.Restricted -> this
    is SourceResult.Failed -> this
}
