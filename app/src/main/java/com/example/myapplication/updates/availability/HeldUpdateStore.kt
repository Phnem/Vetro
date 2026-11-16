package com.example.myapplication.updates.availability

import android.content.Context
import com.example.myapplication.data.local.JsonMapFileStore
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private const val TAG = "HeldUpdates"
private const val FILE_NAME = "held_updates.json"

/** Сколько придержанных серий помним на тайтл: старше — уже давно вышли. */
private const val KEEP_PER_TITLE = 5

@Serializable
data class HeldCheck(val episodes: Int, val lastCheckedMs: Long)

/**
 * Метки «серию N тайтла придержали, последний аудит был тогда-то» ([JsonMapFileStore], ключ — id
 * записи). Нужны только для того, чтобы перепроверять раз в шесть часов, а не при каждом проходе.
 */
class HeldUpdateStore(context: Context) : HeldChecks {

    private val store = JsonMapFileStore(
        File(context.filesDir, FILE_NAME),
        ListSerializer(HeldCheck.serializer()),
        TAG,
    )

    override suspend fun lastCheckedMs(animeId: String, episodes: Int): Long? {
        store.ensureLoaded()
        return store.value[animeId]?.firstOrNull { it.episodes == episodes }?.lastCheckedMs
    }

    override suspend fun markChecked(animeId: String, episodes: Int, atMs: Long) {
        store.update { map ->
            val rest = map[animeId].orEmpty().filter { it.episodes != episodes }
            map + (animeId to (rest + HeldCheck(episodes, atMs)).takeLast(KEEP_PER_TITLE))
        }
    }

    override suspend fun release(animeId: String, episodes: Int) {
        store.ensureLoaded()
        if (store.value[animeId]?.any { it.episodes == episodes } != true) return
        store.update { map ->
            val rest = map[animeId].orEmpty().filter { it.episodes != episodes }
            if (rest.isEmpty()) map - animeId else map + (animeId to rest)
        }
    }
}
