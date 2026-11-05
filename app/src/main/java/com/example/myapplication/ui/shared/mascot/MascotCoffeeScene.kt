package com.example.myapplication.ui.shared.mascot

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * AnimD · «Кофе со звездой» — перенос одобренной сцены прототипа (.scratch/empty-states-mascot/mascot.html,
 * SCENES.coffee) один в один. Сидит в кресле нога на ногу: чашка в левой руке с отставленным мизинцем, звезда
 * в правой. Цикл 9 с: подбрасывает звезду (улыбка до ушей) → ловит, самодовольный прищур → поворачивается
 * вправо почти в профиль, чашка проходит перед телом ко рту и «оборачивается» вокруг кулака → глоток с
 * глазами-дугами → обратно к зрителю.
 *
 * Пар — частицы, выпущенные из чашки в прошлые моменты: позиция чашки тогда + часть её скорости (гаснет) +
 * всплытие; поэтому шлейф сам тянется за движением чашки. Smear-кадры (умеренно): кисть на броске тянет хвост,
 * звезда вытягивается вдоль полёта, на быстром повороте глаза растягиваются по горизонтали.
 */

/** Аффинная матрица (a, b, c, d, e, f) как в SVG — мировые координаты кисти (звезда, пар из чашки). */
private class Aff(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    operator fun times(n: Aff) = Aff(
        a * n.a + c * n.b, b * n.a + d * n.b,
        a * n.c + c * n.d, b * n.c + d * n.d,
        a * n.e + c * n.f + e, b * n.e + d * n.f + f,
    )

    fun ap(x: Float, y: Float) = Offset(a * x + c * y + e, b * x + d * y + f)

    companion object {
        fun t(x: Float, y: Float) = Aff(1f, 0f, 0f, 1f, x, y)
        fun s(x: Float, y: Float = x) = Aff(x, 0f, 0f, y, 0f, 0f)
        fun r(deg: Float): Aff {
            val a = deg * PI.toFloat() / 180f
            return Aff(cos(a), sin(a), -sin(a), cos(a), 0f, 0f)
        }
    }
}

private const val CUP_R = 41f
private fun cupX(phi: Float) = -CUP_R * cos(phi)

private object CoffeePaths {
    val handle by lazy { svgPath("M-17,-13 C3,-16 3,9 -21,6") }
    val foot by lazy { svgPath("M-51,14 L-31,14 L-28,20 L-54,20 Z") }
    val cup by lazy { svgPath("M-67,-20 L-15,-20 C-15,1 -25,12 -33,15 L-49,15 C-57,12 -67,1 -67,-20 Z") }
    val shadeFar by lazy { svgPath("M-61,-11 C-59,1 -53,8 -45,11") }
    val shadeNear by lazy { svgPath("M-21,-11 C-23,1 -29,8 -37,11") }
    val band by lazy { svgPath("M-65,-12 Q-41,-6 -17,-12") }
    val spark by lazy { svgPath("M0,-7 Q1.2,-1.2 7,0 Q1.2,1.2 0,7 Q-1.2,1.2 -7,0 Q-1.2,-1.2 0,-7 Z") }
    val star by lazy {
        polyPath((0 until 10).map { i ->
            val r = if (i % 2 == 1) 6.8f else 15.5f
            val a = -PI.toFloat() / 2 + i * PI.toFloat() / 5
            Offset(r * cos(a), r * sin(a))
        })
    }

    /**
     * Нижняя нога растёт из своего бедра (справа на экране) и выходит из-под колена верхней, а не из
     * середины низа корпуса: корень спрятан под верхним бедром, поверх корпуса видна только голень.
     */
    private val farSides by lazy { bezSides(Offset(40f, -74f), Offset(43f, -58f), Offset(47f, -36f), Offset(50f, -14f), 30f, 27f) }
    val farLeg by lazy { polyPath(farSides.first + farSides.second.asReversed()) }
    val farEdges by lazy {
        Path().also {
            polyPath(farSides.first.subList(3, 18), close = false, into = it)
            polyPath(farSides.second.subList(3, 18), close = false, into = it)
        }
    }
}

