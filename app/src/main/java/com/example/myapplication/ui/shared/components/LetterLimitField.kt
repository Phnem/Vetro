package com.example.myapplication.ui.shared.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.theme.BrandDeepRed
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.CapsuleShape
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.OneUiTheme
import com.example.myapplication.ui.shared.theme.SquircleShape
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Поле ввода с жёстким пределом длины — перенос designbookmark «Letter Limit» (код: motion-src/letter-limit,
 * пружины: shared/tokens.css) под Compose. Сделано как заготовка для будущего «поиска по описанию»
 * (семантический поиск каталога Vetro), на экраны пока не поставлено. Спецификация переноса:
 * `.scratch/semantic-search/letter-limit-android.md`.
 *
 * Как ведёт себя (как в референсе):
 * - кольцо заполняется по мере набора (пружина [MotionTokens.limitSettle]), за [nearThreshold] символов до
 *   предела становится оранжевым, на пределе — тёмно-красным; счётчик показывает, сколько осталось, и
 *   «подпрыгивает» на каждом изменении ([MotionTokens.limitPop]);
 * - всё, что не влезло, отрезается с конца и высыпается буквами: каждая стартует от конца текста,
 *   подпрыгивает, падает с вращением, один раз отскакивает и ложится в кучку под карточкой (пол поднимается
 *   там, где буквы уже лежат); кольцо и счётчик при этом «мотают головой» ([MotionTokens.limitShake]);
 * - стираешь текст — кучка тает с самых новых букв, по одной на каждое освободившееся место.
 *
 * Числа физики букв (dp вместо px референса) — константы ниже, без подгонки.
 * При системном «Отключить анимации» буквы не сыплются и ничего не качается: пружины критические,
 * остаются цвет кольца и счётчик (UNIVERSAL_MOTION_SPEC §7).
 *
 * @param limit предел длины; у поиска по описанию он должен совпадать с пределом сервера.
 * @param remainingDescription текст для TalkBack: «осталось N символов».
 * @param pileSpace место под карточкой, куда ложатся буквы.
 */
