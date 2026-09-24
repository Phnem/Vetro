package com.example.myapplication.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каскад проявления строк меню ТТМ.
 *
 * Правило простое, но ломается незаметно: если строки начнут проявляться раньше, чем оболочка
 * способна их вместить, текст окажется нарезан краем растущей панели, а если каскад пойдёт
 * сверху вниз — меню перестанет читаться как выросшее ИЗ кнопки.
 */
class TtmMenuRevealTest {

    @Test
    fun `nothing is revealed while the shell is still small`() {
        // Пока оболочка меньше порога, строк нет вовсе — вмещать их ей некуда.
        (0..4).forEach { index ->
            assertEquals(
                "строка $index проявилась слишком рано",
                0f,
                rowReveal(shell = 0.3f, indexFromBottom = index),
                0.0001f,
            )
        }
    }

    @Test
    fun `bottom row leads the cascade`() {
        // Нижняя строка ближе к кнопке, значит появляется первой. Обратный порядок означал бы,
        // что меню растёт откуда-то сверху, а не из своего источника.
        val shell = 0.6f
        val bottom = rowReveal(shell, indexFromBottom = 0)
        val middle = rowReveal(shell, indexFromBottom = 2)
        val top = rowReveal(shell, indexFromBottom = 4)

        assertTrue("нижняя строка отстаёт от средней", bottom >= middle)
        assertTrue("средняя строка отстаёт от верхней", middle >= top)
    }

    @Test
    fun `every row is fully revealed once the shell is open`() {
        (0..4).forEach { index ->
            assertEquals(
                "строка $index не доехала до конца",
                1f,
                rowReveal(shell = 1f, indexFromBottom = index),
                0.0001f,
            )
        }
    }

    @Test
    fun `reveal never leaves the zero to one range`() {
        listOf(-1f, 0f, 0.5f, 1f, 2f).forEach { shell ->
            (0..4).forEach { index ->
                val value = rowReveal(shell, index)
                assertTrue("shell=$shell index=$index -> $value", value in 0f..1f)
            }
        }
    }
}
