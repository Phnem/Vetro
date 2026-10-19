package com.example.myapplication.ui.shared.back

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Анимация «назад» в стиле exteraGram (Telegram-форк) при интенсивности 2.
 *
 * Разобрано из APK: org.telegram.ui.ActionBar.ActionBarLayout + com.exteragram.messenger.utils.ui
 * .PredictiveBackAnimationHelper. Числа и кривые ниже — оттуда, не подобраны на глаз.
 *
 * Включается dev-флагом `DevPreferencesKeys.EXTERA_BACK` (по умолчанию выкл). Пока флаг выключен,
 * в приложении не работает ничего из этого файла: ни перехват back-событий, ни слои страниц.
 */

/** Включён ли режим «назад как в extera» (dev-флаг). По умолчанию false. */
val LocalExteraBackEnabled = compositionLocalOf { false }

/** Роутер back-событий под навигацией, см. [ProvideExteraBack]. Null, пока режим выключен. */
val LocalExteraBackRouter = compositionLocalOf<ExteraBackRouter?> { null }

object ExteraBackSpec {
    /** Ползунок «Predictive back» в exteraGram: 0..2, здесь — максимум. */
    const val INTENSITY = 2f

    /** Жест считается начавшимся после этого прогресса, остаток перенормируется в 0..1. */
    const val START_THRESHOLD = 0.015f

    /** Во сколько раз сжимается закрывающаяся страница. */
    const val CLOSING_SCALE = 0.85f

    /** Нижняя страница стартует в этом масштабе (clamp 0.85..0.95 от радиуса окна; у нас радиус 0). */
    const val ENTERING_START_SCALE = 0.95f

    /** Страница под низом стартует сдвинутой влево на max(15% ширины, это). */
    const val ENTERING_SHIFT_MIN_DP = 96f

    /** Длительность «дожима» после отпускания (в extera — 375 мс). */
    const val COMMIT_MS = 375

    /** После коммита закрывающаяся страница уезжает ещё на столько. */
    const val SLIDE_PAST_DP = 96f

    /** Отступ закрывающейся страницы от края, к которому её прижимает жест. */
    const val EDGE_MARGIN_DP = 8f

    /** Радиус скругления в конце жеста. */
    const val CORNER_DP = 40f

    /** Скрим над нижней страницей: в тёмной теме плотный, в светлой деликатный. */
    const val SCRIM_DARK = 0.8f
    const val SCRIM_LIGHT = 0.2f

    /** `PathInterpolator(0.1, 0.1, 0, 1)`: резкий старт, потом почти плато. */
    val Gesture: Easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

    /** `CubicBezierInterpolator.EASE_OUT_QUINT` — откат при отмене жеста. */
    val EaseOutQuint: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /** Material 3 emphasized (двухсегментный путь из `createEmphasizedInterpolator`). */
    val Emphasized: Easing = BezierPathEasing(
        floatArrayOf(0f, 0f, 0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f),
        floatArrayOf(0.166666f, 0.4f, 0.208333f, 0.82f, 0.25f, 1f, 1f, 1f),
    )

    /** Прогресс события системы → прогресс анимации: порог 0.015, затем × интенсивность. */
    fun adjust(raw: Float): Float =
        ((max(0f, raw - START_THRESHOLD) / (1f - START_THRESHOLD)) * INTENSITY).coerceIn(0f, 1f)
}

/** Кривая из нескольких кубических сегментов Безье: x → y решается бисекцией. */
private class BezierPathEasing(private vararg val segments: FloatArray) : Easing {
    override fun transform(fraction: Float): Float {
        if (fraction <= 0f) return 0f
        if (fraction >= 1f) return 1f
        val s = segments.firstOrNull { fraction <= it[6] } ?: segments.last()
        var lo = 0f
        var hi = 1f
        repeat(18) {
            val mid = (lo + hi) / 2f
            if (bezier(s[0], s[2], s[4], s[6], mid) < fraction) lo = mid else hi = mid
        }
        return bezier(s[1], s[3], s[5], s[7], (lo + hi) / 2f)
    }

    private fun bezier(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val u = 1f - t
        return u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
    }
}

