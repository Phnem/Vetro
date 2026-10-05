package com.example.myapplication.manga.translate.render

import com.example.myapplication.manga.translate.pipeline.Box
import com.example.myapplication.manga.translate.pipeline.RgbImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Цвет "бумаги" вокруг блока текста: им замазывается оригинальная надпись. Берётся медиана по
 * каждому каналу в кольце СНАРУЖИ рамки - буквы и их контур остаются внутри рамки, а выбросы
 * (край облака, линия кадра) медиана отбрасывает. В облаках это почти всегда белый.
 */
object PaperColor {

    private const val MAX_SAMPLES = 3_000

    /** Цвет в виде 0xRRGGBB; белый, если вокруг нечего измерить. */
    fun around(image: RgbImage, box: Box): Int {
        val ring = (minOf(box.width, box.height) * 0.12f).toInt().coerceIn(3, 14).toFloat()
        val outer = box.expand(ring, ring).clampTo(image.width, image.height)
        val ol = floor(outer.left).toInt()
        val ot = floor(outer.top).toInt()
        val or = ceil(outer.right).toInt().coerceAtMost(image.width)
        val ob = ceil(outer.bottom).toInt().coerceAtMost(image.height)
        val il = floor(box.left).toInt()
        val it = floor(box.top).toInt()
        val ir = ceil(box.right).toInt()
        val ib = ceil(box.bottom).toInt()

        val area = max(1, (or - ol) * (ob - ot))
        val stride = max(1, sqrt(area / MAX_SAMPLES.toDouble()).toInt())
        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        var y = ot
        while (y < ob) {
            var x = ol
            while (x < or) {
                val inside = x in il until ir && y in it until ib
                if (!inside) {
                    val p = image.pixels[y * image.width + x]
                    reds += p shr 16 and 0xFF
                    greens += p shr 8 and 0xFF
                    blues += p and 0xFF
                }
                x += stride
            }
            y += stride
        }
        if (reds.isEmpty()) return 0xFFFFFF
        return (median(reds) shl 16) or (median(greens) shl 8) or median(blues)
    }

    /** Яркость 0..255 цвета 0xRRGGBB. */
    fun luma(rgb: Int): Int = ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000

    private fun median(values: MutableList<Int>): Int {
        values.sort()
        return values[values.size / 2]
    }
}
