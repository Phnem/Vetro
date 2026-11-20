package com.example.myapplication.ui.shared.mininotif

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.theme.BrandDeepRed
import com.example.myapplication.ui.shared.theme.BrandGreen
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import com.phnem.vetro.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * mininotif — стандарт компактных уведомлений Vetro: короткое сообщение «что произошло» в плашке,
 * которая растёт из маленькой пилюли, как Dynamic Island, но у нижнего края экрана.
 *
 * Перенос designbookmark «Morph island» (motion-src/morph-island, пружины — shared/tokens.css):
 * ширина и высота — две пружины ([MotionTokens.islandWidth] жёстче [MotionTokens.islandHeight]),
 * поэтому каждое изменение сначала уходит вбок и потом оседает по вертикали; содержимое
 * перетекает с размытием и масштабом .9 → 1, пока форма меняется; пока высота быстро движется,
 * ширина немного «отдаёт» (объём); на нажатие плашка проседает до 0.96.
 * Вид — дизайн-код Vetro: серый «объект» без подложки (тёмно-серый в тёмной теме, бледно-серый в
 * светлой, как в референсе), кант в один пиксель, значок смысла в круге палитры бренда
 * (зелёный — готово, оранжевый — внимание, глубокий красный — ошибка), Phosphor-иконки.
 *
 * Как пользоваться: [MiniNotif.show] из любого места, где раньше был `Toast.makeText`.
 * Плашки идут по очереди; когда следующая приходит, пока видна предыдущая, форма не схлопывается,
 * а перетекает в новую (так Dynamic Island меняет «живую активность»). Хост [MiniNotifHost] стоит
 * один раз в корне приложения.
 *
 * Включается dev-флагом («Мини-уведомления», по умолчанию выкл): пока выключен или хоста нет,
 * [MiniNotif.show] показывает обычный системный Toast, то есть всё как раньше.
 *
 * При системном «Отключить анимации» форма не растёт и не качается: размер выставляется сразу,
 * остаётся короткий кроссфейд (UNIVERSAL_MOTION_SPEC §7).
 */
enum class MiniNotifKind { Info, Success, Warning, Error, Progress }

@Immutable
data class MiniNotifItem(
    val id: Long,
    val text: String,
    val kind: MiniNotifKind,
    val holdMillis: Long,
) {
    internal fun sameMessageAs(other: MiniNotifItem) = text == other.text && kind == other.kind
}

object MiniNotif {

    /** Dev-флаг: false — [show] отдаёт системный Toast. Выставляется из MainActivity по настройке. */
    @Volatile
    var enabled: Boolean = false

    private val items = Channel<MiniNotifItem>(capacity = 24, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val dismisses = Channel<Unit>(Channel.CONFLATED)
    private val hosts = AtomicInteger(0)
    private val ids = AtomicLong(0)

    /** Сколько держать плашку: короткая (как Toast.LENGTH_SHORT) и длинная. */
    const val HoldShortMillis: Long = 2400L
    const val HoldLongMillis: Long = 3800L

    /** Раньше этого срока уступать место следующей плашке нельзя: текст должен успеть прочитаться. */
    internal const val MinHoldMillis: Long = 1300L

    fun show(
        context: Context,
        text: String,
        kind: MiniNotifKind = MiniNotifKind.Info,
        long: Boolean = false,
        holdMillis: Long? = null,
    ) {
        if (text.isBlank()) return
        if (!enabled || hosts.get() == 0) {
            val app = context.applicationContext
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(app, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
            return
        }
        items.trySend(
            MiniNotifItem(
                id = ids.incrementAndGet(),
                text = text,
                kind = kind,
                holdMillis = holdMillis ?: if (long) HoldLongMillis else HoldShortMillis,
            ),
        )
    }

    /** Убрать текущую плашку (по тапу делает сам хост). */
    fun dismiss() {
        dismisses.trySend(Unit)
    }

    internal fun attachHost() {
        hosts.incrementAndGet()
    }

    internal fun detachHost() {
        hosts.decrementAndGet()
    }

    internal suspend fun receive(): MiniNotifItem = items.receive()

    /** Держит плашку [MiniNotifItem.holdMillis]; раньше выходит, если пришла следующая или тап. */
    internal suspend fun holdFor(item: MiniNotifItem): HoldResult {
        val shownAt = SystemClock.uptimeMillis()
        val result = withTimeoutOrNull(item.holdMillis) {
            select<HoldResult> {
                items.onReceive { HoldResult.Next(it) }
                dismisses.onReceive { HoldResult.Dismissed }
            }
        } ?: return HoldResult.Expired
        if (result is HoldResult.Next) {
            val left = MinHoldMillis - (SystemClock.uptimeMillis() - shownAt)
            if (left > 0) delay(left)
        }
        return result
    }

    internal sealed interface HoldResult {
        data class Next(val item: MiniNotifItem) : HoldResult
        data object Expired : HoldResult
        data object Dismissed : HoldResult
    }
}

private val SideMargin = 16.dp
private val PadStart = 10.dp
private val PadEnd = 18.dp
private val PadVertical = 10.dp
private val IconSize = 28.dp
private val IconGap = 10.dp
private val MinHeight = 48.dp
private val MinWidth = 96.dp

/** Размер «зародыша»: из него плашка вырастает и в него сжимается (аналог idle-пилюли референса, 126×36). */
private val SeedWidth = 52.dp
private val SeedHeight = 24.dp

/** Серый «объект» референса (--object): тёмно-серый в тёмной теме, бледно-серый в светлой. */
private val ObjectDark = Color(0xFF2C2C2C)
private val ObjectLight = Color(0xFFEDEDED)

/** Кривая появления содержимого из референса: cubic-bezier(.16, 1, .3, 1). */
private val ContentEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

private object IslandShape : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radius = min(size.height / 2f, with(density) { MotionTokens.IslandRadius.toPx() })
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
    }
}

