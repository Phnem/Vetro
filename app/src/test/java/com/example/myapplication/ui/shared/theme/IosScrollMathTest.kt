package com.example.myapplication.ui.shared.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Физика прокрутки: экспонента UIScrollView (δ = 0.998/мс) и асимптотическая «резинка» Apple. */
class IosScrollMathTest {

    @Test
    fun fling_distance_is_velocity_over_decay_rate() {
        // k = −ln(0.998)·1000 ≈ 2.002 с⁻¹ → 3000 px/с проходят ≈ 1499 px.
        assertEquals(1498.5f, IosScroll.flingDistance(3000f), 2f)
    }

    @Test
    fun rubber_band_grows_monotonically_and_never_reaches_viewport() {
        val viewport = 2400f
        var previous = 0f
        for (pull in listOf(10f, 100f, 1000f, 10_000f, 100_000f)) {
            val shown = MotionTokens.rubberBand(pull, viewport)
            assertTrue(shown > previous)
            assertTrue(shown < viewport)
            previous = shown
        }
    }

    @Test
    fun rubber_band_resists_from_the_first_pixel() {
        // Коэффициент Apple 0.55: на малой протяжке видно чуть больше половины пути пальца.
        assertEquals(0.55f, MotionTokens.rubberBand(1f, 2400f), 0.01f)
    }
}
