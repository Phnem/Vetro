package com.example.myapplication.domain.enrichment.title

import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.enrichment.AniLibriaScheduleItem
import com.example.myapplication.network.enrichment.EnrichmentSource
import com.example.myapplication.network.enrichment.TmdbEnrichment
import com.example.myapplication.network.enrichment.TvMazeEpisode
import com.example.myapplication.network.enrichment.TvMazeShow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Трек выхода — для кого выходит серия: русская версия (озвучка или русский оригинал) и английская
 * (английский оригинал). Язык интерфейса выбирает трек; эфир в Японии не заменяет ни один из них.
 */
enum class ReleaseTrack { RU, EN }

/** Выход серии на треке: точный момент или только дата. */
data class ReleasePoint(val at: Instant? = null, val date: LocalDate? = null) {
    /** Только дата — полдень по часовому поясу устройства. */
    fun instant(zone: ZoneId): Instant? = at ?: date?.atTime(NOON)?.atZone(zone)?.toInstant()

    private companion object {
        val NOON: LocalTime = LocalTime.NOON
    }
}

/** Расписание одного трека из одного источника. */
data class TrackSchedule(
    val track: ReleaseTrack,
    val previous: ReleasePoint?,
    val next: ReleasePoint?,
    val nextEpisode: Int?,
    /** Сериал закончен (FINISHED / Ended) — отсчёта нет. */
    val finished: Boolean,
    val source: EnrichmentSource,
)

/** Следующая серия выбранного трека — то, что показывает отсчёт. */
data class NextRelease(
    val track: ReleaseTrack,
    val episode: Int?,
    val at: Instant,
    /** false — источник знал только дату, [at] = 12:00 по поясу устройства. */
    val exactTime: Boolean,
    val source: EnrichmentSource,
)

/**
 * Правила отсчёта «Следующий эпизод через». Чистые функции: всё, что решает, показывать ли секцию,
 * проверяется тестами (ReleaseCountdownTest).
 */
object ReleaseCountdownRules {

    /** Прошлая серия трека должна выйти не раньше чем столько назад. */
    val PREVIOUS_WINDOW: Duration = Duration.ofDays(14)

    fun trackFor(language: AppLanguage): ReleaseTrack = if (language == AppLanguage.RU) ReleaseTrack.RU else ReleaseTrack.EN

    /**
     * Секция есть, только если у трека языка интерфейса: сериал не закончен, следующая серия известна
     * и ещё впереди, прошлая вышла не больше 14 дней назад. Расписания — в порядке точности; решает
     * первое, в котором вообще есть данные о сериях трека, — менее точное его не перекрывает.
     */
    fun select(language: AppLanguage, schedules: List<TrackSchedule>, now: Instant, zone: ZoneId): NextRelease? {
        val track = trackFor(language)
        val schedule = schedules.firstOrNull { it.track == track && (it.next != null || it.previous != null) } ?: return null
        if (schedule.finished) return null
        val next = schedule.next ?: return null
        val nextAt = next.instant(zone) ?: return null
        if (!nextAt.isAfter(now)) return null
        val previousAt = schedule.previous?.instant(zone) ?: return null
        if (previousAt.isAfter(now) || Duration.between(previousAt, now) > PREVIOUS_WINDOW) return null
        return NextRelease(track, schedule.nextEpisode, nextAt, exactTime = next.at != null, schedule.source)
    }

