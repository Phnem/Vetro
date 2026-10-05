package com.example.myapplication.domain.stats

import com.example.myapplication.data.models.Anime
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class CollectionInsightsTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 5) // понедельник
    private val now = today.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * HOUR
    private val day = 24 * HOUR

    private fun ms(date: LocalDate, hour: Int = 12): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli() + hour * HOUR

    private fun anime(
        id: String,
        tags: List<String> = emptyList(),
        episodes: Int = 0,
        rating: Float = 0f,
        addedDaysAgo: Int = 400,
    ) = Anime(
        id = id,
        title = id,
        episodes = episodes,
        rating = rating,
        imageFileName = null,
        orderIndex = 0,
        dateAdded = now - addedDaysAgo * day,
        tags = tags.toImmutableList(),
    )

    // --- heatmap ---

    @Test
    fun `heatmap sums units per day and finds the best day and peak weekday`() {
        val saturday = LocalDate.of(2026, 10, 3)
        val events = listOf(
            ActivityEvent(ms(saturday, 10), ActivityKind.WATCH),
            ActivityEvent(ms(saturday, 20), ActivityKind.WATCH),
            ActivityEvent(ms(saturday, 22), ActivityKind.READ),
            ActivityEvent(ms(LocalDate.of(2026, 10, 1)), ActivityKind.WATCH),
        )
        val map = CollectionInsights.heatmap(events, ActivityFilter.ALL, today, zone)
        assertEquals(2, map.activeDays)
        assertEquals(4, map.totalUnits)
        assertEquals(saturday, map.bestDay?.date)
        assertEquals(3, map.bestDay?.units)
        assertEquals(DayOfWeek.SATURDAY, map.peakWeekday)
    }

    @Test
    fun `heatmap filter keeps only the chosen kind`() {
        val events = listOf(
            ActivityEvent(ms(today), ActivityKind.WATCH),
            ActivityEvent(ms(today), ActivityKind.LISTEN, units = 5),
        )
        assertEquals(5, CollectionInsights.heatmap(events, ActivityFilter.LISTEN, today, zone).totalUnits)
        assertEquals(1, CollectionInsights.heatmap(events, ActivityFilter.WATCH, today, zone).totalUnits)
        assertTrue(CollectionInsights.heatmap(events, ActivityFilter.READ, today, zone).isEmpty)
    }

    @Test
    fun `heatmap is week columns of seven, ends on the current week and hides future cells`() {
        val map = CollectionInsights.heatmap(emptyList(), ActivityFilter.ALL, today, zone, weekCount = 20)
        assertEquals(20, map.weeks.size)
        assertTrue(map.weeks.all { it.size == 7 })
        val last = map.weeks.last()
        assertEquals(DayOfWeek.MONDAY, last.first().date.dayOfWeek)
        assertFalse(last.first().future)
        assertTrue(last[1].future)
    }

    @Test
    fun `heatmap ignores events outside the window and in the future`() {
        val events = listOf(
            ActivityEvent(ms(today.minusWeeks(40)), ActivityKind.WATCH),
            ActivityEvent(ms(today.plusDays(3)), ActivityKind.WATCH),
        )
        assertTrue(CollectionInsights.heatmap(events, ActivityFilter.ALL, today, zone).isEmpty)
    }

    @Test
    fun `heatmap levels scale with the busiest day`() {
        val events = buildList {
            repeat(8) { add(ActivityEvent(ms(today), ActivityKind.WATCH)) }
            repeat(1) { add(ActivityEvent(ms(today.minusDays(7)), ActivityKind.WATCH)) }
            repeat(4) { add(ActivityEvent(ms(today.minusDays(14)), ActivityKind.WATCH)) }
        }
        val cells = CollectionInsights.heatmap(events, ActivityFilter.ALL, today, zone)
            .weeks.flatten().associateBy { it.date }
        assertEquals(4, cells.getValue(today).level)
        assertEquals(1, cells.getValue(today.minusDays(7)).level)
        assertEquals(3, cells.getValue(today.minusDays(14)).level)
    }

    // --- monthly ---

    @Test
    fun `monthly returns twelve ordered months ending in the current one`() {
        val events = listOf(
            ActivityEvent(ms(LocalDate.of(2026, 9, 10)), ActivityKind.WATCH),
            ActivityEvent(ms(LocalDate.of(2026, 9, 20)), ActivityKind.WATCH),
            ActivityEvent(ms(LocalDate.of(2026, 7, 2)), ActivityKind.READ),
        )
        val months = CollectionInsights.monthly(events, ActivityFilter.ALL, today, zone)
        assertEquals(12, months.size)
        assertEquals(YearMonth.of(2026, 10), months.last().month)
        assertEquals(YearMonth.of(2025, 11), months.first().month)
        assertEquals(2, months.first { it.month == YearMonth.of(2026, 9) }.units)
        assertEquals(1, months.first { it.month == YearMonth.of(2026, 7) }.units)
        assertEquals(0, months.last().units)
    }

    // --- listen units / returns ---

    @Test
    fun `listen units round up to twenty five minute blocks`() {
        assertEquals(0, CollectionInsights.listenUnits(0))
        assertEquals(1, CollectionInsights.listenUnits(60_000))
        assertEquals(1, CollectionInsights.listenUnits(25 * 60_000L))
        assertEquals(2, CollectionInsights.listenUnits(25 * 60_000L + 1))
    }

    @Test
    fun `returns count titles with at least one repeat and the repeated items`() {
        val returns = CollectionInsights.returns(
            rewatchedEpisodes = mapOf("a" to listOf(1, 0, 2), "b" to listOf(0, 0), "c" to listOf(1)),
            rereadChapters = mapOf("m" to listOf(0, 3)),
            bookPasses = listOf(0.4, 1.0, 1.6, 2.3),
        )
        assertEquals(2, returns.rewatchTitles)
        assertEquals(3, returns.rewatchEpisodes)
        assertEquals(1, returns.rereadTitles)
        assertEquals(1, returns.rereadChapters)
        assertEquals(2, returns.relistenBooks)
        assertEquals(5, returns.total)
    }

    // --- состояние и жанры ---

    @Test
    fun `rated titles are completed, stale started ones dropped, untouched ones planned`() {
        val rated = anime("rated", episodes = 3, rating = 8f)
        val stale = anime("stale", episodes = 3, addedDaysAgo = 200)
        val fresh = anime("fresh", episodes = 3, addedDaysAgo = 10)
        val planned = anime("planned", addedDaysAgo = 200)
        val none = TitleSignals()
        assertEquals(TitleState.COMPLETED, CollectionInsights.stateOf(rated, none, now))
        assertEquals(TitleState.DROPPED, CollectionInsights.stateOf(stale, none, now))
        assertEquals(TitleState.IN_PROGRESS, CollectionInsights.stateOf(fresh, none, now))
        assertEquals(TitleState.PLANNED, CollectionInsights.stateOf(planned, none, now))
    }

    @Test
    fun `recent playback keeps an old title in progress`() {
        val old = anime("old", episodes = 3, addedDaysAgo = 300)
        val watchedLately = TitleSignals(lastActivityMs = now - 5 * day)
        assertEquals(TitleState.IN_PROGRESS, CollectionInsights.stateOf(old, watchedLately, now))
    }

    @Test
    fun `airing title is completed once the watched count reaches the announced total`() {
        val a = anime("airing", episodes = 12, addedDaysAgo = 10)
        assertEquals(TitleState.COMPLETED, CollectionInsights.stateOf(a, TitleSignals(airingTotal = 12), now))
        assertEquals(TitleState.IN_PROGRESS, CollectionInsights.stateOf(a, TitleSignals(airingTotal = 24), now))
    }

    @Test
    fun `genre quality computes completion drop and return rates over started titles`() {
        val list = buildList {
            repeat(3) { add(anime("done$it", listOf("Drama"), episodes = 5, rating = 7f)) }
            add(anime("dropped", listOf("Drama"), episodes = 2, addedDaysAgo = 300))
            add(anime("planned", listOf("Drama")))
            add(anime("tiny", listOf("Rare"), episodes = 1, rating = 5f))
        }
        val signals = mapOf("done0" to TitleSignals(returns = 2))
        val quality = CollectionInsights.genreQuality(list, signals, now)
        // «Rare» ниже порога выборки, «Drama»: 5 тайтлов, 4 начато
        assertEquals(listOf("Drama"), quality.map { it.tagId })
        val drama = quality.single()
        assertEquals(5, drama.titles)
        assertEquals(4, drama.started)
        assertEquals(0.75f, drama.completionRate, 0.001f)
        assertEquals(0.25f, drama.dropRate, 0.001f)
        assertEquals(1, drama.returned)
    }

    // --- профиль ---

    @Test
    fun `profile shift compares recent genre shares with the whole collection`() {
        val list = buildList {
            repeat(6) { add(anime("old$it", listOf("Action"), episodes = 1, rating = 6f, addedDaysAgo = 300)) }
            repeat(3) { add(anime("new$it", listOf("Romance"), episodes = 1, rating = 9f, addedDaysAgo = 10)) }
            add(anime("newAction", listOf("Action"), episodes = 1, rating = 9f, addedDaysAgo = 5))
        }
        val shift = CollectionInsights.profileShift(list, now)
        assertEquals(4, shift.recentTitles)
        assertEquals(10, shift.totalTitles)
        assertTrue(shift.hasRecent)
        val romance = shift.genres.first { it.tagId == "Romance" }
        assertEquals(0.75f, romance.recentShare, 0.001f)
        assertEquals(0.3f, romance.overallShare, 0.001f)
        assertTrue(romance.delta > 0.4f)
        assertEquals(9.0, shift.recentAvgRating!!, 0.001)
    }

    @Test
    fun `profile shift without enough recent titles reports no comparison`() {
        val list = listOf(anime("a", listOf("Action"), addedDaysAgo = 400), anime("b", listOf("Action"), addedDaysAgo = 10))
        val shift = CollectionInsights.profileShift(list, now)
        assertFalse(shift.hasRecent)
        assertNull(CollectionInsights.profileShift(emptyList(), now).recentAvgRating)
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
