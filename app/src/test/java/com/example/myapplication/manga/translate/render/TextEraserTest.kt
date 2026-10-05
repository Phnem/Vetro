package com.example.myapplication.manga.translate.render

import com.example.myapplication.manga.translate.pipeline.Box
import com.example.myapplication.manga.translate.pipeline.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TextEraserTest {

    private fun image(w: Int, h: Int, background: Int, paint: (IntArray, Int) -> Unit = { _, _ -> }): RgbImage {
        val pixels = IntArray(w * h) { background }
        paint(pixels, w)
        return RgbImage(w, h, pixels)
    }

    private fun channel(rgb: Int) = rgb and 0xFF

    @Test
    fun a_thin_black_stroke_on_grey_is_replaced_by_the_grey_around_it() {
        val img = image(120, 120, 0x808080) { px, w ->
            for (y in 40 until 80) for (x in 58 until 62) px[y * w + x] = 0x000000
        }
        val patch = TextEraser.erase(img, Box(30f, 30f, 90f, 90f), paper = 0x808080)
        assertNotNull(patch)
        patch!!
        // Вся полоса стёрта (с запасом от раздувания маски): серый вместо чёрного.
        for (y in 40 until 80) {
            val c = channel(patch.pixels[(y - patch.top) * patch.width + (60 - patch.left)])
            assertTrue("y=$y c=$c", abs(c - 0x80) <= 6)
        }
    }

    @Test
    fun pixels_far_from_the_text_keep_their_color() {
        val img = image(120, 120, 0x808080) { px, w ->
            for (y in 40 until 80) for (x in 58 until 62) px[y * w + x] = 0x000000
        }
        val patch = TextEraser.erase(img, Box(30f, 30f, 90f, 90f), paper = 0x808080)!!
        val corner = patch.pixels[0]
        assertEquals(0x808080, corner)
    }

    @Test
    fun white_outline_around_black_letters_on_grey_is_erased_too() {
        val img = image(120, 120, 0x808080) { px, w ->
            for (y in 40 until 80) for (x in 54 until 66) px[y * w + x] = 0xFFFFFF // контур
            for (y in 42 until 78) for (x in 58 until 62) px[y * w + x] = 0x000000 // буква
        }
        val patch = TextEraser.erase(img, Box(30f, 30f, 90f, 90f), paper = 0x808080)!!
        val c = channel(patch.pixels[(60 - patch.top) * patch.width + (56 - patch.left)])
        assertTrue("outline left over: $c", abs(c - 0x80) <= 8)
    }

    @Test
    fun a_mostly_dark_box_is_not_erased_so_the_caller_can_use_a_plate() {
        val img = image(100, 100, 0x050505)
        // Бумага "светлая" по оценке, а рамка почти вся тёмная: это рисунок, а не буквы.
        assertNull(TextEraser.erase(img, Box(10f, 10f, 90f, 90f), paper = 0xE0E0E0))
    }

    @Test
    fun a_degenerate_area_gives_nothing() {
        val img = image(50, 50, 0xFFFFFF)
        assertNull(TextEraser.erase(img, Box(10f, 10f, 10.5f, 10.5f), paper = 0xFFFFFF))
    }
}
