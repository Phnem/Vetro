package com.example.myapplication.ui.shared.mascot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/*
 * Маскот на экране входа: сидит на буквах названия, свистит и смотрит по сторонам, а пока вводят
 * пароль — закрывает глаза ладонями и подглядывает. Перенос сцены из
 * .scratch/vetro-mascot-peek/scene.js (стульчик убран: сиденьем служат буквы).
 *
 * Единицы — единицы рига. Линия сиденья — y = PeekSeatY: этим уровнем маскот ставится на верх букв,
 * а ноги свисают ниже неё поверх текста.
 */

private const val IdlePeriod = 4.8f
private const val CoveredPeriod = 2.85f
private const val CoverTime = 0.43f
private const val UncoverTime = 0.56f

/** Ноты свиста: начало в цикле, длительность. */
private val Notes = listOf(
    0.30f to 0.27f, 0.62f to 0.26f, 0.98f to 0.38f,
    2.65f to 0.24f, 2.93f to 0.23f, 3.21f to 0.34f, 3.62f to 0.47f,
)

/** Холст сцены в единицах рига. */
private const val ViewLeft = -180f
private const val ViewTop = -312f
private const val ViewWidth = 360f
private const val ViewHeight = 350f
private const val PeekSeatY = -47f

/** Доля высоты холста сверху до линии сиденья: по ней экран входа ставит маскота на буквы. */
const val PeekSeatFraction = (PeekSeatY - ViewTop) / ViewHeight

/** Ширина холста к высоте. */
const val PeekAspect = ViewWidth / ViewHeight

private data class PeekFrame(
    val pose: MascotPose,
    val kickNear: Float,
    val kickFar: Float,
    val cover: Float,
    val peek: Float,
)

private fun mix(a: Float, b: Float, w: Float) = a + (b - a) * w

private fun idleFrame(elapsed: Float): PeekFrame {
    val t = wrap(elapsed, IdlePeriod)
    val glance = kf(
        t, k(1.35f, 0f), k(1.71f, 1f), k(2.25f, 1f), k(2.61f, 0f),
        k(3.89f, 0f), k(4.17f, 0.82f), k(4.34f, 0.82f), k(4.74f, 0f),
    )
    val whistle = Notes.maxOf { (start, duration) -> env(t, start - 0.055f, start + duration + 0.11f, 0.085f) }
    val leg = TAU * 3 * t / IdlePeriod
    val breath = Breath(t, IdlePeriod)
    return PeekFrame(
        pose = MascotPose(
            sx = breath.sx,
            sy = breath.sy,
            yaw = 37f - 24f * glance + 2.2f * sin(TAU * t / IdlePeriod),
            pitch = -3f - 5.5f * whistle + 2.5f * glance,
            rot = 3f + 1.8f * sin(leg - 0.7f) - 2.5f * glance,
            lean = -2f - 2f * glance,
            lookX = -8f * glance + 2f * whistle,
            lookY = -2f * whistle,
            eyeS = blink(t, 1.53f, 2.46f, 4.09f),
            eyeSY = 1f - 0.13f * whistle,
            armL = 10f + 8f * sin(leg - 1.2f),
            armR = -17f - 5f * sin(leg + 0.3f),
            bendL = 19f,
            bendR = 13f,
            o = whistle,
            smile = 1f - whistle,
        ),
        kickNear = 0.83f * sin(leg) + 0.17f * sin(leg * 2 - 1f),
        kickFar = 0.80f * sin(leg + 2.35f) + 0.15f * sin(leg * 2),
        cover = 0f,
        peek = 0f,
    )
}

private fun coveredFrame(elapsed: Float): PeekFrame {
    val t = wrap(elapsed, CoveredPeriod)
    val breath = sin(TAU * t / CoveredPeriod)
    val peek = kf(t, k(0.95f, 0f), k(1.13f, 0.12f), k(1.41f, 1f), k(1.60f, 1f), k(1.73f, 0f, Ease.out))
    val flinch = kf(t, k(1.59f, 0f), k(1.72f, 1f, Ease.out), k(2.04f, 0f))
    return PeekFrame(
        pose = MascotPose(
            yaw = 4.2f - 1.4f * peek,
            pitch = 1.3f - 1.7f * peek + 2.6f * flinch,
            sx = 1f - 0.003f * breath,
            sy = 1f + 0.006f * breath - 0.008f * flinch,
            rot = 0.5f * breath - 1.5f * flinch,
            lean = -1.2f - 0.7f * flinch,
            y = 0.9f * breath + 1.8f * flinch,
            armL = 9f,
            armR = -9f,
            bendL = 18f,
            bendR = 17f,
            smile = 0.88f - 0.77f * flinch,
            o = 0.52f * flinch,
            eyeS = 1f - 0.58f * flinch,
            eyeSX = 1f + 0.07f * peek,
            eyeSY = 1f - 0.07f * peek,
        ),
        kickNear = -0.12f + 0.017f * breath,
        kickFar = 0.08f - 0.018f * breath,
        cover = 1f,
        peek = peek,
    )
}

