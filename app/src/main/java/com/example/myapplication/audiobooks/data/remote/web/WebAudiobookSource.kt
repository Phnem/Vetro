package com.example.myapplication.audiobooks.data.remote.web

import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.domain.model.AudioTrack
import com.example.myapplication.audiobooks.domain.model.Chapter
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.FailureKind
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceResult
import java.io.IOException

/** Звуковая дорожка сайта: как сайт её назвал, прямая ссылка и длина, если сайт её знает. */
data class SiteTrack(val title: String?, val url: String, val durationSec: Long? = null)

/**
 * Плейлист озвучки на сайте. [headers] едут с каждым запросом звука (например, Referer у CDN, которые
 * отдают файл только «со своей страницы»). [expiresAt] — когда подписанные ссылки протухнут.
 * [totalSec] — общая длина книги со страницы: по ней раскладываются длины треков, если сайт их не дал.
 */
data class SitePlaylist(
    val tracks: List<SiteTrack>,
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: Long? = null,
    val totalSec: Long? = null,
)

/**
 * Общая часть сайтов-источников: ключ варианта `"<source>:<key>"` и сборка манифеста из плейлиста.
 * Сайт отвечает только за то, чтобы найти плейлист ([playlist]).
 */
abstract class WebAudiobookSource(
    protected val web: SourceHttp,
    private val nowMs: () -> Long,
) : AudiobookSource {

    protected abstract suspend fun playlist(key: String): SourceResult<SitePlaylist>

    override fun supports(variant: VariantId): Boolean = variant.value.startsWith("${id.value}:")

    /**
     * Полка «Ещё от автора» (`author:<имя>`) у любого сайта — поиск по имени и отбор по автору:
     * отдельных страниц автора с устойчивой разметкой у сайтов нет, а поиск есть у всех.
     */
    override suspend fun shelf(id: String, page: Int): SourceResult<List<SourceBook>> {
        if (!id.startsWith(AUTHOR_SHELF)) {
            return SourceResult.Failed(FailureKind.PARSE, IllegalArgumentException(id))
        }
        val name = id.removePrefix(AUTHOR_SHELF)
        val words = AudiobookRepository.normalize(name).split(' ').filter { it.length > 2 }.toSet()
        return search(name, page).mapOk { books ->
            books.filter { b -> b.authors.any { a -> AudiobookRepository.normalize(a).split(' ').any(words::contains) } }
        }
    }

    protected fun authorShelfOf(authors: List<String>): String? = authors.firstOrNull()?.let { AUTHOR_SHELF + it }

    override suspend fun refresh(variant: VariantId): MediaManifest {
        val key = variant.value.removePrefix("${id.value}:")
        val list = when (val r = playlist(key)) {
            is SourceResult.Ok -> r.value
            is SourceResult.Restricted -> throw IOException("${id.value}: $key restricted (${r.reason})")
            is SourceResult.Failed -> throw IOException("${id.value}: $key failed (${r.kind})", r.cause)
        }
        if (list.tracks.isEmpty()) throw IOException("${id.value}: $key has no tracks")
        val durationsSec = durations(list)
        val tracks = list.tracks.mapIndexed { i, t ->
            AudioTrack(
                index = i,
                url = t.url,
                headers = list.headers,
                mimeType = "audio/mpeg",
                durationMs = durationsSec[i]?.times(1000),
            )
        }
        // Главы = треки. Разметить их на шкале книги можно, только когда известны все длины.
        val chapters = if (durationsSec.all { it != null }) {
            var start = 0L
            list.tracks.mapIndexed { i, t ->
                val ms = durationsSec[i]!! * 1000
                Chapter(index = i, title = SiteText.chapterTitle(t.title) ?: "Глава ${i + 1}", startMs = start, durationMs = ms)
                    .also { start += ms }
            }
        } else {
            emptyList()
        }
        return MediaManifest(variant, tracks, chapters, resolvedAt = nowMs(), expiresAt = list.expiresAt)
    }

    /**
     * Длины треков в секундах (строго > 0). Чего сайт не дал — раскладываем общую длину книги по
     * размерам файлов (MP3 одной озвучки пишется с одним битрейтом), а без размеров — поровну.
     * Раскладка согласована сама с собой: переход к главе всегда попадает в начало её трека.
     */
    private suspend fun durations(list: SitePlaylist): List<Long?> {
        val given = list.tracks.map { it.durationSec?.takeIf { d -> d > 0 } }
        if (given.all { it != null }) return given
        val total = list.totalSec?.takeIf { it > 0 } ?: return given
        val knownSum = given.filterNotNull().sum()
        val missing = given.indices.filter { given[it] == null }
        val rest = (total - knownSum).takeIf { it > missing.size } ?: return given
        val sizes = web.contentLengths(missing.map { list.tracks[it].url }, list.headers)
        val shares: List<Double> = if (sizes.all { it != null }) {
            val sum = sizes.sumOf { it!! }.toDouble()
            sizes.map { it!! / sum }
        } else {
            missing.map { 1.0 / missing.size }
        }
        val result = given.toMutableList()
        missing.forEachIndexed { k, i -> result[i] = (rest * shares[k]).toLong().coerceAtLeast(1L) }
        return result
    }

    protected companion object {
        const val AUTHOR_SHELF = "author:"

        /** Сайт объясняет, почему звука нет. Голое «правообладател» не годится: оно есть в подвале. */
        val RESTRICTED_MARKERS = listOf(
            "по просьбе правообладателя", "по требованию правообладателя", "удалена по требованию",
            "заблокирована по требованию", "доступ к книге ограничен",
        )
    }
}
