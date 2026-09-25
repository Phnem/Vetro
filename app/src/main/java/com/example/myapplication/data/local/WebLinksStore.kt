package com.example.myapplication.data.local

import android.content.Context
import com.example.myapplication.domain.enrichment.weblinks.ResolvedWebLink
import com.example.myapplication.domain.enrichment.weblinks.WebLinksEntry
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Файловый кэш прямых ссылок на страницы тайтла по одобренным сайтам ([JsonMapFileStore]) — без
 * миграции схемы SQLDelight. Ключ — animeId.
 * В памяти держим Map в [flow], чтобы UI реактивно показывал найденные иконки.
 */
class WebLinksStore(context: Context) {

    private val filesDir = context.filesDir
    private val store = JsonMapFileStore(File(filesDir, CACHE_FILE), WebLinksEntry.serializer(), TAG)

    @Volatile private var oldCachesCleared = false

    val flow: StateFlow<Map<String, WebLinksEntry>> = store.flow

    suspend fun ensureLoaded() {
        if (!oldCachesCleared) {
            // Чистим кэши прежних версий формата ссылок (иначе старые/неверные ссылки живут TTL).
            withContext(Dispatchers.IO) {
                runCatching { OLD_CACHE_FILES.forEach { File(filesDir, it).delete() } }
            }
            oldCachesCleared = true
        }
        store.ensureLoaded()
    }

    /** Ссылки для выбранного языка (то, что показывает UI). */
    fun linksFor(animeId: String, language: AppLanguage): List<ResolvedWebLink> {
        val e = store[animeId] ?: return emptyList()
        return if (language == AppLanguage.RU) e.ruLinks else e.enLinks
    }

    /** Свежесть по языку: резолвили и не истёк TTL. */
    fun isFresh(animeId: String, language: AppLanguage, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val e = store[animeId] ?: return false
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
        store.updateInMemory { map -> map + (animeId to updatedEntry(map[animeId] ?: WebLinksEntry(animeId = animeId), language, links)) }
    }

    /** Сохранить накопленное на диск, если есть что. */
    suspend fun flush() = store.flush()

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
        store.update { map -> map + (animeId to updatedEntry(map[animeId] ?: WebLinksEntry(animeId = animeId), language, links)) }
    }

    /** Убрать записи для тайтлов, которых больше нет в коллекции. */
    suspend fun retainOnly(existingIds: Set<String>) {
        ensureLoaded()
        store.update { map -> map.filterKeys { it in existingIds } }
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
