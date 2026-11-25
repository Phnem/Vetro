package com.example.myapplication.ui.shared.mascot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/*
 * «Палец с отпечатком»: маскот замахивается, тянет руку к зрителю и показывает большой палец с
 * рисунком отпечатка. Перенос сцены из .scratch/vetro-mascot-thumbs-up/scene.js.
 */

private const val Loop = 3.6f

/** Холст в единицах сцены (как 300×300 в прототипе, шире — туда выезжает рука). */
private const val ViewWidth = 340f
private const val ViewHeight = 300f

private fun mix(a: Float, b: Float, u: Float) = a + (b - a) * u

private class ThumbFrame(val pose: MascotPose, val reach: Float, val handScale: Float, val fist: Float)

private fun thumbFrame(t: Float): ThumbFrame {
    val ready = kf(t, k(0.24f, 0f), k(0.80f, 1f), k(1.08f, 0f, Ease.inn))
    val reach = kf(t, k(0.79f, 0f), k(1.16f, 1f), k(2.36f, 1f), k(3.04f, 0f))
    val handScale = kf(
        t, k(0.24f, 1f), k(0.80f, 0.88f), k(1.13f, 2.48f, Ease.out),
        k(1.36f, 2.33f), k(2.31f, 2.33f), k(2.54f, 1.96f), k(3.04f, 1f),
    )
    val fist = kf(t, k(0.62f, 0f), k(1.08f, 1f), k(2.48f, 1f), k(2.99f, 0f))
    val chill = 1f - 0.72f * reach
    val breath = Breath(t, Loop)
    val pose = MascotPose(
        x = -16f * ready - 7f * reach,
        y = 4f * ready - 1.5f * reach,
        sx = 1f + (breath.sx - 1f) * chill - 0.022f * ready,
        sy = 1f + (breath.sy - 1f) * chill - 0.026f * ready,
        yaw = -26f * ready - 8f * reach,
        pitch = -8f * ready + 5f * reach,
        lean = -5.5f * ready + 2f * reach,
        rot = -3f * ready + 1.8f * reach,
        armL = 7f + 7f * ready - 9f * reach,
        bendL = 10f + 8f * reach,
        armR = -7f - 64f * ready - 97f * reach,
        bendR = 10f + 14f * ready + 19f * reach,
        inR = 0.44f * reach,
        frontR = kf(t, k(0.78f, 0f), k(1.13f, 0.98f), k(2.40f, 0.98f), k(2.91f, 0f)),
        eyeS = blink(t, 0.77f, 2.89f),
        lookX = 2f * reach,
        lookY = -1.5f * reach,
        smile = 1f - 0.78f * reach,
        grin = 0.88f * reach,
    )
    return ThumbFrame(pose, reach, handScale, fist)
}

/* ───────────── кисть: варежка → кулак с поднятым пальцем ───────────── */

/** Контур «палец вверх»: первая точка, дальше кубики; столько же, сколько у [RestingMitten]. */
private val ThumbOutline: List<FloatArray> = listOf(
    floatArrayOf(-15f, 18f),
    floatArrayOf(-22f, 15f, -24f, 9f, -24f, 2f),
    floatArrayOf(-25f, -5f, -20f, -12f, -17f, -16f),
    floatArrayOf(-13f, -22f, -12f, -28f, -13f, -36f),
    floatArrayOf(-14f, -43f, -13f, -50f, -7f, -53f),
    floatArrayOf(-1f, -56f, 6f, -52f, 9f, -45f),
    floatArrayOf(12f, -40f, 12f, -32f, 12f, -27f),
    floatArrayOf(13f, -23f, 14f, -20f, 20f, -20f),
    floatArrayOf(29f, -21f, 34f, -16f, 33f, -8f),
    floatArrayOf(35f, -4f, 35f, 2f, 34f, 5f),
    floatArrayOf(35f, 10f, 34f, 16f, 31f, 20f),
    floatArrayOf(30f, 26f, 24f, 31f, 17f, 31f),
    floatArrayOf(4f, 33f, -6f, 28f, -15f, 18f),
)

