package com.example.myapplication.ui.shared.theme

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sign

// ==========================================
// Скролл в духе iOS: экспоненциальное затухание вместо сплайна Android и «резинка» на краю
// вместо stretch. Единственная точка правды для физики прокрутки всего приложения.
//
// Затухание: у UIScrollView скорость падает как v(t) = v0 · δ^t (t в мс, δ = 0.998), то есть
// экспонента с k = −ln δ · 1000 ≈ 2.0 с⁻¹. У `exponentialDecay` в Compose базовое трение
// 4.2 с⁻¹, отсюда множитель k / 4.2. Порог остановки поднят с 0.1 до 20 px/с: экспоненциальный
// хвост иначе ~5 с тянул бы субпиксельные шаги и всё это время перерисовывал стекло.
//
// Край: оттяжка за границу — формула Apple `rubberBand` (MotionTokens), возврат и отскок после
// флика в край — пружина `overscrollReturn` (ζ 1, без колебаний).
// ==========================================

object IosScroll {

    /** Скорость падения скорости, с⁻¹, из «нормального» замедления UIScrollView. */
    private val DecayPerSecond: Float = -ln(MotionTokens.DecelerationNormal) * 1000f

    /** База трения `exponentialDecay` в Compose (FloatExponentialDecaySpec). */
    private const val ComposeDecayBase = 4.2f

    const val StopVelocityPxPerSec = 20f

    fun <T> decay(): DecayAnimationSpec<T> = exponentialDecay(
        frictionMultiplier = DecayPerSecond / ComposeDecayBase,
        absVelocityThreshold = StopVelocityPxPerSec,
    )

    /** Путь флика до остановки для начальной скорости, px (для тестов и расчётов). */
    fun flingDistance(velocityPxPerSec: Float): Float = velocityPxPerSec / DecayPerSecond

    @Composable
    fun flingBehavior(): FlingBehavior = remember { IosFlingBehavior(decay()) }

    /** Пейджер: то же затухание, доводка до страницы — пружиной без перелёта. */
    @Composable
    fun pagerFlingBehavior(state: PagerState): TargetedFlingBehavior =
        PagerDefaults.flingBehavior(
            state = state,
            decayAnimationSpec = remember { decay() },
            snapAnimationSpec = MotionTokens.springObject(),
        )
}

private class IosFlingBehavior(private val decay: DecayAnimationSpec<Float>) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) <= 1f) return initialVelocity
        var velocityLeft = initialVelocity
        var lastValue = 0f
        AnimationState(initialValue = 0f, initialVelocity = initialVelocity).animateDecay(decay) {
            val delta = value - lastValue
            val consumed = scrollBy(delta)
            lastValue = value
            velocityLeft = velocity
            // Упёрлись в край: остаток скорости отдаём наружу — из него «резинка» делает отскок.
            if (abs(delta - consumed) > 0.5f) cancelAnimation()
        }
        return velocityLeft
    }
}

/**
 * Фабрика «резинки» для `LocalOverscrollFactory`: одна строка в корне приложения подключает её
 * ко всем спискам и скроллам. При системном отключении анимаций отскока нет — край просто край.
 */
class IosOverscrollFactory(private val reducedMotion: Boolean) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = IosOverscrollEffect(reducedMotion)
    override fun equals(other: Any?): Boolean =
        other is IosOverscrollFactory && other.reducedMotion == reducedMotion
    override fun hashCode(): Int = reducedMotion.hashCode()
}

fun iosOverscrollFactory(context: Context): IosOverscrollFactory =
    IosOverscrollFactory(reducedMotion = context.isReducedMotion())

private class IosOverscrollEffect(private val reducedMotion: Boolean) : OverscrollEffect {

    /** Сырая протяжка за границу, px (ось определяется знаком сдвига). */
    private var raw by mutableStateOf(Offset.Zero)
    private val spring = Animatable(Offset.Zero, Offset.VectorConverter)
    private var springRunning by mutableStateOf(false)

    /** Размер вьюпорта для асимптоты `rubberBand`; обновляется узлом отрисовки. */
    private var viewport = Offset(1f, 1f)

    override val isInProgress: Boolean get() = raw != Offset.Zero || springRunning

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        if (reducedMotion) return performScroll(delta)
        var remaining = delta
        var unwound = Offset.Zero
        // Палец идёт обратно, а резинка оттянута: сначала сматываем её, список — потом.
        if (raw != Offset.Zero && !springRunning) {
            val backX = if (raw.x != 0f && sign(remaining.x) == -sign(raw.x)) {
                val take = if (abs(remaining.x) > abs(raw.x)) -raw.x else remaining.x
                take
            } else 0f
            val backY = if (raw.y != 0f && sign(remaining.y) == -sign(raw.y)) {
                val take = if (abs(remaining.y) > abs(raw.y)) -raw.y else remaining.y
                take
            } else 0f
            unwound = Offset(backX, backY)
            raw += unwound
            remaining -= unwound
        }
        val consumed = performScroll(remaining)
        val leftover = remaining - consumed
        // Оттягиваем только пальцем: за доводку флика в край отвечает applyToFling.
        if (source == NestedScrollSource.UserInput && !springRunning && leftover != Offset.Zero) {
            raw += leftover
            return unwound + consumed + leftover
        }
        return unwound + consumed
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        if (reducedMotion) {
            performFling(velocity)
            return
        }
        if (raw != Offset.Zero) {
            // Отпустили оттянутым — резинка возвращается, как в iOS, куда бы ни летел палец.
            bounce(start = raw, velocity = Offset.Zero)
            return
        }
        val left = performFling(velocity)
        // Флик упёрся в край с остатком скорости — короткий отскок из этой скорости.
        if (abs(left.x) > BOUNCE_MIN_VELOCITY || abs(left.y) > BOUNCE_MIN_VELOCITY) {
            bounce(start = Offset.Zero, velocity = Offset(left.x, left.y))
        }
    }

    private suspend fun bounce(start: Offset, velocity: Offset) {
        springRunning = true
        try {
            spring.snapTo(start)
            spring.animateTo(
                targetValue = Offset.Zero,
                animationSpec = MotionTokens.overscrollReturn(),
                initialVelocity = velocity,
            ) { raw = value }
        } finally {
            raw = Offset.Zero
            springRunning = false
        }
    }

    /** Видимое смещение содержимого: асимптотическая «резинка» Apple, не линейная доля. */
    private fun displayed(): Offset = Offset(
        x = sign(raw.x) * MotionTokens.rubberBand(abs(raw.x), viewport.x),
        y = sign(raw.y) * MotionTokens.rubberBand(abs(raw.y), viewport.y),
    )

    override val node: DelegatableNode = object : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() {
            viewport = Offset(size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
            val shift = displayed()
            if (shift == Offset.Zero) {
                drawContent()
            } else {
                translate(shift.x, shift.y) { this@draw.drawContent() }
            }
        }
    }

    private companion object {
        /** Ниже этой скорости у края отскок незаметен и только стоил бы кадров, px/с. */
        const val BOUNCE_MIN_VELOCITY = 150f
    }
}
