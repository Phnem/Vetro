package com.example.myapplication.ui.vetrolink

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.mediacatalog.LinkPhase
import com.example.myapplication.mediacatalog.VetroSyncState
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.mascot.MascotIllustration
import com.example.myapplication.ui.shared.mascot.MascotScene
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme

/** Строки экрана: свои, чтобы не раздувать UiStrings (лимит полей, см. память проекта). */
internal class VetroSyncStrings(ru: Boolean) {
    val title = if (ru) "Подождите, пожалуйста" else "Please wait"
    val subtitle = if (ru) "Мы очень стараемся синхронизировать все данные" else "We're doing our best to sync all your data"
    val matching = if (ru) "Сопоставляем тайтлы" else "Matching your titles"
    val covers = if (ru) "Загружаем обложки" else "Fetching covers"
    val skip = if (ru) "Продолжить в фоне" else "Continue in background"
    private val of = if (ru) "из" else "of"
    fun counter(done: Int, total: Int) = "$done $of $total"
}

/**
 * Экран первой синхронизации с каталогом Vetro: анимированный маскот, пояснение и прогресс. Закрывает всё приложение, пока идёт
 * привязка коллекции; через полминуты можно продолжить в фоне.
 */
@Composable
fun VetroSyncScreen(
    state: VetroSyncState,
    russian: Boolean,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = VetroSyncStrings(russian)
    val isDark = isAppInDarkTheme()
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BrandOrange.copy(alpha = if (isDark) 0.16f else 0.10f), background, background)))
            // Экран перекрывает приложение: тапы не должны проходить под него.
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.systemBars),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MascotIllustration(MascotScene.Think, Modifier.size(240.dp), isDark)
            Spacer(Modifier.height(8.dp))
            Text(
                text = strings.title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontFamily = SnProFamily),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = strings.subtitle,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = SnProFamily),
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            ProgressBar(done = state.done, total = state.total)
            Spacer(Modifier.height(12.dp))
            Text(
                text = buildString {
                    append(if (state.phase == LinkPhase.COVERS) strings.covers else strings.matching)
                    if (state.total > 0) append("  ·  ").append(strings.counter(state.done.coerceAtMost(state.total), state.total))
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )
            if (state.canSkip) {
                Spacer(Modifier.height(28.dp))
                Box(
                    Modifier
                        .fluidClickable(onClick = onSkip)
                        .height(46.dp)
                        .clip(CircleShape)
                        .background(if (isDark) BrandOrange else Color(0xFF1B1D21))
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(strings.skip, color = Color.White, fontFamily = SnProFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

/** Полоса прогресса: с известным числом тайтлов заполняется пружиной, пока число неизвестно, по ней бежит отрезок. */
@Composable
private fun ProgressBar(done: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(
        targetValue = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f,
        animationSpec = MotionTokens.springSurface(),
        label = "vetroSyncProgress",
    )
    val sweep by rememberInfiniteTransition(label = "vetroSyncSweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val track = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .drawBehind {
                drawRect(track)
                if (total > 0) {
                    drawRect(BrandOrange, Offset.Zero, Size(size.width * fraction, size.height))
                } else {
                    val segment = size.width * 0.3f
                    val start = ((size.width + segment) * sweep - segment).coerceAtLeast(0f)
                    val end = ((size.width + segment) * sweep).coerceAtMost(size.width)
                    if (end > start) drawRect(BrandOrange, Offset(start, 0f), Size(end - start, size.height))
                }
            },
    )
}
