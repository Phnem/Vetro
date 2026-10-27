package com.example.myapplication.ui.shared.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs

// ==========================================
// SwipeableCardDeck — generic-стопка свайп-карточек.
// Физика портирована из swipe_cards_animation.html (жила в RecommendationsSheet.CardDeck):
//  - свайп вниз = следующая (карточка уходит в конец колоды, круговая)
//  - свайп вверх = вернуть предыдущую (колода циклична в обе стороны)
//  - фоновые карточки веером (offset + поворот) интерполируют к уровню выше по мере драга
// Контент карточки — слот; используется рекомендациями и колодой статистики.
// ==========================================

private const val CARD_SCALE_STEP = 0.055f
private const val CARD_ROTATION_STEP_DEG = 2.5f
private const val VISIBLE_DEPTH = 3
/** Доля ширины колоды под карточку — крупная «постерная» карточка. */
private const val CARD_WIDTH_FRACTION = 0.92f
/** Демпфирование драга вверх — «резинка» перед возвратом карточки. */
private const val UPWARD_DRAG_DAMPING = 0.5f

/** Интерполированное визуальное состояние карточки в стопке (позиция/масштаб/затемнение). */
data class DeckVisualState(
    val translateY: Float,
    val scale: Float,
    val rotation: Float,
    val alpha: Float,
    val dim: Float,
)

/** Вердикт свайпа в режиме оценки: вверх — одобрено, вниз — отклонено. */
enum class SwipeVerdict { Approve, Reject }

/** Управление колодой снаружи: отмена последнего свайпа. */
@Stable
class SwipeDeckController {
    internal var undoAction: (() -> Unit)? = null

    /** Есть что отменять (история свайпов этой колоды не пуста). */
    var canUndo by mutableStateOf(false)
        internal set

    fun undo() {
        undoAction?.invoke()
    }
}

@Composable
fun rememberSwipeDeckController(): SwipeDeckController = remember { SwipeDeckController() }

/**
 * Режим оценки колоды: карточка свайпается в обе стороны, по пути плавно окрашиваясь
 * ([approveColor] вверх, [rejectColor] вниз), и уходит из колоды, а не возвращается в её конец.
 */
class SwipeFeedback<T>(
    val controller: SwipeDeckController,
    val approveColor: Color,
    val rejectColor: Color,
    val approveIcon: ImageVector,
    val rejectIcon: ImageVector,
    val onVerdict: (item: T, verdict: SwipeVerdict) -> Unit,
    /** Свайп отменён: карточка вернулась в колоду, оценку нужно откатить. */
    val onUndo: (item: T, verdict: SwipeVerdict) -> Unit,
    /** true — колода опустела после свайпа, false — отмена вернула карточку. */
    val onEmptyChanged: (Boolean) -> Unit,
)

/** Предел затемнения-подкраски карточки в режиме оценки (на пороге свайпа). */
private const val FEEDBACK_TINT_MAX_ALPHA = 0.7f

