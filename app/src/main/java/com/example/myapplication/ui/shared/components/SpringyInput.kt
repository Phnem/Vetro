package com.example.myapplication.ui.shared.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.BrandDeepRed
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SquircleShape
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Springy input: общее поле ввода Vetro. Перенос designbookmark «Springy input» (motion-src/springy-input,
 * пружины — shared/tokens.css и input.js) под Compose.
 *
 * Что взято из референса:
 * - каждая новая буква «падает» с отскоком (смещение + поворот + масштаб, 500 мс), вставка из буфера
 *   идёт волной; удалённая буква уносится вверх и тает там, где стояла (340 мс);
 * - каретка — своя, на пружине [MotionTokens.inputCaret]: едет к новому месту и вытягивается по скорости;
 *   она мигает только в покое, пока печатаешь, горит ровно;
 * - подпись всплывает над текстом на пружине [MotionTokens.inputLabel];
 * - рамка при фокусе набирает толщину на пружине [MotionTokens.inputMorph];
 * - ошибка: буквы «мотают головой» волной, поле встряхивается пружиной [MotionTokens.inputShake].
 *
 * Как устроено. Настоящий BasicTextField остаётся на месте (ввод, выделение, вставка, TalkBack, IME),
 * но его текст прозрачен; поверх рисуется «зеркало» тем же TextLayoutResult. В покое это один drawText,
 * во время анимации — тот же слой, обрезанный по ячейке каждой буквы и преобразованный по её позе.
 * Если текст не помещается в окно (длинная строка прокручивается внутри поля) или его раскладка не
 * совпадает со значением (маска/форматирование телефона), зеркало выключается и показывается обычный
 * текст: прокрутку поля изнутри не достать, а подделка рассинхронилась бы с настоящей.
 *
 * Вид — дизайн-код Vetro: контейнер и типографика одной семьи с [LetterLimitField], брендовая рамка
 * (оранжевая на фокусе, глубокий красный на ошибке), радиусы IosDesign.
 * При системном «Отключить анимации» буквы не падают и поле не трясётся (UNIVERSAL_MOTION_SPEC §7):
 * остаётся обычный текст и каретка без растяжения.
 */

// ---- Поза буквы по ключевым кадрам референса (@keyframes drop / out / nope) ---------------------------

private class Key(val at: Float, val dy: Float, val dx: Float, val rot: Float, val s: Float, val a: Float)

private class Pose {
    var dy = 0f
    var dx = 0f
    var rot = 0f
    var s = 1f
    var a = 1f
}

private val DropKeys = arrayOf(
    Key(0f, 14f, 0f, -16f, 0.5f, 0f),
    Key(0.55f, -3f, 0f, 4f, 1.1f, 1f),
    Key(0.8f, 1f, 0f, -1f, 0.98f, 1f),
    Key(1f, 0f, 0f, 0f, 1f, 1f),
)
private val NopeKeys = arrayOf(
    Key(0f, 0f, 0f, 0f, 1f, 1f),
    Key(0.2f, 0f, -4f, -8f, 1f, 1f),
    Key(0.4f, 0f, 4f, 6f, 1f, 1f),
    Key(0.6f, 0f, -2f, 0f, 1f, 1f),
    Key(0.8f, 0f, 1f, 0f, 1f, 1f),
    Key(1f, 0f, 0f, 0f, 1f, 1f),
)
private val OutKeys = arrayOf(
    Key(0f, 0f, 0f, 0f, 1f, 1f),
    Key(1f, -18f, 0f, 20f, 0.5f, 0f),
)

private val DropEase: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val NopeEase: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val OutEase: Easing = CubicBezierEasing(0.5f, 0f, 0.75f, 0f)

/** Доля амплитуды референса: 0.75 = на четверть мягче (по просьбе владельца, v3.5.7). */
private const val AMPLITUDE = 0.75f

private const val DROP_MS = 500
private const val NOPE_MS = 460
private const val OUT_MS = 340
private const val DROP_STEP_MS = 18
private const val DROP_WAVE_MAX = 30
private const val NOPE_STEP_MS = 14

private const val KIND_SETTLED = 0
private const val KIND_DROP = 1
private const val KIND_NOPE = 2

/** Метка «кадр, с которого идёт анимация, ещё не назначен»: время берётся у первого кадра цикла. */
private const val PENDING = Long.MIN_VALUE

