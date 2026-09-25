package com.example.myapplication.data.local

import com.example.myapplication.network.AppJson
import android.content.Context
import android.util.Log
import com.example.myapplication.domain.seasons.SeasonEpisodesEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Файловый кэш «серии по сезонам» (filesDir, atomic rename) — по образцу [WebLinksStore],
 * без миграции схемы SQLDelight. Ключ — animeId. UI (Details) подписывается на [flow].
 *
 * TTL двухуровневый: полная запись (все сезоны с числом серий, без онгоингов) живёт
 * [COMPLETE_TTL_MILLIS]; неполная или с онгоингом — [INCOMPLETE_TTL_MILLIS], т.е. фоновые
 * проходы будут долбить источники, пока не соберут всё («гарантированный» резолв).
 */
class SeasonEpisodesStore(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val json = AppJson
    private val mutex = Mutex()
    @Volatile private var loaded = false

    private val _flow = MutableStateFlow<Map<String, SeasonEpisodesEntry>>(emptyMap())
    val flow: StateFlow<Map<String, SeasonEpisodesEntry>> = _flow.asStateFlow()

    private val serializer = MapSerializer(String.serializer(), SeasonEpisodesEntry.serializer())

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val map = withContext(Dispatchers.IO) {
                runCatching {
                    if (!file.exists()) emptyMap()
                    else json.decodeFromString(serializer, file.readText())
                }.getOrElse {
                    Log.w(TAG, "Failed to read season-episodes cache", it)
                    emptyMap()
                }
            }
            _flow.value = map
            loaded = true
        }
    }

    fun entryFor(animeId: String): SeasonEpisodesEntry? = _flow.value[animeId]

    /** Свежа ли запись с учётом двухуровневого TTL (см. kdoc класса). */
    fun isFresh(animeId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val e = _flow.value[animeId] ?: return false
        if (e.resolvedAt <= 0) return false
        // Запись, собранная прежней версией резолвера, показывает неверный расклад — перерезолвить
        // сразу, не дожидаясь месячного TTL «полных» записей.
        if (e.schema < SeasonEpisodesEntry.CURRENT_SCHEMA) return false
        val ttl = if (e.complete) COMPLETE_TTL_MILLIS else INCOMPLETE_TTL_MILLIS
        return nowMillis - e.resolvedAt <= ttl
    }

    suspend fun put(entry: SeasonEpisodesEntry) {
        ensureLoaded()
        mutex.withLock {
            // Отметка о догоне принадлежит тайтлу, а не конкретному резолву: резолвер и
            // discovery собирают запись с нуля и о ней не знают, а затереть её нулём значит
            // снять паузу и пустить каскад по кругу.
            val kept = _flow.value[entry.animeId]?.lastCatchUpAt ?: 0L
            val merged = if (entry.lastCatchUpAt == 0L) entry.copy(lastCatchUpAt = kept) else entry
            val map = _flow.value.toMutableMap().apply { put(entry.animeId, merged) }
            _flow.value = map
            persist(map)
        }
    }

    /** Отметить, что догон расклада по тайтлу только что запускался (см. [SeasonEpisodesEntry.lastCatchUpAt]). */
    suspend fun markCatchUp(animeId: String, atMillis: Long = System.currentTimeMillis()) {
        ensureLoaded()
        mutex.withLock {
            val entry = _flow.value[animeId] ?: return
            val map = _flow.value.toMutableMap()
                .apply { put(animeId, entry.copy(lastCatchUpAt = atMillis)) }
            _flow.value = map
            persist(map)
        }
    }

    /** Убрать записи тайтлов, которых больше нет в коллекции. */
    suspend fun retainOnly(existingIds: Set<String>) {
        ensureLoaded()
        mutex.withLock {
            val map = _flow.value.filterKeys { it in existingIds }
            if (map.size != _flow.value.size) {
                _flow.value = map
                persist(map)
            }
        }
    }

    private suspend fun persist(map: Map<String, SeasonEpisodesEntry>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(json.encodeToString(serializer, map))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Failed to write season-episodes cache", it) }
    }

    companion object {
        private const val TAG = "SeasonEpisodesStore"
        private const val CACHE_FILE = "season_episodes_cache_v1.json"
        /** Полный расклад завершённых сезонов меняется только с анонсом нового — месяц. */
        const val COMPLETE_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
        /** Неполный/онгоинг — перепроверяем ежедневно, пока не соберём всё. */
        const val INCOMPLETE_TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}