@Composable
fun LetterLimitField(
    value: String,
    onValueChange: (String) -> Unit,
    limit: Int,
    placeholder: String,
    actionLabel: String,
    onAction: () -> Unit,
    remainingDescription: (Int) -> String,
    modifier: Modifier = Modifier,
    nearThreshold: Int = 10,
    pileSpace: Dp = 64.dp,
    containerColor: Color = IosDesign.elevatedCard(isAppInDarkTheme()),
    /** Сразу поставить каретку в поле и показать клавиатуру: для шторок, где поле — единственное содержимое. */
    autoFocus: Boolean = false,
) {
    require(limit > 0) { "limit must be positive" }
    val density = LocalDensity.current
    val view = LocalView.current
    val reduced = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = onSurface.copy(alpha = 0.55f)
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = (-0.01f * 16).sp, color = onSurface,
    )
    val letterStyle = textStyle.copy(fontWeight = FontWeight.Medium)

    var field by remember { mutableStateOf(TextFieldValue(value.take(limit), TextRange(min(value.length, limit)))) }
    val pile = remember { LetterPile() }
    var wake by remember { mutableIntStateOf(0) }
    var frame by remember { mutableLongStateOf(0L) }
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var card by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var textBox by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        // Шторка ещё выезжает: фокус в первый кадр клавиатуру не поднимает.
        delay(AUTO_FOCUS_DELAY_MILLIS)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    val fill = remember { Animatable(field.text.length / limit.toFloat()) }
    val countPop = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    val press = remember { Animatable(1f) }

    fun pop(to: Float) {
        if (reduced) return
        scope.launch {
            countPop.snapTo(max(countPop.value, to))
            countPop.animateTo(1f, MotionTokens.limitPop())
        }
    }

    /** Новое значение поля: отрезать лишнее, высыпать его буквами, подвинуть кольцо и счётчик. */
    fun accept(next: TextFieldValue, fromUser: Boolean) {
        val was = field.text.length
        var accepted = next
        if (next.text.length > limit) {
            val kept = next.text.take(limit)
            val extra = next.text.substring(limit)
            val caret = min(next.selection.start, limit)
            accepted = TextFieldValue(kept, TextRange(caret))
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
                else HapticFeedbackConstants.KEYBOARD_TAP,
            )
            if (!reduced) {
                endOfText(kept, measurer, textStyle, textBox, root, density.density)?.let { (x, y) ->
                    pile.drop(extra, x, y)
                    wake++
                }
                scope.launch { shake.animateTo(0f, MotionTokens.limitShake(), initialVelocity = shake.velocity + SHAKE_KICK) }
                pop(1.25f)
            }
        }
        val len = accepted.text.length
        if (len < was) {
            pile.giveBack(was - len)
            wake++
        }
        if (len != was) pop(1.12f)
        field = accepted
        scope.launch { fill.animateTo(len / limit.toFloat(), MotionTokens.limitSettle(reduced)) }
        if (fromUser && accepted.text != value) onValueChange(accepted.text)
    }

    // Внешнее значение (очистка после поиска, подстановка из истории) идёт тем же путём, что и ввод.
    LaunchedEffect(value) {
        if (value != field.text) accept(TextFieldValue(value, TextRange(value.length)), fromUser = false)
    }

    // Физика букв крутится, пока хоть одна летит, тает или темнеет; потом цикл засыпает до новой буквы.
    LaunchedEffect(wake) {
        var last = 0L
        while (pile.active) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else min((now - last) / 1_000_000_000f, MAX_FRAME_SEC)
                last = now
                repeat(SUBSTEPS) { pile.step(dt / SUBSTEPS) }
                frame = now
            }
        }
    }

    val remaining = limit - field.text.length
    val tone = when {
        remaining <= 0 -> BrandDeepRed
        remaining <= nearThreshold -> BrandOrange
        else -> null
    }
    val ringColor by animateColorAsState(tone ?: onSurface.copy(alpha = 0.78f), tween(COLOR_MILLIS), label = "limitRing")
    val countColor by animateColorAsState(tone ?: muted, tween(COLOR_MILLIS), label = "limitCount")
    val track = onSurface.copy(alpha = 0.14f)

    Box(modifier.onGloballyPositioned { root = it }) {
        Column {
            Column(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { card = it }
                    .background(containerColor, SquircleShape(IosDesign.RadiusLg))
                    .padding(16.dp),
            ) {
                BasicTextField(
                    value = field,
                    onValueChange = { accept(it, fromUser = true) },
                    textStyle = textStyle,
                    cursorBrush = SolidColor(onSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .focusRequester(focusRequester)
                        .onGloballyPositioned { textBox = it },
                    decorationBox = { inner ->
                        Box {
                            if (field.text.isEmpty()) Text(placeholder, style = textStyle.copy(color = muted))
                            inner()
                        }
                    },
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Canvas(
                        Modifier
                            .size(24.dp)
                            .graphicsLayer { translationX = shake.value * SHAKE_SCALE * density.density }
                            .clearAndSetSemantics { },
                    ) {
                        val stroke = 2.6.dp.toPx()
                        val r = 9.dp.toPx()
                        val topLeft = Offset(center.x - r, center.y - r)
                        drawCircle(track, r, style = Stroke(stroke))
                        val sweep = 360f * fill.value.coerceIn(0f, 1f)
                        if (sweep > 0.5f) {
                            drawArc(ringColor, -90f, sweep, false, topLeft, Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                        }
                    }
                    Text(
                        text = remaining.toString(),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum",
                        ),
                        color = countColor,
                        modifier = Modifier
                            .graphicsLayer {
                                translationX = shake.value * SHAKE_SCALE * density.density
                                scaleX = countPop.value
                                scaleY = countPop.value
                            }
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = remainingDescription(remaining)
                            },
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .height(36.dp)
                            .graphicsLayer {
                                scaleX = press.value
                                scaleY = press.value
                            }
                            .background(MaterialTheme.colorScheme.primary, CapsuleShape())
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                            ) {
                                if (!reduced) {
                                    scope.launch {
                                        press.snapTo(0.9f)
                                        press.animateTo(1f, MotionTokens.limitPop())
                                    }
                                }
                                onAction()
                            }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            actionLabel,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(pileSpace))
        }

        // Кучка отвергнутых букв: поверх всего, касания пропускает, для TalkBack не существует.
        Canvas(
            Modifier
                .matchParentSize()
                .clipToBounds()
                .clearAndSetSemantics { },
        ) {
            if (frame < 0L) return@Canvas // чтение кадра физики подписывает отрисовку на каждый её шаг
            val dp = density.density
            pile.width = size.width / dp
            val cardBottom = card?.let { c -> root?.localBoundingBoxOf(c)?.bottom } ?: 0f
            pile.floorY = min(cardBottom / dp + FLOOR_BELOW_CARD, size.height / dp - FLOOR_MARGIN)
            pile.letters.forEach { l ->
                val layout = measurer.measure(l.ch, letterStyle)
                val color = when {
                    l.downAge < 0f -> BrandDeepRed
                    else -> lerp(BrandDeepRed, muted, (l.downAge / DOWN_FADE_SEC).coerceIn(0f, 1f))
                }
                translate(l.x * dp, l.y * dp) {
                    rotate(l.r, pivot = Offset.Zero) {
                        scale(l.s, pivot = Offset.Zero) {
                            drawText(layout, color = color, topLeft = Offset(-layout.size.width / 2f, -layout.size.height / 2f))
                        }
                    }
                }
            }
        }
    }
}

/** Где оказалась бы следующая буква: конец текста в координатах всего компонента, в dp. */
private fun endOfText(
    text: String,
    measurer: TextMeasurer,
    style: TextStyle,
    textBox: LayoutCoordinates?,
    root: LayoutCoordinates?,
    dp: Float,
): Pair<Float, Float>? {
    if (textBox == null || root == null || !textBox.isAttached || !root.isAttached) return null
    val layout: TextLayoutResult = measurer.measure(text, style, constraints = Constraints(maxWidth = textBox.size.width))
    val caret = layout.getCursorRect(text.length)
    val at = root.localPositionOf(textBox, Offset(caret.left, caret.center.y))
    return at.x / dp to at.y / dp
}