private fun pose(keys: Array<Key>, ease: Easing, u: Float, out: Pose) {
    val t = u.coerceIn(0f, 1f)
    var i = 0
    while (i < keys.size - 2 && t > keys[i + 1].at) i++
    val a = keys[i]
    val b = keys[i + 1]
    val local = ease.transform(((t - a.at) / (b.at - a.at)).coerceIn(0f, 1f))
    // Амплитуда референса смягчена на четверть: смещение, поворот и «пухлость» масштаба умножены на AMPLITUDE.
    out.dy = (a.dy + (b.dy - a.dy) * local) * AMPLITUDE
    out.dx = (a.dx + (b.dx - a.dx) * local) * AMPLITUDE
    out.rot = (a.rot + (b.rot - a.rot) * local) * AMPLITUDE
    out.s = 1f + ((a.s + (b.s - a.s) * local) - 1f) * AMPLITUDE
    out.a = a.a + (b.a - a.a) * local
}

private class Glyph(var kind: Int, var born: Long, var delayMs: Int)

private class Ghost(val layout: TextLayoutResult, val box: Rect, var born: Long)

/**
 * Часы анимаций по символам значения: что нового, что ушло, кто трясётся. Живёт вне состояния Compose
 * (меняется каждый кадр), перерисовку запускает счётчик кадра в поле.
 */
private class GlyphMirror(initial: String) {
    var text: String = initial
    val glyphs = ArrayList<Glyph>().also { list -> repeat(initial.length) { list += Glyph(KIND_SETTLED, 0L, 0) } }
    val ghosts = ArrayList<Ghost>()
    var lastNow = 0L

    val hasWork: Boolean get() = ghosts.isNotEmpty() || glyphs.any { it.kind != KIND_SETTLED }

    /** Сброс без анимации: при выключенных анимациях. */
    fun reset(next: String) {
        if (text == next && ghosts.isEmpty()) return
        text = next
        glyphs.clear()
        repeat(next.length) { glyphs += Glyph(KIND_SETTLED, 0L, 0) }
        ghosts.clear()
    }

    /** Разбор правки как в референсе: общий префикс и суффикс, середина — ушедшие и пришедшие буквы. */
    fun sync(next: String, prevLayout: TextLayoutResult?) {
        val prev = text
        if (next == prev) return
        var a = 0
        while (a < prev.length && a < next.length && prev[a] == next[a]) a++
        var b = 0
        while (b < prev.length - a && b < next.length - a && prev[prev.length - 1 - b] == next[next.length - 1 - b]) b++
        val removeCount = prev.length - a - b
        if (prevLayout != null && prevLayout.layoutInput.text.length == prev.length) {
            for (i in a until a + removeCount) {
                val ch = prev[i]
                if (ch.isWhitespace() || Character.isLowSurrogate(ch)) continue
                ghosts += Ghost(prevLayout, runCatching { prevLayout.getBoundingBox(i) }.getOrDefault(Rect.Zero), PENDING)
            }
        }
        repeat(removeCount) { glyphs.removeAt(a) }
        val insertCount = next.length - a - b
        val step = if (insertCount <= 24) DROP_STEP_MS else max(1, (24 * DROP_STEP_MS) / insertCount)
        for (k in 0 until insertCount) {
            glyphs.add(a + k, Glyph(KIND_DROP, PENDING, min(k, DROP_WAVE_MAX) * step))
        }
        text = next
    }

    /** Ошибка: буквы качаются волной слева направо. */
    fun nope() {
        glyphs.forEachIndexed { i, g ->
            g.kind = KIND_NOPE
            g.born = PENDING
            g.delayMs = i * NOPE_STEP_MS
        }
    }

    fun stamp(now: Long) {
        lastNow = now
        glyphs.forEach { g ->
            if (g.kind == KIND_SETTLED) return@forEach
            if (g.born == PENDING) g.born = now
            val total = (if (g.kind == KIND_DROP) DROP_MS else NOPE_MS) + g.delayMs
            if ((now - g.born) / 1_000_000L >= total) g.kind = KIND_SETTLED
        }
        ghosts.removeAll { gh ->
            if (gh.born == PENDING) gh.born = now
            (now - gh.born) / 1_000_000L >= OUT_MS
        }
    }
}

private class LayoutHolder {
    var layout: TextLayoutResult? = null
}

// ---- Ядро: замена BasicTextField ----------------------------------------------------------------------

/**
 * Поле без контейнера: прямая замена `BasicTextField(String)` с пружинными буквами и кареткой.
 * Параметры те же, плюс [isError] (волна «нет» по буквам и, если [shakeOnError], встряска).
 * Свой контейнер, подпись и рамку делает [SpringyOutlinedField] либо вызывающий через [decorationBox].
 */