@Composable
fun <T> SwipeableCardDeck(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    cardShape: Shape = RoundedCornerShape(30.dp),
    /** height = width * ratio; 5/4 — «постерная» пропорция рекомендаций. */
    cardHeightRatio: Float = 5f / 4f,
    /** Режим оценки (вверх — одобрить, вниз — отклонить); null — прежняя круговая колода. */
    feedback: SwipeFeedback<T>? = null,
    onTopCardTap: ((T) -> Unit)? = null,
    cardContent: @Composable BoxScope.(item: T, isTop: Boolean) -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var deck by remember(items) { mutableStateOf(items.toList()) }
    val offsetY = remember(items) { Animatable(0f) }
    var isFlyingOut by remember { mutableStateOf(false) }

    val swipeThresholdPx = with(density) { 120.dp.toPx() }
    val undoThresholdPx = with(density) { 60.dp.toPx() }
    val cardOffsetPx = with(density) { 30.dp.toPx() }
    val flyOutDistancePx = with(density) { 620.dp.toPx() }

    fun animateBack() {
        scope.launch { offsetY.animateTo(0f, animationSpec = MotionTokens.menuPop()) }
    }

    fun commitSwipe() {
        scope.launch {
            performHaptic(view, Haptic.Light)
            isFlyingOut = true
            val top = deck.first()
            offsetY.animateTo(flyOutDistancePx, animationSpec = tween(280))
            deck = deck.drop(1) + top // круговая колода: наверх уходит в конец
            offsetY.snapTo(0f)
            isFlyingOut = false
        }
    }

    fun commitUndo() {
        scope.launch {
            performHaptic(view, Haptic.Light)
            // Последняя карточка возвращается наверх и «прилетает» снизу.
            // Порядок важен: сначала offset за нижнюю грань, потом ротация колоды —
            // иначе кадр с новым топом при offset=0 даёт вспышку карточки на фронте.
            offsetY.snapTo(flyOutDistancePx)
            deck = listOf(deck.last()) + deck.dropLast(1)
            offsetY.animateTo(0f, animationSpec = MotionTokens.sheetPresent())
        }
    }

    // ---- Режим оценки: вердикт вместо круговой навигации ----------------------
    val history = remember(items) { ArrayList<Pair<T, SwipeVerdict>>() }

    fun commitVerdict(verdict: SwipeVerdict) {
        val fb = feedback ?: return
        scope.launch {
            performHaptic(view, if (verdict == SwipeVerdict.Approve) Haptic.Success else Haptic.Light)
            isFlyingOut = true
            val top = deck.first()
            val direction = if (verdict == SwipeVerdict.Approve) -1f else 1f
            offsetY.animateTo(direction * flyOutDistancePx, animationSpec = tween(260))
            deck = deck.drop(1)
            history += top to verdict
            fb.controller.canUndo = true
            offsetY.snapTo(0f)
            isFlyingOut = false
            fb.onVerdict(top, verdict)
            if (deck.isEmpty()) fb.onEmptyChanged(true)
        }
    }

    fun undoVerdict() {
        val fb = feedback ?: return
        if (isFlyingOut || history.isEmpty()) return
        val (item, verdict) = history.removeAt(history.lastIndex)
        fb.controller.canUndo = history.isNotEmpty()
        scope.launch {
            performHaptic(view, Haptic.Light)
            // Карточка возвращается с той стороны, куда улетела. Порядок как в commitUndo:
            // сначала offset за грань, потом ротация колоды — без вспышки карточки на фронте.
            val direction = if (verdict == SwipeVerdict.Approve) -1f else 1f
            offsetY.snapTo(direction * flyOutDistancePx)
            deck = listOf(item) + deck
            fb.onEmptyChanged(false)
            fb.onUndo(item, verdict)
            offsetY.animateTo(0f, animationSpec = MotionTokens.sheetPresent())
        }
    }

    if (feedback != null) {
        SideEffect { feedback.controller.undoAction = { undoVerdict() } }
        LaunchedEffect(items) { feedback.controller.canUndo = false }
    }

    BoxWithConstraints(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        // Карточка максимально крупная по ширине, высота колоды = карточка + «пик»
        // задних карточек сверху → шторка обнимает контент без пустот.
        val cardWidth = maxWidth * CARD_WIDTH_FRACTION
        val cardHeight = cardWidth * cardHeightRatio
        val topPeek = 60.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight + topPeek),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Рисуем с нижнего слоя к верхнему; zIndex дублирует порядок для надёжности.
            // Положение карточек считается в фазах слоя и отрисовки: раньше оно вычислялось в
            // композиции, и каждый кадр драга пересобирал всю колоду вместе с содержимым карточек.
            fun visualFor(index: Int): DeckVisualState {
                val offset = offsetY.value
                if (index == 0) {
                    return DeckVisualState(
                        translateY = offset,
                        // В режиме оценки карточка не «приближается»: обратную связь несут цвет и иконка.
                        scale = if (feedback != null) 1f else 1f + (offset.coerceAtLeast(0f) / 4000f).coerceAtMost(0.16f),
                        rotation = 0f,
                        alpha = if (isFlyingOut) (1f - (abs(offset) / 800f)).coerceIn(0f, 1f) else 1f,
                        dim = 0f,
                    )
                }
                // В режиме оценки задние карточки подтягиваются при драге в обе стороны.
                val pull = if (feedback != null) abs(offset) else offset
                val progress = (pull / swipeThresholdPx).coerceIn(0f, 1f)
                val currentY = -cardOffsetPx * index
                val nextY = -cardOffsetPx * (index - 1)
                val currentScale = 1f - CARD_SCALE_STEP * index
                val nextScale = 1f - CARD_SCALE_STEP * (index - 1)
                val currentRot = -CARD_ROTATION_STEP_DEG * index
                val nextRot = -CARD_ROTATION_STEP_DEG * (index - 1)
                return DeckVisualState(
                    translateY = currentY + (nextY - currentY) * progress,
                    scale = currentScale + (nextScale - currentScale) * progress,
                    rotation = currentRot + (nextRot - currentRot) * progress,
                    alpha = when {
                        index > VISIBLE_DEPTH -> 0f
                        index == VISIBLE_DEPTH -> progress
                        else -> 1f
                    },
                    dim = (0.16f * index - 0.16f * progress).coerceIn(0f, 0.6f),
                )
            }

            deck.take(VISIBLE_DEPTH + 1).withIndex().reversed().forEach { (index, card) ->
                // Тап и драг сосуществуют на одном потоке указателя: detectTapGestures
                // отдаёт событие драгу, как только движение уходит за touch slop.
                val gestureModifier = if (index == 0) {
                    val tapModifier = if (onTopCardTap != null) {
                        Modifier.pointerInput(deck) {
                            detectTapGestures(onTap = { onTopCardTap(card) })
                        }
                    } else Modifier
                    tapModifier.pointerInput(deck) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                when {
                                    feedback != null && isFlyingOut -> Unit
                                    feedback != null && offsetY.value > swipeThresholdPx -> commitVerdict(SwipeVerdict.Reject)
                                    feedback != null && offsetY.value < -swipeThresholdPx -> commitVerdict(SwipeVerdict.Approve)
                                    feedback != null -> animateBack()
                                    offsetY.value > swipeThresholdPx && deck.size > 1 -> commitSwipe()
                                    offsetY.value < -undoThresholdPx && deck.size > 1 -> commitUndo()
                                    else -> animateBack()
                                }
                            },
                            onDragCancel = { if (feedback == null || !isFlyingOut) animateBack() }
                        ) { change, dragAmount ->
                            change.consume()
                            // Пока карточка улетает, новый драг не должен сбивать анимацию.
                            if (feedback != null && isFlyingOut) return@detectVerticalDragGestures
                            // UNDISPATCHED: сдвиг применяется сразу в этом же событии, а не
                            // очередью корутин на следующий проход главного потока.
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                val current = offsetY.value
                                // Круговая колода: вверх — с демпфированием (резинка), вниз — свободно.
                                // Режим оценки: обе стороны свободны, это два равноправных вердикта.
                                val delta = if (feedback == null && current + dragAmount < 0f) dragAmount * UPWARD_DRAG_DAMPING else dragAmount
                                offsetY.snapTo(current + delta)
                            }
                        }
                    }
                } else Modifier

                key(key(card)) {
                    Box(
                        modifier = gestureModifier
                            .zIndex((100 - index).toFloat())
                            .graphicsLayer {
                                val visual = visualFor(index)
                                translationY = visual.translateY
                                scaleX = visual.scale
                                scaleY = visual.scale
                                rotationZ = visual.rotation
                                alpha = visual.alpha
                            }
                            .width(cardWidth)
                            .height(cardHeight)
                            .clip(cardShape)
                            // Затемнение задних карточек (аналог filter: brightness()).
                            .drawWithContent {
                                drawContent()
                                val dim = visualFor(index).dim
                                if (dim > 0f) drawRect(Color.Black, alpha = dim)
                            }
                    ) {
                        cardContent(card, index == 0)
                        if (feedback != null && index == 0) {
                            FeedbackTint(feedback, { offsetY.value }, swipeThresholdPx)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Подкраска верхней карточки по мере драга: вверх зеленеет, вниз краснеет, у порога свайпа
 * цвет и иконка достигают максимума. Смещение читается только в фазе отрисовки/слоя —
 * кадры драга не пересобирают содержимое карточки.
 */
@Composable
private fun FeedbackTint(
    feedback: SwipeFeedback<*>,
    offset: () -> Float,
    thresholdPx: Float,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val o = offset()
                val p = (abs(o) / thresholdPx).coerceIn(0f, 1f)
                if (p > 0f) {
                    val smooth = p * p * (3f - 2f * p)
                    drawRect(
                        color = if (o < 0f) feedback.approveColor else feedback.rejectColor,
                        alpha = FEEDBACK_TINT_MAX_ALPHA * smooth,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        VerdictGlyph(feedback.approveIcon, offset, thresholdPx, direction = -1f)
        VerdictGlyph(feedback.rejectIcon, offset, thresholdPx, direction = 1f)
    }
}

/** Иконка вердикта проявляется и растёт только при драге в свою сторону (−1 вверх, +1 вниз). */
@Composable
private fun VerdictGlyph(
    icon: ImageVector,
    offset: () -> Float,
    thresholdPx: Float,
    direction: Float,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .size(72.dp)
            .graphicsLayer {
                val p = (offset() * direction / thresholdPx).coerceIn(0f, 1f)
                alpha = p
                scaleX = 0.7f + 0.3f * p
                scaleY = scaleX
            },
    )
}
