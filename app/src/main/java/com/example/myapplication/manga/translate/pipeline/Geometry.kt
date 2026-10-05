package com.example.myapplication.manga.translate.pipeline

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** Прямоугольник в пикселях страницы; right/bottom не входят в него. */
@Serializable
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun intersection(other: Box): Box? {
        val l = max(left, other.left)
        val t = max(top, other.top)
        val r = min(right, other.right)
        val b = min(bottom, other.bottom)
        return if (r > l && b > t) Box(l, t, r, b) else null
    }

    fun union(other: Box): Box =
        Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    fun iou(other: Box): Float {
        val inter = intersection(other)?.area ?: return 0f
        return inter / (area + other.area - inter)
    }

    /** Какая доля этого прямоугольника лежит внутри [other]. */
    fun fractionInside(other: Box): Float {
        if (area <= 0f) return 0f
        return (intersection(other)?.area ?: 0f) / area
    }

    /** Доля вертикального перекрытия относительно меньшей из двух высот. */
    fun verticalOverlap(other: Box): Float {
        val overlap = min(bottom, other.bottom) - max(top, other.top)
        val smaller = min(height, other.height)
        return if (smaller <= 0f) 0f else max(0f, overlap) / smaller
    }

    fun expand(dx: Float, dy: Float): Box = Box(left - dx, top - dy, right + dx, bottom + dy)

    fun clampTo(width: Int, height: Int): Box = Box(
        left.coerceIn(0f, width.toFloat()),
        top.coerceIn(0f, height.toFloat()),
        right.coerceIn(0f, width.toFloat()),
        bottom.coerceIn(0f, height.toFloat()),
    )

    fun offset(dx: Float, dy: Float): Box = Box(left + dx, top + dy, right + dx, bottom + dy)
}

/** Что нашёл детектор. Порядок и смысл классов - как у модели comic-text-and-bubble-detector. */
enum class DetectionKind {
    /** Облако реплики. Само по себе не переводится - нужно, чтобы объединить колонки текста. */
    BUBBLE,

    /** Текст внутри облака. */
    BUBBLE_TEXT,

    /** Текст вне облака: за кадром, закадровый голос, звукоподражания. */
    FREE_TEXT,
}

data class Detection(val kind: DetectionKind, val box: Box, val score: Float)

/**
 * Жадное подавление дублей: в порядке убывания оценки оставляем рамку, если она не перекрывает
 * уже принятую того же класса больше чем на [iouThreshold].
 */
fun nonMaxSuppression(detections: List<Detection>, iouThreshold: Float): List<Detection> {
    val kept = ArrayList<Detection>()
    for (candidate in detections.sortedByDescending { it.score }) {
        if (kept.none { it.kind == candidate.kind && it.box.iou(candidate.box) > iouThreshold }) kept += candidate
    }
    return kept
}
