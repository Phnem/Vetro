package com.example.myapplication.manga.translate.render

import com.example.myapplication.manga.translate.pipeline.Box
import com.example.myapplication.manga.translate.pipeline.RegionKind
import com.example.myapplication.manga.translate.pipeline.TextRegion

/**
 * Куда писать перевод и что при этом стереть.
 *
 * Перевод на русский обычно длиннее японского оригинала в несколько раз, а японский набран в узкие
 * колонки: впихнуть новый текст в исходные рамки значило бы получить буквы в пару пикселей. Поэтому
 * в облаке текст получает почти всё облако (с отступом от его края), а надпись вне облака -
 * рамку, раздутую по тому, что вокруг пусто.
 */
data class TextPlacement(
    /** Места, закрашиваемые цветом бумаги. */
    val clearBoxes: List<Box>,
    /** Прямоугольник, в который вписывается перевод. */
    val textBox: Box,
    /** Надпись на рисунке: ей нужен контур, иначе на фоне она не читается. */
    val outlined: Boolean,
)

object LayoutPlanner {

    private const val BUBBLE_INSET = 0.08f
    private const val BUBBLE_GROW_X = 0.12f
    private const val BUBBLE_GROW_Y = 0.10f
    private const val FREE_GROW_X = 0.08f
    private const val FREE_GROW_Y = 0.05f
    private const val CLEAR_MARGIN_PX = 3f

    fun plan(region: TextRegion, pageWidth: Int, pageHeight: Int): TextPlacement {
        val text = region.textBounds
        val bubble = region.bubble
        if (region.kind == RegionKind.BUBBLE && bubble != null) {
            val inner = Box(
                bubble.left + bubble.width * BUBBLE_INSET,
                bubble.top + bubble.height * BUBBLE_INSET,
                bubble.right - bubble.width * BUBBLE_INSET,
                bubble.bottom - bubble.height * BUBBLE_INSET,
            )
            val grown = text.expand(text.width * BUBBLE_GROW_X, text.height * BUBBLE_GROW_Y)
            // Раздутая рамка не выходит за внутренность облака, но оригинальный текст всегда внутри.
            val textBox = (grown.intersection(inner) ?: text).union(text).clampTo(pageWidth, pageHeight)
            return TextPlacement(
                clearBoxes = listOf(textBox.expand(CLEAR_MARGIN_PX, CLEAR_MARGIN_PX).clampTo(pageWidth, pageHeight)),
                textBox = textBox,
                outlined = false,
            )
        }
        val textBox = text.expand(text.width * FREE_GROW_X, text.height * FREE_GROW_Y).clampTo(pageWidth, pageHeight)
        return TextPlacement(
            // Стираем только то, где был японский текст: лишний фон вокруг не трогаем.
            clearBoxes = region.textBoxes.map { it.expand(CLEAR_MARGIN_PX, CLEAR_MARGIN_PX).clampTo(pageWidth, pageHeight) },
            textBox = textBox,
            outlined = true,
        )
    }
}
