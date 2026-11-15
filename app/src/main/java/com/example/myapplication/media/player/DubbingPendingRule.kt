package com.example.myapplication.media.player

import com.example.myapplication.domain.enrichment.title.ReleaseObservation
import com.example.myapplication.media.source.VetroVideo
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * «Студии ещё не успели озвучить серию». Показывается вместо повторной плашки «источник не
 * отвечает»: если озвучек у серии мало, а сама серия вышла только что, молчание источников —
 * это скорее отсутствие озвучки, чем поломка.
 */
internal object DubbingPendingRule {
    /** Озвучек строго меньше этого числа. */
    const val STUDIOS_BELOW = 4

    /** Серия вышла не раньше, чем столько календарных дней назад (включительно). */
    const val MAX_AGE_DAYS = 2L

    /** Сколько разных студий в наборе ссылок серии; ссылки без названия студии не считаются. */
    fun studioCount(candidates: List<VetroVideo>): Int =
        candidates.mapNotNull { it.sourceName?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
            .distinct()
            .size

    /** Когда приложение узнало о выходе серии [episode]; null — в журнале этой серии нет. */
    fun releasedAtMs(observations: List<ReleaseObservation>, episode: Int): Long? =
        observations.firstOrNull { it.episode == episode }?.atMs

    fun applies(
        studioCount: Int,
        releasedAtMs: Long?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (releasedAtMs == null || studioCount >= STUDIOS_BELOW) return false
        val released = Instant.ofEpochMilli(releasedAtMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(released, today) <= MAX_AGE_DAYS
    }
}
