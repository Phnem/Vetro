package com.example.myapplication.ui.home.updates

import com.example.myapplication.ui.shared.theme.IosScroll
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.FrostedMaterials
import com.example.myapplication.ui.shared.frostedGlass
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

// ==========================================
// Центр уведомлений: карточки обновлений серий отдельными плашками матового стекла. Открывается
// колокольчиком и разворачивается ИЗ него (прототип experiments/notification-glass).
//
// Движение — одна пружина прогресса 0 → 1 на всю панель: открытие с лёгким перелётом
// (ω 12.8, ζ 0.78), закрытие — плотнее и без перелёта (ω 15.2, ζ 0.94). Каждый элемент читает
// прогресс со своей задержкой (0.075 на позицию) и летит из центра колокольчика в свой слот:
// сдвиг «от колокольчика» × (1 − reveal), масштаб 0.12 → 1, прозрачность проявляется в начале
// пути. Закрытие — та же пружина назад: элементы в обратном порядке складываются в колокольчик.
//
// Заголовок, карточки и кнопка «Очистить всё» — элементы одного списка во весь экран: список
// клипует содержимое, и только так вылет из колокольчика (он выше первой карточки) не обрезается.
// Сама панель лежит ПОД слоем верхнего дока: затемнение и размытие фона дока не касаются.
//
// Смахивание: карточка уходит из списка в момент решения, а её изображение долетает поверх
// списка на тех же Animatable (скорость пальца — начальная скорость полёта). Оставшиеся съезжают
// на освободившееся место пружиной с лёгким перелётом, каждая следующая чуть мягче — волна.
// Зазор между карточками — внутри элемента, а не в Arrangement: иначе сдвиг соседей шёл бы
// двумя движениями (слот, потом зазор).
// ==========================================

@Composable
fun NotificationCenter(
    open: Boolean,
    updates: List<AnimeUpdate>,
    coverPathFor: (animeId: String) -> String?,
    /** «S3 E5» вместо сквозного «47 → 48»; null — у тайтла нет расклада по сезонам. */
    episodeLabelFor: (AnimeUpdate) -> String?,
    bellCenter: () -> Offset?,
    backdrop: Backdrop,
    strings: NotificationStrings,
    onOpenUpdate: (AnimeUpdate) -> Unit,
    onDismissUpdate: (AnimeUpdate) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotion()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) {
        val target = if (open) 1f else 0f
        if (reducedMotion) {
            progress.snapTo(target)
        } else {
            progress.animateTo(target, if (open) OpenSpring else CloseSpring)
        }
    }
    // Узел живёт, пока доигрывает закрытие: иначе обратный ход обрывался бы на первом кадре.
    val animating by remember { derivedStateOf { progress.value > 0.001f } }
    if (!open && !animating) return

    BackHandler(enabled = open, onBack = onClose)

    val isDark = isAppInDarkTheme()
    val onCard = if (isDark) Color.White else Color(0xFF1C1C1E)
    val material = FrostedMaterials.notification()
    val departed = remember { mutableStateListOf<String>() }
    LaunchedEffect(updates) { departed.retainAll { id -> updates.any { it.animeId == id } } }
    val visible = updates.filter { it.animeId !in departed }
    val p = { progress.value }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current
    val departing = remember { mutableStateListOf<CenterDeparture>() }
    var origin by remember { mutableStateOf(Offset.Zero) }

    fun depart(
        update: AnimeUpdate,
        slot: Rect,
        offsetX: Animatable<Float, AnimationVector1D>,
        velocity: Float,
        direction: Float,
    ) {
        if (update.animeId in departed) return
        performHaptic(view, Haptic.Light)
        val card = CenterDeparture(update, slot, offsetX)
        departing += card
        departed += update.animeId
        scope.launch {
            try {
                if (!reducedMotion) {
                    offsetX.animateTo(
                        direction * slot.width * 1.25f,
                        MotionTokens.notificationFling(),
                        initialVelocity = velocity,
                    )
                }
            } finally {
                departing -= card
                onDismissUpdate(update)
            }
        }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPlaced { origin = it.boundsInRoot().topLeft }
            // Затемнение идёт с тем же прогрессом, что и карточки; тап мимо закрывает центр.
            .drawBehind { drawRect(Color.Black, alpha = SCRIM_ALPHA * p().coerceIn(0f, 1f)) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = open,
                onClick = onClose,
            ),
    ) {
        LazyColumn(
            flingBehavior = IosScroll.flingBehavior(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = top + PANEL_TOP,
                bottom = bottom + 32.dp,
                start = 12.dp,
                end = 12.dp,
            ),
        ) {
            item(key = "title") {
                Text(
                    text = strings.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .padding(bottom = HEADER_GAP)
                        .flyFromBell(bellCenter, startScale = 0.16f, fadeStart = 0.12f, fadeSpan = 0.55f) {
                            headingReveal(p())
                        },
                )
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = strings.empty,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = SnProFamily,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .animateItem(fadeInSpec = MotionTokens.easeEnter(), fadeOutSpec = null, placementSpec = null)
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                            .flyFromBell(bellCenter) { itemReveal(p(), 0) },
                    )
                }
            } else {
                itemsIndexed(visible, key = { _, u -> u.animeId }) { index, update ->
                    NotificationCenterCard(
                        update = update,
                        reveal = { itemReveal(p(), index) },
                        bellCenter = bellCenter,
                        coverPath = remember(update.animeId) { coverPathFor(update.animeId) },
                        episodeLabel = episodeLabelFor(update),
                        backdrop = backdrop,
                        material = material,
                        onCard = onCard,
                        isDark = isDark,
                        enabled = open,
                        onOpen = { onOpenUpdate(update) },
                        onDepart = { slot, offsetX, velocity, direction ->
                            depart(update, slot, offsetX, velocity, direction)
                        },
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = MotionTokens.easeEnter(),
                                fadeOutSpec = null,
                                placementSpec = MotionTokens.notificationReflowOffset(index),
                            )
                            .padding(bottom = CARD_GAP),
                    )
                }
                item(key = "clearAll") {
                    Box(
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                                placementSpec = MotionTokens.notificationReflowOffset(visible.size),
                            )
                            .fillMaxWidth()
                            .padding(top = HEADER_GAP),
                        contentAlignment = Alignment.Center,
                    ) {
                        ClearAllGlassButton(
                            text = strings.clearAll,
                            backdrop = backdrop,
                            onCard = onCard,
                            enabled = open,
                            onClick = onClearAll,
                            modifier = Modifier.flyFromBell(bellCenter) { itemReveal(p(), visible.size) },
                        )
                    }
                }
            }
        }

        // Смахнутые карточки долетают поверх списка с места, где их отпустили.
        departing.forEach { card ->
            key(card.update.animeId) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (card.slot.left - origin.x + card.x.value).roundToInt(),
                                (card.slot.top - origin.y).roundToInt(),
                            )
                        }
                        .size(with(density) { card.slot.width.toDp() }, CARD_HEIGHT)
                        .graphicsLayer {
                            alpha = p().coerceIn(0f, 1f)
                            notificationSwipe(card.x.value, card.slot.width)
                        },
                ) {
                    EpisodeUpdateCard(
                        update = card.update,
                        coverPath = remember(card.update.animeId) { coverPathFor(card.update.animeId) },
                        episodeLabel = episodeLabelFor(card.update),
                        isDark = isDark,
                        backdrop = backdrop,
                        material = material,
                        onCard = onCard,
                        accent = BrandOrange,
                        clickEnabled = false,
                        onClick = {},
                    )
                }
            }
        }
    }
}

