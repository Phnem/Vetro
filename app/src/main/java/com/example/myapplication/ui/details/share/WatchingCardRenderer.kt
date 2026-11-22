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
    /** Текст под оранжевой строкой (с оранжевой чертой слева): заметка пользователя или описание тайтла; null — нет. */
    val note: String?,
    /** Заметка пользователя берётся в «ёлочки», описание тайтла идёт как есть. */
    val noteQuoted: Boolean,
    /** Комментарий пользователя: пузырь-мысль над карточкой, холст растёт под него; null — нет. */
    val comment: String?,
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
 * Рисует карточку по макету «Рамка поста» (380 условных единиц в ширину, высота по содержимому, радиус 32, внутренние поля 20)
 * средствами android.graphics: без Compose-слоя и внешних сервисов. Условная единица = [UNIT] px,
 * вокруг карточки поля [PAD] единиц с размытым цветным фоном из обложки: сквозь матовое стекло он
 * и придаёт карточке глубину.
 *
 * Палитра бренда: оранжевый #E85002, песочный #D6B89E, чёрный и серые.
 */
internal object WatchingCardRenderer {
    private const val UNIT = 3f
    private const val CARD_W = 380f
    private const val PAD = 28f

    /** (380 + 2 × 28) × 3. Высота у холста своя: карточка по высоте садится на содержимое. */
    const val WIDTH = 1308

    /** Подвал: зазор над чертой, высота строки и нижнее поле карточки. */
    private const val FOOTER_GAP = 16f
    private const val FOOTER_TEXT_H = 14.4f
    private const val FOOTER_TEXT_TOP = 12f
    private const val CARD_BOTTOM_PAD = 20f

