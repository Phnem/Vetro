package com.example.myapplication.ui.home.updates

import com.example.myapplication.ui.shared.theme.IosScroll
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
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

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
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
            verticalArrangement = Arrangement.spacedBy(CARD_GAP),
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
                        .padding(bottom = HEADER_GAP - CARD_GAP)
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
                        onDismiss = {
                            departed += update.animeId
                            onDismissUpdate(update)
                        },
                    )
                }
                item(key = "clearAll") {
                    Box(
                        modifier = Modifier
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
    }
}

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
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offsetX = remember { Animatable(0f) }
    var departing by remember { mutableStateOf(false) }
    var widthPx by remember { mutableStateOf(1f) }

    fun flyOut(direction: Float) {
        if (departing) return
        departing = true
        scope.launch {
            offsetX.animateTo(direction * widthPx * 1.2f, MotionTokens.springExit())
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onPlaced { widthPx = it.size.width.toFloat().coerceAtLeast(1f) }
            .flyFromBell(bellCenter, extraX = { offsetX.value }, extraAlpha = {
                1f - (abs(offsetX.value) / (widthPx * 0.9f)).coerceIn(0f, 1f)
            }, reveal = reveal)
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = enabled && !departing,
                state = rememberDraggableState { delta ->
                    scope.launch { offsetX.snapTo(offsetX.value + delta) }
                },
                onDragStopped = { velocity ->
                    val dismiss = MotionTokens.willDismiss(
                        offset = abs(offsetX.value),
                        containerSize = widthPx,
                        velocity = velocity / density.density,
                        offsetFraction = SWIPE_DISMISS_FRACTION,
                    )
                    if (dismiss) {
                        val direction = if (offsetX.value != 0f) sign(offsetX.value) else sign(velocity)
                        flyOut(if (direction == 0f) 1f else direction)
                    } else {
                        scope.launch { offsetX.animateTo(0f, MotionTokens.springSnappy()) }
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
            dimmed = false,
            clickEnabled = enabled && !departing,
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
    extraAlpha: () -> Float = { 1f },
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
            alpha = ((r - fadeStart) / fadeSpan).coerceIn(0f, 1f) * extraAlpha()
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
private const val SWIPE_DISMISS_FRACTION = 0.32f
private val PANEL_TOP = 76.dp
private val HEADER_GAP = 14.dp
private val CARD_GAP = 8.dp
