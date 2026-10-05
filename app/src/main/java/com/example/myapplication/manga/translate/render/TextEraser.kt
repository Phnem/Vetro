package com.example.myapplication.manga.translate.render

import com.example.myapplication.manga.translate.pipeline.Box
import com.example.myapplication.manga.translate.pipeline.RgbImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Стирает буквы надписи, лежащей поверх рисунка, не закрашивая весь прямоугольник.
 *
 * У надписи вне облака фона "бумаги" нет: под ней растр, штриховка или лицо героя, и ровная
 * заливка рамки выглядит серым пятном. Поэтому стираются только сами буквы:
 *  1. маска - пиксели внутри рамки, резко отличающиеся от окружающего фона и при этом почти чёрные
 *     (буква) или почти белые (контур буквы);
 *  2. маска раздувается на пару пикселей, чтобы захватить сглаженные края;
 *  3. закрашенное заполняется от краёв к центру средним цветом уже известных соседей - слоями, как
 *     растёт круг на воде. Получается мягкая "заплатка", похожая на окружающий фон.
 * Нейросетевая реконструкция рисунка даёт чище, но стоит ещё 150 МБ и секунды на страницу.
 */
object TextEraser {

    /** Прямоугольник готовых пикселей 0xRRGGBB, который кладётся поверх страницы. */
    class Patch(val left: Int, val top: Int, val width: Int, val height: Int, val pixels: IntArray)

    private const val MAX_ERASED_SHARE = 0.5f
    private const val CONTRAST = 45
    private const val DARK = 90
    private const val LIGHT = 205

    fun erase(image: RgbImage, area: Box, paper: Int): Patch? {
        val l = floor(area.left).toInt().coerceIn(0, image.width - 1)
        val t = floor(area.top).toInt().coerceIn(0, image.height - 1)
        val r = ceil(area.right).toInt().coerceIn(l + 1, image.width)
        val b = ceil(area.bottom).toInt().coerceIn(t + 1, image.height)
        val w = r - l
        val h = b - t
        if (w < 2 || h < 2) return null

        val paperLuma = PaperColor.luma(paper)
        var mask = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val luma = image.luma(l + x, t + y)
                mask[y * w + x] = abs(luma - paperLuma) > CONTRAST && (luma < DARK || luma > LIGHT)
            }
        }
        // Открытие маски (сжать-раздуть), чтобы отсечь тонкие линии рисунка, пробовалось и не
        // годится: у мелких букв штрихи такие же тонкие, и японский текст проглядывал сквозь
        // перевод. Лучше стереть лишнее и при слишком большой площади закрыть плашкой (ниже).
        mask = dilate(mask, w, h, radius = (min(w, h) * 0.04f).toInt().coerceIn(2, 4))
        // Если "буквами" оказалась половина рамки, это не надпись, а тёмный рисунок под ней: стирать
        // нечего без риска стереть сам рисунок - пусть вызывающий закроет надпись плашкой.
        if (mask.count { it } > w * h * MAX_ERASED_SHARE) return null

        val colors = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(image.pixels, (t + y) * image.width + l, colors, y * w, w)
        fillFromNeighbours(colors, mask, w, h, paper)
        relax(colors, mask, w, h)
        return Patch(l, t, w, h, colors)
    }

    private fun dilate(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val horizontal = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var hit = false
                var k = max(0, x - radius)
                val end = min(w - 1, x + radius)
                while (k <= end && !hit) {
                    hit = mask[y * w + k]
                    k++
                }
                horizontal[y * w + x] = hit
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var hit = false
                var k = max(0, y - radius)
                val end = min(h - 1, y + radius)
                while (k <= end && !hit) {
                    hit = horizontal[k * w + x]
                    k++
                }
                out[y * w + x] = hit
            }
        }
        return out
    }

    /** Закрашенные пиксели получают среднее известных соседей, слой за слоем от границы маски внутрь. */
    private fun fillFromNeighbours(colors: IntArray, mask: BooleanArray, w: Int, h: Int, fallback: Int) {
        val known = BooleanArray(w * h) { !mask[it] }
        if (known.none { it }) {
            colors.fill(fallback)
            return
        }
        var frontier = ArrayList<Int>()
        for (i in 0 until w * h) if (!known[i] && hasKnownNeighbour(known, i, w, h)) frontier += i
        while (frontier.isNotEmpty()) {
            val resolved = IntArray(frontier.size)
            for ((n, index) in frontier.withIndex()) resolved[n] = averageOfKnown(colors, known, index, w, h)
            val next = LinkedHashSet<Int>()
            for ((n, index) in frontier.withIndex()) {
                colors[index] = resolved[n]
                known[index] = true
            }
            for (index in frontier) {
                forEachNeighbour(index, w, h) { nb -> if (!known[nb]) next += nb }
            }
            frontier = ArrayList(next)
        }
    }

    /**
     * Первое приближение от слоёв получается "ромбами" (расстояние считается по клеткам). Диффузия
     * по закрашенным пикселям - каждый становится средним четырёх соседей - сглаживает их в
     * гармоническую заплатку, которая плавно стыкуется с окружением (решение уравнения Лапласа с
     * известными краями).
     */
    private fun relax(colors: IntArray, mask: BooleanArray, w: Int, h: Int) {
        val masked = ArrayList<Int>()
        for (i in mask.indices) if (mask[i]) masked += i
        if (masked.isEmpty()) return
        val indices = masked.toIntArray()
        repeat(RELAX_SWEEPS) { sweep ->
            // Прямой и обратный проходы чередуются: информация с краёв заплатки доходит до центра вдвое быстрее.
            val range = if (sweep % 2 == 0) indices.indices else indices.indices.reversed()
            for (n in range) {
                val i = indices[n]
                val x = i % w
                val y = i / w
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                if (x > 0) { val p = colors[i - 1]; r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; count++ }
                if (x < w - 1) { val p = colors[i + 1]; r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; count++ }
                if (y > 0) { val p = colors[i - w]; r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; count++ }
                if (y < h - 1) { val p = colors[i + w]; r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; count++ }
                if (count > 0) colors[i] = ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
    }

    private const val RELAX_SWEEPS = 60

    private fun hasKnownNeighbour(known: BooleanArray, index: Int, w: Int, h: Int): Boolean {
        var found = false
        forEachNeighbour(index, w, h) { if (known[it]) found = true }
        return found
    }

    private fun averageOfKnown(colors: IntArray, known: BooleanArray, index: Int, w: Int, h: Int): Int {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        forEachNeighbour(index, w, h) { nb ->
            if (known[nb]) {
                val p = colors[nb]
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
                n++
            }
        }
        return if (n == 0) colors[index] else ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }

    private inline fun forEachNeighbour(index: Int, w: Int, h: Int, action: (Int) -> Unit) {
        val x = index % w
        val y = index / w
        for (dy in -1..1) {
            val ny = y + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                if (nx < 0 || nx >= w) continue
                action(ny * w + nx)
            }
        }
    }
}
