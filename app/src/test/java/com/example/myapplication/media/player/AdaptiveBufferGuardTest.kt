package com.example.myapplication.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBufferGuardTest {

    private val min = 60_000
    private val max = 90_000
    private val mib = 1L shl 20

    private fun bytesPerSecond(mbit: Double) = (mbit * 1_000_000 / 8).toLong()

    @Test
    fun slow_networks_never_turn_the_guard_on() {
        // 0,5–2 Мбит/с: 90 с = 5,6–22,5 МБ — влезает даже в маленький бюджет.
        for (mbit in listOf(0.5, 1.0, 2.0)) {
            for (budget in listOf(32L, 64L, 128L)) {
                assertNull(
                    "$mbit Мбит/с, бюджет $budget МиБ",
                    AdaptiveBufferGuard.capMs(bytesPerSecond(mbit), budget * mib, min, max),
                )
            }
        }
    }

    @Test
    fun heavy_stream_is_capped_only_when_it_does_not_fit() {
        val rate = bytesPerSecond(8.0) // 90 с = 90 МБ
        assertNull(AdaptiveBufferGuard.capMs(rate, 128 * mib, min, max))
        val cap = AdaptiveBufferGuard.capMs(rate, 77 * mib, min, max)!!
        assertTrue(cap in min until max)
        assertEquals(77 * mib * 1_000 / rate, cap.toLong())
    }

    @Test
    fun cap_never_goes_below_min_buffer() {
        // 20 Мбит/с в 32 МиБ — это 13 с, но страховка от проседаний важнее: держим минимум.
        assertEquals(min, AdaptiveBufferGuard.capMs(bytesPerSecond(20.0), 32 * mib, min, max))
    }

    @Test
    fun unknown_rate_leaves_buffer_untouched() {
        assertNull(AdaptiveBufferGuard.capMs(0L, 32 * mib, min, max))
        assertEquals(0L, AdaptiveBufferGuard.declaredBytesPerSecond(listOf(-1, 0)))
        assertEquals(0L, AdaptiveBufferGuard.measuredBytesPerSecond(10 * mib, 1_000_000L))
    }

    @Test
    fun declared_rate_sums_selected_tracks() {
        assertEquals(1_000_000L, AdaptiveBufferGuard.declaredBytesPerSecond(listOf(7_872_000, 128_000, -1)))
    }

    @Test
    fun budget_is_heap_share_bounded_by_real_free_memory() {
        val heap = 256 * mib
        // Куча почти пустая — бюджет = 30 % кучи.
        assertEquals((heap * 0.30).toLong(), AdaptiveBufferGuard.budgetBytes(heap, 40 * mib, 0, false))
        // Сам буфер в занятом не считается.
        assertEquals(
            AdaptiveBufferGuard.budgetBytes(heap, 40 * mib, 0, false),
            AdaptiveBufferGuard.budgetBytes(heap, 100 * mib, 60 * mib, false),
        )
        // Куча забита — не больше половины реально свободного.
        assertEquals(28 * mib, AdaptiveBufferGuard.budgetBytes(heap, 200 * mib, 0, false))
        // Слабое устройство — меньшая доля.
        assertEquals((heap * 0.20).toLong(), AdaptiveBufferGuard.budgetBytes(heap, 0, 0, true))
    }

    @Test
    fun hold_has_hysteresis_between_cap_and_min() {
        val hold = BufferHold()
        val cap = 75_000_000L
        val minUs = 60_000_000L
        assertTrue(hold.allowLoading(70_000_000L, cap, minUs))
        assertFalse(hold.allowLoading(75_000_000L, cap, minUs))
        // Проигрываем буфер: до нижней границы сеть не трогаем.
        assertFalse(hold.allowLoading(65_000_000L, cap, minUs))
        assertTrue(hold.allowLoading(59_000_000L, cap, minUs))
        // Потолок снят — грузим как обычно.
        assertFalse(hold.allowLoading(80_000_000L, cap, minUs))
        assertTrue(hold.allowLoading(80_000_000L, null, minUs))
    }
}
