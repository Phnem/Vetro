package com.example.myapplication.data.local

import com.example.myapplication.network.AppJson
import android.content.Context
import android.util.Log
import com.example.myapplication.domain.recommendations.RecommendationsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "RecommendationCache"
private const val CACHE_FILE = "recommendations_cache.json"

/**
 * Файловый кэш рекомендаций (filesDir, atomic rename) — без миграции схемы SQLDelight
 * и без блокировки UI: чтение/запись на IO, JSON целиком в одном файле.
 *
 * Семантика stale-while-revalidate живёт в движке: кэш просто хранит снапшот
 * и отвечает, протух ли он (TTL) или устарел (сигнатура библиотеки изменилась).
 */
class RecommendationCacheStore(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val json = AppJson

    suspend fun read(): RecommendationsSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching null
            json.decodeFromString<RecommendationsSnapshot>(file.readText())
        }.getOrElse {
            Log.w(TAG, "Failed to read recommendations cache", it)
            null
        }
    }

    suspend fun write(snapshot: RecommendationsSnapshot): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(json.encodeToString(RecommendationsSnapshot.serializer(), snapshot))
            if (!tmp.renameTo(file)) {
                // rename поверх существующего файла на некоторых FS требует удаления цели
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Failed to write recommendations cache", it) }
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
    }

    fun isStale(snapshot: RecommendationsSnapshot?, nowMillis: Long = System.currentTimeMillis()): Boolean =
        snapshot == null || nowMillis - snapshot.computedAtMillis > TTL_MILLIS

    companion object {
        /** 36 часов — related-графы источников меняются медленно. */
        const val TTL_MILLIS = 36L * 60 * 60 * 1000
    }
}
