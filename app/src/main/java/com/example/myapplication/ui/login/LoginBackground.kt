package com.example.myapplication.ui.login

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Фон экрана входа: фирменный вертикальный градиент, который медленно течёт и переливается.
 * Опорные точки градиента «дышат», поверх плывут мягкие пятна того же тона и редкий косой блик.
 * Время читается только при рисовании (без перекомпозиций) и обновляется ~30 раз в секунду: движение
 * медленное, чаще незачем. При «Отключить анимации» фон статичный.
 */

private fun screenColors(isDark: Boolean): List<Color> =
    if (isDark) {
        listOf(Color(0xFF430B05), Color(0xFF210907), Color(0xFF000000), Color(0xFF000000))
    } else {
        listOf(Color(0xFFFFD8CB), Color(0xFFFFEEE8), Color(0xFFF9F9F9), Color(0xFFF9F9F9))
    }

private fun wave(t: Float, period: Float, phase: Float = 0f) = sin(2f * PI.toFloat() * t / period + phase)

private fun DrawScope.glow(center: Offset, radius: Float, color: Color) {
    drawCircle(
        Brush.radialGradient(
            0f to color,
            0.55f to color.copy(alpha = color.alpha * 0.45f),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

@Composable
fun FlowingVetroBackground(isDark: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReducedMotion()
    val time = remember { mutableFloatStateOf(0f) }
    if (!reduceMotion) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            var last = start
            while (true) {
                withFrameNanos { now ->
                    if (now - last >= 33_000_000L) {
                        last = now
                        time.floatValue = (now - start) / 1_000_000_000f
                    }
                }
            }
        }
    }
    Canvas(modifier) {
        val t = time.floatValue
        val w = size.width
        val h = size.height
        val c = screenColors(isDark)
        val second = (if (isDark) 0.24f else 0.28f) + 0.07f * wave(t, 14f)
        val third = (if (isDark) 0.58f else 0.64f) + 0.09f * wave(t, 19f, 1.7f)
        drawRect(Brush.verticalGradient(0f to c[0], second to c[1], third to c[2], 1f to c[3]))

        val warm = if (isDark) Color(0x42E85002) else Color(0x38E85002)
        val deep = if (isDark) Color(0x52A01C08) else Color(0x55FFB398)
        val soft = if (isDark) Color(0x26FF7A3D) else Color(0x66FFFFFF)
        glow(
            Offset(w * (0.22f + 0.24f * wave(t, 21f)), h * (0.07f + 0.06f * wave(t, 17f, 1f))),
            w * (0.82f + 0.08f * wave(t, 13f)),
            warm,
        )
        glow(
            Offset(w * (0.80f + 0.22f * wave(t, 26f, 2.4f)), h * (0.19f + 0.08f * wave(t, 23f, 0.6f))),
            w * 0.74f,
            deep,
        )
        glow(
            Offset(
                w * (0.50f + 0.42f * cos(2f * PI.toFloat() * t / 31f)),
                h * (0.31f + 0.07f * wave(t, 15f, 3.1f)),
            ),
            w * (0.52f + 0.07f * wave(t, 11f, 2f)),
            soft,
        )

        // Перелив: широкая косая полоса света раз в двенадцать секунд проходит по верхней части.
        if (!reduceMotion) {
            val p = (t % 12f) / 12f
            val x = w * (p * 2.6f - 1.0f)
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = if (isDark) 0.045f else 0.30f), Color.Transparent),
                    start = Offset(x, 0f),
                    end = Offset(x + w * 0.7f, h * 0.30f),
                ),
                size = size.copy(height = h * 0.62f),
            )
        }
    }
}
