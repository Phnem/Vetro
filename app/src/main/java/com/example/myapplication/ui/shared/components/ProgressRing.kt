package com.example.myapplication.ui.shared.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.StatusSuccess

/** Цвет кольца: ход (бренд), успех (зелёный) и ошибка. */
enum class RingTone { Active, Success, Error }

/**
 * Кольцо прогресса. [fraction] = null - ход неизвестен: по кольцу бежит дуга. Дуга догоняет значение
 * мягким твином, по ней пробегает блик, пока идёт ход; при [RingTone.Success] кольцо становится
 * зелёным и делает короткий «толчок». Содержимое [center] - внутри кольца (процент, значок).
 */
@Composable
fun ProgressRing(
    fraction: Float?,
    tone: RingTone,
    modifier: Modifier = Modifier,
    ringSize: Dp = 132.dp,
    strokeWidth: Dp = 10.dp,
    center: @Composable () -> Unit = {},
) {
    val progress by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(MotionTokens.DurationEmphasizedMillis, easing = LinearOutSlowInEasing),
        label = "ringProgress",
    )
    val ringColor by animateColorAsState(
        targetValue = when (tone) {
            RingTone.Active, RingTone.Error -> BrandOrange
            RingTone.Success -> StatusSuccess
        },
        animationSpec = MotionTokens.tweenEmphasized(),
        label = "ringColor",
    )
    val transition = rememberInfiniteTransition(label = "ring")
    val glint by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Restart),
        label = "glint",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val bump = remember { Animatable(1f) }
    LaunchedEffect(tone) {
        if (tone == RingTone.Success) {
            bump.animateTo(1.07f, MotionTokens.springPressIn())
            bump.animateTo(1f, MotionTokens.springSnappy())
        } else {
            bump.snapTo(1f)
        }
    }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    Box(
        modifier = modifier
            .size(ringSize)
            .semantics { if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(ringSize)
                .graphicsLayer {
                    scaleX = bump.value
                    scaleY = bump.value
                },
        ) {
            val stroke = strokeWidth.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            when {
                tone == RingTone.Success -> drawArc(ringColor, -90f, 360f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                tone == RingTone.Error -> drawArc(ringColor.copy(alpha = 0.35f), -90f, 360f, false, topLeft, arc, style = Stroke(stroke))
                fraction == null -> drawArc(ringColor, -90f + spin, 95f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                else -> {
                    val sweep = 360f * progress
                    if (sweep > 0.5f) {
                        drawArc(ringColor, -90f, sweep, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                        if (sweep > 30f) {
                            val start = -90f + (sweep - 18f) * glint
                            drawArc(
                                Color.White.copy(alpha = 0.38f), start, 18f, false, topLeft, arc,
                                style = Stroke(stroke * 0.55f, cap = StrokeCap.Round),
                            )
                        }
                    }
                }
            }
        }
        center()
    }
}
