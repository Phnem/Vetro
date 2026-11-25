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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
 * Два вида. Компактная пилюля: значок смысла и одна строка текста (высота от 48 dp). Раскрытая карточка
 * ([MiniNotifCard]): значок 38 dp, заголовок, необязательный тег справа и подзаголовок в две строки; ширина
 * экрана минус 2·24 dp, углы те же `min(h/2, 32 dp)`, так что при высоте от 64 dp это уже не капсула, а
 * скруглённый прямоугольник. [MiniNotif.showMorph] показывает сначала пилюлю, через [MiniNotif.MorphAfterMillis]
 * она перетекает в карточку; [MiniNotif.show] с `card` растит карточку сразу из «зародыша».
 *
 * При системном «Отключить анимации» форма не растёт и не качается: размер выставляется сразу,
 * остаётся короткий кроссфейд (UNIVERSAL_MOTION_SPEC §7).
 */
enum class MiniNotifKind { Info, Success, Warning, Error, Progress }

/** Содержимое раскрытой карточки: заголовок, тег справа от него и подзаголовок (до двух строк). */
@Immutable
data class MiniNotifCard(
    val title: String,
    val subtitle: String = "",
    val tag: String? = null,
)

@Immutable
data class MiniNotifItem(
    val id: Long,
    /** Полный текст сообщения: он же уходит в системный Toast, когда плашка недоступна. */
    val text: String,
    val kind: MiniNotifKind,
    val holdMillis: Long,
    /** Если задано, плашка рисуется раскрытой карточкой, а не пилюлей. */
    val card: MiniNotifCard? = null,
    /** Вторая стадия: через [morphAfterMillis] эта пилюля перетекает в неё. */
    val morphTo: MiniNotifItem? = null,
    val morphAfterMillis: Long = MiniNotif.MorphAfterMillis,
) {
    internal fun sameMessageAs(other: MiniNotifItem) = text == other.text && kind == other.kind && card == other.card
}

object MiniNotif {

    /**
     * Dev-флаг: false — [show] отдаёт системный Toast. Выставляется из MainActivity по настройке.
     * Состояние Compose, чтобы экраны (например, главная с пилюлей синхронизации) реагировали на смену.
     */
    var enabled: Boolean by mutableStateOf(false)

