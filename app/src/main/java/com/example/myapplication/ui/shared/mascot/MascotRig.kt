package com.example.myapplication.ui.shared.mascot

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import com.example.myapplication.ui.shared.theme.BrandOrange
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/*
 * Риг маскота Vetro — перенос SVG-рига из одобренного прототипа (.scratch/empty-states-mascot/mascot.html)
 * на Compose Canvas. Единицы — как в SVG: x = 0 центр, y = 0 линия земли (подошвы), вверх — минус; рост ≈ 272.
 *
 * Корпус — «обмылок»: суперэллипсоид |x/BA|^n + |y/BB|^n + |z/BD|^n ≤ 1, повёрнутый yaw/pitch и спроецированный
 * ортографически. Силуэт выпуклый и считается точно (опорная функция через двойственную норму), поэтому при любом
 * повороте контур гладкий, без граней. Лицо лежит на плоскости передней грани; объём — один непрерывный
 * радиальный градиент в той же плоскости + тень снизу (без ступенек). Руки — трубки по кривой Безье от бока
 * силуэта к кисти, стык с корпусом скругляется галтелью; рука перед телом вырастает из корпуса через маску.
 * В тёмной теме по общему силуэту идёт тёплый контур: сначала обводки всех частей, сверху заливки.
 */

internal val MascotOrange = BrandOrange

internal class MascotPalette(
    val dark: Boolean,
    val blob: Color,
    val shadow: Color,
    val body: Color,
    val rim: Color?,
    val eye: Color,
    val mouth: Color,
    val paper: Color,
    val paperDeep: Color,
    val bubble: Color,
    val frontLine: Color,
    val lensFill: Color,
    /** Передняя грань — светлее боков, к рёбрам плавно гаснет. */
    val front: Color,
    /** Сила тени снизу корпуса и блика сверху-слева. */
    val shadeK: Float,
    val hiK: Float,
    /** Заливка открытого рта. */
    val mouthIn: Color,
) {
    companion object {
        val Light = MascotPalette(
            dark = false,
            blob = Color(0xFFFDE9DD),
            shadow = Color(0xFFF9D5C1),
            body = Color(0xFF2B3038),
            rim = null,
            eye = Color(0xFFFFD3AE),
            mouth = Color(0xFFE9B7A0),
            paper = Color(0xFFFFFAF6),
            paperDeep = Color(0xFFF9D5C1),
            bubble = Color.White,
            frontLine = Color.White.copy(alpha = 0.38f),
            lensFill = Color.White.copy(alpha = 0.6f),
            front = Color(0xFF3C434F),
            shadeK = 0.16f,
            hiK = 0.06f,
            mouthIn = Color(0xFF6B3324),
        )

        // Тёмная тема: корпус почти чёрный, силуэт держат тёплое пятно за маскотом и контур.
        val Dark = MascotPalette(
            dark = true,
            blob = MascotOrange.copy(alpha = 0.16f),
            shadow = Color.Black.copy(alpha = 0.6f),
            body = Color(0xFF16181C),
            rim = Color(0xFFFFB067).copy(alpha = 0.22f),
            eye = Color(0xFFFFD3AE),
            mouth = Color(0xFFE9B7A0),
            paper = Color(0xFF1A1412),
            paperDeep = Color(0xFF2A1A12),
            bubble = Color(0xFF171717),
            frontLine = Color(0xFFFFD3AE).copy(alpha = 0.38f),
            lensFill = Color(0xFFFFD3AE).copy(alpha = 0.16f),
            front = Color(0xFF2C3038),
            shadeK = 0.4f,
            hiK = 0.07f,
            mouthIn = Color(0xFF4A2016),
        )
    }
}

internal fun svgPath(d: String): Path = PathParser().parsePathString(d).toPath()