/** Левый и правый края трубки по кубической Безье s → c1 → c2 → h с толщиной w0 → w1 (n + 1 точек). */
private fun bezSides(s: Offset, c1: Offset, c2: Offset, h: Offset, w0: Float, w1: Float, n: Int = 20): Pair<List<Offset>, List<Offset>> {
    val left = ArrayList<Offset>(n + 1)
    val right = ArrayList<Offset>(n + 1)
    for (i in 0..n) {
        val t = i.toFloat() / n
        val m = 1 - t
        val p = s * (m * m * m) + c1 * (3 * m * m * t) + c2 * (3 * m * t * t) + h * (t * t * t)
        val tg = (c1 - s) * (3 * m * m) + (c2 - c1) * (6 * m * t) + (h - c2) * (3 * t * t)
        val len = tg.getDistance().takeIf { it > 0f } ?: 1f
        val w = (w0 + (w1 - w0) * t) / 2
        val nrm = Offset(-tg.y / len * w, tg.x / len * w)
        left += p + nrm
        right += p - nrm
    }
    return left to right
}

private val StarCore = Color(0xFFFFD36B)
private val Gold = Color(0xFFF2B33D)
private val CoffeeBrown = Color(0xFF5B3320)

internal object CoffeeScene : SceneSpec(loop = 9f, blob = 1, offsetX = 12f, still = 0.25f) {
    override val sceneScale = 0.84f

    private const val TR = 0.9f   // бросок
    private const val TC = 1.78f  // ловля
    private const val APEX = 74f  // высота параболы

    private class Ph(
        val ant: Float, val thr: Float, val cat: Float, val att: Float, val grin: Float, val smug: Float,
        val turn: Float, val raise: Float, val sip: Float, val bliss: Float, val settle: Float,
    )

    private fun ph(t: Float) = Ph(
        ant = kf(t, k(0.42f, 0f), k(0.72f, 1f), k(0.9f, 0f, Ease.inn)),                        // замах вниз
        thr = kf(t, k(0.74f, 0f), k(0.95f, 1f, Ease.out), k(1.35f, 0.3f), k(1.76f, 0f)),       // бросок, рука к ловле
        cat = kf(t, k(1.76f, 0f), k(1.9f, 1f, Ease.out), k(2.3f, 0f)),                          // звезда упала в ладонь
        att = env(t, 0.55f, 2.1f, 0.25f),                                                       // следит за звездой
        grin = kf(t, k(0.75f, 0f), k(0.92f, 1f, Ease.out), k(1.45f, 1f), k(1.75f, 0f)),         // улыбка до ушей
        smug = kf(t, k(1.88f, 0f), k(2.08f, 1f, Ease.out), k(2.6f, 1f), k(2.85f, 0f)),          // самодовольный прищур
        turn = kf(t, k(2.7f, 0f), k(3.4f, 1f), k(5.75f, 1f), k(6.55f, 0f)),                     // вправо, почти в профиль
        raise = kf(t, k(2.8f, 0f), k(3.75f, 1f), k(5.0f, 1f), k(5.8f, 0f)),                     // чашка через себя ко рту
        sip = kf(t, k(3.7f, 0f), k(4.1f, 1f), k(4.7f, 1f), k(5.0f, 0f)),                        // наклон чашки, глоток
        bliss = kf(t, k(3.4f, 0f), k(3.65f, 1f), k(5.45f, 1f), k(5.95f, 0f)),                   // смакует, глаза-дуги
        settle = kf(t, k(5.0f, 0f), k(5.3f, 1f, Ease.out), k(5.75f, 0f)),                       // кофе качнулся
    )

