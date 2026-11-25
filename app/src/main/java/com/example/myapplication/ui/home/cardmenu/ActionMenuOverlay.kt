package com.example.myapplication.ui.home.cardmenu

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.FrostedMaterials
import com.example.myapplication.ui.shared.frostedGlass
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ==========================================
// Общий оверлей «поднятая строка + ряд круглых кнопок».
//
// Им пользуются меню карточки главной ([CardActionMenuOverlay]) и меню серии/сезона на экране
// Details. Оверлей МОДАЛЕН: скрим съедает и тапы, и перетаскивания, поэтому под ним не скроллится
// список и не листаются страницы. Блюр фона делает не он, а владелец экрана: свой слой блюра здесь
// добавил бы ещё один узел над `layerBackdrop` и уронил стекло.
// ==========================================

private val ACTION_SIZE = 54.dp
private val ACTION_SPACING = 14.dp
private val CARD_TO_ACTIONS_GAP = 16.dp

internal const val DEFAULT_LIFT_SCALE = 1.04f

/** Одна круглая кнопка ряда действий. */
@Immutable
class ActionMenuItem(
    val icon: ImageVector,
    val tint: Color,
    val contentDescription: String?,
    val onClick: () -> Unit,
)

@Composable
fun ActionMenuOverlay(
    /** Границы выделенной строки в координатах корня окна. */
    boundsInRoot: Rect,
    /**
     * Показано ли меню.
     *
     * Отдельный признак, а не «есть цель — значит открыто»: уходить оверлей обязан так же, как
     * пришёл, а для этого он должен пережить собственное закрытие. Цель снимает уже [onClosed],
     * когда обратный ход доигран.
     */
    visible: Boolean,
    /** Живой бэкдроп страницы: кнопки действий сделаны из матового стекла над её содержимым. */
    backdrop: Backdrop,
    /** Нижняя занятая зона (док + нав-бар): ряд кнопок не должен под неё уезжать. */
    bottomInset: Dp,
    topInset: Dp,
    actions: List<ActionMenuItem>,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
    /** Во сколько раз поднятая копия крупнее оригинала. */
    liftScale: Float = DEFAULT_LIFT_SCALE,
    /** Копия строки: рисуется в размер [boundsInRoot], поверх скрима. */
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current

    // Собственное начало координат оверлея: строку мы поймали в координатах КОРНЯ, а
    // раскладываем внутри этого Box — без вычета его смещения копия уедет.
    var overlayOrigin by remember { mutableStateOf<Offset?>(null) }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            progress.animateTo(1f, animationSpec = MotionTokens.menuPop())
        } else {
            // Обратный ход той же дорогой, но без отскока: уходящее меню освобождает строку,
            // а не прощается с ней. Цель снимаем ПОСЛЕ анимации — иначе снимать было бы нечего.
            progress.animateTo(0f, animationSpec = MotionTokens.sheetDismissForced())
            onClosed()
        }
    }

    // Кнопки выезжают «лесенкой»: у каждой своя пружина со сдвигом по времени, а не общий прогресс
    // на весь ряд. Уход — без лесенки: меню освобождает строку разом.
    val actionProgress = remember(actions.size) { List(actions.size) { Animatable(0f) } }
    LaunchedEffect(visible, actionProgress) {
        if (visible) {
            actionProgress.forEachIndexed { index, animatable ->
                launch {
                    delay(index * MotionTokens.CardActionStaggerMillis.toLong())
                    animatable.animateTo(1f, animationSpec = MotionTokens.menuPop())
                }
            }
        } else {
            actionProgress.forEach { animatable ->
                launch { animatable.animateTo(0f, animationSpec = MotionTokens.sheetDismissForced()) }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.boundsInRoot().topLeft },
    ) {
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val actionsRowHeightPx = with(density) { ACTION_SIZE.toPx() }
        val gapPx = with(density) { CARD_TO_ACTIONS_GAP.toPx() }
        val topInsetPx = with(density) { topInset.toPx() }
        val bottomInsetPx = with(density) { bottomInset.toPx() }

        val origin = overlayOrigin
        val cardLocalTop = if (origin != null) boundsInRoot.top - origin.y else 0f
        val cardLocalLeft = if (origin != null) boundsInRoot.left - origin.x else 0f
        // Высота с учётом увеличения: ряд кнопок отсчитывается от РЕАЛЬНОГО низа поднятой строки.
        val scaledHeight = boundsInRoot.height * liftScale
        val grow = (scaledHeight - boundsInRoot.height) / 2f

        val lift = cardLiftFor(
            cardTop = cardLocalTop - grow,
            cardHeight = scaledHeight,
            menuHeight = actionsRowHeightPx,
            gap = gapPx,
            viewportHeight = viewportHeightPx,
            topInset = topInsetPx,
            bottomInset = bottomInsetPx,
        )

        // —— Скрим: гасит фон и запирает жесты —— //
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Прогресс читается в отрисовке, а не в композиции: иначе оверлей вместе с копией
                // строки пересобирался бы на каждом кадре открытия и закрытия.
                .drawBehind { drawRect(Color.Black, alpha = 0.45f * progress.value) }
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                // Перетаскивания съедаем отдельно: clickable их не перехватывает, и без этого
                // палец по скриму листал бы страницы рабочей области.
                .pointerInput(Unit) {
                    detectDragGestures { change, _ -> change.consume() }
                }
        )

        if (origin != null) {
            val cardWidth = with(density) { boundsInRoot.width.toDp() }
            val cardHeight = with(density) { boundsInRoot.height.toDp() }

            // —— Копия строки —— //
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            cardLocalLeft.roundToInt(),
                            (cardLocalTop - lift * progress.value).roundToInt(),
                        )
                    }
                    .width(cardWidth)
                    .height(cardHeight)
                    .graphicsLayer {
                        val s = 1f + (liftScale - 1f) * progress.value
                        scaleX = s
                        scaleY = s
                    }
                    // Тап по самой копии — тоже выход из режима: отдельного действия у неё нет.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDismiss() },
            ) {
                content()
            }

            // —— Ряд действий —— //
            val actionsRestTop = cardLocalTop - grow + scaledHeight + gapPx
            // Все кнопки обязаны уместиться, даже если выделенная строка уже ряда.
            val actionsWidth = maxOf(cardWidth, ACTION_SIZE * actions.size + ACTION_SPACING * (actions.size - 1))
                .coerceAtMost(maxWidth - 32.dp)
            val actionsWidthPx = with(density) { actionsWidth.toPx() }
            val marginPx = with(density) { 16.dp.toPx() }
            val actionsLeft = (cardLocalLeft + boundsInRoot.width / 2f - actionsWidthPx / 2f)
                .coerceIn(marginPx, (with(density) { maxWidth.toPx() } - actionsWidthPx - marginPx).coerceAtLeast(marginPx))
            Row(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            actionsLeft.roundToInt(),
                            (actionsRestTop - lift * progress.value).roundToInt(),
                        )
                    }
                    .width(actionsWidth)
                    .testTag("card_action_row"),
                horizontalArrangement = Arrangement.spacedBy(ACTION_SPACING, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEachIndexed { index, action ->
                    CardActionButton(
                        icon = action.icon,
                        contentDescription = action.contentDescription,
                        tint = action.tint,
                        backdrop = backdrop,
                        entry = actionProgress[index],
                        slideFromPx = gapPx,
                        onClick = action.onClick,
                    )
                }
            }
        }
    }
}

/**
 * Круглая кнопка действия.
 *
 * Материал — матовое стекло, а не сплошная заливка: кнопка висит над затемнённым и размытым
 * списком, то есть ровно над той информацией, ради которой стекло и существует. Сплошной кружок
 * на этом фоне читался как вырезанная дырка.
 */
@Composable
private fun CardActionButton(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    backdrop: Backdrop,
    /** Личный прогресс кнопки 0..1 (с перелётом): лесенку задаёт сдвиг запуска, см. оверлей. */
    entry: Animatable<Float, *>,
    /** Откуда кнопка выезжает: на столько пикселей выше места, «из-под» строки. */
    slideFromPx: Float,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            // Читается в графическом слое: прогресс кнопки не пересобирает композицию на кадрах.
            .graphicsLayer {
                val p = entry.value
                alpha = p.coerceIn(0f, 1f)
                translationY = (1f - p) * -slideFromPx
                val s = 0.55f + 0.45f * p
                scaleX = s
                scaleY = s
            }
            .size(ACTION_SIZE)
            .clip(CircleShape)
            .frostedGlass(
                backdrop = backdrop,
                shape = CircleShape,
                material = FrostedMaterials.dock(),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}
