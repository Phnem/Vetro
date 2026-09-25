package com.example.myapplication.data.local

import android.content.Context
import com.example.myapplication.domain.stats.StatsCardKind
import com.example.myapplication.network.AppLanguage
import kotlinx.serialization.Serializable
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
 * Файловый кэш AI-объяснений статистики ([JsonMapFileStore]) с инвалидацией по фингерпринту
 * данных, а не TTL.
 * Ключ — `"${kind}_${language}"`, так что смена языка = просто cache-miss новой пары.
 */
class StatsExplanationCacheStore(context: Context) {

    private val store = JsonMapFileStore(File(context.filesDir, CACHE_FILE), CachedStatsExplanation.serializer(), TAG)

    suspend fun readAll(): Map<String, CachedStatsExplanation> {
        store.ensureLoaded()
        return store.value
    }

    suspend fun read(kind: StatsCardKind, language: AppLanguage): CachedStatsExplanation? =
        readAll()[keyOf(kind, language)]

    suspend fun write(
        kind: StatsCardKind,
        language: AppLanguage,
        text: String,
        dataFingerprint: String,
    ) {
        store.update {
            it + (keyOf(kind, language) to CachedStatsExplanation(
                text = text,
                dataFingerprint = dataFingerprint,
                generatedAtMillis = System.currentTimeMillis(),
            ))
        }
    }

    companion object {
        fun keyOf(kind: StatsCardKind, language: AppLanguage): String = "${kind.name}_${language.name}"
    }
}
