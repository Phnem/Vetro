package com.example.myapplication.ui.home.updates

import com.example.myapplication.ui.shared.theme.BrandOrange
import androidx.compose.ui.util.lerp
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.geometry.Offset
import com.kyant.backdrop.backdrops.emptyBackdrop
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.FrostedMaterial
import com.example.myapplication.ui.shared.FrostedMaterials
import com.example.myapplication.ui.shared.frostedGlass
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

// ==========================================
// EpisodeUpdateStack — iOS-style top-of-screen пуш-стопка «вышла новая серия».
// Одна карточка — одиночный баннер; несколько — стопка, где старые выглядывают
// снизу (уже и ниже), как сложенные уведомления на iPhone.
//
// Верхняя карточка — матовое стекло (см. FrostedGlass.kt): под плашкой едет список обложек,
// и диффузия честно показывает, что она лежит ПОВЕРХ контента, а не заменяет его собой.
//
// Задние карточки стопки остаются НЕПРОЗРАЧНЫМИ. Это не упрощение, а требование читаемости:
// сквозь полупрозрачную стопку просвечивал текст нижних карточек и превращался в кашу. Набор
// модификаторов у них при этом тот же — меняется только материал, иначе смена верхней карточки
// перестраивала бы цепочку узлов на живом компоненте и роняла запись бэкдропа.
//
// Кнопок нет: серии проставляются автоматически, карточка только сообщает о выходе.
// Тап по плашке = открыть Details тайтла; свайп верхней влево/вправо = смахнуть.
//
// Физика (пружины — MotionTokens.notification*):
// • Каждая карточка стоит в «слоте» (0 — верх). Положение, масштаб, затемнение и видимость —
//   функции дробного слота, поэтому любые переходы между слотами непрерывны.
// • Пока палец уводит верхнюю, задние заранее приподнимаются на долю слота. В момент решения
//   верхняя выходит из стопки сразу (departedIds), но её узел доживает как улетающая карточка с
//   теми же Animatable — скорость пальца становится начальной скоростью полёта. Оставшиеся
//   карточки поднимаются на слот пружиной с лёгким перелётом — «падение на место».
// • Вертикальный жест — резинка: вниз стопка разводится веером, вверх почти не поддаётся.
// • Прилёт: первая стопка падает сверху целиком, новая карточка — поверх, отодвигая остальные.
// • onDismiss вызывается ПОСЛЕ полёта (даже если его прервали), до обновления БД карточку
//   прячет departedIds, чтобы не мигнула обратно.
// ==========================================

private const val VISIBLE_BACK_CARDS = 2
private const val BACK_SCALE_STEP = 0.05f
internal val CARD_HEIGHT = 76.dp

/** Отставание каждой следующей карточки при схлопывании и её конечный масштаб в колокольчике. */
private const val COLLAPSE_STAGGER = 0.12f
private const val COLLAPSE_SCALE = 0.18f
private val BACK_PEEK = 9.dp
private const val BACK_DIM = 0.10f

/** Доля слота, на которую задние карточки приподнимаются, пока палец уводит верхнюю. */
private const val ANTICIPATION = 0.3f

/** Тяга вниз разводит стопку веером: карточка i уходит дальше верхней на SPREAD · (i / N)^1.35. */
private const val ACCORDION_SPREAD = 0.6f
private const val ACCORDION_EXPONENT = 1.35f

/** Пределы резинки: вниз стопка тянется заметно, вверх почти не поддаётся. */
private val PULL_LIMIT = 120.dp
private val PUSH_LIMIT = 48.dp
private val FLY_UP_DISTANCE = 480.dp

/** Растяжение по ходу прилёта (площадь сохраняется) и скорость, на которой оно предельное. */
private const val ARRIVAL_STRETCH = 0.035f
private const val ARRIVAL_STRETCH_VELOCITY_DP = 2400f

/** Наклон на полную ширину свайпа (шарнир у верхней кромки) и сплющивание по вертикали. */
private const val SWIPE_TILT_DEGREES = 8f
private const val SWIPE_FLATTEN = 0.04f

