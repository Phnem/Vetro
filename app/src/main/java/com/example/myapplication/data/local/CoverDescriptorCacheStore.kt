package com.example.myapplication.data.local

import com.example.myapplication.network.AppJson
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

private const val TAG = "CoverDescriptorCache"
private const val CACHE_FILE = "cover_descriptors_cache.json"

/**
 * Файловый кэш визуальных дескрипторов обложек (см. CoverDescriptorProvider) — тот же паттерн,
 * что у RecommendationCacheStore (JSON целиком в одном файле, atomic rename, IO-диспетчер).
 * Ключ — путь локального файла обложки или URL кандидата; TTL не нужен, дескрипторы конкретной
 * обложки не меняются со временем.
 */
class CoverDescriptorCacheStore(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val json = AppJson

    suspend fun read(key: String): List<String>? = withContext(Dispatchers.IO) {
        readAll()[key]
    }

    suspend fun write(key: String, tags: List<String>): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val updated = readAll() + (key to tags)
            val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(json.encodeToString(updated))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Failed to write cover descriptor cache", it) }
    }

    private fun readAll(): Map<String, List<String>> = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        json.decodeFromString<Map<String, List<String>>>(file.readText())
    }.getOrElse {
        Log.w(TAG, "Failed to read cover descriptor cache", it)
        emptyMap()
    }
}
