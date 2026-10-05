package com.example.myapplication.domain.stats

import com.example.myapplication.data.models.Anime
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

// ==========================================
// Аналитика коллекции: активность по дням и месяцам, возвраты, жанровое «качество»
// (досмотр / бросание / возвраты) и сдвиг вкуса за последние 90 дней.
// Всё — чистые функции над уже собранными сигналами (см. CollectionInsightsLoader).
// ==========================================

enum class ActivityKind {
    /** Досмотренная серия / фильм. */
    WATCH,

    /** Прочитанная глава. */
    READ,

    /** Сессия прослушивания аудиокниги. */
    LISTEN,

    /** Тайтл добавлен в коллекцию — слабый след для тех, кто ведёт прогресс вручную. */
    ADDED,
}

/** Один след активности. [units] — «вес» в сопоставимых единицах (серия ≈ глава ≈ 25 минут). */
data class ActivityEvent(
    val atMs: Long,
    val kind: ActivityKind,
    val units: Int = 1,
)

/** Фильтр тепловой карты и графика по месяцам. */
enum class ActivityFilter(val kinds: Set<ActivityKind>) {
    ALL(ActivityKind.entries.toSet()),
    WATCH(setOf(ActivityKind.WATCH)),
    READ(setOf(ActivityKind.READ)),
    LISTEN(setOf(ActivityKind.LISTEN)),
}

data class HeatmapCell(
    val date: LocalDate,
    val units: Int,
    /** 0 — пусто, 1..4 — насыщенность. */
    val level: Int,
    /** Ячейка в будущем (хвост текущей недели) — не рисуется. */
    val future: Boolean,
)

data class ActivityHeatmap(
    /** Колонки-недели слева направо; в каждой семь ячеек Пн..Вс. */
    val weeks: List<List<HeatmapCell>>,
    val activeDays: Int,
    val totalUnits: Int,
    val bestDay: HeatmapCell?,
    /** Самый «тяжёлый» день недели по сумме за окно; null, если активности нет. */
    val peakWeekday: DayOfWeek?,
) {
    val isEmpty: Boolean get() = totalUnits == 0
}

data class MonthBucket(val month: YearMonth, val units: Int)

/** Сколько возвратов к уже законченному: пересмотры, перечитывания, повторные прослушивания. */
data class ReturnsSummary(
    val rewatchTitles: Int = 0,
    val rewatchEpisodes: Int = 0,
    val rereadTitles: Int = 0,
    val rereadChapters: Int = 0,
    val relistenBooks: Int = 0,
) {
    val total: Int get() = rewatchTitles + rereadTitles + relistenBooks
}

/** Что известно о тайтле помимо самой записи коллекции. */
data class TitleSignals(
    /** Последняя активность (просмотр / чтение), 0 — следов нет. */
    val lastActivityMs: Long = 0L,
    /** Сколько раз к тайтлу возвращались (пересмотр серий, перечитывание глав). */
    val returns: Int = 0,
    /** Заявленное число серий выходящего сезона. */
    val airingTotal: Int? = null,
)

enum class TitleState { PLANNED, IN_PROGRESS, COMPLETED, DROPPED }

data class GenreQuality(
    val tagId: String,
    val titles: Int,
    val started: Int,
    val completed: Int,
    val dropped: Int,
    val returned: Int,
) {
    /** Доля досмотренного среди начатого, 0..1. */
    val completionRate: Float get() = if (started > 0) completed.toFloat() / started else 0f
    val dropRate: Float get() = if (started > 0) dropped.toFloat() / started else 0f
    val returnRate: Float get() = if (started > 0) returned.toFloat() / started else 0f
}

data class GenreShare(
    val tagId: String,
    /** Доля тайтлов жанра во всей коллекции, 0..1. */
    val overallShare: Float,
    /** Доля тайтлов жанра среди добавленного за последнее окно, 0..1. */
    val recentShare: Float,
    val recentCount: Int,
) {
    val delta: Float get() = recentShare - overallShare
}

data class ProfileShift(
    val windowDays: Int,
    val recentTitles: Int,
    val totalTitles: Int,
    val genres: List<GenreShare>,
    val recentAvgRating: Double?,
    val overallAvgRating: Double?,
) {
    val hasRecent: Boolean get() = recentTitles >= MIN_RECENT_TITLES

    companion object {
        const val MIN_RECENT_TITLES = 3
    }
}

