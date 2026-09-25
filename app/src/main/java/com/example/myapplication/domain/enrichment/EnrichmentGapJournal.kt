package com.example.myapplication.domain.enrichment

import com.example.myapplication.network.AppJson
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

private const val TAG = "EnrichmentJournal"
private const val JOURNAL_FILE = "enrichment_gap_journal.json"

/**
 * Файловый журнал «пробовали закрыть полевой пробел, не нашлось → пока игнорируем».
 *
 * По образцу [com.example.myapplication.data.local.StatsExplanationCacheStore] (filesDir, atomic
 * rename, mutex). Ключ верхнего уровня — `animeId`, значение — карта `GapKind → метка последней
 * неудачной попытки (ms)`. Запись старше [retryTtlMs] снова становится кандидатом (как
 * `NOT_FOUND_TTL_MS` у проверки серий) — источники со временем пополняются.
 *
 * Хранит ТОЛЬКО полевые пробелы ([GapKind.isFieldGap]); журнал названий живёт в БД (`title_*_checked_at`).
 */
class EnrichmentGapJournal(
    context: Context,
    private val retryTtlMs: Long = DEFAULT_RETRY_TTL_MS,
) {
    private val file = File(context.filesDir, JOURNAL_FILE)
    private val json = AppJson

    // Внешняя карта: animeId -> (gapKind.name -> failedAtMillis).
    private val serializer = MapSerializer(
        String.serializer(),
        MapSerializer(String.serializer(), Long.serializer()),
    )
    private val mutex = Mutex()

    /** Активные (не протухшие) полевые пробелы записи, помеченные как неразрешимые. */
    suspend fun activeFieldGaps(animeId: String, now: Long = System.currentTimeMillis()): Set<GapKind> =
        withContext(Dispatchers.IO) {
            val entry = mutex.withLock { readLocked()[animeId] } ?: return@withContext emptySet()
            entry.mapNotNull { (kindName, failedAt) ->
                val kind = runCatching { GapKind.valueOf(kindName) }.getOrNull() ?: return@mapNotNull null
                if (!kind.isFieldGap) return@mapNotNull null
                if (now - failedAt >= retryTtlMs) null else kind
            }.toSet()
        }

    /** Пометить полевые пробелы записи как неразрешимые (перезаписывает метку времени). */
    suspend fun mark(
        animeId: String,
        kinds: Set<GapKind>,
        now: Long = System.currentTimeMillis(),
    ): Unit = withContext(Dispatchers.IO) {
        val fieldKinds = kinds.filter { it.isFieldGap }
        if (fieldKinds.isEmpty()) return@withContext
        mutex.withLock {
            val all = readLocked().toMutableMap()
            val entry = all[animeId].orEmpty().toMutableMap()
            fieldKinds.forEach { entry[it.name] = now }
            all[animeId] = entry
            writeLocked(all)
        }
    }

    /** Снять пометки по закрытым пробелам (успешно заполнили поле или запись отредактировали). */
    suspend fun clear(animeId: String, kinds: Set<GapKind>): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readLocked().toMutableMap()
            val entry = all[animeId]?.toMutableMap() ?: return@withLock
            kinds.forEach { entry.remove(it.name) }
            if (entry.isEmpty()) all.remove(animeId) else all[animeId] = entry
            writeLocked(all)
        }
    }

    /** Полностью забыть запись (удалена из коллекции). */
    suspend fun forget(animeId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readLocked().toMutableMap()
            if (all.remove(animeId) != null) writeLocked(all)
        }
    }

    private fun readLocked(): Map<String, Map<String, Long>> = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        json.decodeFromString(serializer, file.readText())
    }.getOrElse {
        Log.w(TAG, "Failed to read enrichment journal", it)
        emptyMap()
    }

    private fun writeLocked(data: Map<String, Map<String, Long>>) {
        runCatching {
            val tmp = File(file.parentFile, "$JOURNAL_FILE.tmp")
            tmp.writeText(json.encodeToString(serializer, data))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Failed to write enrichment journal", it) }
    }

    companion object {
        /** 14 дней — как `NOT_FOUND_TTL_MS` в проверке серий. */
        const val DEFAULT_RETRY_TTL_MS = 14L * 24L * 60L * 60L * 1000L
    }
}