internal object MascotPaths {
    val leg by lazy { svgPath("M-14,-12 L-14,34 C-30,34 -32,52 -16,52 L18,52 C32,52 30,34 14,34 L14,-12 Z") }
    val happyEye by lazy { svgPath("M-13,5 Q0,-16 13,5") }
    val happyGlow by lazy { svgPath("M-14,6 Q0,-18 14,6") }
    val smile by lazy { svgPath("M-10,0 Q0,10 10,0") }
    val sad by lazy { svgPath("M-10,6 Q0,-6 10,6") }
    val grin by lazy { svgPath("M-13,-2 Q0,0 13,-2 Q11,15 0,15 Q-11,15 -13,-2 Z") }
    val lensGlint by lazy { svgPath("M-14,6 Q-18,-12 -2,-16") }
    val question by lazy { svgPath("M-15,-16 C-15,-38 19,-38 19,-16 C19,-2 3,0 3,15") }
    val heart by lazy { svgPath("M0,14 C-26,-3 -16,-28 0,-14 C16,-28 26,-3 0,14 Z") }
    val bubble by lazy {
        svgPath("M-40,12 C-62,12 -64,-18 -42,-21 C-45,-45 -10,-52 0,-36 C10,-54 46,-46 43,-23 C66,-20 64,12 41,12 Z")
    }
    val boxBack by lazy { svgPath("M-55,-62 L-44,-84 L44,-84 L55,-62 Z") }
    val boxLid by lazy { svgPath("M-55,22 L-44,0 L44,0 L55,22 Z") }
    val blobs by lazy {
        listOf(
            svgPath("M38,160 C22,86 96,34 168,44 C240,30 286,92 272,156 C292,214 244,264 176,266 C112,280 50,254 46,206 C44,186 42,174 38,160 Z"),
            svgPath("M30,140 C34,76 110,28 180,40 C250,44 290,104 270,168 C262,230 200,270 140,262 C70,262 24,210 30,140 Z"),
            svgPath("M50,120 C70,60 150,26 214,48 C268,70 296,130 270,190 C250,244 180,272 120,258 C60,244 30,180 50,120 Z"),
        )
    }
}

/* ───────────── трансформации и примитивы ───────────── */

/** translate → rotate → scale, все вокруг локального нуля (как SVG transform). */
internal inline fun DrawScope.at(
    tx: Float,
    ty: Float,
    rot: Float = 0f,
    sx: Float = 1f,
    sy: Float = sx,
    block: DrawScope.() -> Unit,
) {
    withTransform({
        translate(tx, ty)
        if (rot != 0f) rotate(rot, Offset.Zero)
        if (sx != 1f || sy != 1f) scale(sx, sy, Offset.Zero)
    }, block)
}

internal fun DrawScope.ellipse(color: Color, cx: Float, cy: Float, rx: Float, ry: Float, style: DrawStyle = Fill) {
    drawOval(color, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2, ry * 2), style = style)
}

internal fun DrawScope.line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
    drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = width, cap = StrokeCap.Round)
}

internal fun stroke(width: Float) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Ломаная из точек [pts] (по желанию замкнутая). */
internal fun polyPath(pts: List<Offset>, close: Boolean = true, into: Path = Path()): Path = into.apply {
    if (pts.isEmpty()) return@apply
    moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    if (close) close()
}

private val layerBounds = Rect(-600f, -800f, 900f, 600f)

/**
 * Слой с маской растворения: всё, что нарисовано в [block], у точки [from] прозрачно и к [to] проявляется
 * полностью (как svg-mask с линейным градиентом). [alpha] — прозрачность слоя целиком.
 */
internal inline fun DrawScope.fadeLayer(from: Offset, to: Offset, alpha: Float, startStop: Float, block: DrawScope.() -> Unit) {
    drawIntoCanvas { it.saveLayer(layerBounds, Paint().apply { this.alpha = alpha }) }
    block()
    drawRect(
        Brush.linearGradient(startStop to Color.Transparent, 1f to Color.Black, start = from, end = to),
        topLeft = layerBounds.topLeft,
        size = layerBounds.size,
        blendMode = BlendMode.DstIn,
    )
    drawIntoCanvas { it.restore() }
}

