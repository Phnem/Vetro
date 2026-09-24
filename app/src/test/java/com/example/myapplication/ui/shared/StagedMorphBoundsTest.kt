package com.example.myapplication.ui.shared

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Геометрия морфа «кнопка → панель».
 *
 * Здесь проверяется ровно одно, зато то, что ломалось незаметно: прогресс пружины НЕ подрезается
 * сверху. Пружина раскрытия недодемпфирована и обязана перелетать цель. Пока перелёт срезался по
 * единице, на экране от него оставался только возврат — панель замирала раскрытой, разово
 * поджималась и раскрывалась снова. Со стороны это выглядело не упругостью, а подвисшим кадром.
 */
class StagedMorphBoundsTest {

    /** Кнопка внизу справа — гнездо дока. */
    private val origin = Rect(left = 300f, top = 900f, right = 344f, bottom = 944f)

    /** Панель во всю ширину у нижней кромки. */
    private val target = Rect(left = 0f, top = 400f, right = 400f, bottom = 1000f)

    @Test
    fun `direct path starts exactly at the button`() {
        val bounds = stagedMorphBounds(0f, origin, target, MorphPath.DIRECT)
        assertEquals(origin, bounds)
    }

    @Test
    fun `direct path lands exactly on the target`() {
        val bounds = stagedMorphBounds(1f, origin, target, MorphPath.DIRECT)
        assertEquals(target, bounds)
    }

    @Test
    fun `overshoot goes past the target instead of freezing on it`() {
        val atTarget = stagedMorphBounds(1f, origin, target, MorphPath.DIRECT)
        val overshot = stagedMorphBounds(1.05f, origin, target, MorphPath.DIRECT)

        assertTrue(
            "перелёт срезан: габариты на 1.05 совпали с целью",
            overshot.width > atTarget.width,
        )
        // Панель у нижней кромки: перелёт по высоте обязан поднять её верх ВЫШЕ конечного.
        assertTrue("верхняя кромка не ушла за цель", overshot.top < atTarget.top)
    }

    @Test
    fun `staged path also overshoots - the tail of phase two is not clipped`() {
        val atTarget = stagedMorphBounds(1f, origin, target, MorphPath.STAGED)
        val overshot = stagedMorphBounds(1.07f, origin, target, MorphPath.STAGED)

        assertTrue(overshot.width > atTarget.width)
        assertTrue(overshot.height > atTarget.height)
    }

    @Test
    fun `negative progress is still clamped - there is nothing before the button`() {
        // Снизу подрезаем: пружина стартует из покоя и ниже нуля не уходит, а вот случайный
        // отрицательный прогресс отправил бы панель в зазеркалье за кнопкой.
        assertEquals(origin, stagedMorphBounds(-0.4f, origin, target, MorphPath.DIRECT))
    }

    @Test
    fun `without a measured button the panel is simply at its place`() {
        // Не из чего выходить — значит и морфа нет: показываем конечную раскладку, а не точку.
        assertEquals(target, stagedMorphBounds(0f, null, target, MorphPath.DIRECT))
        assertEquals(target, stagedMorphBounds(0.5f, null, target, MorphPath.STAGED))
    }

    @Test
    fun `staged path collects into an intermediate form before it expands`() {
        // Суть двухфазности: на полпути панель УЖЕ не кнопка, но ЕЩЁ заметно меньше цели.
        val hub = stagedMorphBounds(0.46f, origin, target, MorphPath.STAGED)

        assertTrue("промежуточная форма не выросла из кнопки", hub.width > origin.width)
        assertTrue("промежуточная форма доросла до цели раньше срока", hub.width < target.width)
        assertTrue(hub.height > origin.height)
        assertTrue(hub.height < target.height)
    }

    @Test
    fun `direct path never pauses on the way - width grows monotonically`() {
        // То, ради чего меню ушло с двухфазного пути: одно непрерывное движение вверх, без
        // остановки на промежуточной форме.
        var previous = -1f
        var p = 0f
        while (p <= 1f) {
            val width = stagedMorphBounds(p, origin, target, MorphPath.DIRECT).width
            assertTrue("ширина просела на прогрессе $p", width > previous)
            previous = width
            p += 0.05f
        }
    }
}
