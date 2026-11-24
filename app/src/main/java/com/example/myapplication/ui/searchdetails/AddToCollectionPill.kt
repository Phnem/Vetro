package com.example.myapplication.ui.searchdetails

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.theme.SnProFamily
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

enum class AddPillPhase { Idle, Working, Done }

/**
 * Кнопка «+ Добавить» из экрана «Детали из поиска».
 *
 * Покой: пилюля с плюсом и подписью, по ней изредка проходит блик. Нажатие: подпись растворяется,
 * плюс уезжает в центр пилюли и крутится, пока тайтл записывается ([AddPillPhase.Working]); когда
 * запись готова ([AddPillPhase.Done]) вращение тормозит, а плюс на ходу складывается в галочку.
 * Дальше пилюлю растворяет [dissolve] — это ход NameDrop-перехода, поэтому он лямбда: читается в
 * слое и в отрисовке, без перекомпозиции на каждый кадр.
 */
@Composable
fun AddToCollectionPill(
    phase: AddPillPhase,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dissolve: () -> Float = { 0f },
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.titleMedium.copy(
        fontFamily = SnProFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
    )
    val textWidthPx = remember(label, labelStyle) { textMeasurer.measure(label, labelStyle).size.width }
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current

    val travel = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    val morph = remember { Animatable(0f) }
    val labelAlpha by animateFloatAsState(
        targetValue = if (phase == AddPillPhase.Idle) 1f else 0f,
        animationSpec = tween(200),
        label = "pillLabelAlpha",
    )

    LaunchedEffect(phase) {
        when (phase) {
            AddPillPhase.Idle -> {
                morph.snapTo(0f)
                spin.snapTo(0f)
                travel.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 300f))
            }
            AddPillPhase.Working -> {
                morph.snapTo(0f)
                launch { travel.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 300f)) }
                // Полный оборот за раз: вращение всегда останавливается на целом числе оборотов.
                while (true) {
                    spin.animateTo(spin.value + 360f, tween(650, easing = LinearEasing))
                }
            }
            AddPillPhase.Done -> {
                // Дотормозить до ближайшего целого оборота плюс ещё один: галочка встаёт прямо.
                val target = (ceil(spin.value / 360f) + 1f) * 360f
                launch { travel.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 300f)) }
                launch { morph.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
                spin.animateTo(target, tween(560, easing = FastOutSlowInEasing))
            }
        }
    }

    val sheen by rememberInfiniteTransition(label = "pillSheen").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "pillSheenPos",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer {
                val d = dissolve()
                val s = 1f + sin(PI.toFloat() * d) * 0.045f - d * 0.12f
                scaleX = s
                scaleY = s
                alpha = 1f - cubicInOut(d)
            }
            .fluidClickable(
                scaleDown = 0.95f,
                enabled = phase == AddPillPhase.Idle,
                onClick = onClick,
            )
            .clip(CircleShape)
            .background(primary),
    ) {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // Место под плюс: сам значок рисуется поверх на Canvas, чтобы уехать в центр.
        Spacer(Modifier.width(28.dp))
        Text(
            text = label,
            style = labelStyle,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.graphicsLayer {
                alpha = labelAlpha
                val s = 0.85f + 0.15f * labelAlpha
                scaleX = s
                scaleY = s
                translationX = (1f - labelAlpha) * 12.dp.toPx()
            },
        )
    }

    // Значок и блик — отдельным слоем над подписью.
    Canvas(
        modifier = Modifier.fillMaxSize(),
    ) {
        val w = size.width
        val h = size.height
        val cy = h / 2f

        if (phase == AddPillPhase.Idle && sheen < 0.45f) {
            val x = lerpF(-0.3f, 1.3f, sheen / 0.45f) * w
            val band = w * 0.22f
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent),
                    startX = x - band,
                    endX = x + band,
                ),
            )
        }

        val flash = sin(PI.toFloat() * dissolve())
        if (flash > 0f) drawRect(Color.White.copy(alpha = 0.24f * flash))

        val startX = (w - (with(density) { 28.dp.toPx() } + textWidthPx)) / 2f + with(density) { 10.dp.toPx() }
        val cx = lerpF(startX, w / 2f, travel.value)
        val r = 8.dp.toPx() * (1f + 0.22f * sin(PI.toFloat() * morph.value))
        val m = morph.value
        val center = Offset(cx, cy)
        // Две черты плюса превращаются в две черты галочки: горизонтальная — в короткую,
        // вертикальная — в длинную; общий конец галочки — её нижняя точка.
        val valley = Offset(-0.18f * r, 0.52f * r)
        val a1 = lerp(Offset(-r, 0f), Offset(-0.62f * r, 0.05f * r), m)
        val a2 = lerp(Offset(r, 0f), valley, m)
        val b1 = lerp(Offset(0f, -r), valley, m)
        val b2 = lerp(Offset(0f, r), Offset(0.74f * r, -0.46f * r), m)
        val stroke = 2.7.dp.toPx()
        rotate(degrees = spin.value, pivot = center) {
            drawLine(Color.White, center + a1, center + a2, strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(Color.White, center + b1, center + b2, strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
    }
}

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun cubicInOut(x: Float): Float =
    if (x < 0.5f) 4f * x * x * x else 1f - Math.pow((-2.0 * x + 2.0), 3.0).toFloat() / 2f