/** Слой с общей прозрачностью: перекрытия внутри не темнеют (как opacity у svg-группы). */
internal inline fun DrawScope.alphaLayer(alpha: Float, block: DrawScope.() -> Unit) {
    drawIntoCanvas { it.saveLayer(layerBounds, Paint().apply { this.alpha = alpha }) }
    block()
    drawIntoCanvas { it.restore() }
}

/* ───────────── корпус-«обмылок» ───────────── */

internal const val BA = 104f
internal const val BB = 112f
internal const val BD = 72f
private const val BN = 4.2f
internal const val BCY = -159f
private const val BM = BN / (BN - 1f)

/** Поворот корпуса: строки r0, r1 матрицы Rx(pitch)·Ry(yaw) (r01 = 0). */
internal class BodyRot(p: MascotPose) {
    private val ya = p.yaw * PI.toFloat() / 180f
    private val pa = p.pitch * PI.toFloat() / 180f
    val cp = cos(pa)
    val r00 = cos(ya)
    val r02 = sin(ya)
    val r10 = -sin(pa) * sin(ya)
    val r11 = cos(pa)
    val r12 = sin(pa) * cos(ya)

    fun project(x: Float, y: Float, z: Float) = Offset(r00 * x + r02 * z, r10 * x + r11 * y + r12 * z + BCY)

    /** Плоскость передней грани (z = BD) → экран: лицо лежит на ней и сжимается/сдвигается с поворотом. */
    fun facePlane(): Matrix = affine(r00, r10, 0f, r11, r02 * BD, r12 * BD + BCY - r11 * BCY)
}

internal fun affine(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float): Matrix = Matrix().apply {
    values[Matrix.ScaleX] = a
    values[Matrix.SkewY] = b
    values[Matrix.SkewX] = c
    values[Matrix.ScaleY] = d
    values[Matrix.TranslateX] = e
    values[Matrix.TranslateY] = f
}

private fun spow(v: Float, e: Float) = sign(v) * abs(v).pow(e)

/** Экранный силуэт: опорные точки по направлениям 0..2π, близкие точки склеены. */
internal fun silhouette(r: BodyRot, n: Int = 180): List<Offset> {
    val out = ArrayList<Offset>(n)
    for (i in 0 until n) {
        val th = TAU * i / n
        val ex = cos(th)
        val ey = sin(th)
        val w0 = BA * (r.r00 * ex + r.r10 * ey)
        val w1 = BB * (r.r11 * ey)
        val w2 = BD * (r.r02 * ex + r.r12 * ey)
        val nm = (abs(w0).pow(BM) + abs(w1).pow(BM) + abs(w2).pow(BM)).pow(1f / BM).takeIf { it > 0f } ?: 1f
        val q = r.project(BA * spow(w0 / nm, BM - 1), BB * spow(w1 / nm, BM - 1), BD * spow(w2 / nm, BM - 1))
        val last = out.lastOrNull()
        if (last == null || (q - last).getDistance() > 1.2f) out += q
    }
    return out
}

/** Бок силуэта на высоте плеча: x края, диапазон по y для галтелей, s3x — плечо на боковой грани. */
private class Edge(var x: Float, val y: Float, val top: Float, val bottom: Float, val s3x: Float)

private fun sideEdge(p: MascotPose, side: Int): Edge {
    val r = BodyRot(p)
    val sil = silhouette(r, 120)
    val s3 = r.project(side * BA * 0.82f, 26f, 0f)
    val ys = s3.y
    var x = side * BA
    var found = false
    for (i in sil.indices) {
        val a = sil[i]
        val b = sil[(i + 1) % sil.size]
        if ((a.y - ys) * (b.y - ys) <= 0f && a.y != b.y) {
            val xx = a.x + (b.x - a.x) * (ys - a.y) / (b.y - a.y)
            if (!found || side * xx > side * x) {
                x = xx
                found = true
            }
        }
    }
    return Edge(x, ys, ys - 70f * r.cp, ys + 24f * r.cp, s3.x)
}

