package com.example.myapplication.ui.details

import android.annotation.SuppressLint
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import com.example.myapplication.ui.shared.theme.MotionTokens
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min

/**
 * Идёт ли сейчас «назад» свайпом по экрану Details. Пока да, переходы NavHost едут по линейной
 * кривой: seekable-переход переводит прогресс жеста в долю ДЛИТЕЛЬНОСТИ, а у пружины смещение от
 * доли нелинейно — окно убегало бы вперёд пальца. Читается в лямбдах переходов NavGraph.
 */
object SwipeBackGesture {
    var active: Boolean by mutableStateOf(false)
        internal set

    /**
     * Длительность линейного перехода «назад» при свайпе: на жесте она не видна (переход едет за
     * пальцем), после отпускания это время доезда оставшейся доли. Флаг [active] держится, пока
     * переход не доиграл: сброс сразу после отпускания менял кривые на полпути, и остаток
     * доигрывался медленным хвостом пружины.
     */
    const val LINEAR_MS: Int = 320
}

/**
 * Свайп слева направо по странице «Детали» = «назад», как почти везде в iOS (а не только от края).
 *
 * Своей анимации нет: жест подаёт синтетический back-событиями в диспетчер, а NavHost уже умеет
 * вести переход за пальцем (predictive back) — уезд деталей вправо и возврат главной из
 * «вдавливания». Кнопка «назад», системный жест с края и этот свайп — одна и та же анимация.
 *
 * Как выделяется жест: на странице 0 пейджеру некуда листать вправо, и остаток горизонтального
 * сдвига приходит сюда в [NestedScrollConnection.onPostScroll]. Жест засчитывается, только если он
 * НАЧАЛСЯ на странице 0 в покое — иначе протяжка «Серии → Детали» одним движением проскакивала бы
 * сразу на главную. Решение на отпускании — общее правило [MotionTokens.willDismiss].
 *
 * @param enabled false, пока на Details открыт лист или меню: синтетический «назад» ушёл бы в него.
 */
@Composable
fun Modifier.detailsSwipeBack(pagerState: PagerState, enabled: Boolean): Modifier {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher ?: return this
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentEnabled by rememberUpdatedState(enabled)
    val connection = remember(pagerState, dispatcher, density, rtl) {
        DetailsSwipeBackConnection(pagerState, dispatcher, density, rtl) { currentEnabled }
    }
    return this
        .onSizeChanged { connection.widthPx = it.width.toFloat() }
        .nestedScroll(connection)
}

@SuppressLint("VisibleForTests") // dispatchOnBack* и конструктор BackEventCompat помечены «для тестов», но публичны.
private class DetailsSwipeBackConnection(
    private val pagerState: PagerState,
    private val dispatcher: OnBackPressedDispatcher,
    private val density: Density,
    private val rtl: Boolean,
    private val enabled: () -> Boolean,
) : NestedScrollConnection {

    var widthPx: Float = 1f

    /** Жест уже классифицирован: null — ещё нет касания, true — может стать «назад». */
    private var armed: Boolean? = null
    private var active = false
    private var dragged = 0f

    /** Направление «назад» в пикселях сдвига: вправо, в RTL — влево. */
    private val backSign: Float get() = if (rtl) -1f else 1f

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (armed == null) {
            armed = enabled() &&
                pagerState.currentPage == 0 &&
                abs(pagerState.currentPageOffsetFraction) < 0.001f
        }
        if (!active) return Offset.Zero
        // Палец пошёл обратно: сначала сматываем прогресс «назад», пейджеру — только остаток.
        val towardBack = available.x * backSign
        if (towardBack >= 0f) return Offset.Zero
        val take = min(-towardBack, dragged)
        dragged -= take
        progress()
        return Offset(-take * backSign, 0f)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || armed != true) return Offset.Zero
        val towardBack = available.x * backSign
        if (towardBack <= 0f) return Offset.Zero
        if (!active) {
            active = true
            SwipeBackGesture.active = true
            dispatcher.dispatchOnBackStarted(event(0f))
        }
        dragged += towardBack
        progress()
        return Offset(available.x, 0f)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val wasActive = active
        if (wasActive) {
            val velocityDp = (available.x * backSign) / density.density
            val commit = MotionTokens.willDismiss(
                offset = dragged,
                containerSize = widthPx,
                velocity = velocityDp.coerceAtLeast(0f),
            ) && velocityDp > -MotionTokens.DismissVelocityThresholdDpPerSec
            if (commit) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled()
            // Переход доигрывает на тех же линейных кривых — флаг снимаем, когда он закончился.
            delay(SwipeBackGesture.LINEAR_MS.toLong() + SETTLE_MARGIN_MS)
        }
        reset()
        // Скорость жеста «назад» пейджеру не отдаём: иначе он доводил бы страницу по инерции.
        return if (wasActive) available else Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        reset()
        return Velocity.Zero
    }

    private fun progress() {
        val p = (dragged / widthPx).coerceIn(0f, 1f)
        dispatcher.dispatchOnBackProgressed(event(p))
    }

    private fun event(progress: Float) = BackEventCompat(
        touchX = if (rtl) widthPx * (1f - progress) else widthPx * progress,
        touchY = 0f,
        progress = progress,
        swipeEdge = if (rtl) BackEventCompat.EDGE_RIGHT else BackEventCompat.EDGE_LEFT,
    )

    private companion object {
        /** Запас на кадр композиции после отпускания, пока NavHost переключает цель. */
        const val SETTLE_MARGIN_MS = 48L
    }

    private fun reset() {
        armed = null
        active = false
        dragged = 0f
        SwipeBackGesture.active = false
    }
}
