package com.example.myapplication.ui.shared.mascot

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import kotlinx.coroutines.delay

private const val ENTER_SECONDS = 0.5f

/**
 * Анимированный маскот Vetro для пустых состояний. Сцена появляется на пружине и дальше крутится
 * циклом; при системном «Отключить анимации» — статичный кадр выразительной позы.
 * Рисуется на Canvas, время читается только в фазе отрисовки — рекомпозиций по кадрам нет.
 */
@Composable
fun MascotIllustration(
    scene: MascotScene,
    modifier: Modifier = Modifier,
    isDark: Boolean = isAppInDarkTheme(),
) {
    val spec = remember(scene) { scene.spec() }
    val palette = if (isDark) MascotPalette.Dark else MascotPalette.Light
    val reduceMotion = rememberReducedMotion()
    val elapsed = remember(scene) { mutableFloatStateOf(0f) }
    if (!reduceMotion) {
        LaunchedEffect(scene) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    val seconds = (now - start) / 1_000_000_000.0
                    // после появления время держим внутри цикла сцены — без потери точности за часы работы
                    elapsed.floatValue = if (seconds < ENTER_SECONDS) {
                        seconds.toFloat()
                    } else {
                        ENTER_SECONDS + ((seconds - ENTER_SECONDS) % spec.loop).toFloat()
                    }
                }
            }
        }
    }
    Canvas(
        modifier
            .aspectRatio(1f)
            .graphicsLayer {
                alpha = if (reduceMotion) 1f else clamp01(elapsed.floatValue / ENTER_SECONDS * 2.2f)
            }
            .clearAndSetSemantics { },
    ) {
        val t = elapsed.floatValue
        val enter = if (reduceMotion) 1f else clamp01(t / ENTER_SECONDS)
        val time = if (reduceMotion) spec.still else (t - ENTER_SECONDS).coerceAtLeast(0f)
        val s = size.minDimension / 300f
        at((size.width - 300f * s) / 2f, (size.height - 300f * s) / 2f, sx = s) {
            drawScene(spec, time, enter, palette)
        }
    }
}

/**
 * Пустое состояние: маскот + заголовок, подпись и (по желанию) одно действие, которое его заполняет.
 * Текст доезжает следом за персонажем.
 */
@Composable
fun MascotEmptyState(
    scene: MascotScene,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    illustrationSize: Dp = 200.dp,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onBackground,
    subtitleColor: Color = MaterialTheme.colorScheme.secondary,
    isDark: Boolean = isAppInDarkTheme(),
) {
    val reduceMotion = rememberReducedMotion()
    val textIn = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (textIn.value < 1f) {
            delay(120)
            textIn.animateTo(1f, MotionTokens.springSurface())
        }
    }
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MascotIllustration(scene, Modifier.size(illustrationSize), isDark)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = textIn.value.coerceIn(0f, 1f)
                translationY = (1f - textIn.value) * 16.dp.toPx()
            },
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFamily = SnProFamily),
                textAlign = TextAlign.Center,
                color = titleColor,
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
                    color = subtitleColor,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier
                        .fluidClickable(onClick = onAction)
                        .height(46.dp)
                        .clip(CircleShape)
                        .background(if (isDark) BrandOrange else Color(0xFF1B1D21))
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = actionLabel,
                        color = Color.White,
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}
