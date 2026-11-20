package com.example.myapplication.ui.shared.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * Кнопка-колокольчик: перенос Kokonut UI «Bell button» (автор @dorianbaffier, лицензия MIT,
 * https://kokonutui.com/docs/buttons/bell-button) на Compose. Тайминги и физика те же, что в
 * референсе; вид — дизайн-код Vetro (оранжевый бренда на включённом, нейтральная пилюля на выключенном).
 *
 * Колокол и язычок — два маятника на одной оси; связывают их только столкновения: качающийся колокол
 * ловит язычок своей кромкой, перебрасывает его на другую сторону, и тот бьёт о стенку. Достаточно
 * сильный удар звонит: с той стороны вспыхивают две короткие дуги и отдаёт в руку. Выключенный
 * колокольчик «глушится»: язычок прижат к колоколу, звонить нечем, поперёк пролегает косая черта.
 * Углы в градусах, время в секундах, шаг физики 1/240 с.
 *
 * При системном «Отключить анимации» колокол не качается; на включение остаются только дуги.
 *
 * @param subscribed включены ли уведомления (колокол залит); состояние ведёт вызывающий.
 * @param label подпись при выключенном состоянии, [subscribedLabel] — при включённом; меняются
 *   перекрёстным проявлением.
 */
enum class BellButtonVariant { Compact, Icon }

@Composable
fun BellButton(
    subscribed: Boolean,
    onSubscribedChange: (Boolean) -> Unit,
    label: String,
    subscribedLabel: String,
    modifier: Modifier = Modifier,
    variant: BellButtonVariant = BellButtonVariant.Compact,
) {
    val reduced = rememberReducedMotion()
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val sim = remember { BellSim().also { it.muffled = !subscribed } }
    var tick by remember { mutableLongStateOf(0L) }
    var kicks by remember { mutableIntStateOf(0) }

    // Дуги: прогресс 0→1 по кривой референса, пик яркости задаёт сила удара.
    val leftWave = remember { Animatable(1f) }
    val rightWave = remember { Animatable(1f) }
    val peaks = remember { FloatArray(2) }

    fun flare(side: Int, strength: Float) {
        val wave = if (side > 0) rightWave else leftWave
        peaks[if (side > 0) 1 else 0] = min(1f, 0.45f + strength * 0.55f)
        scope.launch {
            wave.snapTo(0f)
            wave.animateTo(1f, tween(WAVE_MS, easing = EaseOut))
        }
    }

    LaunchedEffect(subscribed) { sim.muffled = !subscribed }

    // Маятники крутятся, пока в них есть энергия; потом цикл засыпает до следующего толчка.
    LaunchedEffect(kicks) {
        if (kicks == 0) return@LaunchedEffect
        sim.last = 0L
        while (true) {
            withFrameNanos { now ->
                val elapsed = if (sim.last == 0L) STEP else min((now - sim.last) / 1_000_000_000f, 0.05f)
                sim.last = now
                sim.carry += elapsed
                while (sim.carry >= STEP) {
                    sim.step { side, strength ->
                        flare(side, strength)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    sim.carry -= STEP
                }
                tick = now
            }
            if (sim.energy() < REST) {
                sim.settle()
                tick++
                break
            }
        }
    }

    fun kick(speed: Float) {
        if (reduced) return
        sim.bellSpeed += speed
        kicks++
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val bg by animateColorAsState(
        targetValue = if (subscribed) BrandOrange else onSurface.copy(alpha = 0.08f),
        animationSpec = tween(200),
        label = "bellBg",
    )
    val fg by animateColorAsState(
        targetValue = if (subscribed) Color.White else onSurface,
        animationSpec = tween(200),
        label = "bellFg",
    )
    val fillAlpha by animateFloatAsState(if (subscribed) 1f else 0f, tween(200), label = "bellFill")
    val slashOffset by animateFloatAsState(if (subscribed) SLASH_LEN else 0f, tween(300, easing = EaseOut), label = "bellSlash")
    val body = remember { PathParser().parsePathString(BELL_BODY).toPath() }
    val clapperPath = remember { PathParser().parsePathString(BELL_CLAPPER).toPath() }
    val slash = remember { PathParser().parsePathString(BELL_SLASH).toPath() }
    val waveRight = remember { PathParser().parsePathString(WAVE_RIGHT).toPath() }
    val waveLeft = remember { PathParser().parsePathString(WAVE_LEFT).toPath() }
    val icon = variant == BellButtonVariant.Icon

    Row(
        modifier = modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(bg, CircleShape)
            .border(1.dp, if (subscribed) Color.Transparent else onSurface.copy(alpha = 0.12f), CircleShape)
            .toggleable(
                value = subscribed,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
            ) { next ->
                onSubscribedChange(next)
                if (next) {
                    sim.muffled = false
                    // Колокол качнуть от язычка, чтобы первый же удар прозвенел.
                    kick(if (sim.bellSpeed >= 0f) RING_KICK else -RING_KICK)
                    if (reduced) {
                        flare(1, 0.6f)
                        flare(-1, 0.6f)
                    }
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                } else {
                    // Выключили: глухая встряска, язычок прижат к колоколу.
                    sim.muffled = true
                    kick(-MUTE_KICK)
                }
            }
            .semantics { contentDescription = if (subscribed) subscribedLabel else label }
            .then(if (icon) Modifier.width(44.dp) else Modifier.padding(start = 14.dp, end = 18.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (icon) Arrangement.Center else Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(20.dp)) {
            if (tick < 0L) return@Canvas // чтение кадра физики подписывает отрисовку на каждый её шаг
            val k = size.width / 24f
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            scale(k, k, pivot = Offset.Zero) {
                // Дуги звона: вспыхивают с той стороны, куда ударил язычок, и уходят наружу.
                drawWave(waveLeft, -1, leftWave.value, peaks[0], fg, stroke)
                drawWave(waveRight, 1, rightWave.value, peaks[1], fg, stroke)
                rotate(sim.clapper, pivot = PIVOT) {
                    drawPath(clapperPath, fg, style = stroke)
                }
                rotate(sim.bell, pivot = PIVOT) {
                    if (fillAlpha > 0.01f) drawPath(body, fg.copy(alpha = fg.alpha * fillAlpha), style = Fill)
                    drawPath(body, fg, style = stroke)
                    if (slashOffset < SLASH_LEN - 0.5f) {
                        drawPath(
                            slash,
                            fg,
                            style = Stroke(
                                width = 2f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(SLASH_LEN, SLASH_LEN), slashOffset),
                            ),
                        )
                    }
                }
            }
        }
        if (!icon) {
            Box {
                val offAlpha by animateFloatAsState(if (subscribed) 0f else 1f, tween(200), label = "bellLabelOff")
                val onAlpha by animateFloatAsState(if (subscribed) 1f else 0f, tween(200), label = "bellLabelOn")
                val style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = fg,
                )
                Text(label, style = style, maxLines = 1, modifier = Modifier.graphicsLayer { alpha = offAlpha })
                Text(subscribedLabel, style = style, maxLines = 1, modifier = Modifier.graphicsLayer { alpha = onAlpha })
            }
        }
    }
}

private fun DrawScope.drawWave(path: Path, side: Int, progress: Float, peak: Float, color: Color, stroke: Stroke) {
    val alpha = peak * (1f - progress)
    if (alpha <= 0.01f) return
    translate(left = side * 3f * progress, top = 0f) {
        drawPath(path, color.copy(alpha = color.alpha * alpha), style = stroke)
    }
}

/** Колокол и язычок как два маятника (bell-button.tsx: BELL_*, CLAPPER_*, LIP, RESTITUTION, RECOIL). */
private class BellSim {
    var bell = 0f
    var bellSpeed = 0f
    var clapper = 0f
    var clapperSpeed = 0f

    /** Заглушён: язычок прижат к колоколу и ударить не может. */
    var muffled = false
    var carry = 0f
    var last = 0L

    fun step(onStrike: (side: Int, strength: Float) -> Unit) {
        bellSpeed += (-bell * BELL_STIFFNESS - bellSpeed * BELL_DAMPING) * STEP
        bell += bellSpeed * STEP
        if (muffled) {
            clapper = bell
            clapperSpeed = bellSpeed
            return
        }
        clapperSpeed += (-clapper * CLAPPER_STIFFNESS - clapperSpeed * CLAPPER_DAMPING) * STEP
        clapper += clapperSpeed * STEP
        val relative = clapper - bell
        if (abs(relative) <= LIP) return
        val side = if (relative > 0f) 1 else -1
        val impact = (clapperSpeed - bellSpeed) * side
        clapper = bell + side * LIP
        if (impact <= 0f) return
        clapperSpeed -= side * impact * (1f + RESTITUTION)
        bellSpeed += side * impact * RECOIL
        if (impact > STRIKE_SPEED) onStrike(side, impact / 1600f)
    }

    fun energy(): Float =
        abs(bell) + abs(bellSpeed) * 0.02f + abs(clapper) + abs(clapperSpeed) * 0.02f

    fun settle() {
        bell = 0f
        bellSpeed = 0f
        clapper = 0f
        clapperSpeed = 0f
        last = 0L
    }
}

// Числа физики: дословно из bell-button.tsx.
private const val STEP = 1f / 240f
private const val BELL_STIFFNESS = 380f
private const val BELL_DAMPING = 5f
private const val CLAPPER_STIFFNESS = 150f
private const val CLAPPER_DAMPING = 4f
private const val LIP = 13f
private const val RESTITUTION = 0.45f
private const val RECOIL = 0.18f
private const val STRIKE_SPEED = 260f
private const val RING_KICK = 600f
private const val MUTE_KICK = 300f
private const val REST = 0.05f
private const val WAVE_MS = 420
private const val SLASH_LEN = 26f
private val PIVOT = Offset(12f, 2.5f)
private val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

// Геометрия иконки (viewBox 24, штрих 2): колокол, язычок, косая черта выключения и дуги звона.
private const val BELL_BODY =
    "M3.26 15.33A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.67C19.41 13.96 18 12.5 18 8A6 6 0 0 0 6 8c0 4.5-1.41 5.96-2.74 7.33"
private const val BELL_CLAPPER = "M10.27 21a2 2 0 0 0 3.46 0"
private const val BELL_SLASH = "M3 3l18 18"
private const val WAVE_RIGHT = "M21.5 4.5c1.2 1.6 1.8 3.4 1.8 5.5"
private const val WAVE_LEFT = "M2.5 4.5C1.3 6.1.7 7.9.7 10"
