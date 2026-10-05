package com.example.myapplication.manga.translate.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.myapplication.manga.translate.pipeline.AnalyzedPage
import com.example.myapplication.manga.translate.pipeline.Box
import com.example.myapplication.manga.translate.pipeline.RgbImage
import kotlin.math.max

/**
 * Собирает переведённую страницу: стирает японский текст цветом бумаги и вписывает перевод.
 *
 * Сознательно без "ИИ-удаления" текста с рисунка: оно требует ещё ~150 МБ моделей и секунды на
 * страницу. В облаках фон ровный, и заливка выглядит чисто; для надписей на рисунке перевод
 * получает контур, а стираются только сами буквы. Улучшение можно добавить позже без изменения
 * кэша: разбор страницы хранится отдельно и перерисовывается заново.
 */
object PageRenderer {

    /**
     * @param source оригинал страницы; не изменяется
     * @param pixels те же пиксели в виде [RgbImage] (для замера цвета бумаги)
     */
    fun render(source: Bitmap, pixels: RgbImage, page: AnalyzedPage): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val fill = Paint().apply { style = Paint.Style.FILL }
        for (region in page.regions) {
            val translation = region.translation?.trim().orEmpty()
            if (translation.isEmpty()) continue
            val placement = LayoutPlanner.plan(region.region, page.width, page.height)
            val paper = PaperColor.around(pixels, region.region.textBounds)
            fill.color = 0xFF000000.toInt() or paper
            for (clear in placement.clearBoxes) {
                if (placement.outlined) {
                    // Надпись на рисунке: стираем только буквы, а не весь прямоугольник.
                    val patch = TextEraser.erase(pixels, clear, paper)
                    if (patch != null) {
                        val opaque = IntArray(patch.pixels.size) { patch.pixels[it] or 0xFF000000.toInt() }
                        out.setPixels(opaque, 0, patch.width, patch.left, patch.top, patch.width, patch.height)
                    } else {
                        // Под надписью тёмный рисунок: закрываем её плотной плашкой цвета фона.
                        fill.alpha = PLATE_ALPHA
                        canvas.drawRoundRect(clear.left, clear.top, clear.right, clear.bottom, PLATE_RADIUS, PLATE_RADIUS, fill)
                        fill.alpha = 255
                    }
                } else {
                    canvas.drawRect(clear.left, clear.top, clear.right, clear.bottom, fill)
                }
            }
            drawFitted(canvas, translation, placement.textBox, paper, placement.outlined, page.width)
        }
        return out
    }

    private fun drawFitted(canvas: Canvas, text: String, box: Box, paper: Int, outlined: Boolean, pageWidth: Int) {
        val ink = if (PaperColor.luma(paper) > DARK_PAPER_LUMA) 0xFF111111.toInt() else 0xFFF5F5F5.toInt()
        val halo = if (ink == 0xFF111111.toInt()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val width = max(1, box.width.toInt())
        val height = box.height

        val maxSize = (box.width / 4f).coerceIn(MIN_TEXT_PX, max(MIN_TEXT_PX, pageWidth / 15f))
        val minSize = max(MIN_TEXT_PX, pageWidth / 110f)
        var low = minSize
        var high = maxSize
        var best = minSize
        repeat(9) {
            val mid = (low + high) / 2f
            if (fits(text, mid, width, height)) {
                best = mid
                low = mid
            } else {
                high = mid
            }
        }
        val fillPaint = textPaint(best, ink, Paint.Style.FILL)
        val layout = layoutOf(text, fillPaint, width)
        val top = box.top + (height - layout.height) / 2f
        canvas.save()
        canvas.translate(box.left, top)
        if (outlined) {
            val strokePaint = textPaint(best, halo, Paint.Style.STROKE).apply { strokeWidth = best * 0.18f }
            layoutOf(text, strokePaint, width).draw(canvas)
        }
        layout.draw(canvas)
        canvas.restore()
    }

    private fun fits(text: String, size: Float, width: Int, height: Float): Boolean {
        val paint = textPaint(size, 0xFF000000.toInt(), Paint.Style.FILL)
        // Самое длинное слово должно помещаться в строку целиком: иначе StaticLayout порвёт его
        // посреди ("косну-ться"), а так - сначала уменьшаем шрифт.
        val longestWord = text.split(Regex("\\s+")).maxOf { paint.measureText(it) }
        if (longestWord > width) return false
        val layout = layoutOf(text, paint, width)
        return layout.height <= height
    }

    private fun textPaint(size: Float, color: Int, style: Paint.Style) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        this.style = style
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        strokeJoin = Paint.Join.ROUND
    }

    private fun layoutOf(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.95f)
            .build()

    private const val MIN_TEXT_PX = 10f
    private const val PLATE_ALPHA = 255
    private const val PLATE_RADIUS = 10f
    private const val DARK_PAPER_LUMA = 140
}
