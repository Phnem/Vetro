package com.example.myapplication.ui.details.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.max

/** Всё, что попадает на карточку «Что я сейчас смотрю». Без Android-ссылок, кроме постера. */
internal data class ShareCardData(
    val title: String,
    /** Подпись над названием: «СЕЙЧАС СМОТРЮ», «NOW READING» и т.п. */
    val kindLabel: String,
    val typeLabel: String,
    /** Место остановки: «S2 · E5», «E12», «Глава 40»; null, если прогресса нет. */
    val position: String?,
    /** Знаменатель к позиции: «из 12»; null, если длина неизвестна. */
    val positionTotal: String?,
    val fraction: Float?,
    /** 10-балльная шкала, 0 = не оценено. */
    val rating: Float,
    val genres: List<String>,
    val poster: Bitmap?,
    val footer: String = "Vetro",
)

/**
 * Рисует карточку 1080×1350 (4:5, влезает в ленты и истории без обрезки) средствами android.graphics:
 * без Compose-слоя и без внешних сервисов. Палитра бренда: оранжевый #E85002, чёрный и серые.
 *
 * Композиция: размытый и затемнённый постер как фон, сверху чёткий постер со скруглением и тенью,
 * ниже метка-пилюля, название до двух строк, место остановки с прогресс-полоской, рейтинг и подпись.
 */
internal object WatchingCardRenderer {
    const val WIDTH = 1080
    const val HEIGHT = 1350

    private const val ORANGE = 0xFFE85002.toInt()
    private const val INK = 0xFF121214.toInt()
    private const val MARGIN = 84f

    fun render(data: ShareCardData): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        drawBackground(c, data.poster)
        val posterBottom = drawPoster(c, data)
        drawInfo(c, data, posterBottom)
        return bitmap
    }

    private fun drawBackground(c: Canvas, poster: Bitmap?) {
        c.drawColor(INK)
        if (poster != null) {
            // Мягкое размытие без RenderScript: уменьшить до крошки и растянуть с фильтрацией.
            val tiny = Bitmap.createScaledBitmap(poster, 28, max(1, 28 * poster.height / poster.width), true)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val scale = max(WIDTH / tiny.width.toFloat(), HEIGHT / tiny.height.toFloat())
            val w = tiny.width * scale
            val h = tiny.height * scale
            c.drawBitmap(tiny, null, RectF((WIDTH - w) / 2f, (HEIGHT - h) / 2f, (WIDTH + w) / 2f, (HEIGHT + h) / 2f), paint)
            tiny.recycle()
        }
        val fade = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, HEIGHT.toFloat(),
                intArrayOf(Color.argb(150, 18, 18, 20), Color.argb(210, 18, 18, 20), Color.argb(250, 18, 18, 20)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), fade)
        // Тёплое свечение бренда в верхнем углу, чтобы карточка не была просто серой.
        val glow = Paint().apply {
            shader = RadialGradient(
                WIDTH * 0.92f, 0f, WIDTH * 0.75f,
                Color.argb(64, 232, 80, 2), Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), glow)
    }

    /** @return нижняя граница постера. */
    private fun drawPoster(c: Canvas, data: ShareCardData): Float {
        val w = 520f
        val h = 780f
        val rect = RectF((WIDTH - w) / 2f, 96f, (WIDTH + w) / 2f, 96f + h)
        val radius = 52f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(48f, 0f, 20f, Color.argb(170, 0, 0, 0))
        }
        c.drawRoundRect(rect, radius, radius, shadow)
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        val poster = data.poster
        if (poster != null) {
            // Центральная обрезка под 2:3.
            val scale = max(w / poster.width, h / poster.height)
            val sw = w / scale
            val sh = h / scale
            val left = (poster.width - sw) / 2f
            val top = (poster.height - sh) / 2f
            val src = android.graphics.Rect(left.toInt(), top.toInt(), (left + sw).toInt(), (top + sh).toInt())
            c.drawBitmap(poster, src, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            val bg = Paint().apply {
                shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, 0xFF2C2C2C.toInt(), ORANGE, Shader.TileMode.CLAMP)
            }
            c.drawRect(rect, bg)
            val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 255, 255, 255)
                textSize = 320f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            c.drawText(data.title.firstOrNull()?.uppercase() ?: "V", rect.centerX(), rect.centerY() + 112f, letter)
        }
        c.restore()
        // Тонкий светлый кант, как у стеклянных карточек приложения.
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(46, 255, 255, 255)
        }
        c.drawRoundRect(rect, radius, radius, rim)
        return rect.bottom
    }

    private fun drawInfo(c: Canvas, data: ShareCardData, posterBottom: Float) {
        val contentWidth = WIDTH - MARGIN * 2
        var y = posterBottom + 52f

        // Метка-пилюля: статус и тип тайтла.
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val label = "${data.kindLabel}  ·  ${data.typeLabel.uppercase()}"
        val labelW = labelPaint.measureText(label) + 44f
        val pill = RectF(MARGIN, y, MARGIN + labelW, y + 52f)
        c.drawRoundRect(pill, 26f, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE })
        c.drawText(label, pill.left + 22f, pill.top + 35f, labelPaint)
        y = pill.bottom + 26f

        // Название: до двух строк.
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 62f
            letterSpacing = -0.01f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val titleLayout = StaticLayout.Builder
            .obtain(data.title, 0, data.title.length, titlePaint, contentWidth.toInt())
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.02f)
            .build()
        c.save()
        c.translate(MARGIN, y)
        titleLayout.draw(c)
        c.restore()
        y += titleLayout.height + 24f

        // Место остановки и рейтинг на одной строке.
        val muted = Color.argb(150, 255, 255, 255)
        var textRight = MARGIN
        data.position?.let { position ->
            val posPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 44f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            c.drawText(position, MARGIN, y + 40f, posPaint)
            textRight += posPaint.measureText(position)
            data.positionTotal?.let { total ->
                val totalPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = muted
                    textSize = 36f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                }
                c.drawText(" $total", textRight, y + 40f, totalPaint)
            }
        }
        if (data.rating > 0f) {
            val ratingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 44f
                textAlign = Paint.Align.RIGHT
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            val text = "★ " + String.format(java.util.Locale.US, "%.1f", data.rating)
            c.drawText(text, WIDTH - MARGIN, y + 40f, ratingPaint)
        }
        y += 64f

        // Прогресс-полоска.
        data.fraction?.let { fraction ->
            val track = RectF(MARGIN, y, WIDTH - MARGIN, y + 14f)
            c.drawRoundRect(track, 7f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) })
            val fill = RectF(track.left, track.top, track.left + track.width() * fraction.coerceIn(0.02f, 1f), track.bottom)
            c.drawRoundRect(fill, 7f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE })
        }

        // Подвал: жанры слева, марка справа.
        val footPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = 28f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val baseline = HEIGHT - 58f
        val brandPaint = TextPaint(footPaint).apply {
            color = Color.WHITE
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.06f
        }
        c.drawText(data.footer, WIDTH - MARGIN, baseline, brandPaint)
        if (data.genres.isNotEmpty()) {
            val genres = TextUtils.ellipsize(
                data.genres.joinToString("  ·  "), footPaint, contentWidth - 220f, TextUtils.TruncateAt.END,
            )
            c.drawText(genres.toString(), MARGIN, baseline, footPaint)
        }
    }
}