private fun blend(a: PeekFrame, b: PeekFrame, w: Float): PeekFrame {
    val p = a.pose
    val q = b.pose
    return PeekFrame(
        pose = q.copy(
            yaw = mix(p.yaw, q.yaw, w), pitch = mix(p.pitch, q.pitch, w), rot = mix(p.rot, q.rot, w),
            lean = mix(p.lean, q.lean, w), y = mix(p.y, q.y, w), sx = mix(p.sx, q.sx, w), sy = mix(p.sy, q.sy, w),
            armL = mix(p.armL, q.armL, w), armR = mix(p.armR, q.armR, w),
            bendL = mix(p.bendL, q.bendL, w), bendR = mix(p.bendR, q.bendR, w),
            lookX = mix(p.lookX, q.lookX, w), lookY = mix(p.lookY, q.lookY, w),
            eyeS = mix(p.eyeS, q.eyeS, w), eyeSX = mix(p.eyeSX, q.eyeSX, w), eyeSY = mix(p.eyeSY, q.eyeSY, w),
            o = mix(p.o, q.o, w), smile = mix(p.smile, q.smile, w),
        ),
        kickNear = mix(a.kickNear, b.kickNear, w),
        kickFar = mix(a.kickFar, b.kickFar, w),
        cover = mix(a.cover, b.cover, w),
        peek = mix(a.peek, b.peek, w),
    )
}

/** Переходы «закрыл глаза» и «открыл» всегда начинаются из позы, в которой маскот сейчас. */
private class PeekAnimator {
    private class Cue(val time: Float, val covered: Boolean, val from: PeekFrame)

    private var cue: Cue? = null
    private var previous: Cue? = null

    fun setCovered(covered: Boolean, time: Float) {
        if ((cue?.covered ?: false) == covered) return
        val from = at(time)
        previous = cue
        cue = Cue(time, covered, from)
    }

    fun at(time: Float): PeekFrame {
        val current = cue?.takeIf { it.time <= time } ?: previous?.takeIf { it.time <= time } ?: return idleFrame(time)
        val elapsed = time - current.time
        val duration = if (current.covered) CoverTime else UncoverTime
        val after = max(0f, elapsed - duration)
        val next = if (current.covered) coveredFrame(after) else idleFrame(after)
        val ease = if (current.covered) Ease.out else Ease.io
        return blend(current.from, next, ease(clamp01(elapsed / duration)))
    }

    /** Время в цикле свиста; отрицательное — свиста нет (глаза закрыты или идёт раскрытие). */
    fun whistlePhase(time: Float): Float {
        val current = cue ?: return wrap(time, IdlePeriod)
        if (current.covered) return -1f
        val start = current.time + UncoverTime
        return if (time < start) -1f else wrap(time - start, IdlePeriod)
    }
}

/* ───────────── сидячие ноги ───────────── */

private class SeatedLeg(val path: Path, val footX: Float, val footY: Float, val footRot: Float)

private fun seatedLeg(far: Boolean, swing: Float): SeatedLeg {
    val kneeX = if (far) 73f else 25f
    val kneeY = if (far) -45f else -43f
    val dir = dirv(-(if (far) 20f else 16f) - swing * 31f)
    val length = if (far) 49f else 57f
    val x = kneeX + dir.x * length
    val y = kneeY + dir.y * length
    val path = Path().apply {
        if (far) {
            moveTo(28f, -64f)
            cubicTo(48f, -68f, 69f, -64f, kneeX, kneeY)
        } else {
            moveTo(-42f, -66f)
            cubicTo(-12f, -70f, 16f, -65f, kneeX, kneeY)
        }
        quadraticTo(kneeX + dir.x * 28f, kneeY + dir.y * 28f, x, y)
    }
    return SeatedLeg(path, x + 8f, y + 2f, -8f - swing * 22f)
}

