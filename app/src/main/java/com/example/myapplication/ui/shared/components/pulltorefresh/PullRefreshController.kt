package com.example.myapplication.ui.shared.components.pulltorefresh

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/** Фазы pull-to-refresh, отвязанные от «сырого» isRefreshing (см. [rememberPullRefreshController]). */
enum class RefreshPhase { Idle, Pulling, Refreshing, Holding, Releasing }

/**
 * Общий холдер состояния pull-to-refresh. Создаётся на уровне экрана и шарится между контентом
 * (который едет вниз на [revealFraction]) и [PullRefreshIndicator].
 *
 * Все анимируемые поля читаются ТОЛЬКО в draw/placement-фазе — без рекомпозиции на кадр.
 */
@Stable
class PullRefreshController internal constructor(
    val pullState: PullToRefreshState,
    internal val phaseState: MutableState<RefreshPhase>,
    /** «Committed» видимость/фиксация: 1 во время загрузки+удержания, уезжает в 0 на Releasing. */
    val appear: Animatable<Float, AnimationVector1D>,
    /** Непрерывный угол вращения спиннера во время загрузки. */
    val spin: Animatable<Float, AnimationVector1D>,
) {
    internal val refreshTick = mutableIntStateOf(0)

    val phase: RefreshPhase get() = phaseState.value

    /**
     * Вызывается из `onRefresh`. Именно ЖЕСТ (а не флаг isRefreshing) запускает цикл фиксации —
     * поэтому удержание ≥2с работает даже при мгновенном рефреше, когда isRefreshing не успевает
     * подняться до true.
     */
    fun notifyRefreshInvoked() {
        refreshTick.intValue++
    }

    /**
     * Доля раскрытия интерфейса: `max(палец, зафиксированное)`. Читать в draw/placement-фазе.
     * Ей одновременно управляются сдвиг контента вниз и позиция/масштаб индикатора.
     */
    fun revealFraction(): Float =
        max(pullState.distanceFraction.coerceIn(0f, 1.2f), appear.value)
}

/**
 * Собирает [PullRefreshController] и запускает эффекты индикатора.
 *
 * Цикл: `onRefresh` (жест) → Refreshing (контент зафиксирован, спиннер крутится) →
 * держим `max(реальная загрузка, minVisibleMs)` → Releasing (плавный возврат) → Idle.
 * Повторный рефреш во время удержания перезапускает цикл.
 */
@Composable
fun rememberPullRefreshController(
    pullState: PullToRefreshState,
    isRefreshing: Boolean,
    view: View,
    minVisibleMs: Long = 2000L,
    releaseAnimMs: Long = 380L,
): PullRefreshController {
    val phaseState = remember { mutableStateOf(RefreshPhase.Idle) }
    val appear = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    val controller = remember(pullState, phaseState, appear, spin) {
        PullRefreshController(pullState, phaseState, appear, spin)
    }

    val isRefreshingState = rememberUpdatedState(isRefreshing)

    // ---- Цикл загрузки/удержания: владеет Refreshing → Holding → Releasing → Idle ----
    LaunchedEffect(controller.refreshTick.intValue) {
        if (controller.refreshTick.intValue == 0) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        phaseState.value = RefreshPhase.Refreshing
        // Даём флагу реальной загрузки шанс подняться, затем ждём его опускания.
        // Если загрузка мгновенная — выходим сразу и держим только по минимуму.
        withTimeoutOrNull(150L) { snapshotFlow { isRefreshingState.value }.first { it } }
        snapshotFlow { isRefreshingState.value }.first { !it }
        phaseState.value = RefreshPhase.Holding
        val remaining = (minVisibleMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L)
        delay(remaining)
        phaseState.value = RefreshPhase.Releasing
        delay(releaseAnimMs)
        phaseState.value = RefreshPhase.Idle
    }

    // ---- Ветка жеста: трогает фазу ТОЛЬКО в покое (Idle/Pulling) ----
    LaunchedEffect(pullState) {
        snapshotFlow { pullState.distanceFraction }.collect { f ->
            val p = phaseState.value
            if (p == RefreshPhase.Idle || p == RefreshPhase.Pulling) {
                phaseState.value = if (f > 0.001f) RefreshPhase.Pulling else RefreshPhase.Idle
            }
        }
    }

    val phase = phaseState.value

    // ---- Фиксация/появление и уход ----
    LaunchedEffect(phase) {
        when (phase) {
            RefreshPhase.Refreshing, RefreshPhase.Holding ->
                appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 250f))
            RefreshPhase.Releasing ->
                appear.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 170f))
            RefreshPhase.Idle, RefreshPhase.Pulling ->
                if (appear.value != 0f) appear.snapTo(0f)
        }
    }

    // ---- Вращение спиннера — только пока он на экране ----
    val spinning = phase == RefreshPhase.Refreshing ||
        phase == RefreshPhase.Holding ||
        phase == RefreshPhase.Releasing
    LaunchedEffect(spinning) {
        if (!spinning) {
            spin.snapTo(0f)
            return@LaunchedEffect
        }
        spin.snapTo(0f)
        spin.animateTo(
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing)),
        )
    }

    // ---- Хаптик один раз при пересечении порога срабатывания (df: below → above) ----
    LaunchedEffect(pullState) {
        snapshotFlow { pullState.distanceFraction >= 1f }
            .distinctUntilChanged()
            .collect { above -> if (above) performHaptic(view, Haptic.Light) }
    }

    return controller
}