private val RestingMitten: List<FloatArray> by lazy {
    val angles = floatArrayOf(131f, 173f, 200f, 224f, 252f, 285f, 306f, 325f, 347f, 368f, 390f, 424f, 491f)
        .map { it * PI.toFloat() / 180f }
    fun point(a: Float) = Offset(17f * cos(a), 19f * sin(a))
    fun tangent(a: Float) = Offset(-17f * sin(a), 19f * cos(a))
    buildList {
        add(point(angles[0]).let { floatArrayOf(it.x, it.y) })
        for (i in 1 until angles.size) {
            val start = angles[i - 1]
            val end = angles[i]
            val kk = 4f / 3f * tan((end - start) / 4f)
            val a = point(start)
            val b = point(end)
            val u = tangent(start)
            val v = tangent(end)
            add(floatArrayOf(a.x + kk * u.x, a.y + kk * u.y, b.x - kk * v.x, b.y - kk * v.y, b.x, b.y))
        }
    }
}

/** Согнутые пальцы с ладонной стороны: основания у внешнего края, кончики лежат на ладони. */
private val CurledFingers: List<Path> by lazy {
    listOf(
        "M39,-23 C32,-24 23,-22 15,-20 C9,-19 5,-17 4,-13 C3,-9 7,-6 11,-7 C17,-7 24,-11 32,-10 L39,-10 Z",
        "M40,-11 C31,-12 22,-9 12,-7 C5,-6 1,-3 2,2 C3,6 7,8 13,7 C20,6 27,3 33,3 L40,3 Z",
        "M40,2 C30,2 24,5 15,7 C8,9 5,12 6,16 C7,20 11,21 16,20 C22,18 27,16 34,16 L40,17 Z",
        "M38,15 C30,15 25,18 18,20 C13,21 10,24 11,27 C12,31 16,32 21,31 C28,29 33,24 38,23 Z",
    ).map(::svgPath)
}

private val PalmLines by lazy { svgPath("M-17,-14 C-12,-10 -8,-5 -9,2 C-10,8 -13,11 -11,16 M-9,20 Q-4,18 3,24") }

private val Ridges by lazy {
    svgPath(
        "M-9.3,-38 C-10.1,-44.5 -7.3,-49.1 -2,-49.1 C3.1,-49.2 7.2,-45.7 7.8,-39.8 " +
            "M-8.5,-33.8 C-8.6,-36.8 -8.1,-39.1 -7.8,-41.2 C-7.1,-45.9 -4.4,-47.1 -1.4,-46.8 " +
            "C2.4,-46.8 5.4,-44 5.4,-39.2 C5.5,-35.3 6.4,-33.1 7.6,-31.6 " +
            "M-6.6,-30.5 C-5.8,-34.9 -6.3,-38.1 -5.5,-40.9 C-4.8,-43.2 -3.4,-44.3 -1.4,-44.3 " +
            "C1.4,-44.2 3,-42 3,-39.1 C3,-34.6 4.2,-31.6 5.4,-29.5 " +
            "M-4.3,-28.8 C-3.8,-31.4 -3.9,-33.4 -3.8,-35.7 M-3.6,-38.3 C-3.4,-40.6 -2.5,-41.7 -1.2,-41.6 " +
            "C0.3,-41.5 0.7,-40.3 0.7,-38.7 C0.6,-34.7 1.3,-31.6 2.8,-28.1 " +
            "M-1.7,-27.7 C-0.9,-30.7 -1.4,-34.7 -1.3,-38.1 " +
            "M8,-36.9 C8.1,-35.5 8.6,-34 9.1,-33.2",
    )
}

private val RidgeColor = Color(0xFFFFAD75)

private fun handPath(fist: Float): Path = Path().apply {
    RestingMitten.forEachIndexed { i, segment ->
        val target = ThumbOutline[i]
        val v = FloatArray(segment.size) { mix(segment[it], target[it], fist) }
        if (i == 0) moveTo(v[0], v[1]) else cubicTo(v[0], v[1], v[2], v[3], v[4], v[5])
    }
    close()
}