/**
 * Хост плашек: один на приложение, кладётся поверх всего содержимого (последним в корневом Box).
 * Касания мимо плашки не перехватывает.
 *
 * @param bottomClearance расстояние от нижнего края (над жестовой панелью): плашка стоит над доком.
 */
@Composable
fun MiniNotifHost(
    modifier: Modifier = Modifier,
    bottomClearance: Dp = 28.dp,
) {
    val reduced by rememberUpdatedState(rememberReducedMotion())
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val measurer = rememberTextMeasurer()
    val uiScope = rememberCoroutineScope()
    val isDark = isAppInDarkTheme()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.01f * 15).sp,
        color = onSurface,
    )

    val width = remember { Animatable(0f) }
    val height = remember { Animatable(0f) }
    val press = remember { Animatable(1f) }
    val contentAlpha = remember { Animatable(0f) }
    val contentScale = remember { Animatable(0.9f) }
    val hostAlpha = remember { Animatable(0f) }
    val hostLift = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<MiniNotifItem?>(null) }

    val textMaxPx = with(density) {
        ((screenWidthDp.dp - SideMargin * 2) - PadStart - IconSize - IconGap - PadEnd).coerceAtLeast(96.dp).toPx()
    }
    val textMaxDp = with(density) { textMaxPx.toDp() }

    DisposableEffect(Unit) {
        MiniNotif.attachHost()
        onDispose { MiniNotif.detachHost() }
    }

    LaunchedEffect(Unit) {
        val scope: CoroutineScope = this
        val seedW = with(density) { SeedWidth.toPx() }
        val seedH = with(density) { SeedHeight.toPx() }
        val liftPx = with(density) { 10.dp.toPx() }

        fun sizeOf(item: MiniNotifItem): Pair<Float, Float> {
            val layout = measurer.measure(
                text = AnnotatedString(item.text),
                style = textStyle,
                overflow = TextOverflow.Ellipsis,
                maxLines = 3,
                constraints = Constraints(maxWidth = textMaxPx.toInt()),
            )
            val w = with(density) { (PadStart + IconSize + IconGap + PadEnd).toPx() } + layout.size.width
            val h = max(
                with(density) { MinHeight.toPx() },
                layout.size.height + with(density) { (PadVertical * 2).toPx() },
            )
            return max(w, with(density) { MinWidth.toPx() }) to h
        }

        suspend fun present(item: MiniNotifItem, first: Boolean) {
            val (tw, th) = sizeOf(item)
            if (first) {
                width.snapTo(seedW)
                height.snapTo(seedH)
                contentAlpha.snapTo(0f)
                contentScale.snapTo(0.9f)
                hostAlpha.snapTo(0f)
                hostLift.snapTo(if (reduced) 0f else liftPx)
                shown = item
                scope.launch { hostAlpha.animateTo(1f, tween(140)) }
                if (!reduced) scope.launch { hostLift.animateTo(0f, MotionTokens.islandHeight()) }
            } else {
                // Прежнее содержимое уходит быстро (.14 с), пока форма уже начинает менять размер.
                scope.launch { contentAlpha.animateTo(0f, tween(140)) }
                delay(140)
                shown = item
            }
            val grow = tw * th > width.targetValue * height.targetValue
            if (reduced) {
                width.snapTo(tw)
                height.snapTo(th)
            } else {
                scope.launch { width.animateTo(tw, MotionTokens.islandWidth()) }
                scope.launch {
                    height.animateTo(th, if (grow) MotionTokens.islandHeight() else MotionTokens.islandHeightShrink())
                }
                scope.launch {
                    press.animateTo(1f, MotionTokens.islandPress(), initialVelocity = if (grow) -1.6f else -1f)
                }
            }
            // Новое содержимое проявляется с задержкой .09 с: сначала форма, потом текст.
            scope.launch {
                delay(90)
                contentScale.snapTo(if (reduced) 1f else 0.9f)
                contentAlpha.snapTo(0f)
                if (!reduced) scope.launch { contentScale.animateTo(1f, tween(600, easing = ContentEase)) }
                contentAlpha.animateTo(1f, tween(if (reduced) 150 else 350))
            }
        }

        suspend fun collapse() {
            scope.launch { contentAlpha.animateTo(0f, tween(120)) }
            if (reduced) {
                hostAlpha.animateTo(0f, tween(150))
            } else {
                scope.launch { width.animateTo(seedW, MotionTokens.islandWidth()) }
                scope.launch { height.animateTo(seedH, MotionTokens.islandHeightShrink()) }
                delay(170)
                scope.launch { hostLift.animateTo(liftPx * 0.6f, tween(160)) }
                hostAlpha.animateTo(0f, tween(160))
            }
            shown = null
        }

        while (true) {
            var next: MiniNotifItem? = MiniNotif.receive()
            var active: MiniNotifItem? = null
            while (next != null) {
                val item: MiniNotifItem = next
                val current = active
                if (current != null && current.sameMessageAs(item)) {
                    // Тот же текст пришёл ещё раз: не перерисовываем, только подталкиваем и продлеваем.
                    if (!reduced) {
                        scope.launch { press.animateTo(1f, MotionTokens.islandPress(), initialVelocity = -2f) }
                    }
                } else {
                    present(item, first = current == null)
                }
                active = item
                next = when (val result = MiniNotif.holdFor(item)) {
                    is MiniNotif.HoldResult.Next -> result.item
                    MiniNotif.HoldResult.Expired, MiniNotif.HoldResult.Dismissed -> null
                }
            }
            collapse()
        }
    }

    val item = shown
    if (item != null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = bottomClearance, start = SideMargin, end = SideMargin),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val surface = if (isDark) ObjectDark else ObjectLight
            val rim = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = hostAlpha.value
                        translationY = hostLift.value
                        // «Объём»: пока высота быстро движется, ширина немного отдаёт (island.js: give).
                        val give = (-height.velocity / 7000f).coerceIn(-0.05f, 0.05f)
                        scaleX = press.value * (1f + give)
                        scaleY = press.value
                    }
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(
                            Constraints.fixed(
                                width.value.roundToInt().coerceAtLeast(1),
                                height.value.roundToInt().coerceAtLeast(1),
                            ),
                        )
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .shadow(
                        elevation = 14.dp,
                        shape = IslandShape,
                        ambientColor = Color.Black.copy(alpha = 0.25f),
                        spotColor = Color.Black.copy(alpha = 0.25f),
                    )
                    .clip(IslandShape)
                    .background(surface)
                    .border(1.dp, rim, IslandShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                if (!reduced) uiScope.launch { press.animateTo(0.96f, MotionTokens.islandPress()) }
                                tryAwaitRelease()
                                if (!reduced) uiScope.launch { press.animateTo(1f, MotionTokens.islandPress()) }
                            },
                            onTap = { MiniNotif.dismiss() },
                        )
                    }
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Box(
                    modifier = Modifier
                        .wrapContentSize(Alignment.Center, unbounded = true)
                        .graphicsLayer {
                            alpha = contentAlpha.value
                            scaleX = contentScale.value
                            scaleY = contentScale.value
                            // Размытие на входе/выходе, как filter:blur(6px) референса (API 31+).
                            val blur = (1f - contentAlpha.value) * 6.dp.toPx()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > 0.5f) {
                                renderEffect = BlurEffect(blur, blur, TileMode.Decal)
                            }
                        },
                ) {
                    MiniNotifContent(item = item, textStyle = textStyle, textMaxWidth = textMaxDp, isDark = isDark)
                }
            }
        }
    }
}

