package com.example.myapplication.data.local

import com.example.myapplication.network.AppJson
import android.content.Context
import android.util.Log
import com.example.myapplication.domain.enrichment.weblinks.ResolvedWebLink
import com.example.myapplication.domain.enrichment.weblinks.WebLinksEntry
import com.example.myapplication.network.AppLanguage
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
 * Файловый кэш прямых ссылок на страницы тайтла по одобренным сайтам (filesDir, atomic rename),
 * по образцу [RecommendationCacheStore] — без миграции схемы SQLDelight. Ключ — animeId.
 * В памяти держим Map в [flow], чтобы UI реактивно показывал найденные иконки.
 */
class WebLinksStore(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val json = AppJson
    private val mutex = Mutex()
    @Volatile private var loaded = false
    /** В памяти есть результаты, ещё не записанные на диск (см. [putLinksInMemory]). */
    private var dirty = false

    private val _flow = MutableStateFlow<Map<String, WebLinksEntry>>(emptyMap())
    val flow: StateFlow<Map<String, WebLinksEntry>> = _flow.asStateFlow()

    private val serializer = MapSerializer(String.serializer(), WebLinksEntry.serializer())

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val map = withContext(Dispatchers.IO) {
                // Чистим кэши прежних версий формата ссылок (иначе старые/неверные ссылки живут TTL).
                runCatching { OLD_CACHE_FILES.forEach { File(file.parentFile, it).delete() } }
                runCatching {
                    if (!file.exists()) emptyMap()
                    else json.decodeFromString(serializer, file.readText())
                }.getOrElse {
                    Log.w(TAG, "Failed to read web-links cache", it)
                    emptyMap()
                }
            }
            _flow.value = map
            loaded = true
        }
    }

    /** Ссылки для выбранного языка (то, что показывает UI). */
    fun linksFor(animeId: String, language: AppLanguage): List<ResolvedWebLink> {
        val e = _flow.value[animeId] ?: return emptyList()
        return if (language == AppLanguage.RU) e.ruLinks else e.enLinks
    }

    /** Свежесть по языку: резолвили и не истёк TTL. */
    fun isFresh(animeId: String, language: AppLanguage, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val e = _flow.value[animeId] ?: return false
        val at = if (language == AppLanguage.RU) e.ruResolvedAt else e.enResolvedAt
        val links = if (language == AppLanguage.RU) e.ruLinks else e.enLinks
        val streak = if (language == AppLanguage.RU) e.ruEmptyStreak else e.enEmptyStreak
        val ttl = when {
            links.isEmpty() -> emptyResultTtl(streak)
            links.size < HEALTHY_RESULT_SIZE -> PARTIAL_RESULT_TTL_MILLIS
            else -> TTL_MILLIS
        }
        return at > 0 && nowMillis - at <= ttl
    }

    /**
     * Записать результат резолва в память — UI видит иконки сразу. На диск — [flush], один раз
     * на пачку: раньше каждый тайтл заново кодировал и переписывал весь файл кэша.
     */
    suspend fun putLinksInMemory(animeId: String, language: AppLanguage, links: List<ResolvedWebLink>) {
        ensureLoaded()
        mutex.withLock {
            val map = _flow.value.toMutableMap()
            map[animeId] = updatedEntry(map[animeId] ?: WebLinksEntry(animeId = animeId), language, links)
            _flow.value = map
            dirty = true
        }
    }

    /** Сохранить накопленное на диск, если есть что. */
    suspend fun flush() {
        mutex.withLock {
            if (!dirty) return
            persist(_flow.value)
            dirty = false
        }
    }

    private fun updatedEntry(
        prev: WebLinksEntry,
        language: AppLanguage,
        links: List<ResolvedWebLink>,
    ): WebLinksEntry {
        val now = System.currentTimeMillis()
        return if (language == AppLanguage.RU) {
            prev.copy(
                ruLinks = links,
                ruResolvedAt = now,
                ruEmptyStreak = if (links.isEmpty()) prev.ruEmptyStreak + 1 else 0,
            )
        } else {
            prev.copy(
                enLinks = links,
                enResolvedAt = now,
                enEmptyStreak = if (links.isEmpty()) prev.enEmptyStreak + 1 else 0,
            )
        }
    }

    /** Записать результат резолва для одного языка (пустой список тоже сохраняем — метка «проверено»). */
    suspend fun putLinks(animeId: String, language: AppLanguage, links: List<ResolvedWebLink>) {
        ensureLoaded()
        mutex.withLock {
            val map = _flow.value.toMutableMap()
            map[animeId] = updatedEntry(map[animeId] ?: WebLinksEntry(animeId = animeId), language, links)
            _flow.value = map
            persist(map)
            dirty = false
        }
    }

    /** Убрать записи для тайтлов, которых больше нет в коллекции. */
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

    private suspend fun persist(map: Map<String, WebLinksEntry>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(json.encodeToString(serializer, map))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Failed to write web-links cache", it) }
    }

    companion object {
        private const val TAG = "WebLinksStore"
        // v8: use case дошёл до multi-alias резолва (до 3 вариантов названия) + резолвер сканирует до
        // 10 кандидатов на совпадение (раньше — 1). Большинство тайтлов в старом кэше (v7 и раньше)
        // резолвились ДО этих изменений одним запросом и первым же кандидатом — застряли на TTL 14
        // дней с меньшим числом источников, чем нашёл бы текущий код. Сброс форсирует полный re-resolve.
        private const val CACHE_FILE = "web_links_cache_v9.json"
        private val OLD_CACHE_FILES = listOf(
            "web_links_cache.json", "web_links_cache_v2.json", "web_links_cache_v3.json",
            "web_links_cache_v4.json", "web_links_cache_v5.json", "web_links_cache_v6.json",
            "web_links_cache_v7.json", "web_links_cache_v8.json",
        )
        /** 14 дней — каталоги сайтов меняются медленно; новый тайтл догоним по TTL. */
        const val TTL_MILLIS = 14L * 24 * 60 * 60 * 1000
        private const val HEALTHY_RESULT_SIZE = 2
        private const val PARTIAL_RESULT_TTL_MILLIS = 2L * 60 * 60 * 1000
        private const val MINUTE = 60L * 1000
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        /**
         * Пустой результат — не «навсегда», но и не каждые 30 минут: тайтла может просто не быть
         * на этих сайтах. 1-я пустая попытка → повтор через 30 мин, 2-я → 6 ч, 3-я → сутки,
         * дальше — неделя. Любая найденная ссылка сбрасывает счётчик.
         */
        internal fun emptyResultTtl(streak: Int): Long = when {
            streak <= 1 -> 30 * MINUTE
            streak == 2 -> 6 * HOUR
            streak == 3 -> DAY
            else -> 7 * DAY
        }
    }
}
