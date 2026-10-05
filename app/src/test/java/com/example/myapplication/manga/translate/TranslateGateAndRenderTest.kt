package com.example.myapplication.manga.translate

import com.example.myapplication.manga.translate.pipeline.Box
import com.example.myapplication.manga.translate.pipeline.RegionKind
import com.example.myapplication.manga.translate.pipeline.RgbImage
import com.example.myapplication.manga.translate.pipeline.TextRegion
import com.example.myapplication.manga.translate.render.LayoutPlanner
import com.example.myapplication.manga.translate.render.PaperColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateGateAndRenderTest {

    // ---- подпись ----

    private val official = OfficialBuild.OFFICIAL_SHA256

    @Test
    fun the_author_certificate_is_official_and_any_other_is_not() {
        val ours = official.first()
        assertTrue(OfficialBuild.matches(listOf(ours), official))
        assertTrue(OfficialBuild.matches(listOf(ours.uppercase()), official))
        // Отпечаток F-Droid (любой чужой) - неофициальная сборка.
        assertFalse(OfficialBuild.matches(listOf("0".repeat(64)), official))
        // Один свой и один чужой подписант - тоже нет.
        assertFalse(OfficialBuild.matches(listOf(ours, "1".repeat(64)), official))
        // Нет подписи вовсе - нет доверия.
        assertFalse(OfficialBuild.matches(emptyList(), official))
    }

    @Test
    fun sha256_hex_is_lowercase_and_64_chars() {
        val hex = OfficialBuild.sha256Hex(byteArrayOf(1, 2, 3))
        assertEquals(64, hex.length)
        assertEquals(hex.lowercase(), hex)
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", hex)
    }

    // ---- цвет бумаги ----

    private fun flat(w: Int, h: Int, rgb: Int) = RgbImage(w, h, IntArray(w * h) { rgb })

    @Test
    fun paper_color_ignores_the_text_inside_the_box_and_outliers_outside() {
        val w = 100
        val h = 100
        val pixels = IntArray(w * h) { 0xFAFAFA }
        // Чёрный "текст" внутри рамки и чёрная линия справа вне кольца: на цвет бумаги влиять не должны.
        for (y in 30 until 70) for (x in 30 until 70) pixels[y * w + x] = 0x000000
        for (y in 0 until h) pixels[y * w + 98] = 0x000000
        val paper = PaperColor.around(RgbImage(w, h, pixels), Box(30f, 30f, 70f, 70f))
        assertEquals(0xFAFAFA, paper)
    }

    @Test
    fun paper_color_of_a_dark_background_is_dark_and_luma_tells_so() {
        val paper = PaperColor.around(flat(60, 60, 0x101010), Box(20f, 20f, 40f, 40f))
        assertEquals(0x101010, paper)
        assertTrue(PaperColor.luma(paper) < 40)
        assertTrue(PaperColor.luma(0xFFFFFF) > 250)
    }

    // ---- размещение ----

    @Test
    fun a_bubble_gives_its_text_almost_the_whole_bubble_but_never_leaves_it() {
        val bubble = Box(100f, 100f, 400f, 500f)
        val column = Box(220f, 160f, 280f, 440f)
        val placement = LayoutPlanner.plan(TextRegion(RegionKind.BUBBLE, listOf(column), bubble), 1000, 1500)
        // Место под перевод шире исходной узкой колонки...
        assertTrue(placement.textBox.width > column.width)
        // ...но остаётся внутри облака и всегда покрывает исходный текст.
        assertTrue(placement.textBox.left >= bubble.left && placement.textBox.right <= bubble.right)
        assertTrue(placement.textBox.top >= bubble.top && placement.textBox.bottom <= bubble.bottom)
        assertTrue(column.fractionInside(placement.textBox) > 0.99f)
        assertFalse(placement.outlined)
    }

    @Test
    fun free_text_is_cleared_column_by_column_and_gets_an_outline() {
        val a = Box(100f, 100f, 160f, 300f)
        val b = Box(180f, 105f, 240f, 305f)
        val placement = LayoutPlanner.plan(TextRegion(RegionKind.FREE, listOf(a, b)), 1000, 1500)
        assertEquals(2, placement.clearBoxes.size)
        assertTrue(placement.outlined)
        assertTrue(a.fractionInside(placement.textBox) > 0.99f && b.fractionInside(placement.textBox) > 0.99f)
    }

    @Test
    fun placement_stays_inside_the_page() {
        val edge = Box(0f, 0f, 40f, 80f)
        val placement = LayoutPlanner.plan(TextRegion(RegionKind.FREE, listOf(edge)), 500, 700)
        assertTrue(placement.textBox.left >= 0f && placement.textBox.top >= 0f)
        placement.clearBoxes.forEach { assertTrue(it.left >= 0f && it.top >= 0f) }
    }
}