/** Смахнутая карточка центра: где она лежала в списке и её горизонтальный сдвиг. */
private class CenterDeparture(
    val update: AnimeUpdate,
    val slot: Rect,
    val x: Animatable<Float, AnimationVector1D>,
)

/** Стеклянная капсула «Очистить всё» под последним уведомлением — тот же материал, что у карточек. */
@Composable
private fun ClearAllGlassButton(
    text: String,
    backdrop: Backdrop,
    onCard: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(CircleShape)
            .frostedGlass(backdrop = backdrop, shape = CircleShape, material = FrostedMaterials.notification())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
            color = onCard,
        )
    }
}

/**
 * Одна карточка центра. Вылет из колокольчика — общий [flyFromBell]; поверх него свой горизонтальный
 * сдвиг для смахивания. Сдвиги идут через `offset {}`, а не через `graphicsLayer`: стекло сэмплирует
 * бэкдроп по позиции раскладки.
 *
 * Решение «уйти» принимает карточка, полёт — центр ([onDepart]): элемент списка исчезает сразу,
 * а долетает его изображение поверх списка.
 */
@Composable
private fun NotificationCenterCard(
    update: AnimeUpdate,
    reveal: () -> Float,
    bellCenter: () -> Offset?,
    coverPath: String?,
    episodeLabel: String?,
    backdrop: Backdrop,
    material: com.example.myapplication.ui.shared.FrostedMaterial,
    onCard: Color,
    isDark: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDepart: (slot: Rect, offsetX: Animatable<Float, AnimationVector1D>, velocity: Float, direction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val view = LocalView.current
    val reducedMotion = rememberReducedMotion()
    val offsetX = remember { Animatable(0f) }
    // Не состояние: слот меняется каждый кадр сдвига соседей, а читается только в жесте и слое.
    val slot = remember { arrayOf(Rect.Zero) }
    val pastDetent = remember { BooleanArray(1) }
    fun threshold() = slot[0].width * MotionTokens.NotificationDismissFraction

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onPlaced { slot[0] = it.boundsInRoot() }
            .flyFromBell(
                bellCenter,
                extraX = { offsetX.value },
                extraLayer = { notificationSwipe(offsetX.value, slot[0].width) },
                reveal = reveal,
            )
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = enabled,
                state = rememberDraggableState { delta ->
                    // Дельты применяются по очереди в корутинах — каждая от текущего значения,
                    // так что захват посреди возврата продолжает движение без скачка.
                    scope.launch {
                        val x = offsetX.value + delta
                        offsetX.snapTo(x)
                        // Щелчок фиксатора: палец перешёл черту, за которой карточка уйдёт.
                        val past = abs(x) > threshold()
                        if (past && !pastDetent[0]) performHaptic(view, Haptic.Tick)
                        pastDetent[0] = past
                    }
                },
                onDragStarted = { pastDetent[0] = abs(offsetX.value) > threshold() },
                onDragStopped = { velocity ->
                    val x = offsetX.value
                    val flingPx = MotionTokens.NotificationFlingVelocityDpPerSec * density.density
                    // Бросок решает направление сам, дальний ход без броска — по стороне сдвига.
                    val direction = when {
                        abs(velocity) > flingPx -> sign(velocity)
                        abs(x) > threshold() -> sign(x)
                        else -> 0f
                    }
                    if (direction != 0f) {
                        onDepart(slot[0], offsetX, velocity, direction)
                    } else if (reducedMotion) {
                        offsetX.snapTo(0f)
                    } else {
                        offsetX.animateTo(0f, MotionTokens.notificationRebound(), initialVelocity = velocity)
                    }
                },
            ),
    ) {
        EpisodeUpdateCard(
            update = update,
            coverPath = coverPath,
            episodeLabel = episodeLabel,
            isDark = isDark,
            backdrop = backdrop,
            material = material,
            onCard = onCard,
            accent = BrandOrange,
            clickEnabled = enabled,
            onClick = onOpen,
        )
    }
}

