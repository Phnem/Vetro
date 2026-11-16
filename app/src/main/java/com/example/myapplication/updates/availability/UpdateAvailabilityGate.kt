package com.example.myapplication.updates.availability

import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.media.player.DubbingPendingRule
import java.time.ZoneId

/** Итог мини-аудита серии: сколько студий озвучки доступно и когда серия вышла (null — неизвестно). */
data class EpisodeAudit(val studioCount: Int, val releasedAtMs: Long?)

/** Метки «серию придержали, последний аудит был тогда-то»; шов, чтобы фильтр проверялся без файлов. */
interface HeldChecks {
    suspend fun lastCheckedMs(animeId: String, episodes: Int): Long?
    suspend fun markChecked(animeId: String, episodes: Int, atMs: Long)
    suspend fun release(animeId: String, episodes: Int)
}

/**
 * «Порог доступности»: придерживает уведомление о новой серии, пока её нельзя нормально смотреть.
 *
 * Серия придерживается, пока выполняются ОБА условия: озвучек меньше [DubbingPendingRule.STUDIOS_BELOW]
 * и с выхода прошло не больше [DubbingPendingRule.MAX_AGE_DAYS] дней. Как только нарушено хотя бы одно —
 * уведомление уходит. Пока серия придерживается, повторный аудит делается не чаще раза в [RECHECK_MS].
 *
 * Придержанное в ленту не попадает вовсе: фильтр стоит ДО публикации, поэтому следующая проверка
 * находит серию заново (её никто не подтвердил) и снова спрашивает фильтр. Отдельного «хранилища
 * отложенных» с повторной отправкой не нужно, нужны лишь метки времени последнего аудита.
 */
class UpdateAvailabilityGate(
    private val enabled: suspend () -> Boolean,
    private val held: HeldChecks,
    /** null — аудит не удался; серию тогда не придерживаем: лучше лишнее уведомление, чем пропавшее. */
    private val audit: suspend (AnimeUpdate) -> EpisodeAudit?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun filter(updates: List<AnimeUpdate>): List<AnimeUpdate> {
        if (updates.isEmpty() || !enabled()) return updates
        val now = clock()
        val passed = ArrayList<AnimeUpdate>(updates.size)
        for (update in updates) {
            val lastCheck = held.lastCheckedMs(update.animeId, update.newEpisodes)
            if (lastCheck != null && now - lastCheck < RECHECK_MS) continue
            val result = audit(update)
            val tooEarly = result != null &&
                DubbingPendingRule.applies(result.studioCount, result.releasedAtMs, now, zone())
            if (tooEarly) {
                held.markChecked(update.animeId, update.newEpisodes, now)
            } else {
                held.release(update.animeId, update.newEpisodes)
                passed += update
            }
        }
        return passed
    }

    companion object {
        /** Как часто перепроверять придержанную серию. */
        const val RECHECK_MS = 6L * 60 * 60 * 1000
    }
}
