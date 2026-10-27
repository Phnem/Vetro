package com.example.myapplication.data.local

import android.content.Context
import android.util.Log
import com.example.myapplication.domain.recommendations.LibraryTmdbIdBackfill
import com.example.myapplication.domain.recommendations.TmdbIdMissLedger
import com.example.myapplication.network.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

private const val TAG = "TmdbIdMissStore"
private const val MISS_FILE = "tmdb_id_misses.json"

@Serializable
data class TmdbIdMiss(
    val fingerprint: String,
    val atMillis: Long,
    val reason: String,
)

/** Файл `tmdb_id_misses.json`: animeId → последний неудачный поиск TMDB id. Atomic rename, как у соседних хранилищ. */
class TmdbIdMissStore(context: Context) : TmdbIdMissLedger {

    private val file = File(context.filesDir, MISS_FILE)
    private val mutex = Mutex()
    private var cached: Map<String, TmdbIdMiss>? = null
    private val serializer = MapSerializer(String.serializer(), TmdbIdMiss.serializer())

    override suspend fun isRecentMiss(animeId: String, fingerprint: String, nowMillis: Long): Boolean =
        mutex.withLock {
            val miss = load()[animeId] ?: return@withLock false
            miss.fingerprint == fingerprint && nowMillis - miss.atMillis < LibraryTmdbIdBackfill.MISS_TTL_MILLIS
        }

    override suspend fun recordMiss(animeId: String, fingerprint: String, nowMillis: Long, reason: String) {
        mutex.withLock {
            persist(load() + (animeId to TmdbIdMiss(fingerprint, nowMillis, reason)))
        }
    }

    private suspend fun load(): Map<String, TmdbIdMiss> {
        cached?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) emptyMap() else AppJson.decodeFromString(serializer, file.readText())
            }.getOrElse {
                Log.w(TAG, "Failed to read TMDB id misses", it)
                emptyMap()
            }
        }
        cached = loaded
        return loaded
    }

    private suspend fun persist(state: Map<String, TmdbIdMiss>) {
        cached = state
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.parentFile, "$MISS_FILE.tmp")
                tmp.writeText(AppJson.encodeToString(serializer, state))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }.onFailure { Log.w(TAG, "Failed to write TMDB id misses", it) }
        }
    }
}