/** Прямоугольник страницы в координатах контейнера плюс радиус, прозрачность и множитель скрима. */
class ExteraPose {
    var left = 0f
    var top = 0f
    var width = 0f
    var height = 0f
    var corner = 0f
    var alpha = 1f
    var scrim = 1f

    fun set(l: Float, t: Float, w: Float, h: Float) {
        left = l; top = t; width = w; height = h
    }
}

object ExteraBackMath {
    private fun decelerate(x: Float) = 1f - (1f - x) * (1f - x)

    /**
     * Смещение по вертикали вслед за пальцем: затухающее (decelerate), и тем больше, чем сильнее
     * сжата страница — у несжатой его нет.
     */
    fun yOffset(startHeight: Float, currentHeight: Float, dy: Float, dp: Float): Float {
        val half = startHeight / 2f
        if (half <= 0f) return 0f
        val sign = if (dy < 0f) -1f else 1f
        return sign * decelerate(min(half, abs(dy)) / half) *
            max(0f, (startHeight - currentHeight) / 2f - ExteraBackSpec.EDGE_MARGIN_DP * dp)
    }

    /**
     * Фаза жеста (палец ведёт анимацию). [adj] — уже [ExteraBackSpec.adjust], [dy] — сдвиг пальца
     * по Y от точки начала жеста, [fromLeft] — жест начат с левого края.
     */
    fun drag(
        closing: ExteraPose,
        entering: ExteraPose,
        w: Float,
        h: Float,
        dp: Float,
        fromLeft: Boolean,
        adj: Float,
        dy: Float,
    ) {
        val ip = ExteraBackSpec.Gesture.transform(adj)

        // Закрывающаяся: центрированное сжатие, а при жесте с левого края — прижатие вправо.
        val tw = w * ExteraBackSpec.CLOSING_SCALE
        val th = h * ExteraBackSpec.CLOSING_SCALE
        var tl = (w - tw) / 2f
        val tt = (h - th) / 2f
        if (fromLeft) tl = w - tw - ExteraBackSpec.EDGE_MARGIN_DP * dp
        val cw = lerp(w, tw, ip)
        val ch = lerp(h, th, ip)
        val yOff = yOffset(h, ch, dy, dp)
        closing.set(lerp(0f, tl, ip), lerp(0f, tt, ip) + yOff, cw, ch)
        closing.corner = lerp(0f, ExteraBackSpec.CORNER_DP * dp, ip)
        closing.alpha = 1f
        closing.scrim = 1f

        // Нижняя: 0.95, сдвинута влево, по ходу жеста сжимается ещё до 0.85 от своего размера.
        val w0 = w * ExteraBackSpec.ENTERING_START_SCALE
        val h0 = h * ExteraBackSpec.ENTERING_START_SCALE
        val l0 = (w - w0) / 2f - max(w0 * 0.14999998f, ExteraBackSpec.ENTERING_SHIFT_MIN_DP * dp)
        val t0 = (h - h0) / 2f
        val wT = w0 * ExteraBackSpec.CLOSING_SCALE
        val hT = h0 * ExteraBackSpec.CLOSING_SCALE
        val lT = l0 + (w0 - wT) / 2f
        val tT = t0 + (h0 - hT) / 2f
        entering.set(lerp(l0, lT, ip), lerp(t0, tT, ip) + yOff, lerp(w0, wT, ip), lerp(h0, hT, ip))
        entering.corner = closing.corner
        entering.alpha = 1f
        entering.scrim = 1f
    }

    /**
     * Фаза «дожима» после отпускания: из позы [from] в конечную по emphasized-кривой, [t] — линейное
     * время 0..1. Закрывающаяся уезжает ещё на 96dp и гаснет за первую пятую времени, нижняя
     * разворачивается на весь экран, скрим тает.
     */
    fun commit(out: ExteraPose, from: ExteraPose, w: Float, h: Float, dp: Float, t: Float, closing: Boolean) {
        val e = ExteraBackSpec.Emphasized.transform(t)
        val targetLeft = if (closing) from.left + ExteraBackSpec.SLIDE_PAST_DP * dp else 0f
        out.set(
            lerp(from.left, targetLeft, e),
            lerp(from.top, 0f, e),
            lerp(from.width, w, e),
            lerp(from.height, h, e),
        )
        out.corner = lerp(from.corner, 0f, e)
        out.alpha = if (closing) max(1f - 5f * t, 0f) else 1f
        out.scrim = 1f - t
    }
}