@Composable
fun SpringyBasicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = SolidColor(Color.Black),
    isError: Boolean = false,
    shakeOnError: Boolean = true,
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { it() },
) {
    // Как у стандартного BasicTextField(String): выделение и композиция IME живут в TextFieldValue внутри.
    var textFieldValueState by remember { mutableStateOf(TextFieldValue(text = value)) }
    val textFieldValue = textFieldValueState.copy(text = value)
    SideEffect {
        if (textFieldValue.selection != textFieldValueState.selection ||
            textFieldValue.composition != textFieldValueState.composition
        ) {
            textFieldValueState = textFieldValue
        }
    }
    var lastTextValue by remember(value) { mutableStateOf(value) }
    SpringyBasicTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            textFieldValueState = newValue
            val changed = lastTextValue != newValue.text
            lastTextValue = newValue.text
            if (changed) onValueChange(newValue.text)
        },
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        cursorBrush = cursorBrush,
        isError = isError,
        shakeOnError = shakeOnError,
        decorationBox = decorationBox,
    )
}

@Composable
fun SpringyBasicTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = SolidColor(Color.Black),
    isError: Boolean = false,
    shakeOnError: Boolean = true,
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { it() },
) {
    val reduced = rememberReducedMotion()
    val density = LocalDensity.current.density
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val mirror = remember { GlyphMirror(value.text) }
    val holder = remember { LayoutHolder() }
    var layoutTick by remember { mutableIntStateOf(0) }
    var frame by remember { mutableLongStateOf(0L) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var mirrorOn by remember { mutableStateOf(true) }

    if (reduced) mirror.reset(value.text) else mirror.sync(value.text, holder.layout)

    val caretColor = (cursorBrush as? SolidColor)?.value ?: LocalContentColor.current
    val realColor = if (textStyle.color.isSpecified) textStyle.color else LocalContentColor.current
    val currentValue by rememberUpdatedState(value)

    fun refreshMirrorOn() {
        val layout = holder.layout
        val on = !reduced && layout != null &&
            layout.layoutInput.text.length == currentValue.text.length &&
            (if (singleLine) layout.size.width <= boxSize.width + 1 else layout.size.height <= boxSize.height + 1)
        if (on != mirrorOn) mirrorOn = on
    }

    // ---- каретка: две пружины (x и y), цель — прямоугольник курсора из раскладки ----
    val caretX = remember { Animatable(0f) }
    val caretY = remember { Animatable(0f) }
    var caretTarget by remember { mutableStateOf(Offset.Zero) }
    var caretHeight by remember { mutableStateOf(0f) }
    var caretReady by remember { mutableStateOf(false) }

    fun refreshCaret() {
        val layout = holder.layout ?: return
        val len = layout.layoutInput.text.length
        val rect = runCatching { layout.getCursorRect(currentValue.selection.end.coerceIn(0, len)) }.getOrNull() ?: return
        val target = Offset(rect.left, rect.top)
        if (target != caretTarget) caretTarget = target
        if (rect.height != caretHeight) caretHeight = rect.height
    }

    LaunchedEffect(caretTarget) {
        if (!caretReady || reduced) {
            caretX.snapTo(caretTarget.x)
            caretY.snapTo(caretTarget.y)
            caretReady = true
        } else {
            coroutineScope {
                launch { caretX.animateTo(caretTarget.x, MotionTokens.inputCaret()) }
                launch { caretY.animateTo(caretTarget.y, MotionTokens.inputCaret()) }
            }
        }
    }
    LaunchedEffect(value.selection, value.text) { refreshCaret() }

    // Мигание только в покое: пока печатаешь или двигаешь курсор, он горит ровно.
    var moving by remember { mutableStateOf(false) }
    var blinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(value.text, value.selection) {
        moving = true
        delay(550)
        moving = false
    }
    LaunchedEffect(focused) {
        blinkOn = true
        if (focused) {
            while (true) {
                delay(500)
                blinkOn = !blinkOn
            }
        }
    }

    // ---- кадры букв: цикл крутится, пока хоть одна падает, качается или улетает ----
    suspend fun runFrames() {
        while (mirror.hasWork) {
            withFrameNanos { now ->
                mirror.stamp(now)
                frame = now
            }
        }
        frame++
    }
    LaunchedEffect(value.text) { runFrames() }

    val shake = remember { Animatable(0f) }
    LaunchedEffect(isError) {
        if (!isError || reduced) return@LaunchedEffect
        mirror.nope()
        coroutineScope {
            launch { runFrames() }
            if (shakeOnError) {
                launch { shake.animateTo(0f, MotionTokens.inputShake(), initialVelocity = 420f * density) }
            }
        }
    }

    val hidden = mirrorOn && !reduced
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.graphicsLayer { translationX = shake.value },
        enabled = enabled,
        readOnly = readOnly,
        textStyle = if (hidden) textStyle.copy(color = Color.Transparent) else textStyle,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        onTextLayout = { result ->
            holder.layout = result
            layoutTick++
            refreshMirrorOn()
            refreshCaret()
        },
        interactionSource = interaction,
        cursorBrush = if (hidden) SolidColor(Color.Transparent) else cursorBrush,
        decorationBox = { innerTextField ->
            decorationBox {
                Box(
                    Modifier.onSizeChanged {
                        boxSize = it
                        refreshMirrorOn()
                    },
                ) {
                    innerTextField()
                    Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
                        // Чтение счётчиков подписывает отрисовку на каждый кадр анимации и на каждую раскладку.
                        if (frame < 0L || layoutTick < 0) return@Canvas
                        val layout = holder.layout ?: return@Canvas
                        if (!hidden) return@Canvas
                        drawMirror(layout, mirror, realColor, density)
                        if (focused && enabled && !readOnly && (moving || blinkOn)) {
                            val speed = abs(caretX.velocity) / density
                            val stretch = min(speed / 80f, 7f) * density
                            val left = caretX.value - if (caretX.velocity > 0f) stretch else 0f
                            val h = if (caretHeight > 0f) caretHeight else layout.size.height.toFloat()
                            drawRoundRect(
                                color = caretColor,
                                topLeft = Offset(left, caretY.value),
                                size = Size(2f * density + stretch, h),
                                cornerRadius = CornerRadius(1f * density),
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Рисует зеркало текста: покой — один drawText, анимация — клетка за клеткой по позам. */
private fun DrawScope.drawMirror(layout: TextLayoutResult, mirror: GlyphMirror, color: Color, density: Float) {
    val display = layout.layoutInput.text.text
    val glyphs = mirror.glyphs
    val inSync = glyphs.size == display.length
    val animating = inSync && glyphs.any { it.kind != KIND_SETTLED }
    if (!animating && mirror.ghosts.isEmpty()) {
        drawText(layout, color = color)
        return
    }
    val now = mirror.lastNow
    if (inSync) {
        // Покоящиеся буквы: весь слой, из которого вырезаны клетки анимирующихся.
        val holes = Path()
        for (i in display.indices) {
            if (glyphs[i].kind != KIND_SETTLED) holes.addRect(layout.getBoundingBox(i))
        }
        clipPath(holes, ClipOp.Difference) { drawText(layout, color = color) }
        val p = Pose()
        for (i in display.indices) {
            val g = glyphs[i]
            val ch = display[i]
            if (g.kind == KIND_SETTLED || ch.isWhitespace() || Character.isLowSurrogate(ch)) continue
            val t = if (g.born == PENDING) -g.delayMs.toFloat() else (now - g.born) / 1_000_000f - g.delayMs
            if (g.kind == KIND_DROP) {
                if (t < 0f) continue // до своей очереди буква невидима (fill-mode both в референсе)
                pose(DropKeys, DropEase, t / DROP_MS, p)
            } else {
                pose(NopeKeys, NopeEase, max(t, 0f) / NOPE_MS, p)
            }
            drawPosed(layout, layout.getBoundingBox(i), p, color, density)
        }
    } else {
        drawText(layout, color = color)
    }
    val p = Pose()
    for (gh in mirror.ghosts) {
        val t = if (gh.born == PENDING) 0f else (now - gh.born) / 1_000_000f
        pose(OutKeys, OutEase, t / OUT_MS, p)
        drawPosed(gh.layout, gh.box, p, color, density)
    }
}

private fun DrawScope.drawPosed(layout: TextLayoutResult, box: Rect, p: Pose, color: Color, density: Float) {
    if (p.a <= 0.01f) return
    // transform-origin референса: 50% по ширине, 80% по высоте.
    val pivot = Offset(box.center.x, box.top + box.height * 0.8f)
    withTransform({
        translate(p.dx * density, p.dy * density)
        rotate(p.rot, pivot)
        scale(p.s, p.s, pivot)
    }) {
        clipRect(box.left, box.top, box.right, box.bottom) {
            drawText(layout, color = color.copy(alpha = color.alpha * p.a.coerceIn(0f, 1f)))
        }
    }
}

// ---- Поле с контейнером: замена OutlinedTextField -------------------------------------------------------

/**
 * Поле с контейнером в стиле Vetro — прямая замена `OutlinedTextField`: те же `label`, `placeholder`,
 * `leadingIcon`, `trailingIcon`, `supportingText`, `isError`. Подпись лежит на месте текста и всплывает
 * над ним на пружине, рамка при фокусе набирает толщину на пружине, ошибка встряхивает всё поле.
 *
 * @param shape форма контейнера; по умолчанию скругление карточек IosDesign (как у [LetterLimitField]).
 * @param contentColor цвет текста и каретки, [mutedColor] — подписи и подсказки (для тёмных экранов вроде входа).
 */
@Composable
fun SpringyOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = SquircleShape(IosDesign.RadiusMd),
    containerColor: Color = IosDesign.elevatedCard(isAppInDarkTheme()),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    mutedColor: Color = contentColor.copy(alpha = 0.55f),
) {
    val reduced = rememberReducedMotion()
    val density = LocalDensity.current.density
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val onSurface = contentColor
    val muted = mutedColor

    val floatLabel = focused || value.isNotEmpty()
    val labelFloat = remember { Animatable(if (floatLabel) 1f else 0f) }
    LaunchedEffect(floatLabel) {
        if (reduced) labelFloat.snapTo(if (floatLabel) 1f else 0f)
        else labelFloat.animateTo(if (floatLabel) 1f else 0f, MotionTokens.inputLabel())
    }
    val rim = remember { Animatable(if (focused) 1f else 0f) }
    LaunchedEffect(focused) {
        if (reduced) rim.snapTo(if (focused) 1f else 0f)
        else rim.animateTo(if (focused) 1f else 0f, MotionTokens.inputMorph())
    }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(isError) {
        if (isError && !reduced) shake.animateTo(0f, MotionTokens.inputShake(), initialVelocity = 420f * density)
    }

    val restRim = onSurface.copy(alpha = 0.16f)
    val bodyStyle = textStyle.copy(color = onSurface)
    val hasLabel = label != null
    val rimFraction = rim.value.coerceIn(0f, 1f)

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = shake.value }
                .heightIn(min = 56.dp)
                .clip(shape)
                .background(containerColor, shape)
                .border(
                    width = (1f + 0.5f * rim.value).dp,
                    color = if (isError) BrandDeepRed else lerp(restRim, BrandOrange, rimFraction),
                    shape = shape,
                ),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (leadingIcon != null) leadingIcon()
                Box(Modifier.weight(1f)) {
                    SpringyBasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        readOnly = readOnly,
                        textStyle = bodyStyle,
                        keyboardOptions = keyboardOptions,
                        keyboardActions = keyboardActions,
                        singleLine = singleLine,
                        maxLines = maxLines,
                        minLines = minLines,
                        visualTransformation = visualTransformation,
                        interactionSource = interaction,
                        cursorBrush = SolidColor(onSurface),
                        isError = isError,
                        shakeOnError = false,
                        decorationBox = { inner ->
                            Box(Modifier.padding(top = if (hasLabel) 22.dp else 16.dp, bottom = if (hasLabel) 8.dp else 16.dp)) {
                                if (placeholder != null && value.isEmpty() && (!hasLabel || labelFloat.value > 0.6f)) {
                                    ProvideTextStyle(bodyStyle.copy(color = muted.copy(alpha = 0.7f))) { placeholder() }
                                }
                                inner()
                            }
                        },
                    )
                    if (label != null) {
                        // Подпись: на месте текста (16 dp от верха) → вверх (6 dp) и меньше (0.72), origin — левый верх.
                        Box(
                            Modifier.graphicsLayer {
                                val f = labelFloat.value
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationY = (16f - 10f * f).dp.toPx()
                                scaleX = 1f - 0.28f * f
                                scaleY = 1f - 0.28f * f
                            },
                        ) {
                            val labelColor = if (isError) BrandDeepRed else if (focused) BrandOrange else muted
                            ProvideTextStyle(bodyStyle.copy(color = labelColor)) { label() }
                        }
                    }
                }
                if (trailingIcon != null) trailingIcon()
            }
        }
        if (supportingText != null) {
            Box(Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp)) {
                ProvideTextStyle(MaterialTheme.typography.bodySmall.copy(color = if (isError) BrandDeepRed else muted)) {
                    supportingText()
                }
            }
        }
    }
}
