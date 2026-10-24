package com.example.myapplication.ui.secret

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.utils.Haptic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Жест и анимация секретной папки на главной.
 *
 * Палец тянет главную вправо на нулевой странице пейджера — там пейджер сам ничего не берёт, и
 * остаток движения приходит сюда через nested scroll ([connection]). Точку касания и высоту
 * пальца для формы кромки даёт [secretPullObserver]: он только смотрит на события и ничего не
 * потребляет, поэтому со свайпом страниц не конфликтует.
 *
 * Фазы: натяжение ([SecretPullGeometry.stretchFor]) → барьер срывается ([breakThrough]: щелчок
 * и пружина на весь экран) → папка раскрыта, дальше ею владеет оверлей. Отпустили раньше —
 * резкий пружинный отскок назад в левый край.
 */
@Stable
class SecretFolderHostState(
    private val scope: CoroutineScope,
    private val viewModel: SecretFolderViewModel,
) {
    var screenWidth by mutableFloatStateOf(0f)
        private set
    var screenHeight by mutableFloatStateOf(0f)
        private set

    /** Ширина видимой кромки/папки у левого края, px: 0 — закрыта, [screenWidth] — раскрыта. */
    var edge by mutableFloatStateOf(0f)
        private set

    /** Высота пальца: у неё кромка выпячивается сильнее всего. */
    var touchY by mutableFloatStateOf(0f)
        private set

    /** Можно ли сейчас начинать жест: хозяин (рабочая область) знает про шторки и выделение. */
    var gestureAllowed: () -> Boolean = { false }
    var haptic: (Haptic) -> Unit = {}

    private var startX = 0f
    private var travel = 0f
    private var pulling = false

    /** Барьер пройден, палец ещё на экране: остаток его движения пейджеру не отдаём. */
    private var latched = false
    private var animation: Job? = null

    fun updateSize(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
        // Рабочую область пересобрали при открытой папке (вернулись из Details): кромка снова у края.
        if (viewModel.isOpen && animation?.isActive != true && !pulling) edge = width
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if (latched) return Offset(available.x, 0f)
            if (pulling && available.x < 0f) {
                // Палец идёт обратно: сначала сматываем натяжение, а не листаем страницы.
                val take = maxOf(available.x, -travel)
                pull(travel + take)
                return Offset(take, 0f)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.x <= 0f) return Offset.Zero
            if (latched) return Offset(available.x, 0f)
            if (!pulling) {
                if (viewModel.isOpen || !gestureAllowed()) return Offset.Zero
                if (!SecretPullGeometry(screenWidth, startX).isEnabled) return Offset.Zero
                animation?.cancel()
                pulling = true
                travel = 0f
            }
            pull(travel + available.x)
            return Offset(available.x, 0f)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!pulling && !latched) return Velocity.Zero
            release()
            // Бросок после натяжения пейджеру не отдаём: он унёс бы главную на «Настройки».
            return Velocity(available.x, 0f)
        }
    }

    fun onDown(x: Float, y: Float, width: Float, height: Float) {
        startX = x
        touchY = y
        if (width != screenWidth || height != screenHeight) updateSize(width, height)
    }

    fun onMove(y: Float) {
        touchY = y
    }

    /** Палец поднят (или бросок начался): отскок назад, если барьер не взят. */
    fun release() {
        latched = false
        if (!pulling) return
        pulling = false
        travel = 0f
        animateEdgeTo(0f, spring(dampingRatio = 0.5f, stiffness = 650f))
    }

    fun close() {
        pulling = false
        latched = false
        animateEdgeTo(0f, MotionTokens.largeSurfaceExit()) { viewModel.reset() }
    }

    /** Приложение ушло в фон: папка закрывается без анимации и снова встретит засовом. */
    fun closeNow() {
        animation?.cancel()
        pulling = false
        latched = false
        edge = 0f
        viewModel.reset()
    }

    private fun pull(value: Float) {
        travel = value.coerceAtLeast(0f)
        val geometry = SecretPullGeometry(screenWidth, startX)
        when {
            travel <= 0f -> {
                pulling = false
                edge = 0f
            }
            geometry.isBroken(travel) -> breakThrough()
            else -> edge = geometry.stretchFor(travel)
        }
    }

    /** Барьер взят: один щелчок, натяжение срывается, папка распахивается на весь экран. */
    private fun breakThrough() {
        pulling = false
        latched = true
        haptic(Haptic.Heavy)
        viewModel.markOpen()
        animateEdgeTo(screenWidth, MotionTokens.largeSurfaceEnter())
    }

    private fun animateEdgeTo(target: Float, spec: AnimationSpec<Float>, onDone: () -> Unit = {}) {
        animation?.cancel()
        animation = scope.launch {
            animate(initialValue = edge, targetValue = target, animationSpec = spec) { value, _ ->
                edge = value.coerceAtLeast(0f)
            }
            onDone()
        }
    }
}

/**
 * Следит за пальцем на Initial-проходе и ничего не потребляет: нужны точка касания (от неё
 * считается доступный путь) и текущая высота пальца.
 */
fun Modifier.secretPullObserver(host: SecretFolderHostState): Modifier = pointerInput(host) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        host.onDown(down.position.x, down.position.y, size.width.toFloat(), size.height.toFloat())
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.firstOrNull { it.id == down.id }?.let { host.onMove(it.position.y) }
        } while (event.changes.any { it.pressed })
        host.release()
    }
}