private fun DrawScope.drawSeatedLeg(far: Boolean, swing: Float, color: Color, rim: Color?) {
    val leg = seatedLeg(far, swing)
    val width = if (far) 25f else 28f
    val rx = if (far) 20f else 23f
    val ry = if (far) 11f else 13f
    // Начало ноги растворяется в корпусе: у бедра она прозрачна и проявляется к колену.
    val from = if (far) Offset(28f, -60f) else Offset(-38f, -69f)
    val to = if (far) Offset(73f, -42f) else Offset(33f, -45f)
    fadeLayer(from, to, 1f, if (far) 0f else 0.23f) {
        if (rim != null) {
            drawPath(leg.path, rim, style = Stroke(width + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            at(leg.footX, leg.footY, rot = leg.footRot) { ellipse(rim, 0f, 0f, rx, ry, Stroke(5f)) }
        }
        drawPath(leg.path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        at(leg.footX, leg.footY, rot = leg.footRot) {
            ellipse(color, 0f, 0f, rx, ry)
            ellipse(Color.White.copy(alpha = if (far) 0.035f else 0.06f), 6f, -3f, 12f, 5f)
        }
    }
}

/* ───────────── ладони у глаз ───────────── */

/** Контур от локтя к кончикам пальцев и обратно, в координатах эталона: первая точка, дальше кубики. */
private val CoverOutline: List<FloatArray> = listOf(
    floatArrayOf(476f, 412f),
    floatArrayOf(469f, 407f, 466f, 394f, 471f, 382f), floatArrayOf(475f, 374f, 484f, 371f, 494f, 362f),
    floatArrayOf(508f, 351f, 524f, 337f, 530f, 326f), floatArrayOf(535f, 317f, 529f, 308f, 530f, 298f),
    floatArrayOf(531f, 288f, 527f, 279f, 533f, 272f), floatArrayOf(538f, 265f, 546f, 267f, 551f, 272f),
    floatArrayOf(555f, 277f, 556f, 284f, 557f, 293f), floatArrayOf(564f, 282f, 576f, 265f, 585f, 257f),
    floatArrayOf(594f, 249f, 602f, 253f, 607f, 259f), floatArrayOf(612f, 265f, 607f, 273f, 601f, 281f),
    floatArrayOf(610f, 272f, 619f, 262f, 629f, 265f), floatArrayOf(638f, 268f, 638f, 277f, 635f, 284f),
    floatArrayOf(632f, 291f, 627f, 297f, 622f, 303f), floatArrayOf(629f, 295f, 635f, 292f, 641f, 296f),
    floatArrayOf(647f, 301f, 645f, 311f, 640f, 319f), floatArrayOf(629f, 334f, 618f, 345f, 608f, 354f),
    floatArrayOf(598f, 362f, 589f, 367f, 578f, 369f), floatArrayOf(563f, 386f, 544f, 405f, 523f, 413f),
    floatArrayOf(505f, 421f, 488f, 424f, 476f, 412f),
)

/** Указательный и средний пальцы расходятся у костяшек (подглядывание); ладонь на месте. */
private val SpreadFingers: Map<Int, FloatArray> = mapOf(
    8 to floatArrayOf(-2f, -1f, -7f, -3f, -9f, -5f),
    9 to floatArrayOf(-10f, -5f, -12f, -7f, -13f, -5f),
    10 to floatArrayOf(-16f, -3f, -17f, 11f, -17f, 34f),
    11 to floatArrayOf(-6f, 20f, 2f, 9f, 4f, 3f),
    12 to floatArrayOf(4f, 3f, 2f, 1f, 1f, 0f),
)

private fun reference(side: Int, x: Float, y: Float) = Offset((646f - x) * 0.626f * side, (y - 508f) * 0.583f - 47.5f)

/** Та же последовательность точек и кубиков, что у [CoverOutline], но по контуру обычной руки. */
private fun relaxedOutline(arm: Arm, side: Int): List<FloatArray> {
    val outer = if (side < 0) arm.left else arm.right
    val inner = if (side < 0) arm.right else arm.left
    fun onMitten(x: Float, y: Float): Offset = arm.hand + rotv(x, y, arm.handAngle)
    fun curve(points: List<Offset>): List<FloatArray> = (1 until points.size).map { n ->
        val i = n - 1
        val a = points[i]
        val b = points[n]
        val prev = points[max(0, i - 1)]
        val next = points[minOf(i + 2, points.size - 1)]
        floatArrayOf(
            a.x + (b.x - prev.x) / 6f, a.y + (b.y - prev.y) / 6f,
            b.x - (next.x - a.x) / 6f, b.y - (next.y - a.y) / 6f,
            b.x, b.y,
        )
    }

    val start = if (side < 0) PI.toFloat() else 0f
    val step = side * PI.toFloat() / 12f
    val cap = (0 until 12).map { i ->
        val a = start + step * i
        val b = a + step
        val kk = 4f / 3f * tan(step / 4f)
        val c1 = onMitten(17f * (cos(a) - kk * sin(a)), 19f * (sin(a) + kk * cos(a)))
        val c2 = onMitten(17f * (cos(b) + kk * sin(b)), 19f * (sin(b) - kk * cos(b)))
        val end = onMitten(17f * cos(b), 19f * sin(b))
        floatArrayOf(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
    }
    val out = ArrayList<FloatArray>(CoverOutline.size)
    out += floatArrayOf(outer[0].x, outer[0].y)
    out += curve(listOf(outer[0], outer[7], outer[15], outer[23], onMitten(side * 17f, 0f)))
    out += cap
    out += curve(listOf(onMitten(-side * 17f, 0f), inner[18], inner[0], outer[0]))
    return out
}

private class CoverShape(val contour: Path, val lines: Path, val left: Boolean)

private fun degreesBetween(a: Offset, b: Offset) = atan2(b.y - a.y, b.x - a.x) * 180f / PI.toFloat()

private fun coverShape(arm: Arm, side: Int, frame: PeekFrame): CoverShape {
    val rest = relaxedOutline(arm, side)
    val spread = if (side < 0) frame.peek else 0f
    val root = arm.root
    val elbow = reference(side, 491f, 395f)
    val wrist = reference(side, 566f, 334f)
    val restAngle = degreesBetween(root, arm.hand)
    val coverAngle = degreesBetween(elbow, wrist)
    // Рука сначала уходит в сторону от боков и только потом заворачивает к глазам.
    val positive = wrap(coverAngle - restAngle, 360f)
    val turn = positive - if (side > 0) 360f else 0f
    val rotation = restAngle + turn * frame.cover
    val origin = Offset(mix(root.x, elbow.x, frame.cover), mix(root.y, elbow.y, frame.cover))
    val path = Path()
    CoverOutline.forEachIndexed { i, segment ->
        val delta = SpreadFingers[i]
        val out = FloatArray(segment.size)
        var j = 0
        while (j < segment.size) {
            val x = segment[j] + (delta?.getOrNull(j) ?: 0f) * spread
            val y = segment[j + 1] + (delta?.getOrNull(j + 1) ?: 0f) * spread
            val target = reference(side, x, y)
            val localRest = rotv(rest[i][j] - root.x, rest[i][j + 1] - root.y, -restAngle)
            val localCover = rotv(target.x - elbow.x, target.y - elbow.y, -coverAngle)
            val point = rotv(
                mix(localRest.x, localCover.x, frame.cover),
                mix(localRest.y, localCover.y, frame.cover),
                rotation,
            )
            out[j] = origin.x + point.x
            out[j + 1] = origin.y + point.y
            j += 2
        }
        if (i == 0) path.moveTo(out[0], out[1]) else path.cubicTo(out[0], out[1], out[2], out[3], out[4], out[5])
    }
    path.close()

    val lines = Path()
    fun curveLine(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float) {
        val a = reference(side, x0, y0)
        val c = reference(side, cx, cy)
        val b = reference(side, x1, y1)
        lines.moveTo(a.x, a.y)
        lines.quadraticTo(c.x, c.y, b.x, b.y)
    }
    if (spread <= 0.01f) curveLine(601f, 281f, 593f, 290f, 586f, 298f)
    curveLine(622f, 303f, 616f, 309f, 611f, 314f)
    return CoverShape(path, lines, left = side < 0)
}

private val CoverLine = Color(0xFFB27653)

private fun coverArms(left: Arm, right: Arm, frame: PeekFrame, c: MascotPalette): MascotArmLayers? {
    if (frame.cover <= 0.0001f) return null
    val shapes = listOf(coverShape(right, 1, frame), coverShape(left, -1, frame))
    val front = Ease.io(clamp01((frame.cover - 0.12f) / 0.65f))
    val detail = Ease.io(clamp01((frame.cover - 0.88f) / 0.12f))
    return MascotArmLayers(
        back = { color, style ->
            if (front < 0.999f) {
                alphaLayer(1f - front) { shapes.forEach { drawPath(it.contour, color, style = style) } }
            }
        },
        front = {
            if (front > 0.001f) {
                alphaLayer(front) {
                    shapes.forEach { shape ->
                        at(0f, 3f) { drawPath(shape.contour, Color(0xFF08080B).copy(alpha = 0.3f * detail)) }
                        drawPath(shape.contour, c.body)
                        val light = Offset(if (shape.left) -55f else 38f, -173f)
                        val lightColor = if (c.dark) Color(0xFF33343C) else c.front
                        drawPath(
                            shape.contour,
                            Brush.radialGradient(
                                0f to lightColor,
                                0.46f to lightColor.copy(alpha = 0.85f),
                                1f to Color.Transparent,
                                center = light,
                                radius = 80f,
                            ),
                            alpha = 0.42f + 0.58f * detail,
                        )
                        drawPath(shape.contour, CoverLine.copy(alpha = 0.69f), style = stroke(1.62f + 0.23f * detail))
                        drawPath(shape.lines, CoverLine.copy(alpha = 0.6f * detail), style = stroke(1.6f))
                    }
                }
            }
        },
    )
}

/* ───────────── свист ───────────── */

private val NoteStem by lazy { svgPath("M4,10 V-11 Q11,-11 13,-6") }
private val NoteColor = Color(0xFFFFAE72)

private fun DrawScope.drawWhistle(phase: Float, pose: MascotPose) {
    if (phase < 0f) return
    val mouth = BodyRot(pose).project(1f, -130f - BCY, BD)
    Notes.forEachIndexed { i, (onset, _) ->
        val age = phase - onset
        if (age <= 0f || age >= 0.70f) return@forEachIndexed
        val u = age / 0.70f
        val drift = Ease.out(u)
        val alpha = Ease.out(clamp01(u * 5f)) * (1f - Ease.io(clamp01((u - 0.25f) / 0.75f))) * 0.92f
        val s = 0.52f + 0.28f * drift
        at(mouth.x + 19f + 98f * drift, mouth.y - 50f * u - 20f * drift, rot = if (i % 2 == 1) -9f else 12f, sx = s) {
            drawPath(NoteStem, NoteColor.copy(alpha = alpha), style = stroke(2.9f))
            at(-0.7f, 10f, rot = -16f) { ellipse(NoteColor.copy(alpha = alpha), 0f, 0f, 5f, 3.2f, Fill) }
        }
    }
}

/**
 * Маскот экрана входа. [covered] — пароль вводится: ладони закрывают глаза. Низ корпуса стоит на
 * доле [PeekSeatFraction] высоты, ноги свисают ниже неё. При «Отключить анимации» — статичные позы.
 */
@Composable
fun LoginMascot(covered: Boolean, isDark: Boolean, modifier: Modifier = Modifier) {
    val palette = if (isDark) MascotPalette.Dark else MascotPalette.Light
    val reduceMotion = rememberReducedMotion()
    val animator = remember { PeekAnimator() }
    val time = remember { mutableFloatStateOf(0f) }
    val wanted = rememberUpdatedState(covered)
    if (!reduceMotion) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    val t = (now - start) / 1_000_000_000f
                    animator.setCovered(wanted.value, t)
                    time.floatValue = t
                }
            }
        }
    }
    Canvas(modifier.aspectRatio(PeekAspect).clearAndSetSemantics { }) {
        val t = time.floatValue
        val frame: PeekFrame
        val before: PeekFrame
        if (reduceMotion) {
            frame = if (covered) coveredFrame(0.4f) else idleFrame(0f)
            before = frame
        } else {
            frame = animator.at(t)
            before = animator.at(max(0f, t - 0.06f))
        }
        val pose = frame.pose.withLag(before.pose)
        val visible = 1f - Ease.io(clamp01((frame.cover - 0.73f) / 0.25f))
        val extras = MascotExtras(
            legsBack = { _, _ -> },
            legsFront = { _, _ ->
                val farColor = if (palette.dark) Color(0xFF131418) else Color(0xFF22262E)
                drawSeatedLeg(far = true, swing = frame.kickFar, color = farColor, rim = palette.rim)
                drawSeatedLeg(far = false, swing = frame.kickNear, color = palette.body, rim = palette.rim)
            },
            arms = { left, right -> coverArms(left, right, frame, palette) },
            eyeAlphaL = max(visible, Ease.out(clamp01((frame.peek - 0.18f) / 0.78f))),
            eyeAlphaR = visible,
            eyeGlow = 1f - 0.9f * frame.cover,
        )
        val s = size.width / ViewWidth
        at(-ViewLeft * s, -ViewTop * s, sx = s) {
            drawMascot(pose, palette, extras = extras)
            if (!reduceMotion) drawWhistle(animator.whistlePhase(t), pose)
        }
    }
}