/**
 * Состояние текущего жеста «назад» — общее для всех страниц. Пишет его [ExteraBackRouter], читают
 * слои страниц в фазе графики (поэтому состояние на снимках Compose).
 */
object ExteraBackGesture {
    enum class Phase { Idle, Dragging, Committing }

    var phase by mutableStateOf(Phase.Idle)
        private set

    /** Сдвиг пальца по Y от начала жеста. */
    var dy by mutableFloatStateOf(0f)
        private set

    var fromLeft by mutableStateOf(true)
        private set

    /** Прогресс системы в момент отпускания (0, если жеста не было — кнопка «назад»). */
    var commitRaw by mutableFloatStateOf(0f)
        private set

    var commitDy by mutableFloatStateOf(0f)
        private set

    /** Какую страницу уводят — extera-страницу ли. Кэшируется нижней страницей на время жеста. */
    @Volatile
    var poppedIsExteraPage: Boolean = false

    private var startY = 0f
    private var lastRaw = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val toIdle = Runnable { phase = Phase.Idle }

    internal fun begin(touchY: Float, left: Boolean) {
        handler.removeCallbacks(toIdle)
        startY = touchY
        lastRaw = 0f
        dy = 0f
        fromLeft = left
        commitRaw = 0f
        commitDy = 0f
        phase = Phase.Dragging
    }

    internal fun track(progress: Float, touchY: Float) {
        lastRaw = progress
        dy = touchY - startY
    }

    internal fun commit() {
        handler.removeCallbacks(toIdle)
        val gestured = phase == Phase.Dragging
        commitRaw = if (gestured) lastRaw else 0f
        commitDy = if (gestured) dy else 0f
        phase = Phase.Committing
        // NavHost доигрывает не дольше COMMIT_MS; дальше состояние не нужно.
        handler.postDelayed(toIdle, ExteraBackSpec.COMMIT_MS + 350L)
    }

    internal fun cancel() {
        handler.removeCallbacks(toIdle)
        // Откат ведёт NavHost (доля идёт к нулю), фаза остаётся Dragging, пока он не доехал.
        handler.postDelayed(toIdle, ExteraBackSpec.COMMIT_MS + 150L)
    }
}

/**
 * Пересылает back-события в диспетчер навигации и параллельно пишет [ExteraBackGesture].
 * Нужен, потому что NavHost ведёт переход сам и не отдаёт координату пальца, а extera двигает
 * страницу и по вертикали.
 */
class ExteraBackRouter internal constructor(val dispatcher: OnBackPressedDispatcher) {
    @SuppressLint("VisibleForTests") // dispatchOnBack* помечены «для тестов», но публичны.
    fun started(e: BackEventCompat) {
        ExteraBackGesture.begin(e.touchY, e.swipeEdge == BackEventCompat.EDGE_LEFT)
        dispatcher.dispatchOnBackStarted(e)
    }

    @SuppressLint("VisibleForTests")
    fun progressed(e: BackEventCompat) {
        ExteraBackGesture.track(e.progress, e.touchY)
        dispatcher.dispatchOnBackProgressed(e)
    }

    fun invoked() {
        ExteraBackGesture.commit()
        dispatcher.onBackPressed()
    }

    @SuppressLint("VisibleForTests")
    fun cancelled() {
        ExteraBackGesture.cancel()
        dispatcher.dispatchOnBackCancelled()
    }
}

/**
 * Вешает под дерево отдельный диспетчер «назад» и пересылку к нему [ExteraBackRouter]. Все
 * [androidx.activity.compose.BackHandler] и NavHost ниже регистрируются в нём, а события системы
 * приходят через пересылку — так видна координата пальца.
 *
 * Пока [enabled] = false, дерево получает настоящий диспетчер активности, а пересылка выключена:
 * поведение «назад» ровно такое же, как без этого слоя.
 */