/* ───────────── руки ───────────── */

internal fun rotv(x: Float, y: Float, deg: Float): Offset {
    val a = deg * PI.toFloat() / 180f
    val c = cos(a)
    val s = sin(a)
    return Offset(x * c - y * s, x * s + y * c)
}

internal fun dirv(deg: Float) = rotv(0f, 1f, deg)

private fun angOf(v: Offset) = atan2(-v.x, v.y) * 180f / PI.toFloat()

internal class Arm(val left: List<Offset>, val right: List<Offset>, val hand: Offset, val handAngle: Float) {
    val tube: Path = Path().apply {
        moveTo(left[0].x, left[0].y)
        for (i in 1 until left.size) lineTo(left[i].x, left[i].y)
        for (i in right.indices.reversed()) lineTo(right[i].x, right[i].y)
        close()
    }
    val edges: Path = Path().apply {
        moveTo(left[0].x, left[0].y)
        for (i in 1 until left.size) lineTo(left[i].x, left[i].y)
        moveTo(right[0].x, right[0].y)
        for (i in 1 until right.size) lineTo(right[i].x, right[i].y)
    }
    val root: Offset get() = (left[0] + right[0]) / 2f
}

private fun armGeom(side: Int, angle: Float, bend: Float, lag: Float, e: Edge, reach: Float = 0f): Arm {
    val len = 60f
    val sx0 = e.x - side * 30f // корень спрятан в боку корпуса
    val sy0 = e.y
    val turn = (-side * bend - lag).coerceIn(-130f, 130f)
    val d = rotv(0f, 1f, angle)
    val chord = len * (1 - 0.2f * (abs(turn) / 130f).pow(2))
    var hx = sx0 + 22f * side + d.x * chord
    var hy = sy0 + 6f + d.y * chord
    val u = rotv(d.x, d.y, -turn / 2)
    val v = rotv(d.x, d.y, turn / 2)
    val kk = chord * 0.4f
    var c1x = sx0 + u.x * kk
    var c1y = sy0 + u.y * kk
    var c2x = hx - v.x * kk
    var c2y = hy - v.y * kk
    // Рука тянется к зрителю (сцена «палец с отпечатком»): кисть уходит в точку у камеры,
    // трубка к запястью расширяется.
    if (reach > 0f) {
        hx += (124f - hx) * reach
        hy += (-132f - hy) * reach
        c1x += (sx0 + 27f - c1x) * reach
        c1y += (sy0 + 41f - c1y) * reach
        c2x += (117f - c2x) * reach
        c2y += (-74f - c2y) * reach
    }
    val n = 30
    val left = ArrayList<Offset>(n + 1)
    val right = ArrayList<Offset>(n + 1)
    for (i in 0..n) {
        val t = i.toFloat() / n
        val m = 1 - t
        val px = m * m * m * sx0 + 3 * m * m * t * c1x + 3 * m * t * t * c2x + t * t * t * hx
        val py = m * m * m * sy0 + 3 * m * m * t * c1y + 3 * m * t * t * c2y + t * t * t * hy
        val tx = 3 * m * m * (c1x - sx0) + 6 * m * t * (c2x - c1x) + 3 * t * t * (hx - c2x)
        val ty = 3 * m * m * (c1y - sy0) + 6 * m * t * (c2y - c1y) + 3 * t * t * (hy - c2y)
        val l = hypot(tx, ty).takeIf { it > 0f } ?: 1f
        val w = (46f - 15f * t.pow(0.8f)) / 2f + reach * 10f * t.pow(1.6f) // толще у плеча, тоньше к кисти
        val nx = -ty / l * w
        val ny = tx / l * w
        left += Offset(px + nx, py + ny)
        right += Offset(px - nx, py - ny)
    }
    val endLen = hypot(hx - c2x, hy - c2y).takeIf { it > 0f } ?: 1f
    val end = Offset((hx - c2x) / endLen, (hy - c2y) / endLen)
    return Arm(left, right, Offset(hx + end.x * 3, hy + end.y * 3), angOf(end))
}

