package com.example.myapplication.data.local

import android.content.Context
import android.util.Log
import com.example.myapplication.domain.recommendations.FeedbackEntry
import com.example.myapplication.domain.recommendations.FeedbackState
import com.example.myapplication.network.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "RecommendationFeedback"
private const val FEEDBACK_FILE = "recommendation_feedback.json"

/**
 * Оценки рекомендаций (свайп вверх/вниз) — отдельный файл, а не часть кэша: кэш можно сбросить
 * (TTL, смена языка), а вкус пользователя — нет. Atomic rename, как у [RecommendationCacheStore].
 */
class RecommendationFeedbackStore(context: Context) {

    private val file = File(context.filesDir, FEEDBACK_FILE)
    private val mutex = Mutex()
    private var cached: FeedbackState? = null

    suspend fun read(): FeedbackState = mutex.withLock { load() }

    /** Записывает оценку; повторная оценка той же карточки заменяет прежнюю. */
    suspend fun record(entry: FeedbackEntry): FeedbackState = mutex.withLock {
        val current = load()
        val entries = (current.entries.filterNot { it.key == entry.key } + entry).takeLast(MAX_ENTRIES)
        persist(FeedbackState(revision = current.revision + 1, entries = entries))
    }

    /** Отмена оценки (кнопка «Отменить» после свайпа). */
    suspend fun remove(key: String): FeedbackState = mutex.withLock {
        val current = load()
        if (current.entries.none { it.key == key }) return@withLock current
        persist(FeedbackState(revision = current.revision + 1, entries = current.entries.filterNot { it.key == key }))
    }

    private suspend fun load(): FeedbackState {
        cached?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) FeedbackState() else AppJson.decodeFromString<FeedbackState>(file.readText())
            }.getOrElse {
                Log.w(TAG, "Failed to read recommendation feedback", it)
                FeedbackState()
            }
        }
        cached = loaded
        return loaded
    }

    private suspend fun persist(state: FeedbackState): FeedbackState {
        cached = state
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.parentFile, "$FEEDBACK_FILE.tmp")
                tmp.writeText(AppJson.encodeToString(FeedbackState.serializer(), state))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }.onFailure { Log.w(TAG, "Failed to write recommendation feedback", it) }
        }
        return state
    }

    companion object {
        /** Старейшие оценки вытесняются: их вклад и так затухает по времени. */
        const val MAX_ENTRIES = 600
    }
}
