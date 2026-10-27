package com.example.myapplication.localplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.audiobooks.ui.Pill
import com.example.myapplication.audiobooks.ui.TickRuler
import com.example.myapplication.audiobooks.ui.formatSpeed
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.components.rememberIosSheetSwipe
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SheetStyle
import com.example.myapplication.ui.shared.theme.SnProFamily
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MIN_SPEED = 0.5f
private const val SPEED_STEP = 0.05f
private const val SPEED_STEPS = 31 // 0,5× … 2,0×
private val SPEED_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Шторка скорости видео — тот же вид, что у аудиокниг (SpeedSheetContent): крупное значение,
 * линейка-колесо с хаптиком и пресеты. Выбор применяется сразу, шторка не закрывается.
 */
@Composable
internal fun PlayerSpeedSheet(
    state: MutableTransitionState<Boolean>,
    speed: Float,
    ru: Boolean,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    val swipe = rememberIosSheetSwipe(onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val limit = (maxHeight - statusTop - SheetStyle.FloatBottom - 24.dp).coerceAtLeast(160.dp)
        MotionBottomSheet(
            visibleState = state,
            onDismiss = onDismiss,
            isDark = true,
            containerColor = Color(0xFF17130F),
            scrimModifier = Modifier.pointerInput(Unit) { consumeEverything() },
            panelModifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = limit),
            swipe = swipe,
        ) {
            GrabberHandle(isDark = true, modifier = swipe.handleModifier)
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 16.dp)) {
                Text(
                    if (ru) "Скорость" else "Speed",
                    color = Color.White,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    formatSpeed(speed),
                    color = BrandOrange,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 44.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(10.dp))
                TickRuler(
                    count = SPEED_STEPS,
                    selected = ((speed - MIN_SPEED) / SPEED_STEP).roundToInt(),
                    majorEvery = 10,
                    onSelected = { onSelect(((MIN_SPEED + it * SPEED_STEP) * 100).roundToInt() / 100f) },
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEED_PRESETS.forEach { preset ->
                        Pill(formatSpeed(preset), selected = abs(speed - preset) < 0.01f) { onSelect(preset) }
                    }
                }
            }
        }
    }
}