    /** Пузырь-комментарий: отступ от верха холста, зазор до карточки, сдвиг от левого края карточки. */
    private const val BUBBLE_TOP = 16f
    private const val BUBBLE_GAP = 12f
    private const val BUBBLE_INSET = 6f

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
        val t = if (data.dark) dark else light
        val bubble = data.comment?.trim()?.takeIf { it.isNotEmpty() }?.let { measureBubble(it, data.dark) }
        // Пузырь стоит над карточкой, и холст растёт ровно на его высоту (с хвостом и зазором).
        val extraTop = bubble?.let { max(0f, BUBBLE_TOP + it.totalHeight + BUBBLE_GAP - PAD) } ?: 0f
        val plan = plan(data, t)
        val height = ((plan.cardH + PAD * 2f + extraTop) * UNIT).roundToInt()
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        drawBackdrop(c, paletteFor(data.poster), data.dark, height)
        c.scale(UNIT, UNIT)
        bubble?.let { drawBubble(c, it, PAD + BUBBLE_INSET, BUBBLE_TOP) }
        c.translate(PAD, PAD + extraTop)
        drawCard(c, data, t, plan)
        return bitmap
    }

    // ---------------------------------------------------------------- раскладка

    /**
     * Что и где стоит под обложкой. Карточка не держит фиксированную высоту: подвал идёт сразу за
     * последним блоком, поэтому в режиме «минимум данных» пустого места внизу нет, а высота карточки
     * (и холста) просто короче.
     */
    private class Plan(
        val titleLayout: StaticLayout,
        val titleTop: Float,
        val unit: String?,
        val unitTop: Float,
        val noteLayout: StaticLayout?,
        val noteTop: Float,
        val genres: String?,
        val genresTop: Float,
        val footerLine: Float,
        val cardH: Float,
    )

    private const val LEFT = 20f
    private const val CONTENT_W = CARD_W - LEFT * 2f
    private const val HEAD_TOP = 20f
    private const val LOGO_SIZE = 38f
    private const val COVER_H = 215f
    private const val NOTE_BAR_W = 2.5f
    private const val NOTE_GAP = 11f

    private fun plan(d: ShareCardData, t: Tokens): Plan {
        val coverBottom = HEAD_TOP + LOGO_SIZE + 14f + COVER_H
        // Полоска прогресса (6) и отступы вокруг неё: место остаётся и без полоски, чтобы шапка не прыгала.
        var y = coverBottom + 13f + 6f + 12f

        val titlePaint = paint(18.5f, 800, t.primary).apply { letterSpacing = -0.02f }
        val titleLayout = StaticLayout.Builder
            .obtain(d.title, 0, d.title.length, titlePaint, CONTENT_W.toInt())
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.04f)
            .build()
        val titleTop = y
        y += titleLayout.height

        var unitTop = 0f
        val unit = d.currentUnit?.let {
            y += 5f
            unitTop = y
            y += 16.9f
            TextUtils.ellipsize(it, paint(13f, 600, t.accent), CONTENT_W, TextUtils.TruncateAt.END).toString()
        }

        var noteTop = 0f
        val noteLayout = d.note?.let { note ->
            y += 10f
            noteTop = y
            val notePaint = paint(12.5f, 400, t.note, italic = true)
            val text = if (d.noteQuoted) "«$note»" else note
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, notePaint, (CARD_W - LEFT - (LEFT + NOTE_BAR_W + NOTE_GAP)).toInt())
                .setMaxLines(4)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.18f)
                .build()
            y += layout.height
            layout
        }

        var genresTop = 0f
        val genres = if (noteLayout == null) {
            d.genres?.let {
                y += 6f
                genresTop = y
                y += 16.9f
                TextUtils.ellipsize(it, paint(12.5f, 500, t.secondary), CONTENT_W, TextUtils.TruncateAt.END).toString()
            }
        } else null

        val footerLine = y + FOOTER_GAP
        val cardH = footerLine + 1f + FOOTER_TEXT_TOP + FOOTER_TEXT_H + CARD_BOTTOM_PAD
        return Plan(titleLayout, titleTop, unit, unitTop, noteLayout, noteTop, genres, genresTop, footerLine, cardH)
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

    private fun drawBackdrop(c: Canvas, a: Ambient, isDark: Boolean, height: Int) {
        val w = WIDTH.toFloat()
        val h = height.toFloat()
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

    // ---------------------------------------------------------------- пузырь-комментарий

    /** Размеры пузыря-мысли: капсула с текстом, под ней эллипс и кружок «хвоста», как на референсе. */
    private class Bubble(
        val layout: StaticLayout,
        val layoutWidth: Float,
        val fill: Int,
        val width: Float,
        val capsuleH: Float,
        val tailRx: Float,
        val tailRy: Float,
        val dotR: Float,
    ) {
        /** Капсула вместе с хвостом, который свисает ниже неё. */
        val totalHeight: Float
            get() = capsuleH + max(tailRy, 0.26f * capsuleH + dotR)
    }

    private fun measureBubble(text: String, isDark: Boolean): Bubble {
        val paint = paint(14f, 600, if (isDark) 0xFF141414.toInt() else Color.WHITE)
        val padH = 22f
        val padV = 13f
        val maxText = CARD_W - 12f - padH * 2f
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, maxText.toInt())
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.06f)
            .build()
        var textW = 0f
        for (i in 0 until layout.lineCount) textW = max(textW, layout.getLineWidth(i))
        val width = max(textW + padH * 2f, 96f)
        val capsuleH = layout.height + padV * 2f
        val rx = max(14f, 0.38f * capsuleH)
        return Bubble(
            layout = layout,
            layoutWidth = maxText,
            // Тёмная тема — светлый пузырь и наоборот, как инверсная плашка статуса.
            fill = if (isDark) 0xFFF4F3EF.toInt() else 0xFF2B2A29.toInt(),
            width = width,
            capsuleH = capsuleH,
            tailRx = rx,
            tailRy = rx * 0.55f,
            dotR = max(4.5f, 0.106f * capsuleH),
        )
    }

    /** Рисует пузырь одним контуром (капсула + эллипс + кружок), чтобы тень легла один раз. */
    private fun drawBubble(c: Canvas, b: Bubble, left: Float, top: Float) {
        val h = b.capsuleH
        // Хвост всегда целится в логотип под пузырём: его центр примерно в 33 единицах правее края пузыря.
        val ellipseX = left + 47f.coerceIn(30f, b.width - 30f)
        val ellipseY = top + h - 1f
        val shape = Path().apply {
            addRoundRect(RectF(left, top, left + b.width, top + h), h / 2f, h / 2f, Path.Direction.CW)
            addOval(RectF(ellipseX - b.tailRx, ellipseY - b.tailRy, ellipseX + b.tailRx, ellipseY + b.tailRy), Path.Direction.CW)
            addCircle(ellipseX - 0.32f * h, top + h + 0.26f * h, b.dotR, Path.Direction.CW)
        }
        c.drawPath(
            shape,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = b.fill
                setShadowLayer(8f, 0f, 4f, Color.argb(70, 0, 0, 0))
            },
        )
        c.save()
        c.translate(left + (b.width - b.layoutWidth) / 2f, top + 13f)
        b.layout.draw(c)
        c.restore()
    }

    // ---------------------------------------------------------------- карточка

    private fun drawCard(c: Canvas, d: ShareCardData, t: Tokens, plan: Plan) {
        val cardH = plan.cardH
        val card = RectF(0f, 0f, CARD_W, cardH)
        val radius = 32f

        // Тень на отдельном слое с вырезанной серединой: иначе она просвечивала бы сквозь полупрозрачное стекло.
        val layer = c.saveLayer(-PAD, -PAD, CARD_W + PAD, cardH + PAD, null)
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
        val rim = RectF(0.5f, 0.5f, CARD_W - 0.5f, cardH - 0.5f)
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
        val y = cover.bottom + 13f
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

        // 4. Название, оранжевая строка, цитата или жанры: позиции даёт plan().
        c.save()
        c.translate(left, plan.titleTop)
        plan.titleLayout.draw(c)
        c.restore()

        if (plan.unit != null) {
            val unitPaint = paint(13f, 600, t.accent)
            c.drawText(plan.unit, left, baselineIn(plan.unitTop, 16.9f, unitPaint), unitPaint)
        }
        if (plan.noteLayout != null) {
            val noteX = left + NOTE_BAR_W + NOTE_GAP
            c.drawRect(left, plan.noteTop, left + NOTE_BAR_W, plan.noteTop + plan.noteLayout.height, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE })
            c.save()
            c.translate(noteX, plan.noteTop)
            plan.noteLayout.draw(c)
            c.restore()
        } else if (plan.genres != null) {
            val genrePaint = paint(12.5f, 500, t.secondary)
            c.drawText(plan.genres, left, baselineIn(plan.genresTop, 16.9f, genrePaint), genrePaint)
        }

        // 5. Подвал.
        val footerLine = plan.footerLine
        c.drawRect(left, footerLine, right, footerLine + 1f, Paint().apply { color = t.footerBorder })
        val footPaint = paint(12f, 500, t.footerText)
        val footBase = baselineIn(footerLine + 1f + FOOTER_TEXT_TOP, FOOTER_TEXT_H, footPaint)
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
