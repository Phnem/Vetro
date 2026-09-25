package com.example.myapplication.updates

import android.os.SystemClock
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One serialized collection workflow for all episode providers sharing anime_update.
 *
 * Проверку запускают и главная (при входе), и периодический воркер. Раньше ничто не мешало им
 * идти одновременно — два каскада AniList → MAL → поиск по всей коллекции параллельно, с двойной
 * записью в anime_update. Теперь проверки идут строго по одной, а повтор в течение
 * [EpisodeCheckThrottle.DEFAULT_INTERVAL_MS] после успешной пропускается: всё найденное уже в БД.
 * Ручное обновление ([force]) троттлинг обходит.
 */
class EpisodeUpdateCheckCoordinator(
    private val animeCheck: BatchEpisodeCheckUseCase,
    private val seriesCheck: SeriesEpisodeCheckUseCase,
    private val throttle: EpisodeCheckThrottle = EpisodeCheckThrottle(),
) {
    private val mutex = Mutex()

    suspend fun detectAndStore(language: AppLanguage, force: Boolean = false): List<AnimeUpdate> =
        mutex.withLock {
            if (!throttle.shouldRun(force)) return@withLock emptyList()
            val found = animeCheck.detectAndStore(language) + seriesCheck.detectAndStore()
            throttle.markCompleted()
            found
        }
}

/** Когда проверку серий можно не повторять: недавно уже была успешная. */
class EpisodeCheckThrottle(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastCompletedAt: Long? = null

    fun shouldRun(force: Boolean): Boolean {
        if (force) return true
        val last = lastCompletedAt ?: return true
        return nowMs() - last >= intervalMs
    }

    fun markCompleted() {
        lastCompletedAt = nowMs()
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 30 * 60 * 1000L
    }
}
