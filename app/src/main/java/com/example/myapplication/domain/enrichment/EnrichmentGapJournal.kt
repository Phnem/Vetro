package com.example.myapplication.domain.enrichment

import android.content.Context
import com.example.myapplication.data.local.JsonMapFileStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

private const val TAG = "EnrichmentJournal"
private const val JOURNAL_FILE = "enrichment_gap_journal.json"

/**
 * Файловый журнал «пробовали закрыть полевой пробел, не нашлось → пока игнорируем».
 *
 * Хранится в [JsonMapFileStore] (filesDir). Ключ верхнего уровня — `animeId`, значение — карта `GapKind → метка последней
 * неудачной попытки (ms)`. Запись старше [retryTtlMs] снова становится кандидатом (как
 * `NOT_FOUND_TTL_MS` у проверки серий) — источники со временем пополняются.
 *
 * Хранит ТОЛЬКО полевые пробелы ([GapKind.isFieldGap]); журнал названий живёт в БД (`title_*_checked_at`).
 */
class EnrichmentGapJournal(
    context: Context,
    private val retryTtlMs: Long = DEFAULT_RETRY_TTL_MS,
) {
    // animeId -> (gapKind.name -> failedAtMillis). Все записи идут через этот класс, поэтому карта
    // в памяти всегда актуальна: файл читается один раз, а не на каждый вопрос о тайтле.
    private val store = JsonMapFileStore(
        File(context.filesDir, JOURNAL_FILE),
        MapSerializer(String.serializer(), Long.serializer()),
        TAG,
    )

    /** Активные (не протухшие) полевые пробелы записи, помеченные как неразрешимые. */
    suspend fun activeFieldGaps(animeId: String, now: Long = System.currentTimeMillis()): Set<GapKind> {
        store.ensureLoaded()
        val entry = store[animeId] ?: return emptySet()
        return entry.mapNotNull { (kindName, failedAt) ->
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
    ) {
        val fieldKinds = kinds.filter { it.isFieldGap }
        if (fieldKinds.isEmpty()) return
        store.update { all -> all + (animeId to (all[animeId].orEmpty() + fieldKinds.associate { it.name to now })) }
    }

    /** Снять пометки по закрытым пробелам (успешно заполнили поле или запись отредактировали). */
    suspend fun clear(animeId: String, kinds: Set<GapKind>) {
        store.update { all ->
            val entry = all[animeId] ?: return@update all
            val rest = entry - kinds.map { it.name }.toSet()
            if (rest.isEmpty()) all - animeId else all + (animeId to rest)
        }
    }

    /** Полностью забыть запись (удалена из коллекции). */
    suspend fun forget(animeId: String) {
        store.update { it - animeId }
    }

    companion object {
        /** 14 дней — как `NOT_FOUND_TTL_MS` в проверке серий. */
        const val DEFAULT_RETRY_TTL_MS = 14L * 24L * 60L * 60L * 1000L
    }
}
