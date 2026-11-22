package com.example.myapplication.ui.details.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Всё, что попадает на карточку «Что я сейчас смотрю». Без Android-ссылок, кроме постера. */
internal data class ShareCardData(
    /** Ник справа от логотипа; пустой — вместо него имя приложения. */
    val nick: String,
    val typeLabel: String,
    /** Год релиза; пустой, если неизвестен. */
    val year: String,
    val statusLabel: String,
    val title: String,
    /** Плашка прогресса на обложке: «19 / 28 эп. • 68%»; null — прогресса нет. */
    val progressText: String?,
    /** Доля для 10-сегментной полоски; null — полоски нет. */
    val fraction: Float?,
    /** Оранжевая плашка оценки на обложке: «9/10»; null — не показывать. */
    val ratingText: String?,
    /** Оранжевая строка под названием: «Эп. 19», «S3 E5», «Глава 82»; null — нет. */
    val currentUnit: String?,
    /** Личная заметка из карточки тайтла (цитата с оранжевой чертой); null — нет. */
    val note: String?,
    /** Жанры вместо цитаты в режиме «минимум данных»; null — нет. */
    val genres: String?,
    val footerLeft: String,
    val footerRight: String,
    /** Тема приложения: карточка рисуется тёмным или светлым стеклом. */
    val dark: Boolean,
    val poster: Bitmap?,
    val brandName: String = "Vetro Collection",
)

/**
 * Рисует карточку по макету «Рамка поста» (380 × 530 условных единиц, радиус 32, внутренние поля 20)
 * средствами android.graphics: без Compose-слоя и внешних сервисов. Условная единица = [UNIT] px,
 * вокруг карточки поля [PAD] единиц с размытым цветным фоном из обложки: сквозь матовое стекло он
 * и придаёт карточке глубину.
 *
 * Палитра бренда: оранжевый #E85002, песочный #D6B89E, чёрный и серые.
 */
internal object WatchingCardRenderer {
    private const val UNIT = 3f
    private const val CARD_W = 380f
    private const val CARD_H = 530f
    private const val PAD = 28f

    /** (380 + 2 × 28) × 3 */
    const val WIDTH = 1308

    /** (530 + 2 × 28) × 3 */
    const val HEIGHT = 1758

    private const val ORANGE = 0xFFE85002.toInt()
    private const val LOGO_ORANGE = 0xFFE84500.toInt()
    private const val SAND = 0xFFD6B89E.toInt()
    private const val LOGO_BODY = 0xFF121212.toInt()

    private class Tokens(
        val primary: Int,
        val secondary: Int,
        val catPillBg: Int,
        val catPillText: Int,
        val statusBg: Int,
        val statusText: Int,
        val progressPillBg: Int,
        val progressPillText: Int,
        val segmentEmpty: Int,
        val accent: Int,
        val note: Int,
        val footerBorder: Int,
        val footerText: Int,
        val coverBorder: Int,
        val cardFill: Int,
        val cardBorder: Int,
        val highlight: Int,
        val shadow: Int,
        val shadowDy: Float,
        val shadowBlur: Float,
    )

    private fun rgba(hex: Int, alpha: Float): Int = ColorUtils.setAlphaComponent(hex, (alpha * 255f).roundToInt())

    private val light = Tokens(
        primary = 0xFF141414.toInt(),
        secondary = 0xFF76767B.toInt(),
        catPillBg = rgba(Color.BLACK, 0.055f),
        catPillText = 0xFF5A5A60.toInt(),
        statusBg = 0xFF141414.toInt(),
        statusText = Color.WHITE,
        progressPillBg = rgba(Color.WHITE, 0.90f),
        progressPillText = 0xFF141414.toInt(),
        segmentEmpty = rgba(Color.BLACK, 0.11f),
        accent = ORANGE,
        note = 0xFF4A4A50.toInt(),
        footerBorder = rgba(Color.BLACK, 0.08f),
        footerText = 0xFF76767B.toInt(),
        coverBorder = rgba(Color.BLACK, 0.08f),
        cardFill = Color.argb(199, 247, 246, 242),
        cardBorder = rgba(Color.WHITE, 0.72f),
        highlight = rgba(Color.WHITE, 0.92f),
        shadow = Color.argb(61, 12, 12, 16),
        shadowDy = 26f,
        shadowBlur = 60f,
    )

