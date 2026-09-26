package com.example.myapplication.audiobooks.playback

import androidx.media3.common.Player
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.data.PlaybackBookMeta
import com.example.myapplication.audiobooks.data.ProgressSnapshot
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.model.WorkId
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Позиция книги в БД (spec/03 «Сохранение позиции»): время шкалы книги + глава и смещение в ней.
 * Сервис зовёт [save] там же, где сохраняет очередь для возобновления; одинаковая позиция не пишется.
 */
class AudiobookProgressTracker(
    private val repository: AudiobookRepository,
    private val scope: CoroutineScope,
) {
    private var ensuredNarration: String? = null
    private var lastSaved: ProgressSnapshot? = null

    /** [timeline] — шкала варианта [timelineVariant]; для чужого варианта позицию не считаем. */
    fun save(player: Player, timeline: BookTimeline?, timelineVariant: VariantId?) {
        val item = player.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        val workId = extras.getString("workId")?.let { runCatching { WorkId(it) }.getOrNull() } ?: return
        val narrationId = extras.getString("narrationId")?.let { runCatching { NarrationId(it) }.getOrNull() } ?: return
        val ref = item.localConfiguration?.uri?.toString()?.let(TrackUriCodec::decode) ?: return
        if (timeline == null || ref.variant != timelineVariant) return
        val snapshot = snapshot(
            timeline = timeline,
            workId = workId,
            narrationId = narrationId,
            variant = ref.variant,
            trackIndex = ref.trackIndex,
            offsetMs = player.currentPosition.coerceAtLeast(0L),
            speed = player.playbackParameters.speed,
        ) ?: return
        if (snapshot == lastSaved) return
        lastSaved = snapshot
        val meta = PlaybackBookMeta(
            workId = workId,
            narrationId = narrationId,
            title = item.mediaMetadata.albumTitle?.toString().orEmpty(),
            author = item.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() },
            narrator = item.mediaMetadata.albumArtist?.toString()?.takeIf { it.isNotBlank() },
            coverUrl = item.mediaMetadata.artworkUri?.toString(),
        )
        scope.launch {
            if (ensuredNarration != narrationId.value) {
                repository.ensureFromPlayback(meta)
                ensuredNarration = narrationId.value
            }
            repository.saveProgress(snapshot)
        }
    }

    companion object {
        /** Книга дослушана, если осталось меньше минуты или меньше 1 %. */
        private const val FINISH_TAIL_MS = 60_000L

        fun snapshot(
            timeline: BookTimeline,
            workId: WorkId,
            narrationId: NarrationId,
            variant: VariantId,
            trackIndex: Int,
            offsetMs: Long,
            speed: Float,
        ): ProgressSnapshot? {
            if (trackIndex !in timeline.tracks.indices) return null
            val global = timeline.toGlobal(trackIndex, offsetMs) ?: return null
            val chapter = timeline.chapterAt(global)
            val total = timeline.totalMs
            val remaining = total?.minus(global)
            return ProgressSnapshot(
                workId = workId,
                narrationId = narrationId,
                variantId = variant,
                globalMs = global,
                chapterIndex = chapter?.index ?: 0,
                chapterOffsetMs = global - (chapter?.startMs ?: 0L),
                totalMs = total,
                speed = speed,
                finished = remaining != null && (remaining < FINISH_TAIL_MS || remaining < total / 100),
            )
        }
    }
}