/**
 * Карточка под пальцем: наклон от верхнего шарнира, лёгкое сплющивание и растворение к краю.
 * Общее для стопки и центра уведомлений — плашка ведёт себя одинаково, где её ни смахни.
 */
internal fun GraphicsLayerScope.notificationSwipe(x: Float, width: Float) {
    if (x == 0f || width <= 0f) return
    val p = (abs(x) / width).coerceIn(0f, 1f)
    transformOrigin = TransformOrigin(0.5f, 0f)
    rotationZ = (x / width) * SWIPE_TILT_DEGREES
    scaleY *= 1f - SWIPE_FLATTEN * p
    alpha *= (1f - (p - 0.25f) / 0.65f).coerceIn(0f, 1f)
}

/** Улетающая карточка: узел тот же, что был у верхней, и Animatable те же — скорость не рвётся. */
private class DepartingCard(
    val update: AnimeUpdate,
    val x: Animatable<Float, AnimationVector1D>,
    val y: Animatable<Float, AnimationVector1D>,
)

/**
 * Смещение стопки в слотах, которое пружина гасит до нуля. Толчок меняет значение синхронно —
 * в том же кадре, что и сам список, — а новая пружина подхватывает скорость прерванной.
 */
private class SlotSettle {
    var value by mutableFloatStateOf(0f)
        private set
    private var velocity = 0f
    private var job: Job? = null

    fun kick(scope: CoroutineScope, by: Float, spec: AnimationSpec<Float>?) {
        job?.cancel()
        value += by
        if (spec == null) {
            value = 0f
            velocity = 0f
            return
        }
        job = scope.launch {
            animate(value, 0f, velocity, spec) { v, vel ->
                value = v
                velocity = vel
            }
            velocity = 0f
        }
    }
}

