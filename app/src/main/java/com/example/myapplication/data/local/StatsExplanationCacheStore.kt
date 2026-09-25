package com.example.myapplication.data.local

import com.example.myapplication.network.AppJson
import android.content.Context
import android.util.Log
import com.example.myapplication.domain.stats.StatsCardKind
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

private const val TAG = "StatsExplainCache"
private const val CACHE_FILE = "stats_explanations_cache.json"

/** Закэшированное AI-объяснение одной карточки статистики на одном языке. */
@Serializable
data class CachedStatsExplanation(
    val text: String,
    /** Отпечаток данных карточки на момент генерации — протухание данными, не временем. */
    val dataFingerprint: String,
    val generatedAtMillis: Long,
)

/**
 * Файловый кэш AI-объяснений статистики (filesDir, atomic rename) — по образцу
 * [RecommendationCacheStore], но с инвалидацией по фингерпринту данных, а не TTL.
 * Ключ — `"${kind}_${language}"`, так что смена языка = просто cache-miss новой пары.
 */
class StatsExplanationCacheStore(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val json = AppJson
    private val serializer = MapSerializer(String.serializer(), CachedStatsExplanation.serializer())
    private val mutex = Mutex()

    suspend fun readAll(): Map<String, CachedStatsExplanation> = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked() }
    }

    suspend fun read(kind: StatsCardKind, language: AppLanguage): CachedStatsExplanation? =
        readAll()[keyOf(kind, language)]

    suspend fun write(
        kind: StatsCardKind,
        language: AppLanguage,
        text: String,
        dataFingerprint: String,
    ): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = readLocked() + (keyOf(kind, language) to CachedStatsExplanation(
                text = text,
                dataFingerprint = dataFingerprint,
                generatedAtMillis = System.currentTimeMillis(),
            ))
            runCatching {
                val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
                tmp.writeText(json.encodeToString(serializer, updated))
                if (!tmp.renameTo(file)) {
                    // rename поверх существующего файла на некоторых FS требует удаления цели
                    file.delete()
                    tmp.renameTo(file)
                }
            }.onFailure { Log.w(TAG, "Failed to write stats explanations cache", it) }
        }
    }

    private fun readLocked(): Map<String, CachedStatsExplanation> = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        json.decodeFromString(serializer, file.readText())
    }.getOrElse {
        Log.w(TAG, "Failed to read stats explanations cache", it)
        emptyMap()
    }

    companion object {
        fun keyOf(kind: StatsCardKind, language: AppLanguage): String = "${kind.name}_${language.name}"
    }
}
