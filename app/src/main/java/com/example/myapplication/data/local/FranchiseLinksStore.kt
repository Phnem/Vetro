package com.example.myapplication.data.local

import android.content.Context
import android.util.Log
import com.example.myapplication.domain.recommendations.FranchiseLinksCache
import com.example.myapplication.network.AppJson
import com.example.myapplication.network.FranchiseLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

private const val TAG = "FranchiseLinksStore"
private const val LINKS_FILE = "franchise_links.json"

@Serializable
data class FranchiseCacheEntry(val links: FranchiseLinks, val atMillis: Long)

@Serializable
data class FranchiseCacheFile(val entries: List<FranchiseCacheEntry> = emptyList())

/**
 * Файл `franchise_links.json`: связи франшиз AniList для отсева рекомендаций. Связи меняются редко
 * (новый сезон добавляет ребро), поэтому запись живёт [TTL_MILLIS]. Atomic rename, как у соседних хранилищ.
 */
class FranchiseLinksStore(context: Context) : FranchiseLinksCache {

    private val file = File(context.filesDir, LINKS_FILE)
    private val mutex = Mutex()
    private var byAnilist: MutableMap<Int, FranchiseCacheEntry>? = null
    private val malToAnilist = HashMap<Int, Int>()

    override suspend fun byAnilistId(id: Int, nowMillis: Long): FranchiseLinks? = mutex.withLock {
        fresh(load()[id], nowMillis)
    }

    override suspend fun byMalId(id: Int, nowMillis: Long): FranchiseLinks? = mutex.withLock {
        val map = load()
        fresh(malToAnilist[id]?.let { map[it] }, nowMillis)
    }

    override suspend fun putAll(links: List<FranchiseLinks>, nowMillis: Long) {
        mutex.withLock {
            val map = load()
            links.forEach { index(map, FranchiseCacheEntry(it, nowMillis)) }
            if (map.size > MAX_ENTRIES) {
                map.values.sortedBy { it.atMillis }.take(map.size - MAX_ENTRIES).forEach { map.remove(it.links.anilistId) }
            }
            persist(map)
        }
    }

    private fun fresh(entry: FranchiseCacheEntry?, nowMillis: Long): FranchiseLinks? =
        entry?.takeIf { nowMillis - it.atMillis < TTL_MILLIS }?.links

    private fun index(map: MutableMap<Int, FranchiseCacheEntry>, entry: FranchiseCacheEntry) {
        map[entry.links.anilistId] = entry
        entry.links.malId?.let { malToAnilist[it] = entry.links.anilistId }
    }

    private suspend fun load(): MutableMap<Int, FranchiseCacheEntry> {
        byAnilist?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) FranchiseCacheFile() else AppJson.decodeFromString<FranchiseCacheFile>(file.readText())
            }.getOrElse {
                Log.w(TAG, "Failed to read franchise links", it)
                FranchiseCacheFile()
            }
        }
        val map = HashMap<Int, FranchiseCacheEntry>()
        loaded.entries.forEach { index(map, it) }
        byAnilist = map
        return map
    }

    private suspend fun persist(map: Map<Int, FranchiseCacheEntry>) {
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.parentFile, "$LINKS_FILE.tmp")
                tmp.writeText(AppJson.encodeToString(FranchiseCacheFile.serializer(), FranchiseCacheFile(map.values.toList())))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }.onFailure { Log.w(TAG, "Failed to write franchise links", it) }
        }
    }

    companion object {
        const val TTL_MILLIS = 14L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 6000
    }
}