@Composable
fun EpisodeUpdateStack(
    updates: List<AnimeUpdate>,
    coverPathFor: (animeId: String) -> String?,
    /** «S3 E5» вместо сквозного «47 → 48»; null — у тайтла нет расклада по сезонам. */
    episodeLabelFor: (AnimeUpdate) -> String?,
    onOpen: (AnimeUpdate) -> Unit,
    onDismiss: (AnimeUpdate) -> Unit,
    /** Живая запись сцены под плашкой — то, что матовое стекло размывает. */
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    /**
     * Новый интерфейс: стопка схлопывается в колокольчик. Пока true — карточки улетают в точку
     * [collapseTarget] (центр колокольчика в координатах корня), по окончании — [onCollapsed].
     */
    collapsing: Boolean = false,
    collapseTarget: () -> Offset? = { null },
    onCollapsed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val view = LocalView.current
    val reducedMotion = rememberReducedMotion()
    val isDark = isAppInDarkTheme()

    // iOS-палитра: брендовый оранжевый акцент, текст под цвет темы.
    val onCard = if (isDark) Color.White else Color(0xFF1C1C1E)
    val accent = BrandOrange
    val topMaterial = FrostedMaterials.notification()
    val stackedMaterial = FrostedMaterials.stackedNotification()
    val noBackdrop = remember { emptyBackdrop() }
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    // Улетевшие, но ещё не удалённые из БД карточки: скрываем до обновления Flow.
    val departedIds: SnapshotStateList<String> = remember { mutableStateListOf() }
    LaunchedEffect(updates) {
        departedIds.retainAll { id -> updates.any { it.animeId == id } }
    }
    val departing = remember { mutableStateListOf<DepartingCard>() }
    val visible = updates.filter { it.animeId !in departedIds }
    val topKey = visible.firstOrNull()?.animeId

    // Новая карточка наверху — та, которой стопка ещё не видела (первое появление — не в счёт:
    // тогда падает вся стопка).
    val seenIds = remember { HashSet<String>() }
    val arrivedOnTop = topKey != null && seenIds.isNotEmpty() && topKey !in seenIds
    SideEffect { visible.forEach { seenIds += it.animeId } }

    val arrivalPx = with(density) { (CARD_HEIGHT + 24.dp).toPx() } + WindowInsets.statusBars.getTop(density)
    val enter = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (enter.value < 1f) enter.animateTo(1f, MotionTokens.notificationArrive())
    }

    val dragX = remember(topKey) { Animatable(0f) }
    val dragY = remember(topKey) { Animatable(if (arrivedOnTop && !reducedMotion) -arrivalPx else 0f) }
    LaunchedEffect(topKey) {
        if (dragY.value != 0f) dragY.animateTo(0f, MotionTokens.notificationArrive())
    }
    // Подъём всей стопки после ухода верхней и отодвигание задних под прилетевшую.
    val settle = remember { SlotSettle() }
    val pushBack = remember { SlotSettle() }
    val pushBackPending = remember(topKey) { booleanArrayOf(arrivedOnTop && !reducedMotion) }
    // Слоты читаются только в лямбдах раскладки и слоя, а те выполняются после SideEffect того же
    // кадра — прилетевшая карточка не успевает показать стопку уже сдвинутой.
    SideEffect {
        if (pushBackPending[0]) {
            pushBackPending[0] = false
            pushBack.kick(scope, by = -1f, spec = MotionTokens.notificationReflow())
        }
    }

    // Схлопывание (UNIVERSAL_MOTION_SPEC §5, выход): содержимое гаснет коротким EaseExit, оболочки
    // летят в колокольчик пружиной springExit с каскадом — ближняя к нему верхняя карточка первой.
    val collapse = remember { Animatable(0f) }
    val collapseLight = remember { Animatable(1f) }
    val currentOnCollapsed by rememberUpdatedState(onCollapsed)
    LaunchedEffect(collapsing) {
        if (!collapsing) return@LaunchedEffect
        launch {
            collapseLight.animateTo(0f, tween(MotionTokens.EaseExitMillis, easing = MotionTokens.EaseExit))
        }
        collapse.animateTo(1f, MotionTokens.springExit())
        currentOnCollapsed()
    }
    var stackOrigin by remember { mutableStateOf(Offset.Zero) }

    if (visible.isEmpty() && departing.isEmpty()) return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .onPlaced { stackOrigin = it.boundsInRoot().topLeft }
            // Первое появление: стопка падает из-за верхней кромки. Сдвиг раскладкой — стекло
            // сэмплит бэкдроп по положению узла.
            .offset { IntOffset(0, (-(1f - enter.value) * arrivalPx).roundToInt()) },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val peekPx = with(density) { BACK_PEEK.toPx() }
        val cardHeightPx = with(density) { CARD_HEIGHT.toPx() }
        val dismissThresholdPx = widthPx * MotionTokens.NotificationDismissFraction
        val flyOutXPx = widthPx * 1.25f
        val flyUpPx = with(density) { FLY_UP_DISTANCE.toPx() }
        val flingVelocityPx = with(density) { MotionTokens.NotificationFlingVelocityDpPerSec.dp.toPx() }
        val pullLimitPx = with(density) { PULL_LIMIT.toPx() }
        val pushLimitPx = with(density) { PUSH_LIMIT.toPx() }
        val stretchVelocityPx = with(density) { ARRIVAL_STRETCH_VELOCITY_DP.dp.toPx() }

        /** Насколько задние уже приподнялись вслед за уходящей верхней (доля слота). */
        fun anticipation(): Float = ANTICIPATION * (abs(dragX.value) / dismissThresholdPx).coerceIn(0f, 1f)

        fun limitFor(raw: Float) = if (raw >= 0f) pullLimitPx else pushLimitPx
        fun rubber(raw: Float): Float = sign(raw) * MotionTokens.rubberBand(abs(raw), limitFor(raw))
        /** Обратная к резинке: откуда палец «тянет», если схватил карточку посреди возврата. */
        fun unrubber(y: Float): Float {
            val d = limitFor(y)
            val b = abs(y).coerceAtMost(d * 0.98f)
            return sign(y) * b * d / (MotionTokens.RubberBandConstant * (d - b))
        }
        /** Скорость карточки на резинке = скорость пальца × производная резинки в этой точке. */
        fun rubberVelocity(raw: Float, fingerVelocity: Float): Float {
            val d = limitFor(raw)
            val c = MotionTokens.RubberBandConstant
            val denominator = d + c * abs(raw)
            return fingerVelocity * c * d * d / (denominator * denominator)
        }

        /**
         * Верхняя выходит из стопки: сразу пропадает из [visible], а её узел продолжает полёт на
         * тех же Animatable. Задние уже стоят на (слот − предвосхищение) — пружина поднимает их
         * оттуда, а не с исходных мест.
         */
        fun depart(update: AnimeUpdate, haptic: Boolean, flight: suspend (DepartingCard) -> Unit) {
            if (update.animeId in departedIds) return
            if (haptic) performHaptic(view, Haptic.Light)
            val card = DepartingCard(update, dragX, dragY)
            settle.kick(
                scope,
                by = 1f - anticipation(),
                spec = if (reducedMotion) null else MotionTokens.notificationReflow(),
            )
            departing += card
            departedIds += update.animeId
            scope.launch {
                try {
                    if (!reducedMotion) flight(card)
                } finally {
                    departing -= card
                    currentOnDismiss(update)
                }
            }
        }

        fun releaseHorizontal(update: AnimeUpdate, velocity: Float) {
            val x = dragX.value
            // Бросок решает направление сам: быстрый возврат пальца не уводит карточку туда, где
            // она была, — она улетает туда, куда её бросили.
            val direction = when {
                abs(velocity) > flingVelocityPx -> sign(velocity)
                abs(x) > dismissThresholdPx -> sign(x)
                else -> 0f
            }
            if (direction == 0f) {
                scope.launch {
                    if (reducedMotion) dragX.snapTo(0f)
                    else dragX.animateTo(0f, MotionTokens.notificationRebound(), initialVelocity = velocity)
                }
            } else {
                depart(update, haptic = true) {
                    it.x.animateTo(direction * flyOutXPx, MotionTokens.notificationFling(), initialVelocity = velocity)
                }
            }
        }

        fun releaseVertical(raw: Float, fingerVelocity: Float) {
            scope.launch {
                if (reducedMotion) dragY.snapTo(0f)
                else dragY.animateTo(
                    0f,
                    MotionTokens.notificationRebound(),
                    initialVelocity = rubberVelocity(raw, fingerVelocity),
                )
            }
        }

        /** Тап: карточка уходит вверх и открывает Details — как «раскрытие» iOS-пуша. */
        fun openAndFlyUp(update: AnimeUpdate) {
            if (update.animeId in departedIds) return
            currentOnOpen(update)
            depart(update, haptic = false) {
                it.y.animateTo(-flyUpPx, MotionTokens.notificationFling())
            }
        }

        val stack = visible.take(VISIBLE_BACK_CARDS + 2)
        val backCount = (visible.size - 1).coerceIn(0, VISIBLE_BACK_CARDS)
        val stackHeight = CARD_HEIGHT + BACK_PEEK * backCount
        val top = stack.firstOrNull()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stackHeight)
                // Жест — на всей стопке и всегда про текущую верхнюю: палец, попавший в выглядывающий
                // край задней карточки, тоже тянет верхнюю. Ось фиксируется первым смещением.
                .pointerInput(topKey, widthPx, collapsing) {
                    val card = top ?: return@pointerInput
                    if (collapsing) return@pointerInput
                    val tracker = VelocityTracker()
                    var horizontal: Boolean? = null
                    var rawX = 0f
                    var rawY = 0f
                    var pastDetent = false
                    fun release(velocityX: Float, velocityY: Float) {
                        when (horizontal) {
                            true -> releaseHorizontal(card, velocityX)
                            false -> releaseVertical(rawY, velocityY)
                            null -> Unit
                        }
                    }
                    detectDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            horizontal = null
                            rawX = dragX.value
                            rawY = unrubber(dragY.value)
                            pastDetent = abs(rawX) > dismissThresholdPx
                        },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity()
                            release(velocity.x, velocity.y)
                        },
                        onDragCancel = { release(0f, 0f) },
                        onDrag = { change, amount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val isHorizontal = horizontal ?: (abs(amount.x) >= abs(amount.y)).also { horizontal = it }
                            if (isHorizontal) {
                                rawX += amount.x
                                val x = rawX
                                scope.launch { dragX.snapTo(x) }
                                // Щелчок фиксатора: палец перешёл черту, за которой карточка уйдёт.
                                val past = abs(x) > dismissThresholdPx
                                if (past && !pastDetent) performHaptic(view, Haptic.Tick)
                                pastDetent = past
                            } else {
                                rawY += amount.y
                                val y = rubber(rawY)
                                scope.launch { dragY.snapTo(y) }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.TopCenter
        ) {
            // Всё, что зависит от движения, считается ВНУТРИ лямбд раскладки и слоя: чтение
            // Animatable в композиции пересобирало всю стопку на каждом кадре драга.
            // Один цикл на улетающие и лежащие карточки: key() переносит узел верхней в улетающие
            // без пересоздания — стекло, обложка и скорость остаются теми же.
            val entries: List<Triple<AnimeUpdate, Int, DepartingCard?>> =
                departing.map { Triple(it.update, -1, it) } +
                    stack.withIndex().reversed().map { (i, u) -> Triple(u, i, null) }

            entries.forEach { (update, index, departure) ->
                val isTop = index == 0

                // Дробный слот: 0 — верх. Задние дополнительно приподняты предвосхищением и
                // отодвинуты под прилетевшую карточку.
                fun slot(): Float {
                    if (departure != null) return 0f
                    var s = index + settle.value
                    if (!isTop) s += pushBack.value - anticipation()
                    return s
                }
                /** Тяга вниз: задние уходят дальше верхней — стопка разворачивается веером. */
                fun pull(): Float {
                    val y = dragY.value.coerceAtLeast(0f)
                    val depth = (index.toFloat() / VISIBLE_BACK_CARDS).pow(ACCORDION_EXPONENT)
                    return y * (1f + ACCORDION_SPREAD * depth)
                }
                fun translateX(): Float = when {
                    departure != null -> departure.x.value
                    isTop -> dragX.value
                    else -> 0f
                }
                fun translateY(): Float = when {
                    departure != null -> departure.y.value
                    isTop -> peekPx * slot() + dragY.value
                    else -> peekPx * slot() + pull()
                }
                // Доля пути в колокольчик для этой карточки: задние стартуют с отставанием.
                fun collapseProgress(): Float {
                    if (departure != null) return 0f
                    val lag = index * COLLAPSE_STAGGER
                    return ((collapse.value - lag) / (1f - lag)).coerceIn(0f, 1f)
                }
                fun collapseShift(): Offset {
                    val p = collapseProgress()
                    val target = collapseTarget() ?: return Offset.Zero
                    if (p <= 0f) return Offset.Zero
                    val cardCenter = Offset(
                        stackOrigin.x + widthPx / 2f,
                        stackOrigin.y + translateY() + cardHeightPx / 2f,
                    )
                    return (target - cardCenter) * p
                }
                fun alpha(): Float {
                    val appear = (enter.value * 2.5f).coerceIn(0f, 1f) * collapseLight.value
                    return if (departure != null) {
                        appear * (1f + departure.y.value / flyUpPx).coerceIn(0f, 1f)
                    } else {
                        // Слоты за последним видимым тают: четвёртая карточка проявляется, поднимаясь.
                        appear * (VISIBLE_BACK_CARDS + 1 - slot()).coerceIn(0f, 1f)
                    }
                }

                key(update.animeId) {
                    Box(
                        modifier = Modifier
                            .zIndex(if (departure != null) 200f else (100 - index).toFloat())
                            // Сдвиг — через offset (раскладкой), а НЕ через translation в
                            // graphicsLayer: стекло сэмплит бэкдроп по положению узла, и сдвиг
                            // слоем роняет его в плоскую заливку — известные грабли этой
                            // кодовой базы (см. тот же приём у дока рабочей области).
                            .offset {
                                val shift = collapseShift()
                                IntOffset(
                                    x = (translateX() + shift.x).roundToInt(),
                                    y = (translateY() + shift.y).roundToInt(),
                                )
                            }
                            .graphicsLayer {
                                val scale = (1f - BACK_SCALE_STEP * slot()) *
                                    lerp(1f, COLLAPSE_SCALE, collapseProgress())
                                scaleX = scale
                                scaleY = scale
                                alpha = alpha()
                                if (isTop) {
                                    // Растяжение по ходу падения, площадь сохраняется: плашка
                                    // вытягивается на скорости и собирается, приземляясь.
                                    val velocity = abs(dragY.velocity + enter.velocity * arrivalPx)
                                    val stretch = 1f + ARRIVAL_STRETCH *
                                        (velocity / stretchVelocityPx).coerceIn(0f, 1f)
                                    scaleY *= stretch
                                    scaleX /= stretch
                                }
                                if (isTop || departure != null) notificationSwipe(translateX(), widthPx)
                            }
                            .fillMaxWidth()
                    ) {
                        EpisodeUpdateCard(
                            update = update,
                            // Memo: resolve пути идёт в БД, а стопка рекомпозируется каждый кадр драга.
                            coverPath = remember(update.animeId) { coverPathFor(update.animeId) },
                            episodeLabel = episodeLabelFor(update),
                            isDark = isDark,
                            // Задней карточке бэкдроп не нужен: у её материала нет размытия, а под
                            // верхней её всё равно не видно. Узел тот же — меняется параметр.
                            backdrop = if (isTop || departure != null) backdrop else noBackdrop,
                            material = if (isTop || departure != null) topMaterial else stackedMaterial,
                            onCard = onCard,
                            accent = accent,
                            dim = { BACK_DIM * slot().coerceIn(0f, 1f) },
                            clickEnabled = isTop && !collapsing,
                            onClick = { openAndFlyUp(update) },
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// Карточка: [обложка] [название + счётчик серий] — вся плашка кликабельна (→ Details)
// ==========================================

@Composable
internal fun EpisodeUpdateCard(
    update: AnimeUpdate,
    coverPath: String?,
    episodeLabel: String?,
    isDark: Boolean,
    backdrop: Backdrop,
    material: FrostedMaterial,
    onCard: Color,
    accent: Color,
    /** Затемнение задней карточки (0…1 от слота) — читается при отрисовке, узел не меняется. */
    dim: () -> Float = { 0f },
    clickEnabled: Boolean,
    onClick: () -> Unit,
    /** Прозрачность содержимого отдельно от стекла: у оболочки и текста разные траектории (спека §5). */
    contentAlpha: () -> Float = { 1f },
) {
    val tileShape = RoundedCornerShape(22.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            // Тень, кант и подсветка живут внутри материала: отдельный `shadow` поверх стекла
            // рисовал бы вторую тень по тому же контуру.
            .clip(tileShape)
            .frostedGlass(backdrop = backdrop, shape = tileShape, material = material)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = clickEnabled,
            ) { onClick() }
            .drawWithContent {
                drawContent()
                val amount = dim()
                if (amount > 0f) drawRect(Color.Black.copy(alpha = amount))
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // —— Миниатюра обложки —— //
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDark) Color(0xFF2C2C2E) else Color(0xFFEFEFF0)),
                contentAlignment = Alignment.Center
            ) {
                if (coverPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(File(coverPath))
                            .size(Size(104, 104))
                            .crossfade(true)
                            .build(),
                        contentDescription = update.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))

            // —— Название (fade на переполнении) + счётчик серий —— //
            Column(modifier = Modifier.weight(1f)) {
                FadingEndText(
                    text = update.title,
                    color = onCard,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = episodeLabel ?: String.format(
                        Locale.getDefault(),
                        // Без единиц: регистр «ep./Ep.» гулял, а язык строке не передаётся.
                        "%d → %d",
                        update.currentEpisodes,
                        update.newEpisodes
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
            }
            Spacer(Modifier.width(8.dp))

            // —— Шеврон: плашка кликабельна целиком (→ Details) —— //
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = onCard.copy(alpha = 0.35f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Однострочный текст: если не влезает — конец растворяется горизонтальным градиентом
 * (без многоточия), как subject в iOS-пуше. Fade включается только при реальном
 * переполнении, иначе короткий заголовок терял бы последние буквы.
 */
@Composable
private fun FadingEndText(
    text: String,
    color: Color,
) {
    var overflowed by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { overflowed = it.hasVisualOverflow },
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                if (overflowed) compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                if (overflowed) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0.72f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
            }
    )
}
