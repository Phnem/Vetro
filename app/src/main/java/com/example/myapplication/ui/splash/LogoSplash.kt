package com.example.myapplication.ui.splash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.phnem.vetro.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ==========================================
// Заставка-логотип: «Vetro» схлопывается в знак (\ + солнце).
//
// Хореография — из HTML-наброска пользователя, форма букв — со шрифта-образца (PNG-маски в
// drawable-nodpi, нарезаны скриптом из .scratch/splash-logo-animation). Всё считается чистой
// функцией времени [drawLogoSplash]: ни одного аниматора на деталь, один Animatable времени снаружи.
//
//   0.05..   буквы вылетают на пружине;
//   T0       замах: правая ножка V отклоняется, «o» чуть отъезжает;
//   T0..     правая ножка захлопывается на левую, r/t/e засасываются в «o», отверстие закрывается;
//   TR       диск летит по дуге на место солнца, левая ножка на пружине становится «\» знака,
//            диск заливается цветом снизу вверх; вокруг — свечение и две волны.
//
// Геометрия знака — из ic_launcher_foreground.xml (те же числа, что в скрипте-прототипе).
// ==========================================

/** Длительность анимации; дальше кадр держится на финальном знаке. */
internal const val LogoSplashDurationSec = 4.3f

internal class LogoSplashColors(val word: Color, val sun: Color, val dark: Boolean)

internal class LogoSplashArt(
    val vLeft: ImageBitmap,
    val vRight: ImageBitmap,
    val e: ImageBitmap,
    val t: ImageBitmap,
    val r: ImageBitmap,
)

@Composable
internal fun rememberLogoSplashArt(): LogoSplashArt {
    val vLeft = ImageBitmap.imageResource(R.drawable.vetro_splash_v_left)
    val vRight = ImageBitmap.imageResource(R.drawable.vetro_splash_v_right)
    val e = ImageBitmap.imageResource(R.drawable.vetro_splash_e)
    val t = ImageBitmap.imageResource(R.drawable.vetro_splash_t)
    val r = ImageBitmap.imageResource(R.drawable.vetro_splash_r)
    return remember(vLeft, vRight, e, t, r) { LogoSplashArt(vLeft, vRight, e, t, r) }
}

/** Кадр заставки в момент [timeSec]; фон рисует вызывающий. */
@Composable
internal fun LogoSplashCanvas(
    timeSec: () -> Float,
    colors: LogoSplashColors,
    modifier: Modifier = Modifier,
) {
    val art = rememberLogoSplashArt()
    val cache = remember { LogoGeoCache() }
    Canvas(modifier.fillMaxSize()) {
        val designHeight = size.height / (size.width / DesignWidth)
        drawLogoSplash(timeSec(), art, colors, cache.get(designHeight))
    }
}

// ---------- тайминг (секунды), как в наброске ----------

private const val T0 = 1.15f
private const val TR = T0 + 0.95f
private val GulpEnd = floatArrayOf(T0 + 0.85f, T0 + 0.95f, T0 + 1.05f)
private val GulpAmp = floatArrayOf(0.12f, 0.14f, 0.20f)

private class Suck(val start: Float, val dur: Float, val rotDeg: Float, val introIndex: Int)

private val SuckR = Suck(T0 + 0.30f, 0.55f, 22f, 4)
private val SuckT = Suck(T0 + 0.40f, 0.55f, -28f, 3)
private val SuckE = Suck(T0 + 0.50f, 0.55f, 26f, 2)

// ---------- геометрия ----------

private const val DesignWidth = 1080f

/** Дизайн-пикселей на пиксель образца: слово занимает 70% ширины кадра (1322 — ширина слова в образце). */
private const val S = 0.70f * DesignWidth / 1322f

// Размеры и положения в пикселях образца шрифта (см. скрипт нарезки).
private const val ImgWordLeft = 254f
private const val ImgWordSpan = 1323f
private const val ImgRefTop = 233f
private const val ImgRefHeight = 397f

private class LogoGeo(val hd: Float) {
    private val x0 = DesignWidth / 2f - ImgWordSpan * S / 2f
    private val y0 = 0.47f * hd - ImgRefHeight * S / 2f

