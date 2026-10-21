package com.example.myapplication.ui.shared.components

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.myapplication.ui.shared.FrostedMaterials
import com.example.myapplication.ui.shared.frostedGlass
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Двусторонний ползунок в языке приложения: трек-капсула с насечками и два небольших бегунка-капсулы
 * из МАТОВОГО стекла (`frostedGlass`, материал дока), а не «жидкого» с линзой. Вместо стандартного M3
 * `RangeSlider`.
 *
 * Стекло бегунков — живой backdrop: у трека свой изолированный [layerBackdrop], бегунок размывает
 * его под собой. Слой живёт внутри виджета и не задевает `layerBackdrop` экрана.
 *
 * Жест — один сырой pointerInput на весь виджет: касание выбирает ближний бегунок, дальше он идёт
 * за пальцем (без скачка, если схватили именно бегунок; тап по треку переносит ближний под палец).
 *
 * @param divisions число равных шагов между краями; 0 — значение непрерывное.
 * @param hapticStride тик каждые столько шагов; 0 — без тиков. Сотня тиков на рейтинг 0.1 ощущалась
 *  бы как жужжание, поэтому для него тик на целое.
 */
@Composable
fun GlassRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    divisions: Int = 0,
    hapticStride: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val capsule = RoundedCornerShape(percent = 50)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val trackHeight = 28.dp
    val thumbWidth = 40.dp

    val trackBackdrop = rememberLayerBackdrop { drawContent() }

    val currentValue by rememberUpdatedState(value)
    val currentRange by rememberUpdatedState(valueRange)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    // Какой бегунок двигали последним — он рисуется сверху, когда они сошлись в одной точке.
    var lastActive by remember { mutableStateOf(1) }

    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
    val startFraction = ((value.start - valueRange.start) / span).coerceIn(0f, 1f)
    val endFraction = ((value.endInclusive - valueRange.start) / span).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .pointerInput(divisions, hapticStride) {
                val thumbPx = thumbWidth.toPx()
                val half = thumbPx / 2f
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        down.consume()
                        val usable = (size.width - thumbPx).coerceAtLeast(1f)
                        val range = currentRange
                        val total = (range.endInclusive - range.start).coerceAtLeast(1e-6f)
                        fun fractionOf(v: Float) = ((v - range.start) / total).coerceIn(0f, 1f)
                        fun xOf(fraction: Float) = half + usable * fraction

                        val startX = xOf(fractionOf(currentValue.start))
                        val endX = xOf(fractionOf(currentValue.endInclusive))
                        val x0 = down.position.x
                        val active = when {
                            abs(x0 - startX) < abs(x0 - endX) -> 0
                            abs(x0 - startX) > abs(x0 - endX) -> 1
                            // Бегунки на одном месте: левее — тянем левый, правее — правый.
                            x0 < startX -> 0
                            else -> 1
                        }
                        lastActive = active
                        val activeX = if (active == 0) startX else endX
                        // Схватили сам бегунок — не прыгаем; тап по треку переносит бегунок под палец.
                        val grabOffset = if (abs(x0 - activeX) <= half) x0 - activeX else 0f
                        performHaptic(view, Haptic.Light)

                        var lastStep = Int.MIN_VALUE
                        fun update(x: Float) {
                            var fraction = ((x - grabOffset - half) / usable).coerceIn(0f, 1f)
                            if (divisions > 0) fraction = (fraction * divisions).roundToInt() / divisions.toFloat()
                            val cur = currentValue
                            val curStart = fractionOf(cur.start)
                            val curEnd = fractionOf(cur.endInclusive)
                            val newStart = if (active == 0) minOf(fraction, curEnd) else curStart
                            val newEnd = if (active == 1) maxOf(fraction, curStart) else curEnd
                            val next = (range.start + total * newStart)..(range.start + total * newEnd)
                            if (next.start != cur.start || next.endInclusive != cur.endInclusive) {
                                currentOnChange(next)
                            }
                            if (divisions > 0 && hapticStride > 0) {
                                val step = (fraction * divisions).roundToInt() / hapticStride
                                if (lastStep != Int.MIN_VALUE && step != lastStep) performHaptic(view, Haptic.Tick)
                                lastStep = step
                            }
                        }
                        update(x0)
                        drag(down.id) { change ->
                            change.consume()
                            update(change.position.x)
                        }
                        currentOnFinished?.invoke()
                    }
                }
            },
    ) {
        val travelPx = with(density) { (maxWidth - thumbWidth).toPx() }
        val thumbPx = with(density) { thumbWidth.toPx() }

        // ---- Трек: приглушённая капсула, оранжевый отрезок между бегунками, насечки ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .align(Alignment.Center)
                .clip(capsule)
                .layerBackdrop(trackBackdrop)
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    val r = h / 2f
                    val pad = thumbPx / 2f
                    val usable = (w - thumbPx).coerceAtLeast(1f)
                    val fromX = pad + usable * startFraction
                    val toX = pad + usable * endFraction

                    drawRoundRect(
                        color = onSurface.copy(alpha = 0.10f),
                        cornerRadius = CornerRadius(r, r),
                    )
                    // Выбранный отрезок — от центра левого до центра правого бегунка.
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(BrandOrange, BrandOrangeBright),
                            startX = fromX,
                            endX = toX.coerceAtLeast(fromX + 1f),
                        ),
                        topLeft = Offset(fromX, 0f),
                        size = Size((toX - fromX).coerceAtLeast(0f), h),
                        cornerRadius = CornerRadius(r, r),
                    )
                    val tickHalf = h * 0.21f
                    val tickW = h * 0.055f
                    for (i in 0..10) {
                        val x = pad + usable * (i / 10f)
                        val inside = x in fromX..toX
                        drawLine(
                            color = if (inside) Color.Black.copy(alpha = 0.22f) else onSurface.copy(alpha = 0.16f),
                            start = Offset(x, h / 2f - tickHalf),
                            end = Offset(x, h / 2f + tickHalf),
                            strokeWidth = tickW,
                            cap = StrokeCap.Round,
                        )
                    }
                },
        )

        // ---- Два бегунка-капсулы из матового стекла (FrostedMaterials, как у дока) ----
        val material = FrostedMaterials.dock()
        listOf(0, 1).forEach { index ->
            val fraction = if (index == 0) startFraction else endFraction
            Box(
                modifier = Modifier
                    .zIndex(if (index == lastActive) 2f else 1f)
                    .align(Alignment.CenterStart)
                    // Сдвиг через offset{}, а не graphicsLayer: стекло потребляет layerBackdrop трека.
                    .offset { IntOffset((travelPx * fraction).roundToInt(), 0) }
                    .size(thumbWidth, trackHeight)
                    .frostedGlass(backdrop = trackBackdrop, shape = capsule, material = material),
            )
        }
    }
}