/** Объём ладони, согнутые пальцы и отпечаток на большом пальце. */
private fun DrawScope.drawPalm(shape: Path, fist: Float, c: MascotPalette) {
    val openness = Ease.out(clamp01((fist - 0.52f) / 0.48f))
    clipPath(shape) {
        drawPath(
            shape,
            Brush.radialGradient(
                0f to c.front,
                1f to c.front.copy(alpha = 0f),
                center = Offset(-5f, -25f),
                radius = 62f,
            ),
            alpha = 0.88f * openness,
        )
        drawPath(PalmLines, Color(0xFFFFBB90).copy(alpha = 0.24f * openness), style = stroke(0.85f))
        for (index in CurledFingers.indices.reversed()) {
            val curl = Ease.io(clamp01((fist - 0.53f - 0.035f * index) / (0.47f - 0.035f * index)))
            if (curl <= 0.001f) continue
            val finger = CurledFingers[index]
            at(9f * (1f - curl), 0f) {
                alphaLayer(curl) {
                    at(-0.9f, 1.1f) { drawPath(finger, Color(0xFF08090C).copy(alpha = 0.56f)) }
                    val b = finger.getBounds()
                    drawPath(
                        finger,
                        Brush.linearGradient(
                            0f to Color(0xFF42444D),
                            0.44f to Color(0xFF33363E),
                            1f to Color(0xFF22242B),
                            start = Offset(b.left + b.width * 0.29f, b.top),
                            end = Offset(b.left + b.width * 0.47f, b.bottom),
                        ),
                    )
                    drawPath(finger, Color(0xFFC59B7C).copy(alpha = 0.40f), style = stroke(0.68f))
                }
            }
        }
        drawPath(Ridges, RidgeColor.copy(alpha = openness), style = stroke(0.93f))
    }
}

private fun DrawScope.drawGestureHand(
    arm: Arm,
    color: Color,
    style: DrawStyle,
    frame: ThumbFrame,
    c: MascotPalette,
) {
    if (frame.fist == 0f && frame.handScale == 1f) {
        at(arm.hand.x, arm.hand.y, rot = arm.handAngle) { ellipse(color, 0f, 0f, 17f, 19f, style) }
        return
    }
    val shape = handPath(frame.fist)
    val delta = wrap(-12f - frame.pose.rot - frame.pose.lean - arm.handAngle + 180f, 360f) - 180f
    val rotation = arm.handAngle + delta * frame.fist
    // Кисть растёт к камере вместе с контуром: чтобы обводка не жирнела, её толщина убавляется.
    val drawStyle = if (style is Stroke) {
        stroke(if (style.width >= 4f) mix(5f, 2.2f, frame.fist) else mix(2.6f, 1.1f, frame.fist))
    } else {
        style
    }
    at(arm.hand.x, arm.hand.y, rot = rotation, sx = frame.handScale) {
        drawPath(shape, color, style = drawStyle)
        if (drawStyle == Fill && color == c.body && frame.fist > 0.52f) drawPalm(shape, frame.fist, c)
    }
}

/** Маскот, показывающий палец с отпечатком, по кругу. При «Отключить анимации» — кадр с пальцем. */
@Composable
fun FingerprintMascot(isDark: Boolean, modifier: Modifier = Modifier) {
    val palette = if (isDark) MascotPalette.Dark else MascotPalette.Light
    val reduceMotion = rememberReducedMotion()
    val time = remember { mutableFloatStateOf(0f) }
    if (!reduceMotion) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    time.floatValue = (((now - start) / 1_000_000_000.0) % Loop).toFloat()
                }
            }
        }
    }
    Canvas(modifier.aspectRatio(ViewWidth / ViewHeight).clearAndSetSemantics { }) {
        val t = if (reduceMotion) 1.65f else time.floatValue
        val frame = thumbFrame(t)
        val pose = frame.pose.withLag(thumbFrame(wrap(t - 0.06f, Loop)).pose)
        val blobScale = 1f + 0.008f * sin(TAU * 2 * t / Loop)
        val s = size.width / ViewWidth
        at(0f, 0f, sx = s) {
            at(13f, -12f) {
                at(150f, 150f, sx = blobScale) {
                    at(-150f, -150f) { drawPath(MascotPaths.blobs[1], palette.blob.copy(alpha = palette.blob.alpha * 0.81f)) }
                }
                ellipse(palette.shadow, 150f + pose.x * 0.54f, 264f, 77f, 9f)
                at(150f + pose.x * 0.72f, 262f, sx = 0.72f) {
                    drawMascot(
                        pose,
                        palette,
                        extras = MascotExtras(
                            reachR = frame.reach,
                            mittenR = { arm, color, style -> drawGestureHand(arm, color, style, frame, palette) },
                        ),
                    )
                }
            }
        }
    }
}
