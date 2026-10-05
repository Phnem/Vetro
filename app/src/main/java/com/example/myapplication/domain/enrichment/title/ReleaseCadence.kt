package com.example.myapplication.domain.enrichment.title

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.Serializable

/** Серия [episode] стала известна/вышла в [atMs]. [exact] — момент выхода от источника, а не время проверки. */
@Serializable
data class ReleaseObservation(
    val episode: Int,
    val atMs: Long,
    val exact: Boolean = false,
)

/** Прогноз следующей серии по ритму уже вышедших. Только дата: времени суток ритм не знает. */
data class CadenceForecast(
    val weekday: DayOfWeek,
    val lastEpisode: Int,
    val lastReleaseDate: LocalDate,
    val nextEpisode: Int,
    val nextDate: LocalDate,
)

/**
 * Ритм выхода по дням недели — для озвучки, у которой источник не говорит, когда выйдет следующая
 * серия (нет в расписании AniLibria, нет дня выпуска).
 *
 * Правило: берём день недели 1-й и 2-й серий; не совпали — смотрим 3-ю против 2-й; совпали (скажем,
 * оба раза вторник) — это ритм, и следующая серия в следующий вторник. Обобщение для тайтла, за
 * которым начали следить не с первой серии: берётся ПОСЛЕДНЯЯ пара соседних серий с одним днём
 * недели через 5–9 дней. Позднее правило не ломается, если ритм сменился посреди сезона.
 */
object ReleaseCadence {

    /** Между серией и серией ритма — неделя с допуском на сдвиг времени и перерыв в выходных данных. */
    private const val MIN_GAP_DAYS = 5L
    private const val MAX_GAP_DAYS = 9L

    /** Последняя известная серия старше этого — тайтл на паузе/закончился, прогнозировать нечего. */
    const val STALE_AFTER_DAYS = 28L

    fun infer(observations: List<ReleaseObservation>, today: LocalDate, zone: ZoneId): CadenceForecast? {
        val byEpisode = merge(observations).sortedBy { it.episode }
        if (byEpisode.size < 2) return null
        val dated = byEpisode.map { it.episode to Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }

        var weekday: DayOfWeek? = null
        for (i in dated.indices.reversed()) {
            if (i == 0) break
            val (epPrev, datePrev) = dated[i - 1]
            val (epCur, dateCur) = dated[i]
            if (epCur != epPrev + 1) continue
            val gap = ChronoUnit.DAYS.between(datePrev, dateCur)
            if (gap in MIN_GAP_DAYS..MAX_GAP_DAYS && datePrev.dayOfWeek == dateCur.dayOfWeek) {
                weekday = dateCur.dayOfWeek
                break
            }
        }
        val rhythm = weekday ?: return null

        val (lastEpisode, lastDate) = dated.last()
        if (ChronoUnit.DAYS.between(lastDate, today) > STALE_AFTER_DAYS) return null

        // Следующая такая дата строго после последней серии; если она уже в прошлом (серия задержалась),
        // обещать её задним числом нельзя — берётся ближайшая от сегодня.
        var next = lastDate.with(TemporalAdjusters.next(rhythm))
        if (next.isBefore(today)) next = today.with(TemporalAdjusters.nextOrSame(rhythm))
        return CadenceForecast(rhythm, lastEpisode, lastDate, lastEpisode + 1, next)
    }

    /** Одна запись на серию: ранняя побеждает, но точное время от источника вытесняет время проверки. */
    fun merge(observations: List<ReleaseObservation>): List<ReleaseObservation> =
        observations.groupBy { it.episode }.map { (_, group) ->
            val exact = group.filter { it.exact }
            (exact.ifEmpty { group }).minBy { it.atMs }
        }
}
