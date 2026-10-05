package com.example.myapplication.manga.translate.pipeline

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Страница в виде массива пикселей 0xRRGGBB. Своя форма, а не android.graphics.Bitmap, ради одного:
 * весь конвейер (подготовка входа моделей, разбор их выхода, склейка рамок) проверяется обычными
 * JVM-тестами на настоящих моделях, без эмулятора. Bitmap появляется только на краях - при
 * декодировании страницы и при отрисовке результата.
 */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "empty image" }
        require(pixels.size == width * height) { "pixel count does not match size" }
    }

    fun crop(box: Box): RgbImage {
        val l = floor(box.left).toInt().coerceIn(0, width - 1)
        val t = floor(box.top).toInt().coerceIn(0, height - 1)
        val r = ceil(box.right).toInt().coerceIn(l + 1, width)
        val b = ceil(box.bottom).toInt().coerceIn(t + 1, height)
        val w = r - l
        val h = b - t
        val out = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(pixels, (t + y) * width + l, out, y * w, w)
        return RgbImage(w, h, out)
    }

    /** Яркость 0..255 по Rec.601 - ровно та, что получается при переводе в оттенки серого. */
    fun luma(x: Int, y: Int): Int {
        val p = pixels[y * width + x]
        return ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }

    /**
     * Уменьшение/увеличение с треугольным фильтром, у которого окно растёт вместе с масштабом
     * (так считает PIL.BILINEAR). Обычная билинейная интерполяция при уменьшении в 3-4 раза
     * пропускает пиксели и даёт моделям не ту картинку, на которой они обучались.
     */
    fun resized(newWidth: Int, newHeight: Int): RgbImage {
        if (newWidth == width && newHeight == height) return this
        val horizontal = filterTaps(width, newWidth)
        val tmp = FloatArray(newWidth * height * 3)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until newWidth) {
                val tap = horizontal[x]
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in tap.weights.indices) {
                    val p = pixels[row + tap.start + k]
                    val w = tap.weights[k]
                    r += (p shr 16 and 0xFF) * w
                    g += (p shr 8 and 0xFF) * w
                    b += (p and 0xFF) * w
                }
                val o = (y * newWidth + x) * 3
                tmp[o] = r
                tmp[o + 1] = g
                tmp[o + 2] = b
            }
        }
        val vertical = filterTaps(height, newHeight)
        val out = IntArray(newWidth * newHeight)
        for (y in 0 until newHeight) {
            val tap = vertical[y]
            for (x in 0 until newWidth) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in tap.weights.indices) {
                    val o = ((tap.start + k) * newWidth + x) * 3
                    val w = tap.weights[k]
                    r += tmp[o] * w
                    g += tmp[o + 1] * w
                    b += tmp[o + 2] * w
                }
                out[y * newWidth + x] = (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)
            }
        }
        return RgbImage(newWidth, newHeight, out)
    }

    private class Tap(val start: Int, val weights: FloatArray)

    private fun filterTaps(inSize: Int, outSize: Int): Array<Tap> {
        val scale = inSize.toFloat() / outSize
        val filterScale = max(scale, 1f)
        val support = filterScale // у треугольного фильтра радиус 1
        return Array(outSize) { o ->
            val center = (o + 0.5f) * scale
            val lo = max(0, floor(center - support).toInt())
            val hi = min(inSize, ceil(center + support).toInt())
            val weights = FloatArray(max(1, hi - lo))
            var sum = 0f
            for (i in weights.indices) {
                val w = max(0f, 1f - abs((lo + i + 0.5f - center) / filterScale))
                weights[i] = w
                sum += w
            }
            if (sum > 0f) for (i in weights.indices) weights[i] /= sum else weights[0] = 1f
            Tap(lo.coerceAtMost(inSize - 1), weights)
        }
    }

    private fun clamp8(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)
}
