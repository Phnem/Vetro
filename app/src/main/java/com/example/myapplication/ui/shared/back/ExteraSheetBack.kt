package com.example.myapplication.ui.shared.back

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max

/**
 * Жест «назад» для шторки в том же языке движения, что и страницы ([ExteraBackSpec]): панель
 * сжимается до 0.85, отрывается от нижнего края (скругляется со всех сторон), следует за пальцем
 * по вертикали; после отпускания возвращается к полному размеру, уезжает на 96dp и гаснет за
 * первую пятую времени. Фон под шторкой в это время «отпускает» вдавливание.
 *
 * Состояние на снимках Compose: читается в фазе графики, перекомпоновки на кадр нет.
 */
class ExteraSheetBack internal constructor() {
    var raw by mutableFloatStateOf(0f)
        internal set
    var dy by mutableFloatStateOf(0f)
        internal set
    var committing by mutableStateOf(false)
        internal set
    var commitRaw by mutableFloatStateOf(0f)
        internal set
    var commitDy by mutableFloatStateOf(0f)
        internal set

    /** Линейное время дожима 0..1. */
    var commitT by mutableFloatStateOf(0f)
        internal set

    /** Жест или дожим идёт. */
    val active: Boolean get() = committing || raw > 0f

    /** 0..1: насколько шторка «оторвана» жестом (0 — покой). Для фона под ней. */
    val pull: Float
        get() = ExteraBackSpec.Gesture.transform(ExteraBackSpec.adjust(if (committing) commitRaw else raw))

    internal fun reset() {
        raw = 0f
        dy = 0f
        committing = false
        commitRaw = 0f
        commitDy = 0f
        commitT = 0f
    }

    /**
     * Накладывает позу на слой панели. [baseCornerPx] — радиус верхних углов шторки в покое.
     * Слой должен быть внешним по отношению к тому, что сдвигает шторку при появлении.
     */
    fun applyTo(scope: GraphicsLayerScope, baseCornerPx: Float) = with(scope) {
        if (!active) return@with
        val w = size.width
        val h = size.height
        val dp = density
        if (w <= 0f || h <= 0f) return@with

        val ip = pull
        val dyUsed = if (committing) commitDy else dy
        val dragScale = lerp(1f, ExteraBackSpec.CLOSING_SCALE, ip)
        val follow = ExteraBackMath.yOffset(h, h * dragScale, dyUsed, dp)
        val lift = ExteraBackSpec.EDGE_MARGIN_DP * dp * ip
        val cornerFull = ExteraBackSpec.CORNER_DP * dp

        var scale = dragScale
        var ty = follow - lift
        var top = lerp(baseCornerPx, cornerFull, ip)
        var bottom = lerp(0f, cornerFull, ip)
        var a = 1f
        if (committing) {
            val t = commitT
            val e = ExteraBackSpec.Emphasized.transform(t)
            scale = lerp(dragScale, 1f, e)
            ty = lerp(ty, ty + ExteraBackSpec.SLIDE_PAST_DP * dp, e)
            top = lerp(top, baseCornerPx, e)
            bottom = lerp(bottom, 0f, e)
            a = max(1f - 5f * t, 0f)
        }
        transformOrigin = TransformOrigin(0.5f, 1f)
        scaleX = scale
        scaleY = scale
        translationY += ty
        alpha *= a
        clip = true
        val s = max(scale, 0.01f)
        shape = RoundedCornerShape(
            topStart = CornerSize(top / s),
            topEnd = CornerSize(top / s),
            bottomEnd = CornerSize(bottom / s),
            bottomStart = CornerSize(bottom / s),
        )
    }
}

/**
 * Подключает предиктивный «назад» к шторке. Возвращает null, пока режим extera выключен: тогда
 * ничего не регистрируется и «назад» обрабатывает прежний `BackHandler` вызывающего.
 *
 * Обработчик регистрируется позже `BackHandler`, объявленного выше по коду, поэтому пока шторка
 * видна ([enabled]), жест достаётся ему.
 *
 * @param onDismiss закрыть шторку (вызывается при завершении жеста, не при отмене).
 */
@Composable
fun rememberExteraSheetBack(enabled: Boolean, onDismiss: () -> Unit): ExteraSheetBack? {
    if (!LocalExteraBackEnabled.current) return null
    val state = remember { ExteraSheetBack() }
    val dismiss by rememberUpdatedState(onDismiss)
    PredictiveBackHandler(enabled = enabled) { events ->
        var startY = Float.NaN
        try {
            events.collect { e: BackEventCompat ->
                if (startY.isNaN()) startY = e.touchY
                state.dy = e.touchY - startY
                state.raw = e.progress
            }
        } catch (c: CancellationException) {
            // Отмена: откат прогресса по EASE_OUT_QUINT, как в extera (120..220 мс).
            withContext(NonCancellable) {
                val from = state.raw
                if (from > 0f) {
                    val ms = max(120, (ExteraBackSpec.adjust(from) * 220f).toInt())
                    animate(
                        initialValue = from,
                        targetValue = 0f,
                        animationSpec = tween(ms, easing = ExteraBackSpec.EaseOutQuint),
                    ) { v, _ -> state.raw = v }
                }
                state.reset()
            }
            throw c
        }
        state.commitRaw = state.raw
        state.commitDy = state.dy
        state.commitT = 0f
        state.committing = true
        dismiss()
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(ExteraBackSpec.COMMIT_MS, easing = LinearEasing),
        ) { v, _ -> state.commitT = v }
        state.reset()
    }
    return state
}
