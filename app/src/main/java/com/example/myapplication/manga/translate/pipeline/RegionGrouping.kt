package com.example.myapplication.manga.translate.pipeline

import kotlinx.serialization.Serializable
import kotlin.math.max

/**
 * Реплика страницы: то, что уходит в перевод одним фрагментом и возвращается в одно место.
 * [textBoxes] - блоки текста (колонки) в порядке чтения; [bubble] - облако, если текст в нём.
 */
@Serializable
data class TextRegion(
    val kind: RegionKind,
    val textBoxes: List<Box>,
    val bubble: Box? = null,
) {
    /** Объединение блоков текста: то, что надо стереть и заново написать. */
    val textBounds: Box get() = textBoxes.reduce(Box::union)
}

@Serializable
enum class RegionKind { BUBBLE, FREE }

/**
 * Собирает из рамок детектора реплики и расставляет их в порядке чтения.
 *
 * Две идеи, которые делают результат пригодным для перевода:
 *  - колонки одной реплики (в облаке их бывает две-три) читаются как ОДИН фрагмент: переводчик-LLM
 *    видит фразу целиком, а не обрывки, и на выходе остаётся одно место для текста;
 *  - порядок чтения даёт контекст: в запросе реплики идут так, как их прочёл бы человек.
 */
object RegionGrouping {

    /** Блок текста относится к облаку, если не меньше этой доли его площади внутри облака. */
    private const val INSIDE_BUBBLE = 0.6f

    /** Колонки свободного текста сливаем только вплотную: разрыв не больше этой доли ширины. */
    private const val FREE_GAP_IN_WIDTHS = 0.25f

    fun group(detections: List<Detection>, rightToLeft: Boolean): List<TextRegion> {
        val bubbles = detections.filter { it.kind == DetectionKind.BUBBLE }.map { it.box }
        val texts = detections.filter { it.kind != DetectionKind.BUBBLE }

        val inBubble = HashMap<Int, MutableList<Box>>()
        val standalone = ArrayList<TextRegion>()
        for (text in texts) {
            val owner = if (text.kind == DetectionKind.BUBBLE_TEXT) smallestContaining(text.box, bubbles) else null
            when {
                owner != null -> inBubble.getOrPut(owner) { ArrayList() } += text.box
                text.kind == DetectionKind.BUBBLE_TEXT -> standalone += TextRegion(RegionKind.BUBBLE, listOf(text.box))
                else -> standalone += TextRegion(RegionKind.FREE, listOf(text.box))
            }
        }

        val regions = ArrayList<TextRegion>()
        for ((index, boxes) in inBubble) {
            regions += TextRegion(RegionKind.BUBBLE, orderColumns(boxes, rightToLeft), bubbles[index])
        }
        regions += mergeAdjacentFree(standalone.filter { it.kind == RegionKind.FREE }, rightToLeft)
        regions += standalone.filter { it.kind == RegionKind.BUBBLE }
        return readingOrder(regions, rightToLeft)
    }

    private fun smallestContaining(text: Box, bubbles: List<Box>): Int? {
        var best: Int? = null
        for ((i, bubble) in bubbles.withIndex()) {
            if (text.fractionInside(bubble) < INSIDE_BUBBLE) continue
            if (best == null || bubble.area < bubbles[best].area) best = i
        }
        return best
    }

    /** Колонки реплики: справа налево для манги, слева направо для вебтунов. */
    private fun orderColumns(boxes: List<Box>, rightToLeft: Boolean): List<Box> {
        // Блоки в одном ряду (перекрываются по вертикали) идут по горизонтали, ряды - сверху вниз.
        val rows = clusterRows(boxes)
        return rows.flatMap { row -> if (rightToLeft) row.sortedByDescending { it.centerX } else row.sortedBy { it.centerX } }
    }

    /**
     * Соседние колонки свободного текста (закадровый голос набирают несколькими колонками) - одна
     * реплика. Склеиваем только вплотную стоящие и примерно одной высоты: две чужие надписи рядом
     * лучше оставить двумя, чем слить в одну бессмыслицу.
     */
    private fun mergeAdjacentFree(free: List<TextRegion>, rightToLeft: Boolean): List<TextRegion> {
        val boxes = free.map { it.textBoxes.single() }
        val parent = IntArray(boxes.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                if (adjacentColumns(boxes[i], boxes[j])) parent[find(i)] = find(j)
            }
        }
        return boxes.indices.groupBy { find(it) }.values.map { members ->
            TextRegion(RegionKind.FREE, orderColumns(members.map { boxes[it] }, rightToLeft))
        }
    }

    private fun adjacentColumns(a: Box, b: Box): Boolean {
        if (a.verticalOverlap(b) < 0.6f) return false
        val taller = max(a.height, b.height)
        val shorter = minOf(a.height, b.height)
        if (shorter < taller * 0.6f) return false
        val gap = max(a.left, b.left) - minOf(a.right, b.right)
        return gap <= FREE_GAP_IN_WIDTHS * max(a.width, b.width)
    }

    /**
     * Порядок чтения: ряды сверху вниз, внутри ряда - по направлению письма. Ряд - это реплики,
     * вертикальные отрезки которых заметно пересекаются; ряд растёт по мере добавления.
     */
    fun readingOrder(regions: List<TextRegion>, rightToLeft: Boolean): List<TextRegion> {
        if (regions.size < 2) return regions
        val rows = ArrayList<MutableList<TextRegion>>()
        val rowBounds = ArrayList<Box>()
        for (region in regions.sortedBy { it.bounds().top }) {
            val box = region.bounds()
            val index = rowBounds.indexOfFirst { it.verticalOverlap(box) >= ROW_OVERLAP }
            if (index >= 0) {
                rows[index] += region
                rowBounds[index] = rowBounds[index].union(box)
            } else {
                rows += mutableListOf(region)
                rowBounds += box
            }
        }
        return rows.flatMap { row ->
            if (rightToLeft) row.sortedByDescending { it.bounds().centerX } else row.sortedBy { it.bounds().centerX }
        }
    }

    private const val ROW_OVERLAP = 0.35f

    private fun TextRegion.bounds(): Box = bubble ?: textBounds

    private fun clusterRows(boxes: List<Box>): List<List<Box>> {
        val rows = ArrayList<MutableList<Box>>()
        val bounds = ArrayList<Box>()
        for (box in boxes.sortedBy { it.top }) {
            val index = bounds.indexOfFirst { it.verticalOverlap(box) >= ROW_OVERLAP }
            if (index >= 0) {
                rows[index] += box
                bounds[index] = bounds[index].union(box)
            } else {
                rows += mutableListOf(box)
                bounds += box
            }
        }
        return rows
    }
}