@Composable
fun ProvideExteraBack(enabled: Boolean, content: @Composable () -> Unit) {
    val parent = LocalOnBackPressedDispatcherOwner.current
    if (parent == null) {
        content()
        return
    }
    val host = remember(parent) { ExteraBackHost(parent) }
    SideEffect { host.setEnabled(enabled) }
    DisposableEffect(host) {
        host.attach()
        onDispose { host.detach() }
    }
    CompositionLocalProvider(
        LocalOnBackPressedDispatcherOwner provides (if (enabled) host.childOwner else parent),
        LocalExteraBackRouter provides (if (enabled) host.router else null),
        LocalExteraBackEnabled provides enabled,
    ) {
        content()
    }
}

// Конструктор диспетчера с колбэком «появились обработчики» помечен RestrictTo, но публичен в байткоде.
@SuppressLint("RestrictedApi")
private class ExteraBackHost(private val parent: OnBackPressedDispatcherOwner) {
    private var enabled = false
    private var childHasCallbacks = false

    private val child = OnBackPressedDispatcher(
        null,
        Consumer<Boolean> { has ->
            childHasCallbacks = has
            refresh()
        },
    )

    val router = ExteraBackRouter(child)

    val childOwner: OnBackPressedDispatcherOwner = ChildOwner(parent, child)

    /**
     * Ловит жесты системы, только пока у дочернего диспетчера есть что обрабатывать: на корне, где
     * приложение просто закрывается, системная анимация возврата на главный экран не отбирается.
     */
    private val forwarder = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) = router.started(backEvent)
        override fun handleOnBackProgressed(backEvent: BackEventCompat) = router.progressed(backEvent)
        override fun handleOnBackPressed() = router.invoked()
        override fun handleOnBackCancelled() = router.cancelled()
    }

    fun attach() {
        parent.onBackPressedDispatcher.addCallback(parent, forwarder)
        refresh()
    }

    fun detach() {
        forwarder.remove()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        refresh()
    }

    private fun refresh() {
        forwarder.isEnabled = enabled && childHasCallbacks
    }
}

private class ChildOwner(
    parent: OnBackPressedDispatcherOwner,
    override val onBackPressedDispatcher: OnBackPressedDispatcher,
) : OnBackPressedDispatcherOwner, LifecycleOwner by parent

// ---- Слои страниц навигации ----------------------------------------------------------------

/**
 * Слой закрывающейся страницы (Details и подобные). Читает долю ухода из перехода NavHost и позу
 * из [ExteraBackMath]; сам переход при этом пустой (`ExitTransition.None`), двигаем мы.
 *
 * @param entry запись этой страницы: «закрывающаяся» значит, что её уводят «назад» (жестом — она
 * наверху стека, после отпускания или по кнопке — её там уже нет), а не накрывают новой.
 */
@Composable
fun Modifier.exteraClosingPage(
    transition: Transition<EnterExitState>,
    entry: NavBackStackEntry,
    navController: NavController,
): Modifier {
    // Линейная дорожка длительностью COMMIT_MS: сикбл-переход NavHost превращает прогресс жеста
    // в её время, значение дорожки = доля жеста; после отпускания она же ведёт дожим.
    val exit = transition.animateFloat(
        transitionSpec = { tween(ExteraBackSpec.COMMIT_MS, easing = LinearEasing) },
        label = "exteraBackExit",
    ) { if (it == EnterExitState.Visible) 0f else 1f }
    val closing = remember { ExteraPose() }
    val entering = remember { ExteraPose() }
    val from = remember { ExteraPose() }
    return graphicsLayer {
        if (transition.targetState != EnterExitState.PostExit) return@graphicsLayer
        val f = exit.value
        if (f <= 0.0005f) return@graphicsLayer
        val stack = navController.currentBackStack.value
        val dragging = ExteraBackGesture.phase == ExteraBackGesture.Phase.Dragging
        val popped = entry !in stack || (dragging && stack.lastOrNull() == entry)
        if (!popped) return@graphicsLayer

        val w = size.width
        val h = size.height
        val dp = this.density
        if (w <= 0f || h <= 0f) return@graphicsLayer
        if (dragging) {
            ExteraBackMath.drag(
                closing, entering, w, h, dp, ExteraBackGesture.fromLeft,
                ExteraBackSpec.adjust(f), ExteraBackGesture.dy,
            )
        } else {
            val f0 = if (ExteraBackGesture.phase == ExteraBackGesture.Phase.Committing) {
                ExteraBackGesture.commitRaw.coerceIn(0f, 0.999f)
            } else 0f
            ExteraBackMath.drag(
                from, entering, w, h, dp, ExteraBackGesture.fromLeft,
                ExteraBackSpec.adjust(f0), if (f0 > 0f) ExteraBackGesture.commitDy else 0f,
            )
            val t = ((f - f0) / (1f - f0)).coerceIn(0f, 1f)
            ExteraBackMath.commit(closing, from, w, h, dp, t, closing = true)
        }
        applyPose(closing, w)
    }
}