    private fun body(t: Float): MascotPose {
        val b = Breath(t, loop)
        val k = ph(t)
        val idle = sin(TAU * t / loop)
        val sway = sin(TAU * 2 * t / loop)
        val gulp = env(t, 4.2f, 4.68f, 0.08f) * sin(TAU * 4.2f * (t - 4.2f))
        return MascotPose(
            yaw = 10 + 47 * k.turn + 5 * k.att + 2 * idle,
            pitch = -4 + 5 * k.ant + 4 * k.cat - 6 * k.att - 8 * k.sip,
            rot = -2 + 0.6f * k.ant - 0.8f * k.thr + 1.5f * k.cat + 3 * k.turn - 2 * k.sip,
            lean = 2 * k.turn,
            y = 0.8f * k.ant + 1.5f * k.cat,
            sy = b.sy - 0.015f * k.ant - 0.025f * k.cat + 0.012f * gulp,
            sx = b.sx + 0.01f * k.ant + 0.018f * k.cat,
            armR = -100 + 25 * k.ant - 55 * k.thr + 14 * k.cat + 55 * k.turn,
            bendR = 50 - 20 * k.ant - 25 * k.thr - 20 * k.cat - 25 * k.turn,
            armL = 64 - 142 * k.raise + 3 * sway * (1 - k.raise),
            bendL = 40 - 52 * k.raise,
            frontL = clamp01(k.raise * 2.5f),
            inL = k.raise,
            happy = k.bliss,
            grin = k.grin,
            smile = 1 - k.grin,
            lid = 0.55f * k.smug,
            eyeSY = blink(t, 2.62f, 7.5f),
        )
    }

    private fun frame(t: Float) = body(wrap(t, loop)).withLag(body(wrap(t - 0.06f, loop)))

    /** Мировая (сцена 300 × 300) матрица кисти стороны [side] и её полный поворот. */
    private fun hand(pp: MascotPose, side: Int): Pair<Aff, Float> {
        val h = handFrame(pp, side)
        val m = Aff.t(150 + offsetX + pp.x * 0.72f, 262 + pp.jump * 0.72f) * Aff.s(0.72f) * Aff.r(pp.lean) *
            Aff.t(0f, -48 + pp.y) * Aff.r(pp.rot) * Aff.s(pp.sx, pp.sy) * Aff.t(0f, 48f) *
            Aff.t(h.x, h.y) * Aff.r(h.angle)
        return m to h.total
    }

    private fun phi(t: Float) = PI.toFloat() * Ease.io(clamp01(ph(wrap(t, loop)).raise))

    private fun held(t: Float): Offset = hand(frame(t), 1).first.ap(0f, 0f) - Offset(0f, 20f)

    private val throwFrom by lazy { held(TR) }
    private val catchAt by lazy { held(TC) }

    private class Star(val x: Float, val y: Float, val rot: Float, val fly: Boolean)

    private fun star(t: Float): Star {
        if (t > TR && t < TC) {
            val u = (t - TR) / (TC - TR)
            val p0 = throwFrom
            val p1 = catchAt
            return Star(p0.x + (p1.x - p0.x) * u, p0.y + (p1.y - p0.y) * u - 4 * APEX * u * (1 - u), 360 * u, true)
        }
        val q = held(t)
        val dt = if (t >= TC) t - TC else t + loop - TC
        return Star(q.x, q.y, 18 * exp(-4.5f * dt) * sin(15 * dt) + 8 * ph(wrap(t, loop)).ant, false)
    }

    private fun tilt(t: Float): Float {
        val k = ph(t)
        return 3 * sin(TAU * 2 * t / loop) * (1 - k.raise) - 30 * k.sip + 7 * k.settle
    }

    override fun pose(t: Float): MascotPose {
        val k = ph(t)
        val s = star(t)
        val dx = s.x - (150 + offsetX + 10)
        val dy = s.y - (262 - 160 * 0.72f)
        // smear на повороте: глаза чуть вытягиваются по горизонтали пропорционально скорости yaw
        val vy = (body(wrap(t, loop)).yaw - body(wrap(t - 0.03f, loop)).yaw) / 0.03f
        return body(t).copy(
            lookX = (1 - k.att) * (2 + 5 * k.turn) + k.att * (dx / 6).coerceIn(-6f, 11f),
            lookY = k.att * (dy / 6).coerceIn(-11f, 7f) + 2 * k.raise * (1 - k.sip),
            eyeSX = 1 + (abs(vy) / 700).coerceIn(0f, 0.18f),
        )
    }