    fun ax(ix: Float) = x0 + (ix - ImgWordLeft) * S
    fun ay(iy: Float) = y0 + (iy - ImgRefTop) * S
    private fun pt(ix: Float, iy: Float) = Offset(ax(ix), ay(iy))

    // «o»
    val oCenter = pt(1422f, 471f)
    val oRadius = 155f * S
    val oHoleFraction = 65f / 155f

    /** Дизайн-пикселей на «единицу наброска» (в наброске радиус «o» — 52). */
    val k = oRadius / 52f

    // V
    val vOrigin = pt(250f, 236f)
    val vBandTop = ay(240f)
    val vBandBottom = ay(624f)
    val vPivot = pt(463f, 624f)
    val armStart = listOf(pt(275f, 240f), pt(346f, 240f), pt(463f, 482f), pt(463f, 624f), pt(433f, 624f))
    val closeAngle = (2.0 * Math.toDegrees(atan((armStart[4].x - armStart[0].x) / (armStart[4].y - armStart[0].y)).toDouble())).toFloat()

    // знак (ic_launcher_foreground.xml: единицы логотипа -> дизайн-пиксели, 0.9 px на единицу)
    private val logoScale = 0.9f
    private val logoFy = 0.47f * hd
    private fun logo(x: Float, y: Float) = Offset(DesignWidth / 2f + (x - 887f) * logoScale, logoFy + (y - 379f) * logoScale)
    val armTarget = listOf(logo(694f, 258f), logo(774f, 258f), logo(912f, 464f), logo(904f, 504f), logo(862f, 520f))
    val sunCenter = logo(1020f, 298f)
    val sunRadius = 80f * logoScale
    val armStroke = 40f * logoScale
    val rDip = 0.66f * sunRadius

    // засасываемые буквы: центры в дизайн-координатах
    val eCenter = pt(752.5f, 471.5f)
    val tCenter = pt(995f, 430f)
    val rCenter = pt(1193.5f, 468f)

    fun oPos(t: Float): Offset {
        val wind = easeIo((t - T0) / 0.3f)
        var x = oCenter.x + 8f * k * wind
        var y = oCenter.y
        val mu = t - (TR + 0.05f)
        if (mu > 0f) {
            val p = easeIo(mu / 0.85f)
            x = lerp(oCenter.x + 8f * k, sunCenter.x, p)
            y = lerp(oCenter.y, sunCenter.y, p) - 58f * k * sin(PI.toFloat() * p)
            val dt = mu - 0.85f
            if (dt > 0f) y += 9f * k * exp(-7f * dt) * sin(2f * PI.toFloat() * 2.6f * dt)
        }
        return Offset(x, y)
    }

    fun oBaseRadius(t: Float): Float {
        val mu = t - (TR + 0.05f)
        return when {
            mu <= 0f -> oRadius
            mu < 0.25f -> lerp(oRadius, rDip, easeOut(clamp01(mu / 0.3f)))
            else -> lerp(rDip, sunRadius, springE((mu - 0.25f) / 0.8f, 0.5f, 6.5f))
        }
    }
}

private class LogoGeoCache {
    private var geo: LogoGeo? = null
    fun get(hd: Float): LogoGeo {
        val cached = geo
        if (cached != null && cached.hd == hd) return cached
        return LogoGeo(hd).also { geo = it }
    }
}

// ---------- кривые ----------

private fun clamp01(x: Float) = x.coerceIn(0f, 1f)
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerp(a: Offset, b: Offset, t: Float) = Offset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

private fun smoothstep(x: Float): Float {
    val v = clamp01(x)
    return v * v * (3f - 2f * v)
}

private fun easeIo(x: Float): Float {
    val v = clamp01(x)
    return if (v < 0.5f) 4f * v * v * v else 1f - (-2f * v + 2f).pow(3) / 2f
}

private fun easeOut(x: Float): Float = 1f - (1f - clamp01(x)).pow(3)

private fun easeInBack(x: Float): Float {
    val c1 = 1.4f
    return (c1 + 1f) * x * x * x - c1 * x * x
}

