package com.example.myapplication.ui.shared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Низкоуровневый трек «жидкого стекла»: капсула с заливкой прогресса и стеклянным бегунком,
 * который сэмплирует саму капсулу и потому преломляет насечки под собой.
 *
 * Тот же материал, что у трека рейтинга
 * ([com.example.myapplication.ui.shared.components.rating.RatingTrackWidget]), но без его модели
 * значений: рейтинг живёт на непрерывной шкале 0–10 с переходом цвета между состояниями, а сюда
 * приходит любая доля 0–1 и один тон. Общая часть — именно стекло, поэтому она здесь, а не
 * скопирована в каждый экран с ползунком.
 *
 * Компонент не владеет значением: во время перетаскивания он показывает позицию пальца
 * (её же отдаёт в [onScrub]), в покое — переданный [fraction]. Живая позиция читается только
 * внутри draw/layer-лямбд, поэтому движение пальца не вызывает рекомпозицию.
 *
 * @param fraction положение бегунка 0–1 в покое.
 * @param ticks число делений; 0 — без насечек, слишком частые деления рисовать бессмысленно.
 * @param onScrub вызывается на каждое движение пальца с новой долей; `null` — трек только показывает.
 * @param thumbContent содержимое бегунка (например, номер страницы).
 */
@Composable
fun LiquidGlassTrack(
    fraction: Float,
    modifier: Modifier = Modifier,
    accent: Color = BrandOrange,
    trackColor: Color = Color.White.copy(alpha = 0.16f),
    tickColor: Color = Color.White.copy(alpha = 0.22f),
    trackHeight: Dp = 26.dp,
    thumbWidth: Dp = 46.dp,
    ticks: Int = 0,
    onScrubStart: (() -> Unit)? = null,
    onScrub: ((Float) -> Unit)? = null,
    onScrubEnd: ((Float) -> Unit)? = null,
    thumbContent: @Composable (BoxScope.() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val capsule = RoundedCornerShape(percent = 50)
    val thumbPx = with(density) { thumbWidth.toPx() }
    val currentThumbWidth by rememberUpdatedState(thumbWidth)
    val currentStart by rememberUpdatedState(onScrubStart)
    val currentScrub by rememberUpdatedState(onScrub)
    val currentEnd by rememberUpdatedState(onScrubEnd)

    // Живая позиция пальца. Отрицательное значение = не тянем, показываем внешнее состояние.
    var scrubFraction by remember { mutableFloatStateOf(-1f) }
    val target by rememberUpdatedState(fraction)
    val live = { if (scrubFraction >= 0f) scrubFraction else target.coerceIn(0f, 1f) }

    val trackBackdrop = rememberLayerBackdrop { drawContent() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .then(
                if (onScrub != null) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown()
                                down.consume()
                                // Размер и обработчики берутся на момент жеста: pointerInput живёт дольше них.
                                val thumb = currentThumbWidth.toPx()
                                fun xToFraction(x: Float): Float {
                                    val usable = (size.width - thumb).coerceAtLeast(1f)
                                    return ((x - thumb / 2f) / usable).coerceIn(0f, 1f)
                                }
                                try {
                                    currentStart?.invoke()
                                    scrubFraction = xToFraction(down.position.x)
                                    currentScrub?.invoke(scrubFraction)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: event.changes.first()
                                        change.consume()
                                        val next = xToFraction(change.position.x)
                                        if (!change.pressed) {
                                            currentEnd?.invoke(next)
                                            break
                                        }
                                        scrubFraction = next
                                        currentScrub?.invoke(next)
                                    }
                                } finally {
                                    // Отпускаем позицию пальца после коммита (и при отмене жеста): иначе
                                    // бегунок прыгнул бы на старое значение или застрял под пальцем.
                                    scrubFraction = -1f
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val travelPx = with(density) { (maxWidth - thumbWidth).toPx() }

        // ---- Капсула: хвост, заливка до бегунка, насечки делений ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .align(Alignment.Center)
                .clip(capsule)
                .layerBackdrop(trackBackdrop)
                .drawBehind {
                    val h = size.height
                    val r = h / 2f
                    val pad = thumbPx / 2f
                    val usable = (size.width - thumbPx).coerceAtLeast(1f)
                    val centerX = pad + usable * live()

                    drawRoundRect(color = trackColor, cornerRadius = CornerRadius(r, r))
                    drawRoundRect(
                        color = accent,
                        size = Size(centerX.coerceAtLeast(h), h),
                        cornerRadius = CornerRadius(r, r),
                    )
                    if (ticks in 2..MAX_TICKS) {
                        val tickHalf = h * 0.18f
                        val tickW = h * 0.05f
                        for (i in 0 until ticks) {
                            val x = pad + usable * (i / (ticks - 1f))
                            drawLine(
                                color = if (x <= centerX) Color.Black.copy(alpha = 0.20f) else tickColor,
                                start = Offset(x, h / 2f - tickHalf),
                                end = Offset(x, h / 2f + tickHalf),
                                strokeWidth = tickW,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                },
        )

        // ---- Стеклянный бегунок ----
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(thumbWidth, trackHeight)
                .graphicsLayer {
                    translationX = travelPx * live()
                    shadowElevation = with(density) { 6.dp.toPx() }
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
                        lens(14f.dp.toPx(), 40f.dp.toPx())
                    },
                )
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.22f),
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.10f),
                    ),
                    capsule,
                )
                .border(1.dp, Color.White.copy(alpha = 0.45f), capsule),
            contentAlignment = Alignment.Center,
            content = { thumbContent?.invoke(this) },
        )
    }
}

/** Больше делений превращаются в сплошную штриховку — тогда трек рисуется без них. */
private const val MAX_TICKS = 24