/** Рука стороны [side] с краем корпуса; inL/inR уводят корень на плечо боковой грани (рука перед телом). */
private fun armFor(p: MascotPose, side: Int, reach: Float = 0f): Pair<Edge, Arm> {
    val e = sideEdge(p, side)
    val k = clamp01(if (side < 0) p.inL else p.inR)
    if (k > 0f) e.x += (e.s3x - side * 32f - e.x) * k
    val arm = if (side < 0) armGeom(-1, p.armL, p.bendL, p.lagL, e) else armGeom(1, p.armR, p.bendR, p.lagR, e, reach)
    return e to arm
}

/** Кисть стороны [side] в координатах маскота: центр, поворот кисти и полный поворот в мире. */
internal class HandFrame(val x: Float, val y: Float, val angle: Float, val total: Float)

internal fun handFrame(p: MascotPose, side: Int): HandFrame {
    val a = armFor(p, side).second
    return HandFrame(a.hand.x, a.hand.y, a.handAngle, a.handAngle + p.rot + p.lean)
}

private class Hit(val j: Offset, val i: Int, val pts: List<Offset>)

/** Галтели: там, где край трубки выходит из бока корпуса, — плавная перепонка вместо угла. */
private fun fillets(a: Arm, e: Edge, side: Int, into: Path) {
    fun cross(pts: List<Offset>): Hit? {
        val ex = side * e.x
        for (i in 1 until pts.size) {
            val x0 = side * pts[i - 1].x
            val x1 = side * pts[i].x
            if (x0 <= ex && x1 > ex) {
                val f = (ex - x0) / (x1 - x0)
                return Hit(pts[i - 1] + (pts[i] - pts[i - 1]) * f, i, pts)
            }
        }
        return null
    }

    val hits = listOfNotNull(cross(a.left), cross(a.right))
        .filter { it.j.y > e.top - 4 && it.j.y < e.bottom + 4 }
        .sortedBy { it.j.y }
    hits.forEachIndexed { idx, h ->
        val up = if (hits.size == 1) h.j.y < e.y else idx == 0
        val r = if (up) 18f else 12f
        var b = h.pts.last()
        var acc = 0f
        var prev = h.j
        for (i in h.i until h.pts.size) {
            acc += (h.pts[i] - prev).getDistance()
            prev = h.pts[i]
            if (acc >= r) {
                b = h.pts[i]
                break
            }
        }
        val ay = (h.j.y + if (up) -r else r).coerceIn(e.top, e.bottom)
        into.moveTo(e.x, ay)
        into.quadraticTo(h.j.x, h.j.y, b.x, b.y)
        into.lineTo(e.x - side * 16f, (ay + h.j.y) / 2)
        into.close()
    }
}

/* ───────────── отрисовка ───────────── */

/**
 * Дополнения сцены к ригу. [handLFront] — предмет в левой кисти поверх корпуса (система кисти; аргумент —
 * полный поворот кисти, чтобы держать предмет ровно). [pinkySide]/[pinkyAngle] — отставленный мизинчик
 * (направление в мире). [legsBack]/[legsFront] заменяют обычные ноги: задние — под корпусом,
 * передние (заливка и светлый контур) — поверх него.
 */
internal class MascotExtras(
    val handLFront: (DrawScope.(total: Float) -> Unit)? = null,
    val pinkySide: Int = 0,
    val pinkyAngle: Float = 0f,
    val legsBack: (DrawScope.(color: Color, style: DrawStyle) -> Unit)? = null,
    val legsFront: (DrawScope.(fill: Color, line: Color) -> Unit)? = null,
    /** Свои руки вместо трубок с варежками (например ладони у глаз); null — обычные руки. */
    val arms: ((left: Arm, right: Arm) -> MascotArmLayers?)? = null,
    /** Своя кисть правой руки; [color] и [style] — те же, что у обычной варежки в этом проходе. */
    val mittenR: (DrawScope.(arm: Arm, color: Color, style: DrawStyle) -> Unit)? = null,
    /** 0..1 — насколько правая рука вытянута к зрителю. */
    val reachR: Float = 0f,
    /** Видимость глаз по отдельности и сила их свечения (когда глаза закрыты ладонями). */
    val eyeAlphaL: Float = 1f,
    val eyeAlphaR: Float = 1f,
    val eyeGlow: Float = 1f,
)

