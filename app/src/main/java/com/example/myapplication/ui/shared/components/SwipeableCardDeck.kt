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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
fun <T> SwipeableCardDeck(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    cardShape: Shape = RoundedCornerShape(30.dp),
    /** height = width * ratio; 5/4 — «постерная» пропорция рекомендаций. */
    cardHeightRatio: Float = 5f / 4f,
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
                        scale = 1f + (offset.coerceAtLeast(0f) / 4000f).coerceAtMost(0.16f),
                        rotation = 0f,
                        alpha = if (isFlyingOut) (1f - (offset / 800f)).coerceIn(0f, 1f) else 1f,
                        dim = 0f,
                    )
                }
                val progress = (offset / swipeThresholdPx).coerceIn(0f, 1f)
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
                                    offsetY.value > swipeThresholdPx && deck.size > 1 -> commitSwipe()
                                    offsetY.value < -undoThresholdPx && deck.size > 1 -> commitUndo()
                                    else -> animateBack()
                                }
                            },
                            onDragCancel = { animateBack() }
                        ) { change, dragAmount ->
                            change.consume()
                            // UNDISPATCHED: сдвиг применяется сразу в этом же событии, а не
                            // очередью корутин на следующий проход главного потока.
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                val current = offsetY.value
                                // Вверх — с демпфированием (резинка), вниз — свободно
                                val delta = if (current + dragAmount < 0f) dragAmount * UPWARD_DRAG_DAMPING else dragAmount
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
                    }
                }
            }
        }
    }
}
