package com.example.myapplication.ui.settings.help

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Переход «открой настройки и покажи вот этот пункт». Ставит заявку снаружи (плеер → MainActivity),
 * а исполняет её сам экран настроек: прокрутить, мягко подсветить, а в первый раз ещё и показать,
 * как открывается описание пункта.
 */
object SettingsSpotlight {
    const val ACTION_OPEN_SETTINGS = "com.phnem.vetro.action.OPEN_SETTINGS"
    const val EXTRA_TARGET = "com.phnem.vetro.extra.SETTINGS_TARGET"

    /** Обучение «удержанием открывается описание» показано хотя бы раз. */
    val TUTORIAL_SEEN = booleanPreferencesKey("settings_help_tutorial_seen")

    /** Какой пункт нужно показать; null — заявки нет. */
    val target = MutableStateFlow<String?>(null)

    /** Страница настроек уже перед глазами (рабочая область доехала до неё). */
    val pageReady = MutableStateFlow(false)

    fun request(id: String) {
        pageReady.value = false
        target.value = id
    }

    fun finish() {
        target.value = null
        pageReady.value = false
    }
}

/** Анимируемое состояние указателя и таблички; крутит его сценарий в экране настроек. */
class SpotlightUi {
    val pointerAlpha = Animatable(0f)
    val pointerPos = Animatable(Offset.Zero, Offset.VectorConverter)
    val press = Animatable(0f)
    val ring = Animatable(0f)
    var hintVisible by mutableStateOf(false)

    /** Начало координат оверлея в координатах корня: границы строк приходят в корневых. */
    var origin by mutableStateOf(Offset.Zero)

    /** Указатель выезжает к точке [to] (корневые координаты), «нажимает» и держит палец. */
    suspend fun pressAt(to: Offset, from: Offset, reducedMotion: Boolean) {
        val target = to - origin
        val start = from - origin
        pointerPos.snapTo(if (reducedMotion) target else start)
        press.snapTo(0f)
        ring.snapTo(0f)
        coroutineScope {
            launch { pointerAlpha.animateTo(1f, tween(220)) }
            if (!reducedMotion) pointerPos.animateTo(target, tween(760, easing = FastOutSlowInEasing))
        }
        press.animateTo(1f, tween(150))
        coroutineScope {
            if (!reducedMotion) launch { ring.animateTo(1f, tween(900, easing = LinearOutSlowInEasing)) }
            delay(820)
        }
    }

    suspend fun release() {
        coroutineScope {
            launch { press.animateTo(0f, tween(160)) }
            launch { pointerAlpha.animateTo(0f, tween(260)) }
        }
        ring.snapTo(0f)
    }
}

@Composable
fun SettingsSpotlightOverlay(
    ui: SpotlightUi,
    ru: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { ui.origin = it.boundsInRoot().topLeft },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val a = ui.pointerAlpha.value
            if (a <= 0.01f) return@Canvas
            val center = ui.pointerPos.value
            val press = ui.press.value
            val radius = 24.dp.toPx() * (1f - 0.16f * press)
            // Как «показывать касания» в записи экрана Android: полупрозрачный серый диск с тенью
            // и светлым кольцом; при нажатии диск чуть сжимается, от него идёт волна.
            drawCircle(Color.Black.copy(alpha = 0.18f * a), radius + 3.dp.toPx(), center)
            drawCircle(Color(0xFF8E8E93).copy(alpha = 0.55f * a), radius, center)
            drawCircle(Color.White.copy(alpha = 0.92f * a), radius, center, style = Stroke(2.dp.toPx()))
            val wave = ui.ring.value
            if (wave > 0f) {
                drawCircle(
                    Color.White.copy(alpha = 0.55f * (1f - wave) * a),
                    radius + 34.dp.toPx() * wave,
                    center,
                    style = Stroke(2.dp.toPx()),
                )
            }
        }

        AnimatedVisibility(
            visible = ui.hintVisible,
            enter = fadeIn(tween(260)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { -it / 2 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(240)) { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 14.dp, start = 20.dp, end = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xF01C1C1E))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { ui.hintVisible = false }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(BrandOrange),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.TouchApp, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.size(14.dp))
                Text(
                    text = if (ru) {
                        "Удерживайте палец на любом пункте настроек, чтобы прочитать, что он делает"
                    } else {
                        "Press and hold any setting to read what it does"
                    },
                    color = Color.White,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}