/** Свои руки в два прохода: [back] — вместе с корпусом (контур и заливка), [front] — поверх лица. */
internal class MascotArmLayers(
    val back: DrawScope.(color: Color, style: DrawStyle) -> Unit,
    val front: DrawScope.() -> Unit,
)

/**
 * Маскот в своих единицах (вызывающий ставит перенос и масштаб). [handR] рисуется в системе кисти
 * правой руки: начало — центр кисти, +y — вдоль предплечья наружу.
 */
internal fun DrawScope.drawMascot(
    p: MascotPose,
    c: MascotPalette,
    handR: (DrawScope.() -> Unit)? = null,
    extras: MascotExtras? = null,
) {
    val r = BodyRot(p)
    val sil = polyPath(silhouette(r))
    val (eL, aL) = armFor(p, -1)
    val (eR, aR) = armFor(p, 1, extras?.reachR ?: 0f)
    val armLayers = extras?.arms?.invoke(aL, aR)
    val fil = Path().also {
        if (armLayers == null) {
            fillets(aL, eL, -1, it)
            fillets(aR, eR, 1, it)
        }
    }
    val ya = p.yaw * PI.toFloat() / 180f
    val totL = aL.handAngle + p.rot + p.lean
    val totR = aR.handAngle + p.rot + p.lean

    fun DrawScope.legs(color: Color, style: DrawStyle) {
        val custom = extras?.legsBack
        if (custom != null) {
            custom(color, style)
            return
        }
        for ((x0, side, ang) in listOf(Triple(-34f, -1, p.legL), Triple(36f, 1, p.legR))) {
            val hx = x0 * cos(ya) + sin(ya) * 8f
            val hy = -52f - max(0f, -side * sin(ya)) * 4f // дальняя нога чуть выше
            at(hx, hy, rot = ang - p.lean * 0.7f) { drawPath(MascotPaths.leg, color, style = style) }
        }
    }

    fun DrawScope.mitten(a: Arm, color: Color, style: DrawStyle) {
        val custom = extras?.mittenR
        if (a === aR && custom != null) {
            custom(a, color, style)
            return
        }
        at(a.hand.x, a.hand.y, rot = a.handAngle) { ellipse(color, 0f, 0f, 17f, 19f, style) }
        val side = if (a === aL) -1 else 1
        if (extras != null && extras.pinkySide == side) {
            val tot = if (side < 0) totL else totR
            at(a.hand.x, a.hand.y, rot = a.handAngle) {
                at(0f, 0f, rot = extras.pinkyAngle - tot) {
                    drawRoundRect(color, Offset(-5f, 8f), Size(10f, 22f), CornerRadius(5f), style = style)
                }
            }
        }
    }

    fun DrawScope.torso(block: DrawScope.() -> Unit) {
        withTransform({
            translate(0f, -48f + p.y)
            rotate(p.rot, Offset.Zero)
            scale(p.sx, p.sy, Offset.Zero)
            translate(0f, 48f)
        }, block)
    }

    // рука перед телом: корень растворяется в корпусе, контур проявляется к кисти — без шва
    fun DrawScope.frontArm(a: Arm, k: Float) {
        if (k <= 0.001f) return
        val s = a.root
        fadeLayer(s, s + (a.hand - s) * 0.55f, clamp01(k), 0.15f) {
            drawPath(a.tube, c.body)
            drawPath(a.edges, c.frontLine, style = stroke(2.6f))
            mitten(a, c.body, Fill)
            mitten(a, c.frontLine, stroke(2.6f))
        }
    }

    at(0f, 0f, rot = p.lean) {
        // контур по общему силуэту (тёмная тема)
        c.rim?.let { rim ->
            val rs = stroke(5f)
            legs(rim, rs)
            torso {
                if (armLayers != null) {
                    armLayers.back(this, rim, rs)
                } else {
                    drawPath(aL.tube, rim, style = rs)
                    drawPath(aR.tube, rim, style = rs)
                    mitten(aL, rim, rs)
                    mitten(aR, rim, rs)
                }
                drawPath(fil, rim, style = rs)
                drawPath(sil, rim, style = rs)
            }
        }
        legs(c.body, Fill)
        torso {
            if (armLayers != null) {
                armLayers.back(this, c.body, Fill)
            } else {
                drawPath(aL.tube, c.body)
                mitten(aL, c.body, Fill)
                drawPath(aR.tube, c.body)
                mitten(aR, c.body, Fill)
            }
            handR?.let { draw -> at(aR.hand.x, aR.hand.y, rot = aR.handAngle) { draw() } }
            drawPath(fil, c.body)
            drawPath(sil, c.body)
            drawBodyVolume(sil, r, c)
            withTransform({
                translate(p.lookX, p.lookY)
                transform(r.facePlane())
            }) { drawFace(p, c, extras?.eyeAlphaL ?: 1f, extras?.eyeAlphaR ?: 1f, extras?.eyeGlow ?: 1f) }
        }
        extras?.legsFront?.invoke(this, c.body, c.frontLine)
        torso {
            if (armLayers != null) {
                armLayers.front(this)
            } else {
                frontArm(aR, p.frontR)
                frontArm(aL, p.frontL)
            }
            extras?.handLFront?.let { draw -> at(aL.hand.x, aL.hand.y, rot = aL.handAngle) { draw(totL) } }
        }
    }
}