    /**
     * RU-трек аниме по расписанию озвучки AniLibria: день недели выпуска → ближайшая такая дата (без
     * времени). Если серия уже вышла сегодня, следующая — через неделю.
     */
    fun fromAniLibria(item: AniLibriaScheduleItem, today: LocalDate, zone: ZoneId): TrackSchedule {
        val lastDate = item.lastReleasedAt?.atZone(zone)?.toLocalDate()
        val nextDate = item.publishDay?.takeIf { item.ongoing && item.nextEpisode != null }?.let { day ->
            val candidate = today.with(TemporalAdjusters.nextOrSame(day))
            if (candidate == lastDate) candidate.plusWeeks(1) else candidate
        }
        return TrackSchedule(
            track = ReleaseTrack.RU,
            previous = item.lastReleasedAt?.let { ReleasePoint(at = it) },
            next = nextDate?.let { ReleasePoint(date = it) },
            nextEpisode = item.nextEpisode,
            finished = !item.ongoing,
            source = EnrichmentSource.ANILIBRIA,
        )
    }

    /** Эфир оригинала — это трек, только если оригинал на русском или английском. */
    fun fromTvMaze(show: TvMazeShow): TrackSchedule? {
        val track = originalTrack(show.language) ?: return null
        return TrackSchedule(
            track = track,
            previous = show.previous?.point(),
            next = show.next?.point(),
            nextEpisode = show.next?.number,
            finished = show.status.equals("Ended", ignoreCase = true),
            source = EnrichmentSource.TVMAZE,
        )
    }

    fun fromTmdb(tmdb: TmdbEnrichment): TrackSchedule? {
        val track = originalTrack(tmdb.originalLanguage) ?: return null
        return TrackSchedule(
            track = track,
            previous = tmdb.lastEpisode?.airDate?.let { ReleasePoint(date = it) },
            next = tmdb.nextEpisode?.airDate?.let { ReleasePoint(date = it) },
            nextEpisode = tmdb.nextEpisode?.number,
            finished = tmdb.status.equals("Ended", ignoreCase = true) || tmdb.status.equals("Canceled", ignoreCase = true),
            source = EnrichmentSource.TMDB,
        )
    }

    internal fun originalTrack(language: String?): ReleaseTrack? = when (language?.trim()?.lowercase()) {
        "ru", "russian" -> ReleaseTrack.RU
        "en", "english" -> ReleaseTrack.EN
        else -> null
    }

    private fun TvMazeEpisode.point(): ReleasePoint? =
        if (airstamp == null && airdate == null) null else ReleasePoint(at = airstamp, date = airdate)
}

/**
 * «4 дн 16 ч 33 мин 00 сек» / «16 ч 33 мин 00 сек» / «33 мин 00 сек». Секунды округляются вверх:
 * «00 сек» не висит, пока до выхода ещё есть время. null — время вышло, показывать нечего.
 */
fun countdownText(remaining: Duration, ru: Boolean): String? {
    if (remaining.isNegative || remaining.isZero) return null
    val total = (remaining.toMillis() + 999) / 1000
    val d = total / 86_400
    val h = total % 86_400 / 3_600
    val m = total % 3_600 / 60
    val s = total % 60
    val units = if (ru) listOf("дн", "ч", "мин", "сек") else listOf("d", "h", "min", "sec")
    return buildList {
        if (d > 0) add("$d ${units[0]}")
        if (d > 0 || h > 0) add("$h ${units[1]}")
        add("$m ${units[2]}")
        add("%02d ${units[3]}".format(s))
    }.joinToString(" ")
}

/**
 * Тикает раз в секунду по часам устройства до выхода серии; время вышло — возвращается (отсчёт
 * остановлен, вызывающий обновляет расписание). Отрицательных значений [onTick] не получает.
 */
suspend fun runCountdown(
    target: Instant,
    nowMs: () -> Long,
    sleep: suspend (Long) -> Unit,
    onTick: (Duration) -> Unit,
) {
    while (true) {
        val now = nowMs()
        val remaining = Duration.ofMillis(target.toEpochMilli() - now)
        if (remaining.isNegative || remaining.isZero) return
        onTick(remaining)
        // До следующей смены секунды на экране (секунды округлены вверх).
        val toNextSecond = remaining.toMillis() % 1000
        sleep(if (toNextSecond == 0L) 1000L else toNextSecond)
    }
}