    override fun extras(t: Float, c: MascotPalette): MascotExtras {
        val tl = tilt(t)
        val cupPhi = phi(t)
        return MascotExtras(
            handLFront = { total -> at(0f, 0f, rot = -total + tl) { drawTeacup(cupPhi, c.dark) } },
            pinkySide = -1,
            pinkyAngle = 118 + 0.4f * tl + 6 * sin(TAU * 6 * t / loop),
            legsBack = { color, style -> drawFarLeg(color, style) },
            legsFront = { fill, line -> drawNearLeg(t, fill, line) },
        )
    }

    /* ── ноги «нога на ногу» ── */

    private fun DrawScope.drawFarLeg(color: Color, style: DrawStyle) {
        drawPath(CoffeePaths.farLeg, color, style = style)
        ellipse(color, 62f, -9f, 21f, 10.5f, style)
    }

    /** Верхняя нога: бедро лежит поверх низа корпуса и растворяется у корня, голень свисает и покачивает стопой. */
    private fun DrawScope.drawNearLeg(t: Float, fill: Color, line: Color) {
        val swing = 7 * sin(TAU * 6 * t / loop)
        val knee = Offset(78f, -66f)
        val ankle = knee + dirv(-14 - swing) * 40f
        val fd = dirv(-102 - 0.6f * swing)
        val fc = ankle + fd * 10f
        val fa = atan2(fd.y, fd.x) * 180f / PI.toFloat()
        val s = Offset(-52f, -60f)
        val c1 = Offset(-6f, -86f)
        val c2 = Offset(knee.x + 8, knee.y - 24)
        fun pt(u: Float): Offset = if (u <= 0.72f) {
            val q = u / 0.72f
            val m = 1 - q
            s * (m * m * m) + c1 * (3 * m * m * q) + c2 * (3 * m * q * q) + knee * (q * q * q)
        } else {
            knee + (ankle - knee) * ((u - 0.72f) / 0.28f)
        }
        val n = 26
        val left = ArrayList<Offset>(n + 1)
        val right = ArrayList<Offset>(n + 1)
        for (i in 0..n) {
            val u = i.toFloat() / n
            val a = pt(max(0f, u - 0.02f))
            val b = pt(minOf(1f, u + 0.02f))
            val q = pt(u)
            val len = (b - a).getDistance().takeIf { it > 0f } ?: 1f
            val w = (33 - 8 * u) / 2
            val nrm = Offset(-(b.y - a.y) / len * w, (b.x - a.x) / len * w)
            left += q + nrm
            right += q - nrm
        }
        val tube = polyPath(left + right.asReversed())
        val edges = Path().also {
            polyPath(left.subList(2, n), close = false, into = it)
            polyPath(right.subList(2, n), close = false, into = it)
        }
        // голень нижней ноги поверх корпуса — её корень уходит под верхнее бедро
        drawPath(CoffeePaths.farLeg, fill)
        drawPath(CoffeePaths.farEdges, line, style = stroke(2.6f))
        at(fc.x, fc.y, rot = fa) { ellipse(line, 0f, 0f, 21f, 11f, stroke(5f)) }
        fadeLayer(s, s + Offset(62f, -14f), 1f, 0.1f) {
            drawPath(tube, fill)
            drawPath(edges, line, style = stroke(2.6f))
        }
        at(fc.x, fc.y, rot = fa) { ellipse(fill, 0f, 0f, 21f, 11f) }
    }

    /* ── реквизит ── */

