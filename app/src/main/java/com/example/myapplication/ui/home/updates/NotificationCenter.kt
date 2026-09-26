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
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
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
// Хореография (§5): у стекла карточки и её содержимого разные траектории. Оболочка раскрывается
// пружиной `springSurface` с 0.92 и 8 dp выше слота, опорная точка масштаба — под колокольчиком по
// горизонтали и у верхней кромки по вертикали: стопка разворачивается вниз из колокольчика и не
// вылезает за клип списка. Каскад — время, `StaggerMillis` на карточку (очередь до
// `StaggerMaxItems`), у каждой своя пружина: хвост не сжимается и перелёт не обрезается. Текст и
// обложка не масштабируются отдельно и проявляются `EaseEnter` через `ContentRevealDelayMillis`,
// когда оболочка уже почти на месте. Закрытие — без каскада: содержимое гаснет `EaseExit`, оболочка
// уходит `springExit` (закон 5). Блюр фона не анимируется (§8): только затемнение.
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
    // Общая оболочка держит узел живым до конца закрытия; карточки ведут свои пружины сами.
    val shell = remember { Animatable(0f) }
    // Затемнение живёт на своей кривой, общей с размытием главной (backdropEnter/Exit): стартует
    // сразу, идёт дольше содержимого и так же плавно уходит.
    val scrim = remember { Animatable(0f) }
    // Заголовок и «Очистить всё» — часть той же панели: прозрачность как у стекла карточек
    // (появляется сразу, уходит за 160 мс), движение — общая пружина [shell].
    val chrome = remember { Animatable(0f) }
    LaunchedEffect(open) {
        launch {
            scrim.animateTo(
                if (open) 1f else 0f,
                if (open) MotionTokens.backdropEnter() else MotionTokens.backdropExit(),
            )
        }
        launch {
            chrome.animateTo(
                if (open) 1f else 0f,
                if (open) {
                    tween(MotionTokens.EaseEnterMillis, easing = MotionTokens.EaseEnter)
                } else {
                    tween(MotionTokens.DurationFastMillis, easing = MotionTokens.EaseExit)
                },
            )
        }
        when {
            reducedMotion -> shell.snapTo(if (open) 1f else 0f)
            open -> shell.animateTo(1f, MotionTokens.springSurface())
            else -> shell.animateTo(0f, MotionTokens.springExit())
        }
    }
    // Узел живёт, пока доигрывает закрытие: иначе обратный ход обрывался бы на первом кадре.
    val animating by remember {
        derivedStateOf { shell.value > 0.001f || scrim.value > 0.001f || chrome.value > 0.001f }
    }
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
            // Затемнение — свет, поэтому кривая, а не пружина; тап мимо закрывает центр.
            .drawBehind { drawRect(Color.Black, alpha = SCRIM_ALPHA * scrim.value) }
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
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .graphicsLayer { panelChrome(shell.value, chrome.value) },
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
                    Spacer(Modifier.height(HEADER_GAP))
                    Text(
                        text = strings.empty,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = SnProFamily,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                            .graphicsLayer { panelChrome(shell.value, chrome.value) },
                    )
                } else {
                    // Верхний отступ списка — место для стартового сдвига первой карточки: список
                    // клипует содержимое, и без запаса она выезжала бы из-под ровной линии.
                    Spacer(Modifier.height(HEADER_GAP - ENTER_LIFT))
                    LazyColumn(
                        flingBehavior = IosScroll.flingBehavior(),
                        modifier = Modifier.heightIn(max = maxListHeight),
                        contentPadding = PaddingValues(top = ENTER_LIFT),
                        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
                    ) {
                        itemsIndexed(visible, key = { _, u -> u.animeId }) { index, update ->
                            NotificationCenterCard(
                                update = update,
                                index = index,
                                open = open,
                                reducedMotion = reducedMotion,
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
 * Одна карточка центра. Пространство (масштаб, сдвиг) — своя пружина с каскадным стартом,
 * свет (прозрачность стекла и содержимого) — короткие кривые. Сдвиг идёт через `offset {}`, а не
 * через `graphicsLayer`: стекло сэмплирует бэкдроп по позиции раскладки.
 */
@Composable
private fun NotificationCenterCard(
    update: AnimeUpdate,
    index: Int,
    open: Boolean,
    reducedMotion: Boolean,
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
    val reveal = remember { Animatable(0f) }
    val shellAlpha = remember { Animatable(0f) }
    val contentAlpha = remember { Animatable(0f) }
    var departing by remember { mutableStateOf(false) }
    // Левая кромка и ширина слота в координатах корня — от них считается опорная точка под колокольчиком.
    var slotLeft by remember { mutableStateOf(0f) }
    var widthPx by remember { mutableStateOf(1f) }

    LaunchedEffect(open) {
        if (open) {
            if (reducedMotion) {
                reveal.snapTo(1f)
                launch { shellAlpha.animateTo(1f, tween(MotionTokens.EaseExitMillis)) }
                contentAlpha.animateTo(1f, tween(MotionTokens.EaseExitMillis))
                return@LaunchedEffect
            }
            delay(index.coerceAtMost(MotionTokens.StaggerMaxItems) * MotionTokens.StaggerMillis.toLong())
            launch { reveal.animateTo(1f, MotionTokens.springSurface()) }
            launch {
                shellAlpha.animateTo(1f, tween(MotionTokens.EaseEnterMillis, easing = MotionTokens.EaseEnter))
            }
            delay(MotionTokens.ContentRevealDelayMillis.toLong())
            contentAlpha.animateTo(1f, tween(MotionTokens.EaseEnterMillis, easing = MotionTokens.EaseEnter))
        } else {
            // Уход без каскада: закрытие освобождает дорогу, а не прощается очередью.
            launch {
                contentAlpha.animateTo(0f, tween(MotionTokens.EaseExitMillis, easing = MotionTokens.EaseExit))
            }
            launch {
                shellAlpha.animateTo(0f, tween(MotionTokens.DurationFastMillis, easing = MotionTokens.EaseExit))
            }
            if (reducedMotion) reveal.snapTo(0f) else reveal.animateTo(0f, MotionTokens.springExit())
        }
    }

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
                slotLeft = coordinates.boundsInRoot().left
            }
            .offset {
                val lift = ENTER_LIFT.toPx() * (1f - reveal.value)
                IntOffset(offsetX.value.roundToInt(), (-lift).roundToInt())
            }
            .graphicsLayer {
                val s = lerp(ENTER_SCALE, 1f, reveal.value)
                scaleX = s
                scaleY = s
                val pivotX = bellCenter()?.let { ((it.x - slotLeft) / widthPx).coerceIn(0f, 1f) } ?: 0.5f
                transformOrigin = TransformOrigin(pivotX, 0f)
                alpha = shellAlpha.value * (1f - (abs(offsetX.value) / (widthPx * 0.9f)).coerceIn(0f, 1f))
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
            contentAlpha = { contentAlpha.value },
        )
    }
}

/**
 * Подписи панели едут вместе с карточками: масштаб 0.92 → 1 и подъём к колокольчику по той же
 * пружине, опора масштаба — правый верх (колокольчик над правым краем).
 */
private fun GraphicsLayerScope.panelChrome(reveal: Float, alphaValue: Float) {
    val s = lerp(ENTER_SCALE, 1f, reveal)
    scaleX = s
    scaleY = s
    transformOrigin = TransformOrigin(1f, 0f)
    translationY = -ENTER_LIFT.toPx() * (1f - reveal)
    alpha = alphaValue
}

private const val SCRIM_ALPHA = 0.42f
/** Стартовый масштаб оболочки (спека §5.3). */
private const val ENTER_SCALE = 0.92f
private const val SWIPE_DISMISS_FRACTION = 0.32f
private val PANEL_TOP = 76.dp
private val HEADER_GAP = 10.dp
/** Стартовый сдвиг оболочки к колокольчику (спека §5.3, 8 px). */
private val ENTER_LIFT = 8.dp
private val CARD_GAP = 8.dp
