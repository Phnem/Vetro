package com.example.myapplication.audiobooks.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.session.MediaController
import com.example.myapplication.audiobooks.domain.model.Chapter
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.audiobooks.domain.model.WorkId
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline

/**
 * Что играет сейчас — одно на приложение (Koin-синглтон). Пишет его [AudiobookPlayerHost], который
 * держит живой `MediaController`; читают плеер, карточка «Продолжить» и запуск книги. Так карточка
 * на доме показывает живой прогресс и управляет тем же плеером, а «Слушать» раскрывает полный плеер.
 */
@Stable
class AudiobookPlayerState {
    var controller by mutableStateOf<MediaController?>(null)
        internal set
    var book by mutableStateOf<PlayingBook?>(null)
        internal set

    /** Позиция внутри текущего трека. Отдельное состояние: меняется каждые 250 мс. */
    val trackPositionMs = mutableLongStateOf(0L)
    var timeline by mutableStateOf<BookTimeline?>(null)
        internal set
    var sleepRemainingMs by mutableLongStateOf(-1L)
        internal set
    var skipSilence by mutableStateOf(false)
        internal set

    /** Последний переход плеера по цепочке сайтов: номер и имя сайта (пусто — запасных нет). */
    var sourceNotice by mutableStateOf(0 to "")
        internal set

    /** Счётчик просьб раскрыть полный плеер; хост отвечает на каждое новое значение. */
    var expandRequests by mutableIntStateOf(0)
        private set

    fun requestExpand() {
        expandRequests++
    }

    fun playPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekBack() = controller?.seekBack()

    fun seekForward() = controller?.seekForward()

    /** Позиция на шкале книги; null, пока шкала не известна. */
    fun globalMs(): Long? {
        val b = book ?: return null
        val ref = TrackUriCodec.decode(b.uri) ?: return null
        return timeline?.toGlobal(ref.trackIndex, trackPositionMs.longValue)
    }

    fun seekToGlobal(globalMs: Long) {
        val (index, offset) = timeline?.toTrack(globalMs.coerceAtLeast(0L)) ?: return
        controller?.seekTo(index, offset)
    }
}

data class PlayingBook(
    val mediaId: String,
    val uri: String,
    val workId: WorkId?,
    val narrationId: NarrationId?,
    val title: String,
    val chapterTitle: String,
    val author: String,
    val narrator: String,
    val artworkUri: String?,
    val durationMs: Long?,
    val trackIndex: Int,
    val isPlaying: Boolean,
    val speed: Float,
)

internal fun MediaController.snapshot(): PlayingBook? {
    val item = currentMediaItem ?: return null
    val extras = item.mediaMetadata.extras
    return PlayingBook(
        mediaId = item.mediaId,
        uri = item.localConfiguration?.uri?.toString().orEmpty(),
        workId = extras?.getString("workId")?.let { runCatching { WorkId(it) }.getOrNull() },
        narrationId = extras?.getString("narrationId")?.let { runCatching { NarrationId(it) }.getOrNull() },
        title = item.mediaMetadata.albumTitle?.toString().orEmpty().ifBlank { item.mediaMetadata.title?.toString().orEmpty() },
        chapterTitle = item.mediaMetadata.title?.toString().orEmpty(),
        author = item.mediaMetadata.artist?.toString().orEmpty(),
        narrator = item.mediaMetadata.albumArtist?.toString().orEmpty(),
        artworkUri = item.mediaMetadata.artworkUri?.toString(),
        durationMs = duration.takeIf { it != C.TIME_UNSET && it > 0L },
        trackIndex = currentMediaItemIndex,
        isPlaying = isPlaying,
        speed = playbackParameters.speed,
    )
}

/** Текущая глава и позиция в ней — общий расчёт для плеера, мини-плеера и шторки глав. */
internal data class ChapterPosition(
    val chapter: Chapter?,
    val index: Int,
    val count: Int,
    val positionMs: Long,
    val durationMs: Long?,
    val globalMs: Long?,
)

internal fun chapterPosition(book: PlayingBook, timeline: BookTimeline?, trackPositionMs: Long): ChapterPosition {
    val ref = TrackUriCodec.decode(book.uri)
    val global = if (ref != null) timeline?.toGlobal(ref.trackIndex, trackPositionMs) else null
    val chapter = global?.let { timeline?.chapterAt(it) }
    val next = chapter?.let { c -> timeline?.chapters?.firstOrNull { it.startMs > c.startMs }?.startMs }
    val duration = chapter?.durationMs ?: if (chapter != null && next != null) next - chapter.startMs else book.durationMs
    val position = if (chapter != null && global != null) (global - chapter.startMs).coerceAtLeast(0L) else trackPositionMs
    return ChapterPosition(
        chapter = chapter,
        index = chapter?.let { c -> timeline?.chapters?.indexOf(c) } ?: 0,
        count = timeline?.chapters?.size ?: 0,
        positionMs = position,
        durationMs = duration,
        globalMs = global,
    )
}

/** «1.0×», «1.25×»: одна цифра после точки, если второй нет. */
internal fun formatSpeed(speed: Float): String {
    val hundredths = kotlin.math.round(speed * 100).toInt()
    return if (hundredths % 10 == 0) "%.1f×".format(hundredths / 100f) else "%.2f×".format(hundredths / 100f)
}

internal fun formatClock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000
    val minutes = seconds / 60
    return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds % 60)
    else "%d:%02d".format(minutes, seconds % 60)
}
