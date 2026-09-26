package com.example.myapplication.ui.home.updates

import com.example.myapplication.ui.shared.theme.IosScroll
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.util.lerp
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.FrostedMaterials
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
// Центр уведомлений: карточки обновлений серий отдельными плашками матового стекла, как шторка
// уведомлений смартфона. Открывается колокольчиком и вырастает ИЗ него (UNIVERSAL_MOTION_SPEC,
// закон 1 — у появления есть точка исхода).
//
// Хореография (§5): оболочки карточек — пружина `springSurface` из точки колокольчика в свой слот
// с каскадом `StaggerMillis` (не больше `StaggerMaxItems` в очереди), содержимое и скрим — короткое
// `EaseEnter`, стартует на `ContentRevealDelayMillis`. Закрытие — `springExit` + `EaseExit`, быстрее
// входа и без отскока. Блюр фона не анимируется (§8): только затемнение.
//
// Цена стекла: пока центр открыт, фон под скримом неподвижен, поэтому стекло карточек
// пересчитывается только на кадрах анимации и при прокрутке самого центра.
// ==========================================

@Composable
fun NotificationCenter(
    open: Boolean,
    updates: List<AnimeUpdate>,
    coverPathFor: (animeId: String) -> String?,
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
    val shell = remember { Animatable(0f) }
    val light = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) {
            launch {
                if (reducedMotion) shell.snapTo(1f)
                else shell.animateTo(1f, MotionTokens.springSurface())
            }
            launch {
                if (!reducedMotion) kotlinx.coroutines.delay(MotionTokens.ContentRevealDelayMillis.toLong())
                light.animateTo(1f, tween(MotionTokens.EaseEnterMillis, easing = MotionTokens.EaseEnter))
            }
        } else {
            launch { light.animateTo(0f, tween(MotionTokens.EaseExitMillis, easing = MotionTokens.EaseExit)) }
            launch {
                if (reducedMotion) shell.snapTo(0f)
                else shell.animateTo(0f, MotionTokens.springExit())
            }
        }
    }
    // Узел живёт, пока доигрывает закрытие: иначе обратный ход обрывался бы на первом кадре.
    val animating by remember { derivedStateOf { shell.value > 0.001f || light.value > 0.001f } }
    if (!open && !animating) return

    BackHandler(enabled = open, onBack = onClose)

    val isDark = isAppInDarkTheme()
    val onCard = if (isDark) Color.White else Color(0xFF1C1C1E)
    val material = FrostedMaterials.notification()
    val departed = remember { mutableStateListOf<String>() }
    LaunchedEffect(updates) { departed.retainAll { id -> updates.any { it.animeId == id } } }
    val visible = updates.filter { it.animeId !in departed }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Затемнение — свет, поэтому короткая кривая, а не пружина; тап мимо закрывает центр.
            .drawBehind { drawRect(Color.Black, alpha = SCRIM_ALPHA * light.value) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = open,
                onClick = onClose,
            ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = PANEL_TOP, start = 12.dp, end = 12.dp),
        ) {
            val maxListHeight = maxHeight * 0.62f
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .graphicsLayer { alpha = light.value },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = strings.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    if (visible.isNotEmpty()) {
                        Text(
                            text = strings.clearAll,
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = BrandOrange,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = open,
                                    onClick = onClearAll,
                                )
                                .padding(vertical = 6.dp),
                        )
                    }
                }

                if (visible.isEmpty()) {
                    Text(
                        text = strings.empty,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = SnProFamily,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                            .graphicsLayer { alpha = light.value },
                    )
                } else {
                    LazyColumn(
                        flingBehavior = IosScroll.flingBehavior(),
                        modifier = Modifier.heightIn(max = maxListHeight),
                        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
                    ) {
                        itemsIndexed(visible, key = { _, u -> u.animeId }) { index, update ->
                            NotificationCenterCard(
                                update = update,
                                index = index,
                                shell = { shell.value },
                                light = { light.value },
                                bellCenter = bellCenter,
                                coverPath = remember(update.animeId) { coverPathFor(update.animeId) },
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
                    }
                }
            }
        }
    }
}

/**
 * Одна карточка центра. Вырастает из колокольчика: сдвиг из его центра в свой слот и масштаб —
 * пространство (пружина общей оболочки со сдвигом по каскаду), прозрачность — свет.
 */
@Composable
private fun NotificationCenterCard(
    update: AnimeUpdate,
    index: Int,
    shell: () -> Float,
    light: () -> Float,
    bellCenter: () -> Offset?,
    coverPath: String?,
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
    // Центр слота в координатах корня — от него считается путь из колокольчика.
    var slotCenter by remember { mutableStateOf<Offset?>(null) }
    var widthPx by remember { mutableStateOf(1f) }
    // Каскад: каждая следующая карточка отстаёт на долю пути, очередь ограничена.
    val lag = (index.coerceAtMost(MotionTokens.StaggerMaxItems) * STAGGER_FRACTION)

    fun progress(): Float = ((shell() - lag) / (1f - lag)).coerceIn(0f, 1f)

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
            .onPlaced { coordinates ->
                widthPx = coordinates.size.width.toFloat().coerceAtLeast(1f)
                slotCenter = coordinates.boundsInRoot().center
            }
            .offset {
                val p = progress()
                val from = bellCenter()
                val slot = slotCenter
                val (dx, dy) = if (from != null && slot != null) {
                    (from.x - slot.x) * (1f - p) to (from.y - slot.y) * (1f - p)
                } else {
                    0f to 0f
                }
                IntOffset((dx + offsetX.value).roundToInt(), dy.roundToInt())
            }
            .graphicsLayer {
                val p = progress()
                val s = lerp(ENTER_SCALE, 1f, p)
                scaleX = s
                scaleY = s
                alpha = light() * (1f - (abs(offsetX.value) / (widthPx * 0.9f)).coerceIn(0f, 1f))
            }
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

private const val SCRIM_ALPHA = 0.42f
private const val STAGGER_FRACTION = 0.1f
private const val ENTER_SCALE = 0.55f
private const val SWIPE_DISMISS_FRACTION = 0.32f
private val PANEL_TOP = 76.dp
private val CARD_GAP = 8.dp
