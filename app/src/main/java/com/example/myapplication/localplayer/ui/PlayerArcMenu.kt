package com.example.myapplication.localplayer.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Барабан-дуга для длинных списков плеера (дорожки, скорость): капсулы выложены по дуге от
 * правого края, активная — в середине дуги. Тянешь вертикально — дуга проворачивается, соседи
 * уменьшаются и уезжают вправо-вниз; каждый переход центра отдаётся щелчком вибрации.
 *
 * Два принципиальных момента:
 *  • список ЗАЦИКЛЕН — расстояние между индексами считается по кратчайшей дуге, поэтому крутить
 *    можно бесконечно в обе стороны;
 *  • отпускание пальца лишь ДЕЛАЕТ капсулу активной (превью/committed-стиль) — сам выбор
 *    подтверждается только явным тапом по капсуле, иначе на длинном списке любая случайная
 *    остановка драга подтверждала бы пункт, до которого долистали.
 *
 * Жесты потребляются целиком, иначе вертикальная прокрутка проваливалась бы в плеер под меню.
 */
@Composable
fun ArcCapsuleMenu(
    labels: List<String>,
    selectedIndex: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val total = labels.size

    var rawIndex by remember(total) { mutableFloatStateOf(selectedIndex.toFloat()) }
    var committed by remember(total) { mutableIntStateOf(selectedIndex) }
    var dragging by remember { mutableStateOf(false) }

    val stepPx = with(density) { DRAG_PER_ITEM.toPx() }
    val radiusPx = with(density) { ARC_RADIUS.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(total) {
                var startIndex = 0f
                var lastNearest = 0
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        startIndex = rawIndex
                        lastNearest = rawIndex.roundToInt()
                    },
                    onDragCancel = { dragging = false },
                    onDragEnd = {
                        dragging = false
                        val target = rawIndex.roundToInt()
                        scope.launch {
                            animate(
                                initialValue = rawIndex,
                                targetValue = target.toFloat(),
                                animationSpec = tween(SNAP_MILLIS, easing = FastOutSlowInEasing),
                            ) { value, _ -> rawIndex = value }
                            // Отпускание пальца только делает пункт активным — сам выбор
                            // подтверждается тапом по капсуле (см. её onClick), не settle'ом.
                            val normalized = ((target % total) + total) % total
                            committed = normalized
                            rawIndex = normalized.toFloat()
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        rawIndex -= dragAmount / stepPx
                        val nearest = rawIndex.roundToInt()
                        if (nearest != lastNearest) {
                            lastNearest = nearest
                            performHaptic(view, Haptic.Light)
                        }
                    },
                )
            },
    ) {
        val nearest = ((rawIndex.roundToInt() % total) + total) % total
        labels.forEachIndexed { index, label ->
            val distance = circularDistance(index, rawIndex, total)
            val absDistance = abs(distance)
            val alpha = max(0f, 1f - absDistance * FADE_PER_STEP)
            if (alpha <= 0.01f) return@forEachIndexed

            val angle = distance * ANGLE_STEP_RAD
            val scale = max(MIN_SCALE, CENTER_SCALE - absDistance * SCALE_PER_STEP)
            val previewed = dragging && index == nearest
            val isCommitted = !dragging && index == committed

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .graphicsLayer {
                        // Дуга уходит за правый край: по мере удаления от центра капсула
                        // одновременно опускается (sin) и отъезжает вправо (1 - cos).
                        translationY = sin(angle) * radiusPx
                        translationX = (1f - cos(angle)) * radiusPx * ARC_X_FACTOR
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        transformOrigin = TransformOrigin(1f, 0.5f)
                    },
            ) {
                ArcCapsule(
                    label = label,
                    previewed = previewed,
                    committed = isCommitted,
                    onClick = {
                        performHaptic(view, Haptic.Light)
                        committed = index
                        rawIndex = index.toFloat()
                        onCommit(index)
                    },
                )
            }
        }
    }
}

@Composable
private fun ArcCapsule(
    label: String,
    previewed: Boolean,
    committed: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    val container = when {
        committed -> BrandOrangeBright
        previewed -> Color.White.copy(alpha = 0.20f)
        else -> Color.Black.copy(alpha = 0.45f)
    }
    val content = when {
        committed -> Color.White
        previewed -> Color.White
        else -> Color.White.copy(alpha = 0.72f)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = SnProFamily,
            fontWeight = if (committed || previewed) FontWeight.Bold else FontWeight.Medium,
        ),
        color = content,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(container)
            .border(1.dp, Color.White.copy(alpha = if (previewed) 0.35f else 0.12f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 12.dp),
    )
}

/** Кратчайшее расстояние между индексами по кольцу: список крутится в обе стороны без края. */
private fun circularDistance(index: Int, current: Float, total: Int): Float {
    var diff = (index - current) % total
    if (diff < -total / 2f) diff += total
    if (diff > total / 2f) diff -= total
    return diff
}

/** С этого числа вариантов обычный столбик уже не помещается — показываем дугу. */
const val ARC_MENU_THRESHOLD = 5

private val ARC_RADIUS = 220.dp
private val DRAG_PER_ITEM = 70.dp
private const val ANGLE_STEP_RAD = 0.38f
private const val ARC_X_FACTOR = 1.15f
private const val CENTER_SCALE = 1.1f
private const val MIN_SCALE = 0.65f
private const val SCALE_PER_STEP = 0.22f
private const val FADE_PER_STEP = 0.32f
private const val SNAP_MILLIS = 220
