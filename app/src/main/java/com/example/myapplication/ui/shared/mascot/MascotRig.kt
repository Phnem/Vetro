package com.example.myapplication.ui.shared.mascot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.sin

/*
 * Риг маскота Vetro (куб с глазами) — перенос SVG-рига из одобренного прототипа на Compose Canvas.
 * Единицы — как в SVG: x = 0 центр, y = 0 линия земли (подошвы), вверх — минус; рост ≈ 272.
 *
 * Корпус — псевдо-3D куб: BODY рисуется стопкой копий от задней грани к передней (задняя чуть меньше —
 * перспектива), лицо лежит на передней грани и уезжает в сторону поворота. Руки — трубки по кривой Безье
 * от бока силуэта к кисти, стык с корпусом скругляется галтелью. В тёмной теме по общему силуэту идёт
 * тёплый контур: сначала обводки всех частей, сверху заливки.
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
        )
    }
}

private fun svgPath(d: String): Path = PathParser().parsePathString(d).toPath()

internal object MascotPaths {
    val body by lazy {
        svgPath("M-102,-205 C-102,-250 -88,-264 -45,-268 L50,-272 C90,-272 104,-252 104,-215 L104,-110 C104,-70 90,-50 50,-48 L-50,-46 C-90,-46 -102,-68 -102,-110 Z")
    }
    val leg by lazy { svgPath("M-14,-12 L-14,34 C-30,34 -32,52 -16,52 L18,52 C32,52 30,34 14,34 L14,-12 Z") }
    val highlightTop by lazy { svgPath("M-88,-226 C-88,-244 -78,-254 -58,-259") }
    val highlightBottom by lazy { svgPath("M-92,-84 C-91,-72 -86,-64 -78,-60") }
    val happyEye by lazy { svgPath("M-13,5 Q0,-16 13,5") }
    val happyGlow by lazy { svgPath("M-14,6 Q0,-18 14,6") }
    val smile by lazy { svgPath("M-10,0 Q0,10 10,0") }
    val sad by lazy { svgPath("M-10,6 Q0,-6 10,6") }
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

/* ───────────── геометрия корпуса и рук ───────────── */

private const val DEPTH = 70f
private const val CY = -160f
private const val BACK = 0.92f
private const val STACK = 9

private class Face(val dx: Float, val dy: Float, val sx: Float, val sy: Float)

/** Грань куба на глубине k ∈ [−1 задняя .. 1 передняя]. */
private fun boxFace(p: MascotPose, k: Float): Face {
    val ya = p.yaw * PI.toFloat() / 180f
    val pa = p.pitch * PI.toFloat() / 180f
    val s = 1 - (1 - BACK) * (1 - k) / 2
    return Face(sin(ya) * DEPTH * k / 2, sin(pa) * DEPTH * k / 2, cos(ya) * s, cos(pa) * s)
}

private inline fun DrawScope.onFace(f: Face, block: DrawScope.() -> Unit) {
    withTransform({
        translate(f.dx, f.dy + CY)
        scale(f.sx, f.sy, Offset.Zero)
        translate(0f, -CY)
    }, block)
}

private fun faceY(f: Face, y: Float) = (y - CY) * f.sy + CY + f.dy

/** Бок силуэта на высоте плеча: x края и прямой участок по y. */
private class Edge(val x: Float, val y: Float, val top: Float, val bottom: Float)

private fun sideEdge(p: MascotPose, side: Int): Edge {
    val ff = boxFace(p, 1f)
    val fb = boxFace(p, -1f)
    val xf = ff.dx + side * 103 * ff.sx
    val xb = fb.dx + side * 103 * fb.sx
    val f = if (side * xf >= side * xb) ff else fb
    return Edge(side * max(side * xf, side * xb), faceY(f, -134f), faceY(f, -205f), faceY(f, -110f))
}

private fun rotv(x: Float, y: Float, deg: Float): Offset {
    val a = deg * PI.toFloat() / 180f
    val c = cos(a)
    val s = sin(a)
    return Offset(x * c - y * s, x * s + y * c)
}

private fun angOf(v: Offset) = atan2(-v.x, v.y) * 180f / PI.toFloat()

private class Arm(val left: List<Offset>, val right: List<Offset>, val hand: Offset, val handAngle: Float) {
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
}