/**
 * Вылет элемента из центра колокольчика в свой слот. [reveal] 0 — элемент стянут в центр
 * колокольчика, 1 — на месте (перелёт пружины даёт чуть больше 1). Слот меряется до сдвига: узел
 * `onPlaced` стоит раньше `offset` в цепочке.
 */
@Composable
private fun Modifier.flyFromBell(
    bellCenter: () -> Offset?,
    extraX: () -> Float = { 0f },
    /** Дополнительные преобразования слоя поверх вылета (смахивание). */
    extraLayer: GraphicsLayerScope.() -> Unit = {},
    startScale: Float = START_SCALE,
    fadeStart: Float = FADE_START,
    fadeSpan: Float = FADE_SPAN,
    reveal: () -> Float,
): Modifier {
    var slotCenter by remember { mutableStateOf<Offset?>(null) }
    return this
        .onPlaced { coordinates -> slotCenter = coordinates.boundsInRoot().center }
        .offset {
            val r = reveal()
            val bell = bellCenter()
            val slot = slotCenter
            val towardsBell = if (bell != null && slot != null) bell - slot else Offset.Zero
            IntOffset(
                (towardsBell.x * (1f - r) + extraX()).roundToInt(),
                (towardsBell.y * (1f - r)).roundToInt(),
            )
        }
        .graphicsLayer {
            val r = reveal()
            val s = startScale + (1f - startScale) * r
            scaleX = s
            scaleY = s
            alpha = ((r - fadeStart) / fadeSpan).coerceIn(0f, 1f)
            extraLayer()
        }
}

/** Заголовок — с небольшой задержкой и на более длинной части пути (как в прототипе). */
private fun headingReveal(progress: Float): Float =
    ((progress - 0.05f) / 0.91f).coerceIn(0f, MAX_REVEAL)

/** Элемент [index] стартует на 0.075 прогресса позже предыдущего и доходит к концу пружины. */
private fun itemReveal(progress: Float, index: Int): Float {
    val delay = (index * STAGGER).coerceAtMost(MAX_DELAY)
    return ((progress - delay) / (1f - delay)).coerceIn(0f, MAX_REVEAL)
}

/** Открытие: ω = 12.8 рад/с, ζ = 0.78 — лёгкий перелёт. Жёсткость = ω². */
private val OpenSpring = spring(dampingRatio = 0.78f, stiffness = 12.8f * 12.8f, visibilityThreshold = 0.0007f)
/** Закрытие: ω = 15.2 рад/с, ζ = 0.94 — быстрее и почти без перелёта. */
private val CloseSpring = spring(dampingRatio = 0.94f, stiffness = 15.2f * 15.2f, visibilityThreshold = 0.0007f)

private const val SCRIM_ALPHA = 0.42f
private const val STAGGER = 0.075f
/** Длинный список не должен стартовать хвостом «после конца» пружины. */
private const val MAX_DELAY = 0.6f
private const val MAX_REVEAL = 1.025f
private const val START_SCALE = 0.12f
private const val FADE_START = 0.06f
private const val FADE_SPAN = 0.31f
private val PANEL_TOP = 76.dp
private val HEADER_GAP = 14.dp
private val CARD_GAP = 8.dp
