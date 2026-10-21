package com.example.myapplication.ui.shared.components.rating

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

/** С чего начинать оценку строки, у которой оценки ещё нет: нейтральное лицо, не крайность. */
private const val NEUTRAL_START = 5f

/** Сдвиг, после которого жест считается намеренным: лёгкая дрожь пальца при удержании не оценка. */
private const val MOVE_SLOP_DP = 6f

/**
 * Жест оценки строки: удерживаешь — открывается бабл со смайликом ([RatingOverlayHost]), не
 * отпуская ведёшь палец влево/вправо, отпускаешь — бабл втягивается обратно в строку и значение
 * сохраняется.
 *
 * Палец остаётся на строке, а не на треке бабла, поэтому значение считается от сдвига: бабл
 * открывается с текущей оценки строки ([currentRating], а без неё — с нейтральной пятёрки), и
 * сдвиг на ширину хода бегунка меняет оценку на весь диапазон 0–10, то есть бегунок идёт за
 * пальцем 1:1. Тот же [RatingSliderState], что и у остальных баблов: сам бабл рисует вызывающий.
 *
 * Если палец не сдвинулся дальше [MOVE_SLOP_DP] — это просто удержание, оценка не меняется.
 * Срыв жеста (система отобрала указатель) тоже ничего не сохраняет.
 *
 * Модификатор ставится ПОСЛЕ `clickable` строки: тогда он первым получает события и, поглотив
 * отпускание после удержания, не даёт строке принять его за тап.
 */
fun Modifier.ratingHoldGesture(
    state: RatingSliderState,
    /** Текущая оценка строки по десятибалльной шкале, 0 — не оценена. */
    currentRating: Float,
    onCommitted: (Float) -> Unit,
    enabled: Boolean = true,
): Modifier = composed {
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val latestRating by rememberUpdatedState(currentRating)
    val latestCommit by rememberUpdatedState(onCommitted)
    val holder = remember { CoordinatesHolder() }

    this
        .onGloballyPositioned { holder.coordinates = it }
        .then(
            if (!enabled) {
                Modifier
            } else {
                Modifier.pointerInput(state) {
                    val slopPx = with(density) { MOVE_SLOP_DP.dp.toPx() }
                    var active = false
                    var moved = false
                    var startValue = NEUTRAL_START
                    var travelPx = 1f
                    var dragX = 0f
                    var lastWhole = 0f

                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            val coordinates = holder.coordinates?.takeIf { it.isAttached }
                                ?: return@detectDragGesturesAfterLongPress
                            val rootWidth = coordinates.findRootCoordinates().size.width.toFloat()
                            val sidePx = with(density) { RatingExpandedSidePadding.toPx() }
                            val thumbPx = with(density) { RatingExpandedThumbWidth.toPx() }
                            travelPx = (rootWidth - 2f * sidePx - thumbPx).coerceAtLeast(1f)
                            startValue = latestRating.takeIf { it > 0f } ?: NEUTRAL_START
                            lastWhole = floor(startValue)
                            dragX = 0f
                            moved = false
                            active = true
                            state.anchorBoundsInRoot = coordinates.boundsInRoot()
                            performHaptic(view, Haptic.Light)
                            scope.launch { state.beginDragFrom(startValue) }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (!active) return@detectDragGesturesAfterLongPress
                            dragX += dragAmount.x
                            if (abs(dragX) > slopPx) moved = true
                            val t = (startValue + dragX / travelPx * 10f).coerceIn(0f, 10f)
                            // Тик на пересечение каждого ЦЕЛОГО значения — как на треке в форме.
                            val whole = floor(t)
                            if (whole != lastWhole) {
                                lastWhole = whole
                                performHaptic(view, Haptic.Tick)
                            }
                            scope.launch { state.onDrag(t) }
                        },
                        onDragEnd = {
                            if (!active) return@detectDragGesturesAfterLongPress
                            active = false
                            if (moved) {
                                performHaptic(view, Haptic.Heavy)
                                scope.launch { state.settle { snapped -> latestCommit(snapped) } }
                            } else {
                                state.cancelDrag()
                            }
                        },
                        onDragCancel = {
                            if (!active) return@detectDragGesturesAfterLongPress
                            active = false
                            state.cancelDrag()
                        },
                    )
                }
            },
        )
}

/** Координаты строки нужны только в момент удержания — в состояние (и рекомпозицию) их не кладём. */
private class CoordinatesHolder {
    var coordinates: LayoutCoordinates? = null
}