    private val items = Channel<MiniNotifItem>(capacity = 24, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** id плашки, которую закрывают; null — ту, что на экране. */
    private val dismisses = Channel<Long?>(Channel.CONFLATED)
    private val hosts = AtomicInteger(0)
    private val ids = AtomicLong(0)

    /** Окно приложения на экране (STARTED). В фоне плашку никто не увидит, поэтому там остаётся Toast. */
    @Volatile
    private var hostVisible: Boolean = false

    /** Сколько держать плашку: короткая (как Toast.LENGTH_SHORT) и длинная. */
    const val HoldShortMillis: Long = 2400L
    const val HoldLongMillis: Long = 3800L

    /** Сколько держать раскрытую карточку (в прототипе 2400–2530 мс после раскрытия). */
    const val HoldCardMillis: Long = 2500L

    /** Через сколько пилюля раскрывается в карточку (в прототипе 1400–1450 мс). */
    const val MorphAfterMillis: Long = 1450L

    /** Раньше этого срока уступать место следующей плашке нельзя: текст должен успеть прочитаться. */
    internal const val MinHoldMillis: Long = 1300L

    private fun canShow(): Boolean = enabled && hosts.get() != 0 && hostVisible

    private fun fallbackToast(context: Context, text: String, long: Boolean) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * @param card если задана, плашка растёт сразу в раскрытую карточку; [text] при этом остаётся
     *   полным сообщением для системного Toast, когда плашка недоступна.
     */
    fun show(
        context: Context,
        text: String,
        kind: MiniNotifKind = MiniNotifKind.Info,
        long: Boolean = false,
        holdMillis: Long? = null,
        card: MiniNotifCard? = null,
    ) {
        if (text.isBlank()) return
        if (!canShow()) {
            fallbackToast(context, text, long)
            return
        }
        val short = if (card != null) HoldCardMillis else HoldShortMillis
        items.trySend(
            MiniNotifItem(
                id = ids.incrementAndGet(),
                text = text,
                kind = kind,
                holdMillis = holdMillis ?: if (long) HoldLongMillis else short,
                card = card,
            ),
        )
    }

    /**
     * Две стадии, как в прототипе: сначала компактная пилюля ([compactText]), через [morphAfterMillis]
     * она пружинно раскрывается в карточку [card]. [text] — полное сообщение для системного Toast.
     */
    fun showMorph(
        context: Context,
        text: String,
        compactText: String,
        compactKind: MiniNotifKind,
        card: MiniNotifCard,
        kind: MiniNotifKind,
        long: Boolean = false,
        morphAfterMillis: Long = MorphAfterMillis,
    ) {
        if (text.isBlank()) return
        if (!canShow()) {
            fallbackToast(context, text, long)
            return
        }
        val expanded = MiniNotifItem(
            id = ids.incrementAndGet(),
            text = text,
            kind = kind,
            holdMillis = if (long) HoldLongMillis else HoldCardMillis,
            card = card,
        )
        items.trySend(
            MiniNotifItem(
                id = ids.incrementAndGet(),
                text = compactText,
                kind = compactKind,
                holdMillis = morphAfterMillis,
                morphTo = expanded,
                morphAfterMillis = morphAfterMillis,
            ),
        )
    }

    /**
     * Статус процесса (синхронизация и т.п.): показывается только плашкой и только пока приложение
     * на экране; при выключенном флаге или в фоне не делает ничего, а не падает в Toast.
     */
    fun status(text: String, kind: MiniNotifKind, holdMillis: Long = HoldShortMillis, card: MiniNotifCard? = null) {
        if (text.isBlank() || !canShow()) return
        items.trySend(MiniNotifItem(ids.incrementAndGet(), text, kind, holdMillis, card))
    }

    /** Убрать плашку (по тапу делает сам хост и называет её [id]). */
    fun dismiss(id: Long? = null) {
        dismisses.trySend(id)
    }

    internal fun setHostVisible(visible: Boolean) {
        hostVisible = visible
    }

    internal fun attachHost() {
        hosts.incrementAndGet()
    }

    internal fun detachHost() {
        hosts.decrementAndGet()
    }

    internal suspend fun receive(): MiniNotifItem = items.receive()

    /** Держит плашку [MiniNotifItem.holdMillis]; раньше выходит, если пришла следующая или тап. */
    internal suspend fun holdFor(item: MiniNotifItem, millis: Long = item.holdMillis): HoldResult {
        val shownAt = SystemClock.uptimeMillis()
        val result = withTimeoutOrNull(millis) {
            var outcome: HoldResult? = null
            while (outcome == null) {
                outcome = select<HoldResult?> {
                    items.onReceive { HoldResult.Next(it) }
                    // Закрытие прошлой плашки, пришедшее в паузе между ними, эту не касается.
                    dismisses.onReceive { id ->
                        if (id == null || id == item.id || id == item.morphTo?.id) HoldResult.Dismissed else null
                    }
                }
            }
            outcome
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

/** Раскрытая карточка (прототип Dynamic Island Morph, «размер 2»). */
private val CardSideMargin = 24.dp
private val CardPadStart = 14.dp
private val CardPadEnd = 18.dp
private val CardPadVertical = 14.dp
private val CardBadgeSize = 38.dp
private val CardGlyphSize = 20.dp
private val CardGap = 12.dp
private val CardTextGap = 2.dp
private val CardTagGap = 8.dp

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

/** Три начертания текста карточки: заголовок, тег и подзаголовок. */
private class CardTextStyles(val title: TextStyle, val tag: TextStyle, val subtitle: TextStyle)

/**
 * Хост плашек: один на приложение, кладётся поверх всего содержимого (последним в корневом Box).
 * Касания мимо плашки не перехватывает.
 *
 * @param bottomClearance расстояние от жестовой панели вверх: 84 dp выше плавающего дока и нижних
 *   кнопок (галочка сохранения, мини-док Details), чтобы плашка не наезжала на них.
 */
@Composable
fun MiniNotifHost(
    modifier: Modifier = Modifier,
    bottomClearance: Dp = 84.dp,
) {
    val reduced by rememberUpdatedState(rememberReducedMotion())
    val densityNow = LocalDensity.current
    val density by rememberUpdatedState(densityNow)
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
    val base = MaterialTheme.typography.bodyMedium
    val cardStyles = CardTextStyles(
        title = base.copy(
            fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.01f * 15).sp, color = onSurface,
        ),
        tag = base.copy(
            fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp, color = onSurface.copy(alpha = 0.45f),
        ),
        subtitle = base.copy(
            fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal,
            letterSpacing = (-0.005f * 13).sp, color = onSurface.copy(alpha = 0.65f),
        ),
    )
    val cardWidth by rememberUpdatedState((screenWidthDp.dp - CardSideMargin * 2).coerceAtLeast(240.dp))

    val width = remember { Animatable(0f) }
    val height = remember { Animatable(0f) }
    val press = remember { Animatable(1f) }
    val contentAlpha = remember { Animatable(0f) }
    val contentScale = remember { Animatable(0.9f) }
    val hostAlpha = remember { Animatable(0f) }
    val hostLift = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<MiniNotifItem?>(null) }

    val textMaxPx by rememberUpdatedState(with(density) {
        ((screenWidthDp.dp - SideMargin * 2) - PadStart - IconSize - IconGap - PadEnd).coerceAtLeast(96.dp).toPx()
    })
    val textMaxDp = with(density) { textMaxPx.toDp() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        MiniNotif.attachHost()
        MiniNotif.setHostVisible(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> MiniNotif.setHostVisible(true)
                Lifecycle.Event.ON_STOP -> MiniNotif.setHostVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            MiniNotif.setHostVisible(false)
            MiniNotif.detachHost()
        }
    }

    LaunchedEffect(Unit) {
        val scope: CoroutineScope = this
        val seedW = with(density) { SeedWidth.toPx() }
        val seedH = with(density) { SeedHeight.toPx() }
        val liftPx = with(density) { 10.dp.toPx() }

        fun sizeOf(item: MiniNotifItem): Pair<Float, Float> {
            val card = item.card
            if (card != null) {
                // Ширина фиксированная (экран − 48 dp), высота по тексту: 14 + max(значок 38, текст) + 14.
                val textW = with(density) {
                    (cardWidth - CardPadStart - CardPadEnd - CardBadgeSize - CardGap).coerceAtLeast(96.dp).toPx()
                }.toInt()
                val titleH = measurer.measure(
                    text = AnnotatedString(card.title),
                    style = cardStyles.title,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = textW),
                ).size.height
                val subH = if (card.subtitle.isBlank()) 0 else measurer.measure(
                    text = AnnotatedString(card.subtitle),
                    style = cardStyles.subtitle,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    constraints = Constraints(maxWidth = textW),
                ).size.height
                val gap = if (subH > 0) with(density) { CardTextGap.toPx() } else 0f
                val column = titleH + gap + subH
                val h = with(density) { (CardPadVertical * 2).toPx() } +
                    max(with(density) { CardBadgeSize.toPx() }, column)
                return with(density) { cardWidth.toPx() } to h
            }
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

        var active: MiniNotifItem? = null

        // Сообщение с раскрытием: сначала пилюля, через morphAfterMillis она перетекает в карточку.
        suspend fun hold(item: MiniNotifItem): MiniNotif.HoldResult {
            val morph = item.morphTo ?: return MiniNotif.holdFor(item)
            val early = MiniNotif.holdFor(item, item.morphAfterMillis)
            if (early is MiniNotif.HoldResult.Dismissed) return early
            present(morph, first = false)
            active = morph
            if (early is MiniNotif.HoldResult.Next) {
                // Следующее уже ждёт, но раскрытие не пропускаем: карточке дают минимальный срок.
                delay(MiniNotif.MinHoldMillis)
                return early
            }
            return MiniNotif.holdFor(morph)
        }

        while (true) {
            var next: MiniNotifItem? = MiniNotif.receive()
            active = null
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
                next = when (val result = hold(item)) {
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
                            onTap = { shown?.let { MiniNotif.dismiss(it.id) } },
                        )
                    }
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                // Содержимое по центру плашки и по вертикали, и по горизонтали (раньше липло к верхнему краю).
                contentAlignment = Alignment.Center,
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
                    MiniNotifContent(
                        item = item, textStyle = textStyle, textMaxWidth = textMaxDp, isDark = isDark,
                        cardStyles = cardStyles, cardWidth = cardWidth,
                    )
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
    cardStyles: CardTextStyles,
    cardWidth: Dp,
) {
    val card = item.card
    if (card != null) {
        MiniNotifCardContent(card = card, kind = item.kind, styles = cardStyles, width = cardWidth, isDark = isDark)
        return
    }
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

/**
 * Раскрытая карточка: значок 38 dp, справа колонка «заголовок + тег у правого края» и подзаголовок.
 * Вертикальные 14 dp отступа заложены в высоту плашки ([MiniNotifHost], sizeOf), содержимое по центру.
 */
@Composable
private fun MiniNotifCardContent(
    card: MiniNotifCard,
    kind: MiniNotifKind,
    styles: CardTextStyles,
    width: Dp,
    isDark: Boolean,
) {
    val tagBackground = if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.06f)
    Row(
        modifier = Modifier.width(width).padding(start = CardPadStart, end = CardPadEnd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(kind = kind, isDark = isDark, size = CardBadgeSize, glyphSize = CardGlyphSize)
        Spacer(Modifier.width(CardGap))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.title,
                    style = styles.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                card.tag?.let { tag ->
                    Spacer(Modifier.width(CardTagGap))
                    Box(
                        modifier = Modifier.clip(CircleShape).background(tagBackground)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(text = tag, style = styles.tag, maxLines = 1)
                    }
                }
            }
            if (card.subtitle.isNotBlank()) {
                Spacer(Modifier.height(CardTextGap))
                Text(
                    text = card.subtitle,
                    style = styles.subtitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Значок смысла: круг цвета бренда с белой пиктограммой Phosphor; у «Info» и «Progress» — нейтральный круг. */
@Composable
private fun KindBadge(kind: MiniNotifKind, isDark: Boolean, size: Dp = IconSize, glyphSize: Dp = 16.dp) {
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
        modifier = Modifier.size(size).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (kind == MiniNotifKind.Progress) {
            CircularProgressIndicator(
                modifier = Modifier.size(glyphSize),
                strokeWidth = 2.2.dp,
                color = BrandOrange,
                trackColor = Color.Transparent,
            )
        } else {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = glyph,
                modifier = Modifier.size(glyphSize),
            )
        }
    }
}