/** Затухающий осциллятор 0 -> 1 с перелётом. */
private fun springE(u: Float, zeta: Float, decay: Float): Float {
    if (u <= 0f) return 0f
    if (u >= 1f) return 1f
    val w0 = decay / zeta
    val wd = w0 * sqrt(1f - zeta * zeta)
    return 1f - exp(-decay * u) * (cos(wd * u) + (decay / wd) * sin(wd * u))
}

private fun introU(t: Float, index: Int) = clamp01((t - (0.05f + index * 0.07f)) / 0.6f)

// ---------- кадр ----------

private fun DrawScope.drawLogoSplash(t: Float, art: LogoSplashArt, colors: LogoSplashColors, g: LogoGeo) {
    val designScale = size.width / DesignWidth
    val word = colors.word
    val sun = colors.sun
    val k = g.k
    val pi = PI.toFloat()

    fun introY(u: Float) = (1f - springE(u, 0.7f, 6f)) * 45f * k
    fun introOp(u: Float) = clamp01(u * 4f)

    withTransform({ scale(designScale, designScale, Offset.Zero) }) {
        val pos = g.oPos(t)
        val rBase = g.oBaseRadius(t)
        val fill = easeIo((t - TR) / 0.85f)

        // свечение — под всем остальным
        val glowOpacity = 0.55f * fill * (if (colors.dark) 1f else 0.7f)
        if (glowOpacity > 0.003f) {
            val radius = rBase * 3.1f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to sun.copy(alpha = 0.9f * glowOpacity),
                    0.45f to sun.copy(alpha = 0.25f * glowOpacity),
                    1f to sun.copy(alpha = 0f),
                    center = pos,
                    radius = radius,
                ),
                radius = radius,
                center = pos,
            )
        }

        // --- левая ножка V -> «\» знака ---
        val uLeft = introU(t, 0)
        val morph = springE((t - TR) / 0.95f, 0.62f, 6.5f)
        if (morph <= 0f) {
            drawArt(art.vLeft, g.vOrigin.x, g.vOrigin.y + introY(uLeft), introOp(uLeft), word)
        } else {
            val points = List(5) { lerp(g.armStart[it], g.armTarget[it], morph) }
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                close()
            }
            drawPath(path, word, style = Fill)
            val stroke = g.armStroke * morph.coerceIn(0f, 1.1f)
            if (stroke >= 1f) {
                drawPath(path, word, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        // --- правая ножка V: замах и захлопывание как ножницы ---
        val uRight = introU(t, 1)
        val wind = easeIo((t - T0) / 0.3f)
        val closeU = easeIo((t - (T0 + 0.3f)) / 0.65f)
        val angle = 8f * wind - (8f + g.closeAngle) * closeU
        val rightAlpha = introOp(uRight) * (if (closeU < 0.88f) 1f else clamp01(1f - (closeU - 0.88f) / 0.12f))
        if (rightAlpha > 0.003f) {
            withTransform({
                translate(0f, introY(uRight))
                clipRect(-10_000f, g.vBandTop, 10_000f, g.vBandBottom)   // обрезка по высоте букв
                rotate(angle, g.vPivot)
            }) {
                drawArt(art.vRight, g.vOrigin.x, g.vOrigin.y, rightAlpha, word)
            }
        }

        // --- r, t, e втягиваются в «o» ---
        drawSucked(art.r, g.rCenter, pos, SuckR, t, k, word, ::introY, ::introOp)
        drawSucked(art.t, g.tCenter, pos, SuckT, t, k, word, ::introY, ::introOp)
        drawSucked(art.e, g.eCenter, pos, SuckE, t, k, word, ::introY, ::introOp)

        // --- «o» -> диск знака ---
        val uO = introU(t, 5)
        var pulse = 0f
        for (i in 0 until 3) {
            val dt = t - GulpEnd[i]
            if (dt > 0f) pulse += GulpAmp[i] * exp(-9f * dt) * sin(2f * pi * 3.2f * dt)
        }
        val radius = rBase * (1f + pulse)
        val before = g.oPos(t - 0.01f)
        val after = g.oPos(t + 0.01f)
        val vy = (after.y - before.y) / 0.02f / k
        val stretch = min(0.22f, abs(vy) / 3800f)
        val rx = radius * (1f - stretch * 0.7f)
        val ry = radius * (1f + stretch)
        var hole = 1f
        for (i in 0 until 3) hole -= smoothstep((t - (GulpEnd[i] - 0.12f)) / 0.25f) / 3f
        hole = max(hole, 0f) * g.oHoleFraction
        drawDisc(Offset(pos.x, pos.y + introY(uO)), rx, ry, hole, fill, word, sun, introOp(uO))

        // --- волны на финале ---
        drawRipple(g, t, 0f, 0.7f, sun, k)
        drawRipple(g, t, 0.18f, 0.4f, sun, k)
    }
}