@Composable
private fun MiniNotifContent(
    item: MiniNotifItem,
    textStyle: TextStyle,
    textMaxWidth: Dp,
    isDark: Boolean,
) {
    Row(
        modifier = Modifier.padding(start = PadStart, end = PadEnd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(kind = item.kind, isDark = isDark)
        Spacer(Modifier.width(IconGap))
        Text(
            text = item.text,
            style = textStyle,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = textMaxWidth),
        )
    }
}

/** Значок смысла: круг цвета бренда с белой пиктограммой Phosphor; у «Info» и «Progress» — нейтральный круг. */
@Composable
private fun KindBadge(kind: MiniNotifKind, isDark: Boolean) {
    val neutral = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f)
    val glyphNeutral = MaterialTheme.colorScheme.onSurface
    val bg: Color
    val glyph: Color
    val icon: Int
    when (kind) {
        MiniNotifKind.Success -> { bg = BrandGreen; glyph = Color.White; icon = R.drawable.ph_check }
        MiniNotifKind.Warning -> { bg = BrandOrange; glyph = Color.White; icon = R.drawable.ph_warning_fill }
        MiniNotifKind.Error -> { bg = BrandDeepRed; glyph = Color.White; icon = R.drawable.ph_x }
        MiniNotifKind.Info -> { bg = neutral; glyph = glyphNeutral; icon = R.drawable.ph_info_fill }
        MiniNotifKind.Progress -> { bg = neutral; glyph = glyphNeutral; icon = 0 }
    }
    Box(
        modifier = Modifier.size(IconSize).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (kind == MiniNotifKind.Progress) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.2.dp,
                color = BrandOrange,
                trackColor = Color.Transparent,
            )
        } else {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = glyph,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