    /** Мягкое кресло: спинка за корпусом, подлокотники-валики, подушка, деревянные ножки. */
    private fun DrawScope.drawArmchair(dark: Boolean) {
        val base = if (dark) Color(0xFF2B2C31) else Color(0xFFF3D6C6)
        val shade = if (dark) Color(0xFF1F2024) else Color(0xFFE6BCA6)
        val hi = if (dark) Color(0xFF393A40) else Color(0xFFFCE9DF)
        val wood = if (dark) Color(0xFF6A4630) else Color(0xFFC68B63)
        fun leg(x: Float, dx: Float, top: Float = -26f) {
            drawPath(Path().apply {
                moveTo(x - 8, top)
                lineTo(x + 8, top)
                lineTo(x + 3 + dx, -1f)
                quadraticTo(x + dx, 2f, x - 3 + dx, -1f)
                close()
            }, wood)
        }
        fun rr(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) =
            drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(minOf(r, w / 2, h / 2)))
        fun armrest(x: Float) {
            rr(x, -128f, 60f, 108f, 29f, base)
            rr(x, -62f, 60f, 42f, 18f, shade.copy(alpha = 0.55f))
            ellipse(hi, x + 30, -113f, 22f, 11f)
        }
        leg(-84f, -3f, -30f)
        leg(76f, 3f, -30f)
        rr(-150f, -184f, 296f, 164f, 64f, shade)
        rr(-136f, -174f, 268f, 134f, 56f, base)
        ellipse(hi.copy(alpha = 0.7f), -24f, -160f, 92f, 11f)
        rr(-146f, -82f, 288f, 60f, 26f, base)
        rr(-146f, -46f, 288f, 24f, 12f, shade.copy(alpha = 0.7f))
        leg(-122f, -9f)
        leg(116f, 9f)
        armrest(-170f)
        armrest(110f)
    }

    /**
     * Белая чашка, ручка в кулаке (начало координат). [phi] — поворот вокруг кулака: 0 — чашка левее руки,
     * π — правее (так она «оборачивается», когда её несут через себя ко рту). Верх — −y.
     */
    private fun DrawScope.drawTeacup(phi: Float, dark: Boolean) {
        val lineC = if (dark) Color.Black.copy(alpha = 0.35f) else Color(0xFF1B1D21).copy(alpha = 0.22f)
        val shade = if (dark) Color(0xFFE4DAD2) else Color(0xFFEEE6E0)
        val c = cos(phi)
        if (abs(c) > 0.01f) {
            at(0f, 0f, sx = c, sy = 1f) {
                drawPath(CoffeePaths.handle, lineC, style = stroke(11.5f))
                drawPath(CoffeePaths.handle, Color.White, style = stroke(6.5f))
            }
        }
        at(cupX(phi) + CUP_R, 0f) {
            drawPath(CoffeePaths.foot, Color.White)
            drawPath(CoffeePaths.foot, lineC, style = stroke(2.5f))
            drawPath(CoffeePaths.cup, Color.White)
            drawPath(CoffeePaths.cup, lineC, style = stroke(2.5f))
            // блик живёт на стороне чашки, дальней от кулака
            drawPath(if (c >= 0f) CoffeePaths.shadeFar else CoffeePaths.shadeNear, shade, style = stroke(5f))
            drawPath(CoffeePaths.band, Gold, style = stroke(2.4f))
            ellipse(CoffeeBrown, -41f, -20f, 26f, 5.5f)
            ellipse(Gold, -41f, -20f, 26f, 5.5f, stroke(2.4f))
        }
    }

    /** Звезда; [smearAngle]/[smear] — растяжение вдоль полёта (smear-кадр), поперёк чуть сжимается. */
    private fun DrawScope.drawStar(x: Float, y: Float, rot: Float, s: Float, smearAngle: Float, smear: Float) {
        at(x, y, rot = smearAngle) {
            at(0f, 0f, sx = 1 + smear, sy = 1 - 0.35f * smear) {
                at(0f, 0f, rot = -smearAngle + rot, sx = s) {
                    drawCircle(
                        Brush.radialGradient(
                            0f to Color(0xFFFFD66B).copy(alpha = 0.8f),
                            0.45f to Color(0xFFFFB524).copy(alpha = 0.3f),
                            1f to Color(0xFFFFA000).copy(alpha = 0f),
                            center = Offset.Zero,
                            radius = 36f,
                        ),
                        radius = 36f,
                        center = Offset.Zero,
                    )
                    drawPath(
                        CoffeePaths.star,
                        Brush.verticalGradient(listOf(Color(0xFFFFE68F), Color(0xFFFFAE1A)), startY = -15.5f, endY = 12.6f),
                    )
                    drawPath(CoffeePaths.star, Color(0xFFF29A0C), style = stroke(3.4f))
                    line(Color.White.copy(alpha = 0.75f), -3f, -9f, -6.5f, -2f, 2.4f)
                }
            }
        }
    }

    /* ── слои сцены ── */

    override fun DrawScope.under(t: Float, c: MascotPalette) {
        ellipse(c.shadow, 150 + offsetX, 264f, 128f, 9f)
        at(150 + offsetX, 262f, sx = 0.72f) { drawArmchair(c.dark) }
        drawHandSmear(t, c)
    }

    /** Smear кисти со звездой: пока рука быстрая, за ней тянется сужающийся хвост цвета корпуса. */
    private fun DrawScope.drawHandSmear(t: Float, c: MascotPalette) {
        val dt = 1f / 240f
        val p0 = hand(frame(t), 1).first.ap(0f, 0f)
        val p1 = hand(frame(t - dt), 1).first.ap(0f, 0f)
        val k = clamp01(((p0 - p1).getDistance() / dt - 260f) / 420f)
        if (k < 0.02f) return
        val pts = listOf(p0, p1) + (2..9).map { j -> hand(frame(t - j * dt), 1).first.ap(0f, 0f) }
        alphaLayer(0.85f * k) {
            for (j in 1..9) drawCircle(c.body, 13f * (1 - j / 10f) * (0.55f + 0.45f * k), pts[j])
        }
    }

    override fun DrawScope.over(t: Float, c: MascotPalette) {
        drawSteam(t, c)
        drawStarLayer(t)
        val s = star(t)
        val tc = pop(t, TC, 0.3f) * kf(t, k(2.25f, 1f), k(2.55f, 0f))
        ticks(
            floatArrayOf(s.x + 16, s.y - 20, s.x + 20, s.y - 30, s.x + 24, s.y - 10, s.x + 34, s.y - 15, s.x + 26, s.y + 2, s.x + 36, s.y + 2),
            clamp01(tc), 0.6f + 0.4f * tc, s.x + 18, s.y - 8,
        )
        val ts = pop(t, 3.95f, 0.35f) * kf(t, k(4.75f, 1f), k(5.05f, 0f))
        val hx = 150 + offsetX + 62
        val hy = 52f
        ticks(
            floatArrayOf(hx - 18, hy, hx - 14, hy - 12, hx - 4, hy + 4, hx + 4, hy - 6, hx + 6, hy + 14, hx + 18, hy + 10),
            clamp01(ts), 0.6f + 0.4f * ts, hx - 6, hy + 8,
        )
    }

    private class Emit(val a: Float, val te: Float, val m: Aff, val cx: Float, val sip: Float)

    private fun DrawScope.drawSteam(t: Float, c: MascotPalette) {
        val span = 1.35f
        val n = 36
        val da = span / n
        val emits = (0..n + 3).map { i ->
            val a = i * da
            val te = wrap(t - a, loop)
            val (m, total) = hand(frame(te), -1)
            Emit(a, te, m * Aff.r(-total + tilt(te)), cupX(phi(te)), ph(te).sip)
        }
        val color = if (c.dark) Color(0xFFFFECE0).copy(alpha = 0.30f) else Color.White.copy(alpha = 0.88f)
        val strands = arrayOf(floatArrayOf(-12f, -24f, 0f, 0.8f), floatArrayOf(0f, -26f, 2.1f, 1f), floatArrayOf(12f, -24f, 4.3f, 0.75f))
        for ((rx, ry, phase, sz) in strands) {
            val e0 = emits.map { it.m.ap(it.cx + rx, ry) }
            val p = ArrayList<Offset>(n + 1)
            val spd = FloatArray(n + 1)
            for (i in 0..n) {
                val a = emits[i].a
                val i0 = max(0, i - 2)
                val i1 = i + 3
                // скорость чашки в момент выпуска — по окну ±0.1 с, иначе рывки корпуса дают изломы шлейфа
                val v = (e0[i0] - e0[i1]) / ((i1 - i0) * da)
                val drag = 0.35f * 0.25f * (1 - exp(-a / 0.25f)) // унаследованная скорость чашки гаснет
                val wob = (1 + 6 * a) * sin(phase + 6.5f * a + TAU * 3 * emits[i].te / loop)
                p += Offset(e0[i].x + v.x * drag + wob, e0[i].y + v.y * drag - (30 * a + 14 * a * a))
                spd[i] = v.getDistance()
            }
            // диффузия: чем старше пар, тем сильнее он размыт — сглаживаем окном, растущим с возрастом
            val ps = List(n + 1) { i ->
                val r = 1 + (4f * i / n).roundToInt()
                var sx = 0f
                var sy = 0f
                var cnt = 0
                for (j in max(0, i - r)..minOf(n, i + r)) {
                    sx += p[j].x
                    sy += p[j].y
                    cnt++
                }
                Offset(sx / cnt, sy / cnt)
            }
            val left = ArrayList<Offset>(n + 1)
            val right = ArrayList<Offset>(n + 1)
            for (i in 0..n) {
                // пар, выпущенный на быстром ходу чашки, рвётся: нет длинной нити через кадр
                val brk = (1.35f - spd[i] / 160f).coerceIn(0f, 1f)
                val u = i.toFloat() / n
                val w = 12 * sz * u.pow(0.4f) * (1 - u).pow(1.2f) * (1 - 0.7f * emits[i].sip) * brk / 2
                val a0 = ps[max(0, i - 1)]
                val a1 = ps[minOf(n, i + 1)]
                val len = hypot(a1.x - a0.x, a1.y - a0.y).takeIf { it > 0f } ?: 1f
                val nrm = Offset(-(a1.y - a0.y) / len * w, (a1.x - a0.x) / len * w)
                left += ps[i] + nrm
                right += ps[i] - nrm
            }
            drawPath(polyPath(left + right.asReversed()), color)
        }
    }

    private fun DrawScope.drawStarLayer(t: Float) {
        val s = star(t)
        val s1 = star(t - 1f / 120f)
        val sv = hypot(s.x - s1.x, s.y - s1.y) * 120
        val ang = atan2(s.y - s1.y, s.x - s1.x) * 180f / PI.toFloat()
        val stretch = if (s.fly) ((sv - 120) / 900).coerceIn(0f, 0.42f) else 0f
        // smear-хвост звезды: сужающаяся золотая полоса по недавней траектории
        if (s.fly && stretch > 0.02f) {
            val q = (0..6).map { j -> star(t - j * 0.014f) }.filterIndexed { j, it -> it.fly || j == 0 }
            if (q.size > 2) {
                val left = ArrayList<Offset>(q.size)
                val right = ArrayList<Offset>(q.size)
                q.forEachIndexed { i, it ->
                    val a = q[max(0, i - 1)]
                    val b = q[minOf(q.size - 1, i + 1)]
                    val len = hypot(b.x - a.x, b.y - a.y).takeIf { l -> l > 0f } ?: 1f
                    val w = 9 * (1 - i.toFloat() / q.size)
                    val nrm = Offset(-(b.y - a.y) / len * w, (b.x - a.x) / len * w)
                    left += Offset(it.x, it.y) + nrm
                    right += Offset(it.x, it.y) - nrm
                }
                drawPath(polyPath(left + right.asReversed()), StarCore.copy(alpha = 0.5f * stretch / 0.42f))
            }
        }
        // поймал: звезда сплющивается в ладони и отпружинивает
        val dc = t - TC
        val squash = if (dc > 0 && dc < 0.35f) 0.16f * exp(-9 * dc) * cos(18 * dc) else 0f
        val pulse = 1 + 0.07f * sin(TAU * 6 * t / loop)
        if (s.fly) drawStar(s.x, s.y, s.rot, pulse, ang, stretch) else drawStar(s.x, s.y, s.rot, pulse, 90f, -squash)
        for ((i, nn, phase) in listOf(Triple(0, 4, 0f), Triple(1, 5, 2f), Triple(2, 7, 4.1f))) {
            val o = max(0f, sin(TAU * nn * t / loop + phase)).pow(6)
            if (o < 0.02f) continue
            val a = phase * 1.7f + TAU * t / loop
            val r = 25f + 4 * i
            at(s.x + r * cos(a), s.y + r * sin(a), sx = 0.45f + 0.6f * o) {
                drawPath(CoffeePaths.spark, StarCore.copy(alpha = o))
            }
        }
    }
}