/** Буква-PNG (белая с альфой), закрашенная [color]; [left]/[top] — левый верхний угол в дизайн-пикселях. */
private fun DrawScope.drawArt(image: ImageBitmap, left: Float, top: Float, alpha: Float, color: Color) {
    if (alpha <= 0.003f) return
    withTransform({
        translate(left, top)
        scale(S, S, Offset.Zero)
    }) {
        drawTinted(image, alpha, color)
    }
}

private fun DrawScope.drawTinted(image: ImageBitmap, alpha: Float, color: Color) {
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(image.width, image.height),
        alpha = alpha,
        colorFilter = ColorFilter.tint(color),
        filterQuality = FilterQuality.High,
    )
}

private fun DrawScope.drawSucked(
    image: ImageBitmap,
    center: Offset,
    target: Offset,
    suck: Suck,
    t: Float,
    k: Float,
    color: Color,
    introY: (Float) -> Float,
    introOp: (Float) -> Float,
) {
    val ui = introU(t, suck.introIndex)
    val u = clamp01((t - suck.start) / suck.dur)
    if (u >= 1f) return
    val pi = PI.toFloat()
    val eased = easeInBack(u)
    val px = lerp(center.x, target.x, eased)
    val py = lerp(center.y, target.y, eased) - 26f * k * sin(pi * u) + introY(ui)
    val base = 1f - 0.92f * smoothstep(u)
    val bump = sin(pi * u)
    val sx = base * (1f + 0.45f * bump)
    val sy = base * (1f - 0.3f * bump)
    val alpha = introOp(ui) * (1f - clamp01((u - 0.55f) / 0.45f))
    if (alpha <= 0.003f) return
    withTransform({
        translate(px, py)
        rotate(suck.rotDeg * u, Offset.Zero)
        scale(sx * S, sy * S, Offset.Zero)
        translate(-image.width / 2f, -image.height / 2f)
    }) {
        drawTinted(image, alpha, color)
    }
}

/** Диск (кольцо, пока отверстие не закрылось), заливка цветом снизу вверх с мягкой кромкой. */
private fun DrawScope.drawDisc(
    center: Offset,
    rx: Float,
    ry: Float,
    holeFraction: Float,
    fill: Float,
    word: Color,
    sun: Color,
    alpha: Float,
) {
    if (alpha <= 0.003f) return
    val path = Path().apply {
        fillType = PathFillType.EvenOdd
        addOval(Rect(center.x - rx, center.y - ry, center.x + rx, center.y + ry))
        if (holeFraction * rx > 0.5f) {
            addOval(
                Rect(
                    center.x - holeFraction * rx, center.y - holeFraction * ry,
                    center.x + holeFraction * rx, center.y + holeFraction * ry,
                ),
            )
        }
    }
    val level = -0.05f + 1.12f * fill
    val edge = 1f - level                                   // доля высоты диска сверху, с которой начинается заливка
    val from = (edge - 0.04f).coerceIn(0f, 1f)
    val to = (edge + 0.04f).coerceIn(0f, 1f)
    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            0f to word, from to word, to to sun, 1f to sun,
            startY = center.y - ry,
            endY = center.y + ry,
        ),
        alpha = alpha,
    )
}

private fun DrawScope.drawRipple(g: LogoGeo, t: Float, delay: Float, strength: Float, sun: Color, k: Float) {
    val u = clamp01((t - (TR + 0.8f) - delay) / 0.95f)
    if (u <= 0f || u >= 1f) return
    val e = easeOut(u)
    drawCircle(
        color = sun,
        radius = lerp(g.sunRadius, g.sunRadius * 3.3f, e),
        center = g.sunCenter,
        alpha = (1f - u) * strength,
        style = Stroke(width = lerp(6f, 0.5f, e) * k),
    )
}