/**
 * Страница, которую открывает уходящая назад (главная под Details). Рисует под собой фон темы,
 * над собой — скрим; сама сжата и сдвинута, пока верхнюю страницу ведёт жест.
 *
 * @param isExteraPage является ли уводимая страница «extera-страницей»: если нет (настройки,
 * формы с морфом), нижняя страница остаётся такой, как её рисует NavHost.
 */
@Composable
fun ExteraRevealedPage(
    transition: Transition<EnterExitState>,
    navController: NavController,
    isExteraPage: (NavBackStackEntry) -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val hidden = transition.animateFloat(
        transitionSpec = { tween(ExteraBackSpec.COMMIT_MS, easing = LinearEasing) },
        label = "exteraBackReveal",
    ) { if (it == EnterExitState.Visible) 0f else 1f }
    val isDark = isAppInDarkTheme()
    val background = MaterialTheme.colorScheme.background
    val scratchA = remember { ExteraPose() }
    val scratchB = remember { ExteraPose() }
    val entering = remember { ExteraPose() }
    val scrimBase = if (isDark) ExteraBackSpec.SCRIM_DARK else ExteraBackSpec.SCRIM_LIGHT

    fun active(): Boolean {
        if (transition.targetState != EnterExitState.Visible || transition.currentState == EnterExitState.Visible) {
            return false
        }
        val gesture = ExteraBackGesture
        if (gesture.phase == ExteraBackGesture.Phase.Dragging) {
            gesture.poppedIsExteraPage =
                navController.currentBackStack.value.lastOrNull()?.let(isExteraPage) == true
        }
        return gesture.phase != ExteraBackGesture.Phase.Idle && gesture.poppedIsExteraPage
    }

    fun compute(w: Float, h: Float, dp: Float) {
        val gesture = ExteraBackGesture
        val f = 1f - hidden.value
        if (gesture.phase == ExteraBackGesture.Phase.Dragging) {
            ExteraBackMath.drag(scratchA, entering, w, h, dp, gesture.fromLeft, ExteraBackSpec.adjust(f), gesture.dy)
        } else {
            val f0 = gesture.commitRaw.coerceIn(0f, 0.999f)
            ExteraBackMath.drag(scratchA, scratchB, w, h, dp, gesture.fromLeft, ExteraBackSpec.adjust(f0), gesture.commitDy)
            val t = ((f - f0) / (1f - f0)).coerceIn(0f, 1f)
            ExteraBackMath.commit(entering, scratchB, w, h, dp, t, closing = false)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                val on = active()
                if (on) drawRect(background)
                drawContent()
                if (on) {
                    compute(size.width, size.height, density)
                    drawRect(Color.Black.copy(alpha = (scrimBase * entering.scrim).coerceIn(0f, 1f)))
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (!active()) return@graphicsLayer
                    val w = size.width
                    val h = size.height
                    if (w <= 0f || h <= 0f) return@graphicsLayer
                    compute(w, h, this.density)
                    applyPose(entering, w)
                },
        ) {
            content()
        }
    }
}

/** Переносит [pose] в слой: масштаб и сдвиг от левого верхнего угла, скругление с поправкой на масштаб. */
fun GraphicsLayerScope.applyPose(pose: ExteraPose, fullWidth: Float) {
    val s = if (fullWidth > 0f) pose.width / fullWidth else 1f
    transformOrigin = TransformOrigin(0f, 0f)
    translationX = pose.left
    translationY = pose.top
    scaleX = s
    scaleY = s
    alpha = pose.alpha
    clip = true
    shape = RoundedCornerShape(CornerSize(pose.corner / max(s, 0.01f)))
}