object CollectionInsights {

    /** Одна «единица» прослушивания ≈ серия аниме. */
    const val LISTEN_UNIT_MS = 25L * 60_000L

    /** Без активности дольше этого срока недосмотренное считается брошенным. */
    const val DROP_AFTER_DAYS = 90

    const val PROFILE_WINDOW_DAYS = 90

    /** Книга считается переслушанной, когда суммарное прослушивание ≥ 1.6 длины (с запасом на паузы и пропуски). */
    const val RELISTEN_MIN_PASSES = 1.6

    private const val DAY_MS = 86_400_000L

    fun listenUnits(listenedMs: Long): Int =
        if (listenedMs <= 0L) 0 else ((listenedMs + LISTEN_UNIT_MS - 1) / LISTEN_UNIT_MS).toInt()

    // --- Тепловая карта -----------------------------------------------------------------

    fun heatmap(
        events: List<ActivityEvent>,
        filter: ActivityFilter,
        today: LocalDate,
        zone: ZoneId,
        weekCount: Int = 20,
    ): ActivityHeatmap {
        val lastMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val firstMonday = lastMonday.minusWeeks((weekCount - 1).toLong())
        val perDay = HashMap<LocalDate, Int>()
        for (e in events) {
            if (e.kind !in filter.kinds || e.units <= 0) continue
            val day = Instant.ofEpochMilli(e.atMs).atZone(zone).toLocalDate()
            if (day < firstMonday || day > today) continue
            perDay.merge(day, e.units, Int::plus)
        }
        val maxUnits = perDay.values.maxOrNull() ?: 0

        fun levelOf(units: Int): Int = when {
            units <= 0 -> 0
            maxUnits <= 1 -> 4
            units * 4 >= maxUnits * 3 -> 4
            units * 2 >= maxUnits -> 3
            units * 4 >= maxUnits -> 2
            else -> 1
        }

        val weeks = (0 until weekCount).map { w ->
            val monday = firstMonday.plusWeeks(w.toLong())
            (0 until 7).map { d ->
                val date = monday.plusDays(d.toLong())
                val units = perDay[date] ?: 0
                HeatmapCell(date, units, levelOf(units), future = date > today)
            }
        }
        val best = perDay.entries.maxWithOrNull(compareBy({ it.value }, { it.key }))
            ?.let { HeatmapCell(it.key, it.value, levelOf(it.value), future = false) }
        val peak = perDay.entries
            .groupBy({ it.key.dayOfWeek }, { it.value })
            .mapValues { (_, v) -> v.sum() }
            .maxByOrNull { it.value }
            ?.key
        return ActivityHeatmap(
            weeks = weeks,
            activeDays = perDay.size,
            totalUnits = perDay.values.sum(),
            bestDay = best,
            peakWeekday = peak,
        )
    }

    // --- Динамика по месяцам ------------------------------------------------------------

    fun monthly(
        events: List<ActivityEvent>,
        filter: ActivityFilter,
        today: LocalDate,
        zone: ZoneId,
        monthCount: Int = 12,
    ): List<MonthBucket> {
        val last = YearMonth.from(today)
        val months = (monthCount - 1 downTo 0).map { last.minusMonths(it.toLong()) }
        val perMonth = HashMap<YearMonth, Int>()
        for (e in events) {
            if (e.kind !in filter.kinds || e.units <= 0) continue
            val ym = YearMonth.from(Instant.ofEpochMilli(e.atMs).atZone(zone))
            perMonth.merge(ym, e.units, Int::plus)
        }
        return months.map { MonthBucket(it, perMonth[it] ?: 0) }
    }

    // --- Возвраты -----------------------------------------------------------------------

    /**
     * [rewatchedEpisodes] / [rereadChapters] — по тайтлам: сколько повторов зафиксировано у каждой
     * серии / главы. [bookPasses] — по книгам: сколько раз прослушано полное время книги.
     */
    fun returns(
        rewatchedEpisodes: Map<String, List<Int>>,
        rereadChapters: Map<String, List<Int>>,
        bookPasses: Collection<Double>,
    ): ReturnsSummary {
        fun count(map: Map<String, List<Int>>): Pair<Int, Int> {
            var titles = 0
            var items = 0
            for (counts in map.values) {
                val repeated = counts.count { it > 0 }
                if (repeated > 0) {
                    titles++
                    items += repeated
                }
            }
            return titles to items
        }
        val (rwTitles, rwEpisodes) = count(rewatchedEpisodes)
        val (rrTitles, rrChapters) = count(rereadChapters)
        return ReturnsSummary(
            rewatchTitles = rwTitles,
            rewatchEpisodes = rwEpisodes,
            rereadTitles = rrTitles,
            rereadChapters = rrChapters,
            relistenBooks = bookPasses.count { it >= RELISTEN_MIN_PASSES },
        )
    }

