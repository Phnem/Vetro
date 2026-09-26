package com.example.myapplication.ui.shared.components.rating

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

enum class RatingTrackMode { Compact, Expanded }

/**
 * Трек рейтинга в форме капсулы с вертикальными насечками и стеклянным бегунком-лозенгой
 * (референс — фото 2). Один и тот же composable для формы (Compact, владеет жестом) и
 * полноэкранного бабла (Expanded).
 *
 * Стекло бегунка — НАСТОЯЩИЙ backdrop: у трека-капсулы собственный [layerBackdrop], а бегунок
 * его сэмплирует с линзой → визуально увеличивает насечки под собой, как жидкое стекло дока.
 * Слой изолирован в самом виджете, поэтому не задевает layerBackdrop экрана (см. gotcha про
 * flatten дока).
 *
 * Жест: единственный сырой pointerInput у Compact-экземпляра; во время драга компонент не
 * покидает композицию (бабл — отдельный визуальный слой поверх) — удержание не прерывается.
 */
@Composable
fun RatingTrackWidget(
    state: RatingSliderState,
    mode: RatingTrackMode,
    ru: Boolean,
    modifier: Modifier = Modifier,
    onCommitted: ((Float) -> Unit)? = null,
    /** Подписи цветом «чернил» бабла (true) или акцентом на фоне приложения (false). */
    inkLabels: Boolean = false,
) {
    val compact = mode == RatingTrackMode.Compact
    // Тонкая капсула-пилюля. Бегунок ровно в высоту капсулы: стекло сэмплирует слой
    // трека без «пустых» краёв.
    val trackHeight = if (compact) 27.dp else 33.dp
    val thumbH = trackHeight
    val thumbW = if (compact) 48.dp else 60.dp
    val capsule = RoundedCornerShape(percent = 50)

    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val thumbWpx = with(density) { thumbW.toPx() }

    // Пересчёт подписей/цвета — только при смене квантованного значения (шаг 0.1),
    // не на каждый пиксель движения пальца.
    val quantized by remember(state) {
        derivedStateOf { (state.liveT.value * 10).roundToInt() / 10f }
    }
    val labels by remember(state, ru) { derivedStateOf { labelsFor(quantized, ru) } }
    val accentColor by remember(state) { derivedStateOf { faceColorFor(quantized) } }
    val onSurface = MaterialTheme.colorScheme.onSurface

    // Собственный слой-источник для стекла бегунка: захватывает пиксели капсулы (насечки+заливку).
    val trackBackdrop = rememberLayerBackdrop { drawContent() }

    Column(
        modifier = modifier.semantics {
            contentDescription = "Rating: ${labels.currentValueText}, ${labels.current}"
        },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(thumbH)
                .then(
                    if (onCommitted != null) {
                        Modifier.pointerInput(state) {
                            val padPx = thumbWpx / 2f
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown()
                                    down.consume()
                                    performHaptic(view, Haptic.Light)
                                    state.beginDrag()
                                    var lastWhole = floor(state.liveT.value)
                                    fun xToT(x: Float): Float {
                                        val usable = (size.width - thumbWpx).coerceAtLeast(1f)
                                        return ((x - padPx) / usable * 10f).coerceIn(0f, 10f)
                                    }
                                    scope.launch { state.onDrag(xToT(down.position.x)) }
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: event.changes.first()
                                        if (!change.pressed) {
                                            change.consume()
                                            performHaptic(view, Haptic.Heavy)
                                            scope.launch {
                                                state.settle { snapped -> onCommitted(snapped) }
                                            }
                                            break
                                        }
                                        change.consume()
                                        val t = xToT(change.position.x)
                                        // Тик на пересечение каждого ЦЕЛОГО значения:
                                        // 100 тиков на 0.1 ощущались бы как жужжание.
                                        val whole = floor(t)
                                        if (whole != lastWhole) {
                                            lastWhole = whole
                                            performHaptic(view, Haptic.Tick)
                                        }
                                        scope.launch { state.onDrag(t) }
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            val travelPx = with(density) { (maxWidth - thumbW).toPx() }

            // ---- Капсула-трек: заливка прогресса + затемнённый хвост + насечки ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .align(Alignment.Center)
                    .clip(capsule)
                    .layerBackdrop(trackBackdrop)
                    .drawBehind {
                        val t = state.liveT.value
                        val w = size.width
                        val h = size.height
                        val r = h / 2f
                        val current = faceColorFor(t)
                        val pad = thumbWpx / 2f
                        val usable = (w - thumbWpx).coerceAtLeast(1f)
                        val centerX = pad + usable * (t / 10f)

                        // Затемнённый «хвост» (незалитая часть) — приглушённая версия
                        // активного цвета, не отдельная палитра (как #336C1B в референсе).
                        drawRoundRect(
                            color = lerpOklab(current, Color.Black, 0.60f),
                            cornerRadius = CornerRadius(r, r),
                        )
                        // Пройденная часть — растянутый градиент BAD→текущий от 0 до бегунка,
                        // иначе трек на 0.3 выглядел бы «пусто».
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(RATING_KEYFRAMES.first().backgroundColor, current),
                                startX = 0f,
                                endX = centerX.coerceAtLeast(h),
                            ),
                            size = Size(centerX.coerceAtLeast(h), h),
                            cornerRadius = CornerRadius(r, r),
                        )
                        // Вертикальные насечки по всей капсуле (11 = целые деления 0..10).
                        val tickHalf = h * 0.21f
                        val tickW = h * 0.055f
                        for (i in 0..10) {
                            val x = pad + usable * (i / 10f)
                            // На пройденной части насечки тёмные, на хвосте — светлые.
                            val onProgress = x <= centerX
                            drawLine(
                                color = if (onProgress) Color.Black.copy(alpha = 0.22f)
                                else Color.White.copy(alpha = 0.16f),
                                start = Offset(x, h / 2f - tickHalf),
                                end = Offset(x, h / 2f + tickHalf),
                                strokeWidth = tickW,
                                cap = StrokeCap.Round,
                            )
                        }
                    },
            )

            // ---- Стеклянный бегунок-лозенга (жидкое стекло, сэмплирует капсулу) ----
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(thumbW, thumbH)
                    .graphicsLayer {
                        translationX = travelPx * (state.liveT.value / 10f)
                        val bounce = state.releaseBounce.value
                        scaleX = bounce
                        scaleY = bounce
                        shadowElevation = with(density) { 8.dp.toPx() }
                        shape = capsule
                        clip = false
                    }
                    .clip(capsule)
                    .drawBackdrop(
                        backdrop = trackBackdrop,
                        shape = { capsule },
                        effects = {
                            vibrancy()
                            blur(2f.dp.toPx())
                            // Крупная линза = ощутимое «увеличительное» преломление насечек.
                            lens(16f.dp.toPx(), 44f.dp.toPx())
                        },
                    )
                    // Стеклянный блик сверху + мягкая тень снизу внутри капсулы.
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.22f),
                            0.5f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.10f),
                        ),
                        capsule,
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.45f), capsule),
            )
        }

        // ---- Три подписи: пред. / текущая (+число) / след. ----
        val mutedColor = if (inkLabels) {
            faceInkColor(accentColor).copy(alpha = 0.5f)
        } else {
            onSurface.copy(alpha = 0.45f)
        }
        val currentColor = if (inkLabels) faceInkColor(accentColor) else accentColor
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = labels.previous.orEmpty(),
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Medium,
                fontSize = if (compact) 11.sp else 13.sp,
                color = mutedColor,
                maxLines = 1,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
            )
            Column(
                modifier = Modifier.weight(1.4f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = labels.currentValueText,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 17.sp else 22.sp,
                    color = currentColor,
                    maxLines = 1,
                )
                Text(
                    text = labels.current,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = if (compact) 10.sp else 12.sp,
                    color = currentColor.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
            Text(
                text = labels.next.orEmpty(),
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Medium,
                fontSize = if (compact) 11.sp else 13.sp,
                color = mutedColor,
                maxLines = 1,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }
    }
}