/** Объём корпуса: светлая передняя грань (градиент в её плоскости), тень снизу, блик сверху-слева. */
private fun DrawScope.drawBodyVolume(sil: Path, r: BodyRot, c: MascotPalette) {
    val b = sil.getBounds()
    clipPath(sil) {
        withTransform({
            transform(r.facePlane())
            translate(0f, BCY)
            scale(BA * 0.95f, BB * 0.95f, Offset.Zero)
        }) {
            drawCircle(
                Brush.radialGradient(
                    0f to c.front,
                    0.45f to c.front.copy(alpha = 0.8f),
                    0.78f to c.front.copy(alpha = 0.32f),
                    1f to c.front.copy(alpha = 0f),
                    center = Offset.Zero,
                    radius = 1f,
                ),
                radius = 1f,
                center = Offset.Zero,
            )
        }
        drawRect(
            Brush.verticalGradient(
                0.45f to Color.Transparent,
                1f to Color.Black.copy(alpha = c.shadeK),
                startY = b.top,
                endY = b.bottom,
            ),
            topLeft = b.topLeft,
            size = b.size,
        )
        at(b.left + b.width * 0.3f, b.top + b.height * 0.18f, sx = b.width * 0.62f, sy = b.height * 0.62f) {
            drawCircle(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = c.hiK),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = 1f,
                ),
                radius = 1f,
                center = Offset.Zero,
            )
        }
    }
}

