package com.example.myapplication.data.local

import com.example.myapplication.domain.enrichment.weblinks.WebLinksEntry
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLinksBackoffTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun empty_results_back_off_30m_6h_1d_then_a_week() {
        assertEquals(30 * minute, WebLinksStore.emptyResultTtl(1))
        assertEquals(6 * hour, WebLinksStore.emptyResultTtl(2))
        assertEquals(day, WebLinksStore.emptyResultTtl(3))
        assertEquals(7 * day, WebLinksStore.emptyResultTtl(4))
        assertEquals(7 * day, WebLinksStore.emptyResultTtl(40))
    }

    @Test
    fun back_off_never_shrinks() {
        val ttls = (1..6).map(WebLinksStore::emptyResultTtl)
        assertTrue(ttls.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun cache_written_before_streaks_still_reads() {
        // Формат на диске расширен полями с умолчаниями — старый файл читается без миграции.
        val old = """{"a1":{"animeId":"a1","ruLinks":[],"enLinks":[],"ruResolvedAt":5,"enResolvedAt":0}}"""
        val map = Json { ignoreUnknownKeys = true }
            .decodeFromString(MapSerializer(String.serializer(), WebLinksEntry.serializer()), old)
        assertEquals(0, map.getValue("a1").ruEmptyStreak)
        assertEquals(5L, map.getValue("a1").ruResolvedAt)
    }
}
