package com.example.myapplication.updates

import com.example.myapplication.domain.BackgroundSchedule
import android.os.SystemClock
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.domain.seasons.SeasonCatchUp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
 *
 * За найденными сериями сразу догоняется расклад сезонов ([SeasonCatchUp]): уведомление «вышла
 * 192-я» и список серий на 191 — противоречие, которое пользователь закрывал кнопкой «Найти ещё».
 * Раньше догон запускал только фоновый воркер, а проверка при входе на главную — нет, хотя
 * стопку уведомлений в приложении показывает именно она.
 */
class EpisodeUpdateCheckCoordinator(
    private val animeCheck: BatchEpisodeCheckUseCase,
    private val seriesCheck: SeriesEpisodeCheckUseCase,
    private val throttle: EpisodeCheckThrottle = EpisodeCheckThrottle(),
    private val seasonCatchUp: SeasonCatchUp? = null,
    private val appScope: CoroutineScope? = null,
) {
    private val mutex = Mutex()

    /**
     * @param catchUpSeasons догнать расклад сезонов найденных тайтлов в фоне ([appScope]).
     *   Воркер передаёт false и догоняет сам, дожидаясь результата: пуш должен открывать уже
     *   полный список.
     */
    suspend fun detectAndStore(
        language: AppLanguage,
        force: Boolean = false,
        catchUpSeasons: Boolean = true,
    ): List<AnimeUpdate> {
        val found = mutex.withLock {
            if (!throttle.shouldRun(force)) return@withLock emptyList()
            val detected = animeCheck.detectAndStore(language) + seriesCheck.detectAndStore()
            throttle.markCompleted()
            detected
        }
        val catchUp = seasonCatchUp
        if (catchUpSeasons && catchUp != null && found.isNotEmpty()) {
            appScope?.launch { runCatching { catchUp.catchUpAll(found.map { it.animeId }) } }
        }
        return found
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
        const val DEFAULT_INTERVAL_MS = BackgroundSchedule.EPISODE_CHECK_MIN_INTERVAL_MS
    }
}
