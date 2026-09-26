package com.example.myapplication.ui.home.updates

import com.example.myapplication.ui.shared.theme.BrandOrange
import androidx.compose.ui.util.lerp
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.geometry.Offset
import com.kyant.backdrop.backdrops.emptyBackdrop
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs
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
// Callback вызывается ПОСЛЕ анимации; до обновления БД карточка прячется через
// departedIds, чтобы не мигнула обратно.
// ==========================================

private const val VISIBLE_BACK_CARDS = 2
private const val BACK_SCALE_STEP = 0.05f
internal val CARD_HEIGHT = 76.dp

/** Отставание каждой следующей карточки при схлопывании и её конечный масштаб в колокольчике. */
private const val COLLAPSE_STAGGER = 0.12f
private const val COLLAPSE_SCALE = 0.18f
private val BACK_PEEK = 9.dp
/** Доля ширины карточки, после которой отпущенный свайп засчитывается как отказ. */
private const val SWIPE_DISMISS_FRACTION = 0.32f

@Composable
fun EpisodeUpdateStack(
    updates: List<AnimeUpdate>,
    coverPathFor: (animeId: String) -> String?,
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
    val isDark = isAppInDarkTheme()

    // iOS-палитра: брендовый оранжевый акцент, текст под цвет темы.
    val onCard = if (isDark) Color.White else Color(0xFF1C1C1E)
    val accent = BrandOrange
    val topMaterial = FrostedMaterials.notification()
    val stackedMaterial = FrostedMaterials.stackedNotification()
    val noBackdrop = remember { emptyBackdrop() }

    // Улетевшие, но ещё не удалённые из БД карточки: скрываем до обновления Flow.
    val departedIds: SnapshotStateList<String> = remember { mutableStateListOf() }
    LaunchedEffect(updates) {
        departedIds.retainAll { id -> updates.any { it.animeId == id } }
    }
    val visible = updates.filter { it.animeId !in departedIds }
    if (visible.isEmpty()) return

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

    val top = visible.first()
    val offsetX = remember(top.animeId) { Animatable(0f) }
    val offsetY = remember(top.animeId) { Animatable(0f) }
    var departing by remember(top.animeId) { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .onPlaced { stackOrigin = it.boundsInRoot().topLeft },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val peekPx = with(density) { BACK_PEEK.toPx() }
        val cardHeightPx = with(density) { CARD_HEIGHT.toPx() }
        val dismissThresholdPx = widthPx * SWIPE_DISMISS_FRACTION
        val flyOutXPx = widthPx * 1.2f
        val flyOutYPx = with(density) { 480.dp.toPx() }
        val velocityThresholdPx = with(density) {
            MotionTokens.DismissVelocityThresholdDpPerSec.dp.toPx()
        }

        fun flyOutHorizontally(direction: Float, update: AnimeUpdate) {
            if (departing) return
            departing = true
            scope.launch {
                offsetX.animateTo(direction * flyOutXPx, animationSpec = tween(240))
                departedIds += update.animeId
                onDismiss(update)
            }
        }

        /** Тап: карточка уходит вверх и открывает Details — как «раскрытие» iOS-пуша. */
        fun openAndFlyUp(update: AnimeUpdate) {
            if (departing) return
            departing = true
            onOpen(update)
            scope.launch {
                offsetY.animateTo(-flyOutYPx, animationSpec = tween(280))
                departedIds += update.animeId
                onDismiss(update)
            }
        }

        val backCount = (visible.size - 1).coerceAtMost(VISIBLE_BACK_CARDS)
        val stackHeight = CARD_HEIGHT + BACK_PEEK * backCount

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stackHeight),
            contentAlignment = Alignment.TopCenter
        ) {
            // Всё, что зависит от смещения топа, считается ВНУТРИ лямбд раскладки и слоя: чтение
            // Animatable в композиции пересобирало всю стопку на каждом кадре драга.
            // Прогресс ухода топа: задние карточки подтягиваются на уровень выше.
            fun progress(): Float =
                (maxOf(abs(offsetX.value), abs(offsetY.value)) / dismissThresholdPx).coerceIn(0f, 1f)

            // +2: топ, видимые пики и одна скрытая карточка, всплывающая при уходе топа.
            visible.take(VISIBLE_BACK_CARDS + 2).withIndex().reversed().forEach { (index, update) ->
                val isTop = index == 0

                fun translateY(): Float = if (isTop) {
                    offsetY.value
                } else {
                    val currentY = peekPx * index
                    val nextY = peekPx * (index - 1)
                    currentY + (nextY - currentY) * progress()
                }
                fun scale(): Float = if (isTop) {
                    1f
                } else {
                    val currentScale = 1f - BACK_SCALE_STEP * index
                    val nextScale = 1f - BACK_SCALE_STEP * (index - 1)
                    currentScale + (nextScale - currentScale) * progress()
                }
                // Доля пути в колокольчик для этой карточки: задние стартуют с отставанием.
                fun collapseProgress(): Float {
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
                fun alpha(): Float = collapseLight.value * if (isTop) {
                    val horizontalFade = abs(offsetX.value) / (widthPx * 0.9f)
                    val verticalFade = -offsetY.value / flyOutYPx
                    (1f - maxOf(horizontalFade, verticalFade)).coerceIn(0f, 1f)
                } else {
                    if (index > VISIBLE_BACK_CARDS) progress() else 1f
                }

                val gestureModifier = if (isTop && !departing) {
                    Modifier.draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            scope.launch { offsetX.snapTo(offsetX.value + delta) }
                        },
                        onDragStopped = { velocity ->
                            val shouldDismiss = abs(offsetX.value) > dismissThresholdPx ||
                                abs(velocity) > velocityThresholdPx
                            if (shouldDismiss) {
                                val direction = if (offsetX.value != 0f) sign(offsetX.value) else sign(velocity)
                                flyOutHorizontally(if (direction == 0f) 1f else direction, update)
                            } else {
                                scope.launch {
                                    offsetX.animateTo(0f, animationSpec = MotionTokens.menuPop())
                                }
                            }
                        }
                    )
                } else Modifier

                key(update.animeId) {
                    Box(
                        modifier = gestureModifier
                            .zIndex((100 - index).toFloat())
                            // Сдвиг — через offset (раскладкой), а НЕ через translation в
                            // graphicsLayer: стекло сэмплит бэкдроп по положению узла, и сдвиг
                            // слоем роняет его в плоскую заливку — известные грабли этой
                            // кодовой базы (см. тот же приём у дока рабочей области).
                            .offset {
                                val shift = collapseShift()
                                IntOffset(
                                    x = ((if (isTop) offsetX.value else 0f) + shift.x).roundToInt(),
                                    y = (translateY() + shift.y).roundToInt(),
                                )
                            }
                            .graphicsLayer {
                                val scale = scale() * lerp(1f, COLLAPSE_SCALE, collapseProgress())
                                scaleX = scale
                                scaleY = scale
                                rotationZ = if (isTop) {
                                    ((offsetX.value / widthPx) * 10f).coerceIn(-7f, 7f)
                                } else 0f
                                this.alpha = alpha()
                            }
                            .fillMaxWidth()
                    ) {
                        EpisodeUpdateCard(
                            update = update,
                            // Memo: resolve пути идёт в БД, а стопка рекомпозируется каждый кадр драга.
                            coverPath = remember(update.animeId) { coverPathFor(update.animeId) },
                            isDark = isDark,
                            // Задней карточке бэкдроп не нужен: у её материала нет размытия, а под
                            // верхней её всё равно не видно. Узел тот же — меняется параметр.
                            backdrop = if (isTop) backdrop else noBackdrop,
                            material = if (isTop) topMaterial else stackedMaterial,
                            onCard = onCard,
                            accent = accent,
                            dimmed = !isTop,
                            clickEnabled = isTop && !departing,
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
    isDark: Boolean,
    backdrop: Backdrop,
    material: FrostedMaterial,
    onCard: Color,
    accent: Color,
    dimmed: Boolean,
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
                    text = String.format(
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

        // Лёгкое затемнение задних карточек стопки для ощущения глубины.
        if (dimmed) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.10f)))
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
