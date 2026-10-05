package com.example.myapplication.data.local

import android.content.Context
import com.example.myapplication.domain.enrichment.title.ReleaseCadence
import com.example.myapplication.domain.enrichment.title.ReleaseObservation
import java.io.File
import kotlinx.serialization.builtins.ListSerializer

private const val TAG = "ReleaseObservations"
private const val FILE_NAME = "release_observations.json"

/** Сколько наблюдений держим на тайтл: ритму хватает нескольких последних серий. */
private const val KEEP_PER_TITLE = 40

/**
 * Журнал «серия N вышла в такой-то момент» по тайтлам ([JsonMapFileStore], ключ — id записи).
 * Из него [ReleaseCadence] выводит день недели выхода, когда источник расписания молчит.
 *
 * Пишут двое: проверка серий (момент, когда номер серии вырос) и расписание озвучки AniLibria
 * (точное время выхода). Точная запись вытесняет приблизительную для той же серии.
 */
class ReleaseObservationStore(context: Context) {

    private val store = JsonMapFileStore(
        File(context.filesDir, FILE_NAME),
        ListSerializer(ReleaseObservation.serializer()),
        TAG,
    )

    suspend fun all(): Map<String, List<ReleaseObservation>> {
        store.ensureLoaded()
        return store.value
    }

    suspend fun get(animeId: String): List<ReleaseObservation> = all()[animeId].orEmpty()

    suspend fun record(animeId: String, episode: Int, atMs: Long, exact: Boolean) {
        if (episode <= 0 || atMs <= 0L) return
        store.update { map ->
            val current = map[animeId].orEmpty()
            val existing = current.firstOrNull { it.episode == episode }
            // Уже есть точная запись этой серии, а пришла приблизительная — оставляем точную.
            if (existing != null && (existing.exact || !exact)) return@update map
            val merged = ReleaseCadence.merge(current + ReleaseObservation(episode, atMs, exact))
                .sortedBy { it.episode }
                .takeLast(KEEP_PER_TITLE)
            map + (animeId to merged)
        }
    }

    suspend fun forget(animeId: String) {
        store.update { it - animeId }
    }
}
