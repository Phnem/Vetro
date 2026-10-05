package com.example.myapplication.domain.enrichment.title

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class ReleaseCadenceTest {

    private val zone = ZoneId.of("UTC")

    private fun obs(episode: Int, date: LocalDate, exact: Boolean = false) =
        ReleaseObservation(episode, date.atTime(14, 0).atZone(zone).toInstant().toEpochMilli(), exact)

    // 2026-09-01 — вторник
    private val tue1 = LocalDate.of(2026, 9, 1)

    @Test
    fun `first two episodes on the same weekday set the rhythm`() {
        val today = tue1.plusDays(9) // четверг
        val forecast = ReleaseCadence.infer(
            listOf(obs(1, tue1), obs(2, tue1.plusWeeks(1))),
            today, zone,
        )!!
        assertEquals(DayOfWeek.TUESDAY, forecast.weekday)
        assertEquals(3, forecast.nextEpisode)
        assertEquals(tue1.plusWeeks(2), forecast.nextDate)
        assertEquals(2, forecast.lastEpisode)
    }

    @Test
    fun `when episode 2 differs from 1 the third decides`() {
        // 1-я — понедельник (премьера), 2-я и 3-я — вторники
        val monday = tue1.minusDays(1)
        val today = tue1.plusWeeks(1).plusDays(2)
        val forecast = ReleaseCadence.infer(
            listOf(obs(1, monday), obs(2, tue1), obs(3, tue1.plusWeeks(1))),
            today, zone,
        )!!
        assertEquals(DayOfWeek.TUESDAY, forecast.weekday)
        assertEquals(4, forecast.nextEpisode)
        assertEquals(tue1.plusWeeks(2), forecast.nextDate)
    }

    @Test
    fun `no two neighbours share a weekday - no forecast`() {
        val today = tue1.plusWeeks(2)
        assertNull(
            ReleaseCadence.infer(
                listOf(obs(1, tue1), obs(2, tue1.plusDays(8)), obs(3, tue1.plusDays(17))),
                today, zone,
            ),
        )
    }

    @Test
    fun `a single episode is not enough`() {
        assertNull(ReleaseCadence.infer(listOf(obs(1, tue1)), tue1.plusDays(2), zone))
        assertNull(ReleaseCadence.infer(emptyList(), tue1, zone))
    }

    @Test
    fun `non adjacent episodes do not form a pair`() {
        assertNull(ReleaseCadence.infer(listOf(obs(1, tue1), obs(3, tue1.plusWeeks(1))), tue1.plusWeeks(1), zone))
    }

    @Test
    fun `the latest matching pair wins after the rhythm changed`() {
        // первые серии по вторникам, потом перенос на пятницы
        val fri = tue1.plusWeeks(2).plusDays(3)
        val observations = listOf(
            obs(1, tue1), obs(2, tue1.plusWeeks(1)), obs(3, tue1.plusWeeks(2)),
            obs(4, fri), obs(5, fri.plusWeeks(1)),
        )
        val forecast = ReleaseCadence.infer(observations, fri.plusWeeks(1).plusDays(1), zone)!!
        assertEquals(DayOfWeek.FRIDAY, forecast.weekday)
        assertEquals(fri.plusWeeks(2), forecast.nextDate)
    }

    @Test
    fun `a delayed episode rolls the forecast to the coming weekday, not into the past`() {
        val lastTue = tue1.plusWeeks(1)
        val today = lastTue.plusWeeks(1).plusDays(2) // должна была быть позавчера, но серии нет
        val forecast = ReleaseCadence.infer(listOf(obs(1, tue1), obs(2, lastTue)), today, zone)!!
        assertEquals(lastTue.plusWeeks(2), forecast.nextDate)
    }

    @Test
    fun `a stalled title gives no forecast`() {
        val forecast = ReleaseCadence.infer(
            listOf(obs(1, tue1), obs(2, tue1.plusWeeks(1))),
            tue1.plusWeeks(1).plusDays(ReleaseCadence.STALE_AFTER_DAYS + 1),
            zone,
        )
        assertNull(forecast)
    }

    @Test
    fun `an exact observation replaces an approximate one for the same episode`() {
        val approximate = obs(2, tue1.plusWeeks(1).plusDays(1))
        val exact = obs(2, tue1.plusWeeks(1), exact = true)
        val merged = ReleaseCadence.merge(listOf(approximate, exact))
        assertEquals(1, merged.size)
        assertEquals(true, merged.single().exact)
        assertEquals(exact.atMs, merged.single().atMs)
    }
}