    // --- Состояние тайтла и качество жанров ---------------------------------------------

    /**
     * Эвристика, потому что явного статуса в коллекции нет:
     * оценённое или досмотренное до заявленного конца — завершено; начатое и заброшенное
     * дольше [DROP_AFTER_DAYS] — брошено; ни прогресса, ни следов — в планах.
     */
    fun stateOf(anime: Anime, signals: TitleSignals, nowMs: Long): TitleState {
        val total = signals.airingTotal
        val finishedByCount = total != null && total > 0 && anime.episodes >= total
        if (anime.rating > 0f || finishedByCount) return TitleState.COMPLETED
        val started = anime.episodes > 0 || signals.lastActivityMs > 0L
        if (!started) return TitleState.PLANNED
        val lastTouch = maxOf(signals.lastActivityMs, anime.dateAdded)
        val idleDays = (nowMs - lastTouch) / DAY_MS
        return if (idleDays >= DROP_AFTER_DAYS) TitleState.DROPPED else TitleState.IN_PROGRESS
    }

    private class GenreAcc {
        var titles = 0
        var started = 0
        var completed = 0
        var dropped = 0
        var returned = 0
    }

    fun genreQuality(
        animeList: List<Anime>,
        signals: Map<String, TitleSignals>,
        nowMs: Long,
        limit: Int = 6,
    ): List<GenreQuality> {
        if (animeList.isEmpty()) return emptyList()
        val minTitles = if (animeList.size <= 50) 3 else 5
        val acc = LinkedHashMap<String, GenreAcc>()
        for (a in animeList) {
            val s = signals[a.id] ?: TitleSignals()
            val state = stateOf(a, s, nowMs)
            for (tag in a.tags.distinct()) {
                val g = acc.getOrPut(tag) { GenreAcc() }
                g.titles++
                if (state != TitleState.PLANNED) g.started++
                if (state == TitleState.COMPLETED) g.completed++
                if (state == TitleState.DROPPED) g.dropped++
                if (s.returns > 0) g.returned++
            }
        }
        return acc.entries
            .filter { it.value.titles >= minTitles && it.value.started > 0 }
            .map { (tag, g) -> GenreQuality(tag, g.titles, g.started, g.completed, g.dropped, g.returned) }
            .sortedWith(compareByDescending<GenreQuality> { it.titles }.thenBy { it.tagId })
            .take(limit)
    }

    // --- Профиль сейчас vs вся коллекция ------------------------------------------------

    fun profileShift(
        animeList: List<Anime>,
        nowMs: Long,
        windowDays: Int = PROFILE_WINDOW_DAYS,
        limit: Int = 5,
    ): ProfileShift {
        val since = nowMs - windowDays * DAY_MS
        val recent = animeList.filter { it.dateAdded >= since }
        fun avgRated(list: List<Anime>): Double? =
            list.filter { it.rating > 0f }.map { it.rating.toDouble() }.average().takeIf { !it.isNaN() }

        val total = animeList.size
        val overallCounts = animeList.flatMap { it.tags.distinct() }.groupingBy { it }.eachCount()
        val recentCounts = recent.flatMap { it.tags.distinct() }.groupingBy { it }.eachCount()
        val genres = recentCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { (tag, n) ->
                GenreShare(
                    tagId = tag,
                    overallShare = if (total > 0) (overallCounts[tag] ?: 0).toFloat() / total else 0f,
                    recentShare = if (recent.isNotEmpty()) n.toFloat() / recent.size else 0f,
                    recentCount = n,
                )
            }
        return ProfileShift(
            windowDays = windowDays,
            recentTitles = recent.size,
            totalTitles = total,
            genres = genres,
            recentAvgRating = avgRated(recent),
            overallAvgRating = avgRated(animeList),
        )
    }
}
