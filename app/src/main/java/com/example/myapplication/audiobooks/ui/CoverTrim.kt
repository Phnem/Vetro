package com.example.myapplication.audiobooks.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter

/** Доли картинки, которые занимают однотонные поля по краям; 0 — поля нет. */
internal data class CoverTrim(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    val isEmpty: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f

    companion object {
        private const val MAX_TRIM = 0.25f
        private const val MIN_TRIM = 0.015f
        /** Сумма дисперсий каналов ARGB в строке/столбце, ниже которой он считается однотонным. */
        private const val FLAT_VARIANCE = 3 * 7 * 7

        /**
         * У части источников обложка приходит с серой/белой/прозрачной каждой кромкой: на весь экран она
         * растягивается с щелью. Ищем такие кромки в уменьшенной копии ([pixels] ARGB, [w]×[h]).
         */
        fun detect(pixels: IntArray, w: Int, h: Int): CoverTrim {
            if (w < 8 || h < 8 || pixels.size < w * h) return CoverTrim()
            fun rowFlat(y: Int) = flat(w) { pixels[y * w + it] }
            fun colFlat(x: Int) = flat(h) { pixels[it * w + x] }
            fun run(limit: Int, isFlat: (Int) -> Boolean, fromEnd: Boolean, size: Int): Float {
                var n = 0
                while (n < limit && isFlat(if (fromEnd) size - 1 - n else n)) n++
                val f = n.toFloat() / size
                return if (f < MIN_TRIM) 0f else f
            }
            val maxY = (h * MAX_TRIM).toInt()
            val maxX = (w * MAX_TRIM).toInt()
            return CoverTrim(
                left = run(maxX, ::colFlat, false, w),
                top = run(maxY, ::rowFlat, false, h),
                right = run(maxX, ::colFlat, true, w),
                bottom = run(maxY, ::rowFlat, true, h),
            )
        }

        private inline fun flat(n: Int, pixel: (Int) -> Int): Boolean {
            var variance = 0.0
            for (shift in intArrayOf(24, 16, 8, 0)) {
                var sum = 0.0
                var sumSq = 0.0
                for (i in 0 until n) {
                    val v = ((pixel(i) ushr shift) and 0xFF).toDouble()
                    sum += v
                    sumSq += v * v
                }
                val mean = sum / n
                variance += sumSq / n - mean * mean
            }
            return variance < FLAT_VARIANCE
        }
    }
}

/** Рисует [delegate] без однотонных кромок [trim]; с `ContentScale.Crop` оставшееся тянется на всю область. */
internal class TrimmedPainter(private val delegate: Painter, private val trim: CoverTrim) : Painter() {
    private val kw = 1f - trim.left - trim.right
    private val kh = 1f - trim.top - trim.bottom

    override val intrinsicSize: Size
        get() {
            val s = delegate.intrinsicSize
            return if (s == Size.Unspecified) s else Size(s.width * kw, s.height * kh)
        }

    override fun DrawScope.onDraw() {
        val fullW = size.width / kw
        val fullH = size.height / kh
        translate(left = -trim.left * fullW, top = -trim.top * fullH) {
            with(delegate) { draw(Size(fullW, fullH)) }
        }
    }
}