private fun armGeom(side: Int, angle: Float, bend: Float, lag: Float, e: Edge): Arm {
    val len = 60f
    val sx0 = e.x - side * 30f // корень спрятан в боку корпуса
    val sy0 = e.y
    val turn = (-side * bend - lag).coerceIn(-130f, 130f)
    val d = rotv(0f, 1f, angle)
    val chord = len * (1 - 0.2f * (abs(turn) / 130f).pow(2))
    val hx = sx0 + 22f * side + d.x * chord
    val hy = sy0 + 6f + d.y * chord
    val u = rotv(d.x, d.y, -turn / 2)
    val v = rotv(d.x, d.y, turn / 2)
    val kk = chord * 0.4f
    val c1x = sx0 + u.x * kk
    val c1y = sy0 + u.y * kk
    val c2x = hx - v.x * kk
    val c2y = hy - v.y * kk
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
        val w = (46f - 15f * t.pow(0.8f)) / 2f // толще у плеча, тоньше к кисти
        val nx = -ty / l * w
        val ny = tx / l * w
        left += Offset(px + nx, py + ny)
        right += Offset(px - nx, py - ny)
    }
    return Arm(left, right, Offset(hx + v.x * 3, hy + v.y * 3), angOf(v))
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
 * Маскот в своих единицах (вызывающий ставит перенос и масштаб). [handR] рисуется в системе кисти
 * правой руки: начало — центр кисти, +y — вдоль предплечья наружу.
 */
internal fun DrawScope.drawMascot(p: MascotPose, c: MascotPalette, handR: (DrawScope.() -> Unit)? = null) {
    val front = boxFace(p, 1f)
    val eL = sideEdge(p, -1)
    val eR = sideEdge(p, 1)
    val aL = armGeom(-1, p.armL, p.bendL, p.lagL, eL)
    val aR = armGeom(1, p.armR, p.bendR, p.lagR, eR)
    val fil = Path().also {
        fillets(aL, eL, -1, it)
        fillets(aR, eR, 1, it)
    }
    val ya = p.yaw * PI.toFloat() / 180f

    fun DrawScope.legs(color: Color, style: DrawStyle) {
        for ((x0, side, ang) in listOf(Triple(-34f, -1, p.legL), Triple(36f, 1, p.legR))) {
            val hx = x0 * cos(ya) + sin(ya) * 8f
            val hy = -52f - max(0f, -side * sin(ya)) * 4f // дальняя нога чуть выше
            at(hx, hy, rot = ang - p.lean * 0.7f) { drawPath(MascotPaths.leg, color, style = style) }
        }
    }

    fun DrawScope.mitten(a: Arm, color: Color, style: DrawStyle) {
        at(a.hand.x, a.hand.y, rot = a.handAngle) { ellipse(color, 0f, 0f, 17f, 19f, style) }
    }

    fun DrawScope.stack(frontColor: Color, sideColor: Color, style: DrawStyle) {
        for (i in 0..STACK) {
            onFace(boxFace(p, -1f + 2f * i / STACK)) {
                drawPath(MascotPaths.body, if (i == STACK) frontColor else sideColor, style = style)
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

    at(0f, 0f, rot = p.lean) {
        // контур по общему силуэту (тёмная тема)
        c.rim?.let { rim ->
            val rs = stroke(5f)
            legs(rim, rs)
            torso {
                drawPath(aL.tube, rim, style = rs)
                drawPath(aR.tube, rim, style = rs)
                mitten(aL, rim, rs)
                mitten(aR, rim, rs)
                drawPath(fil, rim, style = rs)
                stack(rim, rim, rs)
            }
        }
        legs(c.body, Fill)
        torso {
            drawPath(aL.tube, c.body)
            mitten(aL, c.body, Fill)
            drawPath(aR.tube, c.body)
            mitten(aR, c.body, Fill)
            handR?.let { draw -> at(aR.hand.x, aR.hand.y, rot = aR.handAngle) { draw() } }
            drawPath(fil, c.body)
            stack(c.body, c.body, Fill)
            onFace(front) {
                drawPath(MascotPaths.highlightTop, Color.White.copy(alpha = 0.12f), style = stroke(4f))
                drawPath(MascotPaths.highlightBottom, Color.White.copy(alpha = 0.1f), style = stroke(3f))
            }
            // лицо на передней грани: сжимается по cos(yaw) и чуть «обгоняет» поворот — так читается объём
            withTransform({
                translate(front.dx * 1.25f + p.lookX, front.dy * 1.25f + p.lookY - 150f)
                scale(front.sx, front.sy, Offset.Zero)
                translate(0f, 150f)
            }) { drawFace(p, c) }
            if (p.frontR > 0.001f) {
                val a = clamp01(p.frontR)
                drawPath(aR.tube, c.body.copy(alpha = a))
                drawPath(aR.edges, c.frontLine.copy(alpha = c.frontLine.alpha * a), style = stroke(2.6f))
                mitten(aR, c.body.copy(alpha = a), Fill)
                mitten(aR, c.frontLine.copy(alpha = c.frontLine.alpha * a), stroke(2.6f))
            }
        }
    }
}

private fun DrawScope.drawFace(p: MascotPose, c: MascotPalette) {
    val open = 1 - p.happy
    val glowAlpha = if (c.dark) 0.8f else 0.55f
    if (open > 0.001f) {
        for ((x, y) in listOf(-40f to -166f, 42f to -168f)) {
            at(x, y, sx = p.eyeS, sy = p.eyeS * p.eyeSY) {
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
        }
    }
    if (p.happy > 0.001f) {
        for (x in listOf(-40f, 42f)) {
            at(x, -160f) {
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
    }
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
