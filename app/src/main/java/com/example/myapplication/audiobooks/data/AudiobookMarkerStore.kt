package com.example.myapplication.audiobooks.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.example.myapplication.data.local.AnimeDatabase
import com.example.myapplication.data.local.Audiobook_bookmark
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Маркер — место в книге, куда пользователь хочет вернуться: позиция на шкале книги (все треки подряд). */
data class AudiobookMarker(
    val id: String,
    val globalMs: Long,
    val createdAt: Long,
)

/**
 * Маркеры аудиокниг по озвучке — таблица `audiobook_bookmark`: у каждой озвучки своя шкала
 * времени, позиция из одной в другой ничего не значит. Удаление озвучки уносит её маркеры каскадом.
 */
class AudiobookMarkerStore(private val db: AnimeDatabase) {
    private val q get() = db.audiobookQueries

    fun markers(narrationId: String): Flow<List<AudiobookMarker>> =
        q.bookmarksByNarration(narrationId).asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.map { AudiobookMarker(it.id, it.global_ms, it.created_at) } }
            .distinctUntilChanged()

    /**
     * Новый маркер. Рядом (ближе [MERGE_WINDOW_MS]) с уже стоящим второй не ставится. Озвучка
     * заводится в базе с первым сохранением прогресса; маркер до этого момента молча не ставится.
     */
    suspend fun add(narrationId: String, globalMs: Long, chapterIndex: Int, chapterOffsetMs: Long) =
        withContext(Dispatchers.IO) {
            val existing = q.bookmarksByNarration(narrationId).executeAsList()
            if (existing.any { kotlin.math.abs(it.global_ms - globalMs) < MERGE_WINDOW_MS }) return@withContext
            runCatching {
                q.insertBookmark(
                    Audiobook_bookmark(
                        id = UUID.randomUUID().toString(),
                        narration_id = narrationId,
                        global_ms = globalMs,
                        chapter_idx = chapterIndex.toLong(),
                        chapter_offset_ms = chapterOffsetMs,
                        note = null,
                        created_at = System.currentTimeMillis(),
                    ),
                )
            }
        }

    suspend fun remove(marker: AudiobookMarker) = withContext(Dispatchers.IO) {
        q.deleteBookmark(marker.id)
    }

    companion object {
        /** Два нажатия подряд в одном месте — один маркер, а не две точки друг на друге. */
        const val MERGE_WINDOW_MS = 5_000L
    }
}
