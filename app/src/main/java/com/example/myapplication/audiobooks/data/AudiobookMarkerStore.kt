package com.example.myapplication.audiobooks.data

import android.content.Context
import com.example.myapplication.data.local.JsonMapFileStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Маркер — место в книге, куда пользователь хочет вернуться: позиция на шкале книги (все треки подряд). */
@Serializable
data class AudiobookMarker(
    val globalMs: Long,
    val createdAt: Long,
)

/**
 * Маркеры аудиокниг по озвучке (narrationId): у каждой озвучки своя шкала времени, и позиция из
 * одной в другой ничего не значит. Файловый стор без миграции базы — как у соседних кэшей.
 */
class AudiobookMarkerStore(context: Context) {

    private val store = JsonMapFileStore(
        File(context.filesDir, "audiobook_markers_v1.json"),
        ListSerializer(AudiobookMarker.serializer()),
        TAG,
    )

    /** Маркеры озвучки. Стор читается с диска при первой подписке: без этого после перезапуска поток был пуст до первой записи. */
    fun markers(narrationId: String): Flow<List<AudiobookMarker>> =
        kotlinx.coroutines.flow.flow {
            store.ensureLoaded()
            emitAll(store.flow)
        }
            .map { it[narrationId].orEmpty().sortedBy { m -> m.globalMs } }
            .distinctUntilChanged()

    suspend fun ensureLoaded() = store.ensureLoaded()

    /** Новый маркер. Рядом (ближе [MERGE_WINDOW_MS]) с уже стоящим второй не ставится. */
    suspend fun add(narrationId: String, globalMs: Long) {
        store.ensureLoaded()
        store.update { map ->
            val current = map[narrationId].orEmpty()
            if (current.any { kotlin.math.abs(it.globalMs - globalMs) < MERGE_WINDOW_MS }) return@update map
            map + (narrationId to (current + AudiobookMarker(globalMs, System.currentTimeMillis())))
        }
    }

    suspend fun remove(narrationId: String, marker: AudiobookMarker) {
        store.ensureLoaded()
        store.update { map ->
            val rest = map[narrationId].orEmpty() - marker
            if (rest.isEmpty()) map - narrationId else map + (narrationId to rest)
        }
    }

    companion object {
        private const val TAG = "AudiobookMarkers"

        /** Два нажатия подряд в одном месте — один маркер, а не две точки друг на друге. */
        const val MERGE_WINDOW_MS = 5_000L
    }
}
