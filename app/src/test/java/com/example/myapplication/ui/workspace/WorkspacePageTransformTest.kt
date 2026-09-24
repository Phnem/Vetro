package com.example.myapplication.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Таблица значений «наезда» (TICKET-03).
 *
 * Знак смещения решает роль страницы: положительное (страница справа от центра) — наезжающая,
 * едет за пальцем один в один и лежит сверху; отрицательное — уходящая под неё, углубляется и
 * параллаксит. Само смещение считает [workspacePageOffset].
 */
class WorkspacePageTransformTest {

    private val eps = 0.0001f

    @Test
    fun `settled page is untouched`() {
        val t = workspacePageTransform(0f)
        assertEquals(1f, t.scale, eps)
        assertEquals(0f, t.translationXFraction, eps)
        assertEquals(0f, t.dimAlpha, eps)
        assertEquals(0f, t.cornerDp, eps)
        assertEquals(0f, t.shadowAlpha, eps)
    }

    @Test
    fun `incoming page keeps full travel and no depth`() {
        listOf(0.25f, 0.5f, 1f).forEach { offset ->
            val t = workspacePageTransform(offset)
            assertEquals("scale at $offset", 1f, t.scale, eps)
            // Ноль добавки: страница едет ровно столько, сколько её сдвинул пейджер.
            assertEquals("travel at $offset", 0f, t.translationXFraction, eps)
            assertEquals("dim at $offset", 0f, t.dimAlpha, eps)
        }
    }

    @Test
    fun `incoming page casts a shadow on its leading edge while it moves`() {
        assertEquals(0f, workspacePageTransform(0f).shadowAlpha, eps)
        assertTrue(workspacePageTransform(0.5f).shadowAlpha > 0f)
        // Соседняя страница в покое за правым краем: её тень не должна лечь на текущую.
        assertEquals(0f, workspacePageTransform(1f).shadowAlpha, eps)
        assertTrue(
            workspacePageTransform(0.8f).shadowAlpha >= workspacePageTransform(0.5f).shadowAlpha,
        )
    }

    @Test
    fun `outgoing page sinks - scales down, dims and rounds its corners`() {
        val half = workspacePageTransform(-0.5f)
        val full = workspacePageTransform(-1f)

        assertEquals(0.97f, half.scale, eps)
        assertEquals(0.94f, full.scale, eps)
        assertTrue(half.dimAlpha > 0f && half.dimAlpha < full.dimAlpha)
        assertEquals(8f, half.cornerDp, eps)
        assertEquals(16f, full.cornerDp, eps)
        assertEquals(0f, full.shadowAlpha, eps)
    }

    @Test
    fun `outgoing page travels a quarter of the way`() {
        // Пейджер уже сдвинул её на offset ширины; добавка гасит три четверти этого хода,
        // и на экране остаётся ровно 25 % (решение D8).
        listOf(-0.25f, -0.5f, -1f).forEach { offset ->
            val t = workspacePageTransform(offset)
            val netTravel = offset + t.translationXFraction
            assertEquals("net travel at $offset", offset * 0.25f, netTravel, eps)
        }
    }

    @Test
    fun `offsets beyond a full page are clamped`() {
        assertEquals(workspacePageTransform(-1f).scale, workspacePageTransform(-2.4f).scale, eps)
        assertEquals(workspacePageTransform(1f).scale, workspacePageTransform(3f).scale, eps)
        assertEquals(
            workspacePageTransform(-1f).cornerDp,
            workspacePageTransform(-9f).cornerDp,
            eps,
        )
    }

    @Test
    fun `transform is continuous through zero`() {
        val justBefore = workspacePageTransform(-0.001f)
        val justAfter = workspacePageTransform(0.001f)
        assertEquals(justBefore.scale, justAfter.scale, 0.001f)
        assertEquals(justBefore.dimAlpha, justAfter.dimAlpha, 0.001f)
        assertEquals(justBefore.cornerDp, justAfter.cornerDp, 0.05f)
        assertEquals(
            justBefore.translationXFraction,
            justAfter.translationXFraction,
            0.001f,
        )
    }
}
