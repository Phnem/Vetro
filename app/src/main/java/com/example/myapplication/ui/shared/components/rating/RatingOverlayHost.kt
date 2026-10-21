package com.example.myapplication.ui.shared.components.rating

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.theme.SnProFamily
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Полноэкранный «бабл» рейтинга: последний ребёнок корневого Box экрана (поверх всего).
 * Появляется на время драга, разворачиваясь из компактного трека и —  ключевое —
 * при отпускании **втягивается обратно ровно в слайдер**: неравномерный масштаб
 * (по X → к ширине слайдера, по Y → в его тонкую высоту) вокруг центра якоря
 * ([RatingSliderState.anchorBoundsInRoot]) + позднее исчезновение alpha, так что бабл
 * визуально «всасывается» в исходный слайдер, а не просто гаснет.
 *
 * Жестом НЕ владеет: палец остаётся на компактном треке в форме — бабл только
 * визуализирует то же состояние. Все покадровые чтения — в draw-фазе / graphicsLayer.
 */
@Composable
fun RatingOverlayHost(
    state: RatingSliderState,
    ru: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Открыт ли бабл. По умолчанию — пока палец ведёт трек в форме (AddEdit) или держит строку
     * серии. У пикера по тапу на рейтинг карточки палец в момент открытия уже поднят, поэтому
     * открытием и закрытием управляет вызывающий.
     */
    shown: Boolean = state.isDragging,
    /**
     * Не null — трек внутри бабла живой: палец ставит значение, отпускание его фиксирует.
     * Null — бабл только показывает состояние, жестом владеет кто-то другой.
     */
    onCommitted: ((Float) -> Unit)? = null,
    /** Тап мимо трека и системный «назад» закрывают живой бабл без сохранения. */
    onOutsideTap: (() -> Unit)? = null,
) {
    // progress: 0 = свёрнут в слайдер, 1 = полноэкранный бабл.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) {
            progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 320f))
        } else {
            // Дать «желе» (releaseBounce) отыграть в баблe перед втягиванием.
            delay(200)
            progress.animateTo(0f, spring(dampingRatio = 1f, stiffness = 260f))
        }
    }

    val visible = shown || progress.value > 0.001f
    if (!visible) return

    if (onOutsideTap != null) {
        BackHandler(enabled = shown, onBack = onOutsideTap)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWpx = constraints.maxWidth.toFloat()
        val screenHpx = constraints.maxHeight.toFloat()

        // Слово категории и «чернила» — обновляются только при смене квантованного t.
        val quantized by remember(state) {
            derivedStateOf { (state.liveT.value * 10).roundToInt() / 10f }
        }
        val bigWord by remember(state, ru) {
            derivedStateOf { labelsFor(quantized, ru).current.uppercase() }
        }
        val inkColor by remember(state) {
            derivedStateOf { faceInkColor(faceColorFor(quantized)) }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Живой бабл модален: тап мимо трека закрывает его, а не проваливается в экран
                // под ним. Трек гасит свой down сам, так что до этого обработчика он не доходит.
                .then(
                    if (onOutsideTap != null) {
                        Modifier.pointerInput(onOutsideTap) {
                            detectTapGestures { onOutsideTap() }
                        }
                    } else {
                        Modifier
                    },
                )
                .graphicsLayer {
                    val p = progress.value
                    val anchor = state.anchorBoundsInRoot
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    if (anchor != null && anchor.width > 0f && anchor.height > 0f) {
                        // Неравномерный scale: при p=0 бабл сжат ровно в прямоугольник слайдера
                        // (широкий по X, тонкий по Y) и смещён в его центр → «втягивание».
                        val sx = anchor.width / screenWpx
                        val sy = anchor.height / screenHpx
                        scaleX = sx + (1f - sx) * p
                        scaleY = sy + (1f - sy) * p
                        translationX = (anchor.center.x - screenWpx / 2f) * (1f - p)
                        translationY = (anchor.center.y - screenHpx / 2f) * (1f - p)
                    } else {
                        val s = 0.25f + 0.75f * p
                        scaleX = s
                        scaleY = s
                    }
                    // Держим бабл видимым почти весь путь и гасим только в конце втягивания,
                    // чтобы было видно, как он «всасывается» в слайдер.
                    alpha = (p * 5f).coerceIn(0f, 1f)
                    shape = RoundedCornerShape((8f + (1f - p) * 40f).dp)
                    clip = true
                },
        ) {
            // ---- Фон + виньетка ----
            Canvas(modifier = Modifier.fillMaxSize()) {
                val bg = faceColorFor(state.liveT.value)
                drawRect(bg)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.14f)),
                        center = Offset(size.width / 2f, size.height * 0.42f),
                        radius = max(size.width, size.height) * 0.72f,
                    ),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(56.dp))
                EmojiFaceCanvas(
                    tProvider = { state.liveT.value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .graphicsLayer {
                            // Лёгкий сквош лица вместе с бегунком на отпускание.
                            val b = 1f + (state.releaseBounce.value - 1f) * 0.5f
                            scaleX = b
                            scaleY = b
                        },
                )
                Text(
                    text = bigWord,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Black,
                    fontSize = 56.sp,
                    letterSpacing = (-1.5).sp,
                    color = inkColor.copy(alpha = 0.55f),
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(150.dp))
            }

            // ---- Расширенный трек (тот же state, тот же стеклянный бегунок) ----
            RatingTrackWidget(
                state = state,
                mode = RatingTrackMode.Expanded,
                ru = ru,
                onCommitted = onCommitted,
                inkLabels = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = RatingExpandedSidePadding)
                    .padding(bottom = 40.dp),
            )
        }
    }
}