    private val dark = Tokens(
        primary = 0xFFF9F9F9.toInt(),
        secondary = 0xFF98989F.toInt(),
        catPillBg = rgba(Color.WHITE, 0.09f),
        catPillText = 0xFFD1D1D6.toInt(),
        statusBg = 0xFFF9F9F9.toInt(),
        statusText = 0xFF141414.toInt(),
        progressPillBg = Color.argb(214, 24, 24, 28),
        progressPillText = 0xFFF9F9F9.toInt(),
        segmentEmpty = rgba(Color.WHITE, 0.14f),
        accent = 0xFFFF6B35.toInt(),
        note = rgba(0xFFF9F9F9.toInt(), 0.78f),
        footerBorder = rgba(Color.WHITE, 0.09f),
        footerText = 0xFF8E8E95.toInt(),
        coverBorder = rgba(Color.WHITE, 0.12f),
        cardFill = Color.argb(189, 26, 26, 29),
        cardBorder = rgba(Color.WHITE, 0.14f),
        highlight = rgba(Color.WHITE, 0.20f),
        shadow = Color.argb(166, 0, 0, 0),
        shadowDy = 28f,
        shadowBlur = 64f,
    )

    fun render(data: ShareCardData): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val t = if (data.dark) dark else light
        drawBackdrop(c, paletteFor(data.poster), data.dark)
        c.scale(UNIT, UNIT)
        c.translate(PAD, PAD)
        drawCard(c, data, t)
        return bitmap
    }

    // ---------------------------------------------------------------- фон

    private class Ambient(val c1: Int, val c3: Int, val lightBase: Int, val darkBase: Int)

    /** Цвета фона из обложки; без обложки — тёплая гамма бренда. */
    private fun paletteFor(poster: Bitmap?): Ambient {
        var c1 = 0xFF7C2D12.toInt()
        var c3 = 0xFF374151.toInt()
        if (poster != null) {
            runCatching {
                val p = Palette.from(poster).maximumColorCount(12).generate()
                c1 = p.getVibrantColor(p.getDominantColor(c1))
                c3 = p.getDarkMutedColor(p.getMutedColor(c3))
            }
        }
        return Ambient(
            c1 = c1,
            c3 = c3,
            lightBase = ColorUtils.blendARGB(c1, Color.WHITE, 0.86f),
            darkBase = ColorUtils.blendARGB(c1, Color.BLACK, 0.88f),
        )
    }

    private fun drawBackdrop(c: Canvas, a: Ambient, isDark: Boolean) {
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        c.drawColor(if (isDark) a.darkBase else a.lightBase)
        // Три цветных пятна: радиальный градиент вместо размытия, чтобы не тянуть RenderScript.
        val alphas = if (isDark) floatArrayOf(0.72f, 0.58f, 0.52f) else floatArrayOf(0.48f, 0.42f, 0.36f)
        orb(c, w * 0.18f, h * 0.16f, w * 0.62f, a.c1, alphas[0])
        orb(c, w * 0.88f, h * 0.9f, w * 0.56f, ORANGE, alphas[1])
        orb(c, w * 0.5f, h * 0.5f, w * 0.46f, a.c3, alphas[2])
    }

    private fun orb(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(rgba(color, alpha), rgba(color, alpha * 0.45f), rgba(color, 0f)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx, cy, radius, paint)
    }

    // ---------------------------------------------------------------- карточка

    private fun drawCard(c: Canvas, d: ShareCardData, t: Tokens) {
        val card = RectF(0f, 0f, CARD_W, CARD_H)
        val radius = 32f

        // Тень на отдельном слое с вырезанной серединой: иначе она просвечивала бы сквозь полупрозрачное стекло.
        val layer = c.saveLayer(-PAD, -PAD, CARD_W + PAD, CARD_H + PAD, null)
        c.drawRoundRect(
            card, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                setShadowLayer(t.shadowBlur / 2f, 0f, t.shadowDy / 2f, t.shadow)
            },
        )
        c.drawRoundRect(
            card, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) },
        )
        c.restoreToCount(layer)

        c.drawRoundRect(card, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.cardFill })
        // Кромка стекла и верхний блик, угасающий вниз (inset 0 1px 0 в макете).
        val rim = RectF(0.5f, 0.5f, CARD_W - 0.5f, CARD_H - 0.5f)
        c.drawRoundRect(
            rim, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = t.cardBorder
            },
        )
        c.drawRoundRect(
            rim, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                shader = LinearGradient(0f, 0f, 0f, 120f, t.highlight, rgba(t.highlight, 0f), Shader.TileMode.CLAMP)
            },
        )

        val left = 20f
        val right = CARD_W - 20f
        val contentW = right - left

        // 1. Шапка: логотип, ник с плашкой типа, «Vetro Collection • год», статус справа.
        val headTop = 20f
        val logoSize = 38f
        drawLogo(c, left, headTop, logoSize, d.dark)

        val statusPaint = paint(12.5f, 700, t.statusText)
        val statusW = statusPaint.measureText(d.statusLabel) + 30f
        val statusH = 12.5f + 14f
        val statusRect = RectF(right - statusW, headTop + (logoSize - statusH) / 2f, right, headTop + (logoSize + statusH) / 2f)
        c.drawRoundRect(statusRect, statusH / 2f, statusH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.statusBg })
        c.drawText(d.statusLabel, statusRect.left + 15f, baselineIn(statusRect.top, statusH, statusPaint), statusPaint)

        val textX = left + logoSize + 11f
        val typePaint = paint(11f, 600, t.catPillText)
        val typeW = typePaint.measureText(d.typeLabel) + 18f
        val nickText = d.nick.ifBlank { d.brandName }
        val nickPaint = paint(15f, 700, t.primary)
        val nickMax = (statusRect.left - 10f) - textX - 7f - typeW
        val nickShown = TextUtils.ellipsize(nickText, nickPaint, max(nickMax, 40f), TextUtils.TruncateAt.END).toString()
        val blockH = 18f + 2f + 15f
        val blockTop = headTop + (logoSize - blockH) / 2f
        c.drawText(nickShown, textX, baselineIn(blockTop, 18f, nickPaint), nickPaint)
        val typeLeft = textX + nickPaint.measureText(nickShown) + 7f
        val typeRect = RectF(typeLeft, blockTop, typeLeft + typeW, blockTop + 18f)
        c.drawRoundRect(typeRect, 9f, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.catPillBg })
        c.drawText(d.typeLabel, typeRect.left + 9f, baselineIn(typeRect.top, 18f, typePaint), typePaint)
        val subPaint = paint(12f, 500, t.secondary)
        // Без ника имя приложения уже стоит на первой строке: не дублируем его во второй.
        val sub = when {
            d.nick.isBlank() -> d.year
            d.year.isBlank() -> d.brandName
            else -> "${d.brandName} • ${d.year}"
        }
        if (sub.isNotEmpty()) c.drawText(sub, textX, baselineIn(blockTop + 18f + 2f, 15f, subPaint), subPaint)

        // 2. Обложка 340 × 215, радиус 20.
        val cover = RectF(left, headTop + logoSize + 14f, right, headTop + logoSize + 14f + 215f)
        drawCover(c, cover, d, t)

        // 3. 10-сегментная полоска прогресса.
        var y = cover.bottom + 13f
        d.fraction?.let { fraction ->
            val filled = (fraction * 10f).roundToInt().coerceIn(1, 10)
            val gap = 5f
            val segW = (contentW - gap * 9f) / 10f
            for (i in 0 until 10) {
                val x = left + i * (segW + gap)
                c.drawRoundRect(
                    RectF(x, y, x + segW, y + 6f), 3f, 3f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (i < filled) ORANGE else t.segmentEmpty },
                )
            }
        }
        y += 6f + 12f

        // От верхней границы подвала зависит, сколько строк влезет в заметку.
        val footerLine = CARD_H - 20f - 14.4f - 12f
        // 4. Название, оранжевая строка, цитата или жанры.
        val titlePaint = paint(18.5f, 800, t.primary).apply { letterSpacing = -0.02f }
        val titleLayout = StaticLayout.Builder
            .obtain(d.title, 0, d.title.length, titlePaint, contentW.toInt())
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.04f)
            .build()
        c.save()
        c.translate(left, y)
        titleLayout.draw(c)
        c.restore()
        y += titleLayout.height

        if (d.currentUnit != null) {
            y += 5f
            val unitPaint = paint(13f, 600, t.accent)
            val unit = TextUtils.ellipsize(d.currentUnit, unitPaint, contentW, TextUtils.TruncateAt.END).toString()
            c.drawText(unit, left, baselineIn(y, 16.9f, unitPaint), unitPaint)
            y += 16.9f
        }
        if (d.note != null) {
            y += 10f
            val notePaint = paint(12.5f, 400, t.note, italic = true)
            val noteX = left + 2.5f + 11f
            val lineH = 12.5f * 1.42f
            val room = ((footerLine - 8f - y) / lineH).toInt().coerceAtMost(3)
            if (room >= 1) {
                val text = "«${d.note}»"
                val layout = StaticLayout.Builder
                    .obtain(text, 0, text.length, notePaint, (right - noteX).toInt())
                    .setMaxLines(room)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.18f)
                    .build()
                c.drawRect(left, y, left + 2.5f, y + layout.height, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE })
                c.save()
                c.translate(noteX, y)
                layout.draw(c)
                c.restore()
            }
        } else if (d.genres != null) {
            y += 6f
            val genrePaint = paint(12.5f, 500, t.secondary)
            val genres = TextUtils.ellipsize(d.genres, genrePaint, contentW, TextUtils.TruncateAt.END).toString()
            c.drawText(genres, left, baselineIn(y, 16.9f, genrePaint), genrePaint)
        }

        // 5. Подвал.
        c.drawRect(left, footerLine, right, footerLine + 1f, Paint().apply { color = t.footerBorder })
        val footPaint = paint(12f, 500, t.footerText)
        val footBase = baselineIn(footerLine + 1f + 12f, 14.4f, footPaint)
        val rightText = d.footerRight
        val rightW = footPaint.measureText(rightText)
        val leftShown = TextUtils.ellipsize(d.footerLeft, footPaint, contentW - rightW - 12f, TextUtils.TruncateAt.END).toString()
        c.drawText(leftShown, left, footBase, footPaint)
        c.drawText(rightText, right - rightW, footBase, footPaint)
    }

    private fun drawCover(c: Canvas, cover: RectF, d: ShareCardData, t: Tokens) {
        val r = 20f
        val clip = Path().apply { addRoundRect(cover, r, r, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        val poster = d.poster
        if (poster != null) {
            // Широкая обложка из портретного постера: берём полосу чуть выше центра, там обычно лица и название.
            val scale = max(cover.width() / poster.width, cover.height() / poster.height)
            val sw = cover.width() / scale
            val sh = cover.height() / scale
            val sl = (poster.width - sw) / 2f
            val st = (poster.height - sh) * 0.3f
            val src = Rect(sl.toInt(), st.toInt(), (sl + sw).toInt(), (st + sh).toInt())
            c.drawBitmap(poster, src, cover, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            c.drawRect(
                cover,
                Paint().apply {
                    shader = LinearGradient(cover.left, cover.top, cover.right, cover.bottom, 0xFF2C2C2C.toInt(), ORANGE, Shader.TileMode.CLAMP)
                },
            )
            val letter = paint(96f, 800, rgba(Color.WHITE, 0.8f)).apply { textAlign = Paint.Align.CENTER }
            c.drawText(d.title.firstOrNull()?.uppercase() ?: "V", cover.centerX(), cover.centerY() + 34f, letter)
        }
        c.restore()
        c.drawRoundRect(
            cover, r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = t.coverBorder
            },
        )

        val pillPaint = paint(12.5f, 700, t.progressPillText)
        d.progressText?.let { text ->
            val w = pillPaint.measureText(text) + 26f
            val h = 12.5f + 12f
            val rect = RectF(cover.left + 12f, cover.bottom - 12f - h, cover.left + 12f + w, cover.bottom - 12f)
            c.drawRoundRect(
                rect, h / 2f, h / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = t.progressPillBg
                    setShadowLayer(6f, 0f, 3f, Color.argb(51, 0, 0, 0))
                },
            )
            c.drawText(text, rect.left + 13f, baselineIn(rect.top, h, pillPaint), pillPaint)
        }
        d.ratingText?.let { text ->
            val ratingPaint = paint(12.5f, 700, Color.WHITE)
            val starSize = 13f
            val w = starSize + 5f + ratingPaint.measureText(text) + 26f
            val h = 12.5f + 12f
            val rect = RectF(cover.right - 12f - w, cover.bottom - 12f - h, cover.right - 12f, cover.bottom - 12f)
            c.drawRoundRect(
                rect, h / 2f, h / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ORANGE
                    setShadowLayer(7f, 0f, 4f, Color.argb(115, 232, 80, 2))
                },
            )
            drawStar(c, rect.left + 13f + starSize / 2f, rect.centerY(), starSize / 2f, Color.WHITE)
            c.drawText(text, rect.left + 13f + starSize + 5f, baselineIn(rect.top, h, ratingPaint), ratingPaint)
        }
    }

    /** Логотип Vetro: тёмный «сквиркл», наклонный песочный брусок и оранжевый круг. */
    private fun drawLogo(c: Canvas, x: Float, y: Float, size: Float, isDark: Boolean) {
        c.save()
        c.translate(x, y)
        val s = size / 100f
        c.scale(s, s)
        val body = Path().apply {
            moveTo(50f, 3f)
            cubicTo(84.5f, 3f, 97f, 15.5f, 97f, 50f)
            cubicTo(97f, 84.5f, 84.5f, 97f, 50f, 97f)
            cubicTo(15.5f, 97f, 3f, 84.5f, 3f, 50f)
            cubicTo(3f, 15.5f, 15.5f, 3f, 50f, 3f)
            close()
        }
        c.drawPath(body, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LOGO_BODY })
        c.drawPath(
            body,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = if (isDark) 2.2f else 1.5f
                color = if (isDark) rgba(Color.WHITE, 0.16f) else rgba(Color.BLACK, 0.06f)
            },
        )
        c.save()
        c.translate(38.5f, 53.5f)
        c.rotate(-31f)
        c.drawRoundRect(RectF(-10.5f, -27f, 10.5f, 27f), 6.5f, 6.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SAND })
        c.restore()
        c.drawCircle(71f, 39f, 14.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LOGO_ORANGE })
        c.restore()
    }

    private fun drawStar(c: Canvas, cx: Float, cy: Float, outer: Float, color: Int) {
        val inner = outer * 0.5f
        val path = Path()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) outer else inner
            val angle = Math.PI / 5.0 * i - Math.PI / 2.0
            val px = cx + (rad * cos(angle)).toFloat()
            val py = cy + (rad * sin(angle)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL_AND_STROKE
                strokeJoin = Paint.Join.ROUND
                strokeWidth = 0.9f
            },
        )
    }

    // ---------------------------------------------------------------- текст

    private fun face(weight: Int, italic: Boolean): Typeface =
        if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, italic)
        } else {
            val bold = weight >= 600
            Typeface.create(
                "sans-serif",
                when {
                    bold && italic -> Typeface.BOLD_ITALIC
                    bold -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                },
            )
        }

    private fun paint(size: Float, weight: Int, color: Int, italic: Boolean = false): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.textSize = size
            this.color = color
            typeface = face(weight, italic)
        }

    /** Базовая линия текста, центрированного в строке высотой [lineHeight], как делает CSS для `line-height`. */
    private fun baselineIn(top: Float, lineHeight: Float, paint: TextPaint): Float {
        val fm = paint.fontMetrics
        return top + lineHeight / 2f - (fm.ascent + fm.descent) / 2f
    }
}
