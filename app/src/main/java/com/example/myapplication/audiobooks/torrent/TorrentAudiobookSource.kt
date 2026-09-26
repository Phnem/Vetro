package com.example.myapplication.audiobooks.torrent

import com.example.myapplication.audiobooks.data.remote.web.SiteText
import com.example.myapplication.audiobooks.data.remote.web.SourceHttp
import com.example.myapplication.audiobooks.domain.model.AudioTrack
import com.example.myapplication.audiobooks.domain.model.Chapter
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.NaturalAudioOrder
import com.example.myapplication.audiobooks.domain.source.SourceResult
import java.io.IOException

/**
 * Раздача на трекере: как её получить и что сайт сообщает о звуке. [bitrateKbps] и [totalSec] нужны,
 * чтобы разметить главы на шкале книги до того, как файлы скачаны.
 */
data class TorrentRelease(val link: TorrentLink, val bitrateKbps: Int? = null, val totalSec: Long? = null)

/**
 * Трекер как источник аудиокниг: поиск и страница раздачи — у сайта, звук — из раздачи через
 * [TorrentEngine]. Треки — аудиофайлы раздачи по порядку имён, главы — по файлам. Скачанная раздача
 * остаётся на устройстве. В цепочке точек доступа трекеры стоят после потоковых сайтов: первый звук
 * приходит медленнее.
 */
abstract class TorrentAudiobookSource(
    protected val web: SourceHttp,
    private val engine: TorrentEngine,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AudiobookSource {

    protected abstract suspend fun release(key: String): SourceResult<TorrentRelease>

    override fun supports(variant: VariantId): Boolean = variant.value.startsWith("${id.value}:")

    override suspend fun refresh(variant: VariantId): MediaManifest {
        val key = variant.value.removePrefix("${id.value}:")
        val release = when (val r = release(key)) {
            is SourceResult.Ok -> r.value
            is SourceResult.Restricted -> throw IOException("${id.value}: $key restricted")
            is SourceResult.Failed -> throw IOException("${id.value}: $key failed (${r.kind})", r.cause)
        }
        val info = engine.metadata(release.link) { file -> web.bytes(file.url, file.headers) }
        val files = info.files()
        val audio = (0 until info.numFiles())
            .filter { TorrentFiles.isAudio(files.fileName(it)) }
            .sortedWith { a, b -> NaturalAudioOrder.compare(files.filePath(a), files.filePath(b)) }
        if (audio.isEmpty()) throw IOException("${id.value}: $key has no audio files")
        val hash = info.infoHash().toHex()
        val sizes = audio.map { files.fileSize(it) }
        val durationsSec = estimate(sizes, release)
        var start = 0L
        return MediaManifest(
            variant = variant,
            tracks = audio.mapIndexed { i, fileIndex ->
                AudioTrack(
                    index = i,
                    url = SchemeRoutingDataSource.trackUri(hash, fileIndex),
                    mimeType = TorrentFiles.mimeOf(files.fileName(fileIndex)),
                    durationMs = durationsSec[i] * 1000,
                    sizeBytes = sizes[i],
                )
            },
            chapters = audio.mapIndexed { i, fileIndex ->
                val ms = durationsSec[i] * 1000
                val name = files.fileName(fileIndex).substringBeforeLast('.')
                Chapter(i, SiteText.chapterTitle(name) ?: "Глава ${i + 1}", start, ms).also { start += ms }
            },
            resolvedAt = nowMs(),
            expiresAt = null,
        )
    }

    /**
     * Длины файлов до загрузки: по битрейту со страницы, иначе по общей длине пропорционально
     * размерам, иначе — по типичным для аудиокниг 64 кбит/с. Оценка согласована сама с собой:
     * переход к главе попадает в начало её файла.
     */
    private fun estimate(sizes: List<Long>, release: TorrentRelease): List<Long> {
        val kbps = release.bitrateKbps?.takeIf { it in 8..512 }
        val total = release.totalSec?.takeIf { it > 0 }
        val sum = sizes.sum().coerceAtLeast(1)
        return sizes.map { size ->
            when {
                kbps != null -> size * 8 / (kbps * 1000L)
                total != null -> total * size / sum
                else -> size * 8 / (DEFAULT_KBPS * 1000L)
            }.coerceAtLeast(1L)
        }
    }

    private companion object {
        const val DEFAULT_KBPS = 64
    }
}
