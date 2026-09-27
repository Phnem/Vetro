package com.example.myapplication.network.enrichment

import java.time.DayOfWeek
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Живой ответ `/anime/schedule/week` AniLibria (26.09.2026), урезанный до четырёх релизов. */
class AniLibriaScheduleParserTest {

    private val items = AniLibriaScheduleParser.week(
        javaClass.classLoader!!.getResource("enrichment/anilibria_schedule_week.json")!!.readText(),
    ).associateBy { it.releaseId }

    @Test
    fun `ongoing dub with a known next episode`() {
        val liarGame = items.getValue(10187)
        assertEquals(62331, liarGame.shikimoriId)
        assertEquals(DayOfWeek.MONDAY, liarGame.publishDay)
        assertEquals(26, liarGame.nextEpisode)
        assertEquals(25, liarGame.lastEpisode)
        // fresh_at релиза, а не updated_at серии: тот меняется и при правке старой серии.
        assertEquals(Instant.parse("2026-09-15T15:56:40Z"), liarGame.lastReleasedAt)
    }

    @Test
    fun `finished season and stopped releases`() {
        assertNull("сезон озвучен целиком — следующей нет", items.getValue(10171).nextEpisode)
        assertFalse("Bleach не выходит", items.getValue(8452).ongoing)
        assertEquals(DayOfWeek.SUNDAY, items.getValue(8452).publishDay)
    }
}