private class FallingLetter(val ch: String, var x: Float, var y: Float, var vx: Float, var vy: Float, var vr: Float) {
    var r = 0f
    var rest = false
    var out = 0f
    var s = 1f
    var bin: Int? = null
    var hits = 0
    /** Сколько секунд буква лежит (для перехода цвета); −1 — ещё не легла. */
    var downAge = -1f
}

/** Физика кучки — построчный перенос `frame()` из limit.js; единицы — dp и секунды. */
private class LetterPile {
    val letters = ArrayList<FallingLetter>()
    private val bins = HashMap<Int, Int>()
    var width = 0f
    var floorY = 0f

    val active: Boolean
        get() = letters.any { !it.rest || it.out > 0f || it.downAge in 0f..DOWN_FADE_SEC }

    fun drop(chars: String, x0: Float, y0: Float) {
        chars.forEachIndexed { i, ch ->
            if (ch.isWhitespace()) return@forEachIndexed
            letters += FallingLetter(
                ch = ch.toString(),
                x = x0 + 4f + i * 9f,
                y = y0,
                vx = -40f + Random.nextFloat() * 150f,
                vy = -150f - Random.nextFloat() * 110f,
                vr = (Random.nextFloat() - 0.5f) * 720f,
            )
        }
        while (letters.count { it.out == 0f } > MAX_PILE) letters.first { it.out == 0f }.let(::leave)
    }

    /** Освободилось [n] мест — столько же самых новых букв уходят из кучки. */
    fun giveBack(n: Int) {
        val live = letters.filter { it.out == 0f }
        for (i in 0 until min(n, live.size)) leave(live[live.size - 1 - i])
    }

    private fun leave(l: FallingLetter) {
        if (l.out > 0f) return
        l.out = 0.001f
        l.vy = -230f
        l.rest = false
        l.bin?.let { b -> bins[b] = max(0, (bins[b] ?: 0) - 1) }
    }

    fun step(dt: Float) {
        letters.forEach { l ->
            if (l.out > 0f) {
                l.out += dt
                l.vy += 900f * dt
                l.y += l.vy * dt
                l.s = max(0f, 1f - l.out / 0.32f)
                return@forEach
            }
            if (l.rest) {
                if (l.downAge >= 0f) l.downAge += dt
                return@forEach
            }
            l.vy += 1900f * dt
            l.x += l.vx * dt
            l.y += l.vy * dt
            l.r += l.vr * dt
            if (l.x < WALL) {
                l.x = WALL; l.vx = abs(l.vx) * 0.5f
            } else if (l.x > width - WALL) {
                l.x = width - WALL; l.vx = -abs(l.vx) * 0.5f
            }
            val b = (l.x / BIN).roundToInt()
            val ground = floorY - (bins[b] ?: 0) * 9f
            if (l.y >= ground && l.vy > 0f) {
                l.y = ground
                if (l.hits++ < 1 && l.vy > 260f) {
                    l.vy *= -0.3f; l.vx *= 0.6f; l.vr *= 0.5f
                } else {
                    // Легла: на ближайший бок, а пол здесь поднимается.
                    l.rest = true
                    l.bin = b
                    bins[b] = min(3, (bins[b] ?: 0) + 1)
                    l.r = (l.r / 90f).roundToInt() * 90f + (Random.nextFloat() - 0.5f) * 34f
                    l.downAge = 0f
                }
            }
        }
        letters.removeAll { it.out > 0.34f }
    }
}

private const val SUBSTEPS = 4
private const val MAX_FRAME_SEC = 1f / 30f
private const val MAX_PILE = 46
private const val BIN = 10f
private const val WALL = 14f
private const val FLOOR_BELOW_CARD = 46f
private const val FLOOR_MARGIN = 14f
private const val DOWN_FADE_SEC = 0.5f
private const val COLOR_MILLIS = 200
private const val AUTO_FOCUS_DELAY_MILLIS = 250L
/** Толчок скорости «мотания головой» на отклонённой букве и масштаб сдвига — как в limit.js. */
private const val SHAKE_KICK = 34f
private const val SHAKE_SCALE = 0.5f

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 360, heightDp = 300)
@Composable
private fun LetterLimitFieldPreview() {
    OneUiTheme(darkTheme = true) {
        var query by remember { mutableStateOf("Аниме, где два брата пытались вернуть маму, а один потерял тело") }
        LetterLimitField(
            value = query,
            onValueChange = { query = it },
            limit = 80,
            placeholder = "Опишите сюжет, героя или сцену",
            actionLabel = "Найти",
            onAction = { },
            remainingDescription = { "Осталось символов: $it" },
            modifier = Modifier.padding(16.dp).width(328.dp),
        )
    }
}
