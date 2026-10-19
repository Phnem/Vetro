package com.example.myapplication.ui.shared.back

import com.example.myapplication.data.local.DevPreferencesKeys
import com.example.myapplication.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Числа анимации «назад как в extera»: границы кривых, порог жеста и позы страниц. */
class ExteraBackMathTest {

    private val w = 1000f
    private val h = 2000f
    private val dp = 3f

    @Test
    fun flag_is_on_by_default_and_can_be_turned_off() {
        assertTrue(SettingsUiState().devExteraBack)
        assertFalse(SettingsUiState(devExteraBack = false).devExteraBack)
        assertEquals("dev_extera_back", DevPreferencesKeys.EXTERA_BACK.name)
    }

    @Test
    fun adjust_ignores_start_threshold_and_saturates_at_half_the_gesture() {
        assertEquals(0f, ExteraBackSpec.adjust(0f), 0f)
        assertEquals(0f, ExteraBackSpec.adjust(0.015f), 1e-6f)
        // Интенсивность 2: ровно половина хода системы доводит анимацию до конца.
        assertEquals(1f, ExteraBackSpec.adjust(0.5075f), 1e-4f)
        assertEquals(1f, ExteraBackSpec.adjust(1f), 0f)
    }

    @Test
    fun easings_hit_their_endpoints_and_stay_monotonic() {
        for (easing in listOf(ExteraBackSpec.Gesture, ExteraBackSpec.Emphasized, ExteraBackSpec.EaseOutQuint)) {
            assertEquals(0f, easing.transform(0f), 1e-4f)
            assertEquals(1f, easing.transform(1f), 1e-4f)
            var prev = 0f
            for (i in 1..100) {
                val v = easing.transform(i / 100f)
                assertTrue("кривая убывает на $i%", v >= prev - 1e-4f)
                prev = v
            }
        }
    }

    @Test
    fun emphasized_is_front_loaded_and_passes_the_joint_of_its_two_segments() {
        // Стык сегментов createEmphasizedInterpolator: (0.166666, 0.4).
        assertEquals(0.4f, ExteraBackSpec.Emphasized.transform(0.166666f), 1e-3f)
        // Четверть времени — уже три четверти пути (Material 3 emphasized), 40% — свыше 90%.
        assertEquals(0.77f, ExteraBackSpec.Emphasized.transform(0.25f), 0.01f)
        assertTrue(ExteraBackSpec.Emphasized.transform(0.4f) > 0.9f)
    }

    @Test
    fun drag_at_rest_is_identity() {
        val closing = ExteraPose()
        val entering = ExteraPose()
        ExteraBackMath.drag(closing, entering, w, h, dp, fromLeft = true, adj = 0f, dy = 500f)
        assertEquals(0f, closing.left, 1e-3f)
        assertEquals(0f, closing.top, 1e-3f)
        assertEquals(w, closing.width, 1e-3f)
        assertEquals(h, closing.height, 1e-3f)
        assertEquals(0f, closing.corner, 1e-3f)
    }

    @Test
    fun drag_at_full_progress_shrinks_to_085_and_pins_to_the_right_edge() {
        val closing = ExteraPose()
        val entering = ExteraPose()
        ExteraBackMath.drag(closing, entering, w, h, dp, fromLeft = true, adj = 1f, dy = 0f)
        assertEquals(w * 0.85f, closing.width, 1e-2f)
        assertEquals(h * 0.85f, closing.height, 1e-2f)
        // Правый край — на 8dp от границы.
        assertEquals(w - 8f * dp, closing.left + closing.width, 1e-2f)
        assertEquals(40f * dp, closing.corner, 1e-3f)
        // Нижняя страница стартует левее нуля и тоже сжимается.
        assertTrue(entering.left < 0f)
        assertTrue(entering.width < w * 0.95f)
    }

    @Test
    fun finger_offset_is_zero_without_shrink_and_follows_the_finger_with_it() {
        assertEquals(0f, ExteraBackMath.yOffset(h, h, 400f, dp), 0f)
        val down = ExteraBackMath.yOffset(h, h * 0.85f, 400f, dp)
        val up = ExteraBackMath.yOffset(h, h * 0.85f, -400f, dp)
        assertTrue(down > 0f)
        assertEquals(-down, up, 1e-3f)
        // Не больше половины «лишней» высоты за вычетом отступа.
        assertTrue(down <= (h - h * 0.85f) / 2f)
    }

    @Test
    fun commit_ends_full_size_faded_and_without_scrim() {
        val from = ExteraPose()
        val entering = ExteraPose()
        ExteraBackMath.drag(from, entering, w, h, dp, fromLeft = true, adj = 1f, dy = 0f)
        val out = ExteraPose()
        ExteraBackMath.commit(out, from, w, h, dp, t = 1f, closing = true)
        assertEquals(w, out.width, 1e-3f)
        assertEquals(h, out.height, 1e-3f)
        assertEquals(0f, out.alpha, 0f)
        assertEquals(0f, out.scrim, 0f)
        assertEquals(0f, out.corner, 1e-3f)
        // Страница гаснет за первую пятую времени.
        ExteraBackMath.commit(out, from, w, h, dp, t = 0.2f, closing = true)
        assertEquals(0f, out.alpha, 1e-6f)

        ExteraBackMath.commit(out, entering, w, h, dp, t = 1f, closing = false)
        assertEquals(0f, out.left, 1e-3f)
        assertEquals(w, out.width, 1e-3f)
        assertEquals(1f, out.alpha, 0f)
    }
}
