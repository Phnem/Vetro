package com.example.myapplication.audiobooks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverTrimTest {
    private val size = 100
    private val gray = 0xFF9A9A9A.toInt()

    /** Пёстрая картинка: каждая клетка своего цвета, так что ни строка, ни столбец не однотонны. */
    private fun art(): IntArray = IntArray(size * size) { i ->
        val x = i % size
        val y = i / size
        0xFF000000.toInt() or (((x * 37 + y * 11) and 0xFF) shl 16) or (((x * 5 + y * 53) and 0xFF) shl 8) or ((x * 91 + y * 7) and 0xFF)
    }

    @Test
    fun fullBleedCoverIsNotTrimmed() {
        assertTrue(CoverTrim.detect(art(), size, size).isEmpty)
    }

    @Test
    fun grayBandAtTheBottomIsFound() {
        val px = art()
        for (y in 90 until size) for (x in 0 until size) px[y * size + x] = gray
        val t = CoverTrim.detect(px, size, size)
        assertEquals(0.10f, t.bottom, 0.001f)
        assertEquals(0f, t.top, 0f)
        assertEquals(0f, t.left, 0f)
    }

    @Test
    fun trimIsCapped() {
        val px = IntArray(size * size) { gray }
        val t = CoverTrim.detect(px, size, size)
        assertEquals(0.25f, t.top, 0.001f)
    }
}