private fun DrawScope.drawFace(
    p: MascotPose,
    c: MascotPalette,
    eyeAlphaL: Float,
    eyeAlphaR: Float,
    eyeGlow: Float,
) {
    val open = 1 - p.happy
    val glowAlpha = (if (c.dark) 0.8f else 0.55f) * eyeGlow
    if (open > 0.001f) {
        listOf(-40f to -166f, 42f to -168f).forEachIndexed { i, (x, y) ->
            val visible = if (i == 0) eyeAlphaL else eyeAlphaR
            if (visible <= 0.001f) return@forEachIndexed
            alphaLayerIf(visible) { at(x, y, sx = p.eyeS * p.eyeSX, sy = p.eyeS * p.eyeSY) {
                val lidRot = (if (i == 0) 1 else -1) * 13f * p.lid
                withTransform({
                    // прищур: наклонная «шторка» срезает верх глаза вместе с ореолом
                    if (p.lid > 0.001f) {
                        rotate(lidRot, Offset.Zero)
                        clipRect(-40f, -24f + 27f * p.lid, 40f, 60f)
                        rotate(-lidRot, Offset.Zero)
                    }
                }) {
                    // свечение глаза: мягкий ореол вместо гауссова размытия
                    at(0f, 0f, sx = 1f, sy = 20f / 14f) {
                        drawCircle(
                            Brush.radialGradient(
                                0f to MascotOrange.copy(alpha = glowAlpha * open),
                                0.5f to MascotOrange.copy(alpha = glowAlpha * open * 0.8f),
                                1f to Color.Transparent,
                                center = Offset.Zero,
                                radius = 22f,
                            ),
                            radius = 22f,
                            center = Offset.Zero,
                        )
                    }
                    ellipse(c.eye.copy(alpha = open), 0f, 0f, 11.5f, 17.5f)
                    ellipse(MascotOrange.copy(alpha = open), 0f, 0f, 11.5f, 17.5f, Stroke(3.5f))
                    ellipse(Color.White.copy(alpha = 0.55f * open), -2f, -6f, 4f, 6f)
                }
            } }
        }
    }
    if (p.happy > 0.001f) {
        for (x in listOf(-40f, 42f)) {
            at(x, -160f, sx = p.eyeSX, sy = 1f) {
                drawPath(MascotPaths.happyGlow, MascotOrange.copy(alpha = 0.22f * p.happy), style = stroke(15f))
                drawPath(MascotPaths.happyGlow, MascotOrange.copy(alpha = 0.3f * p.happy), style = stroke(9f))
                drawPath(MascotPaths.happyEye, MascotOrange.copy(alpha = p.happy), style = stroke(4.5f))
            }
        }
    }
    at(1f, -134f) {
        val s = stroke(3.6f)
        if (p.smile > 0.001f) drawPath(MascotPaths.smile, c.mouth.copy(alpha = clamp01(p.smile)), style = s)
        if (p.sad > 0.001f) drawPath(MascotPaths.sad, c.mouth.copy(alpha = clamp01(p.sad)), style = s)
        if (p.o > 0.001f) ellipse(c.mouth.copy(alpha = clamp01(p.o)), 0f, 3f, 4f + p.o, 5f + 1.5f * p.o, s)
        if (p.grin > 0.001f) {
            val g = clamp01(p.grin)
            drawPath(MascotPaths.grin, c.mouthIn.copy(alpha = g))
            drawPath(MascotPaths.grin, c.mouth.copy(alpha = g), style = s)
        }
    }
}

/** [alphaLayer] только когда он нужен: при полной видимости лишний слой не создаётся. */
private inline fun DrawScope.alphaLayerIf(alpha: Float, block: DrawScope.() -> Unit) {
    if (alpha >= 0.999f) block() else alphaLayer(alpha, block)
}

/** Лупа в кисти: начало — центр кисти, лупа смотрит вдоль +y. */
internal fun DrawScope.drawMagnifier(c: MascotPalette, glint: Float) {
    line(MascotOrange, 0f, 0f, 0f, 38f, 9f)
    at(0f, 64f) {
        drawCircle(c.lensFill, 27f, Offset.Zero)
        drawCircle(MascotOrange, 27f, Offset.Zero, style = Stroke(7.5f))
        drawPath(MascotPaths.lensGlint, Color.White.copy(alpha = glint), style = stroke(4f))
        drawCircle(Color.White.copy(alpha = 0.35f), 27f, Offset.Zero, style = Stroke(2f))
    }
    drawCircle(c.body, 18f, Offset(0f, 2f))
}
