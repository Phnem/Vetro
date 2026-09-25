package com.example.myapplication.ui.splash

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.DarkSurface
import com.example.myapplication.ui.shared.theme.LightSurface
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val HoldBlackMs = 250L
private const val WaveInMs = 1400
private const val HoldMinMs = 500L
private const val WaveOutMs = 700
/** Дольше экран под сплэшем не ждём: коллекция догрузится уже на виду. */
private const val ContentReadyTimeoutMs = 1500L

@Composable
fun VetroSplashScreen(
    uiState: SplashState,
    migrationTitle: String,
    migrationSubtitle: String,
    jsonMigrationTitle: String,
    jsonMigrationSubtitle: String,
    legacyFolderTitle: String,
    legacyFolderSubtitle: String,
    legacyFolderAction: String,
    legacyFolderSkip: String,
    cloudRestoreTitle: String,
    cloudRestoreSubtitle: String,
    onPickLegacyFolder: () -> Unit,
    onSkipLegacyFolder: () -> Unit,
    onBuildContent: (String) -> Unit,
    contentReady: () -> Boolean,
    onSplashComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val waveProgress = remember { Animatable(0f) }
    val exitProgress = remember { Animatable(0f) }
    var waveInDone by remember { mutableStateOf(false) }
    var outroStarted by remember { mutableStateOf(false) }

    // Intro: black hold → wave in
    LaunchedEffect(Unit) {
        delay(HoldBlackMs)
        waveProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(WaveInMs, easing = FastOutSlowInEasing),
        )
        waveInDone = true
    }

    // Экран под сплэшем строится в неподвижной паузе после волны (первая композиция главной —
    // сотни мс на телефоне, секунды на слабых устройствах; во время волны она дала бы рывок).
    // Outro — не раньше HoldMinMs и готовности экрана, и открывает его, а не чёрный фон.
    // Только после Completed (не во время AwaitingLegacyFolder).
    LaunchedEffect(uiState, waveInDone) {
        val completed = uiState as? SplashState.Completed ?: return@LaunchedEffect
        if (!waveInDone || outroStarted) return@LaunchedEffect
        outroStarted = true
        val holdStart = SystemClock.uptimeMillis()
        onBuildContent(completed.nextRoute)
        // Два кадра: экран скомпонован и отрисован под сплэшем.
        withFrameNanos { }
        withFrameNanos { }
        withTimeoutOrNull(ContentReadyTimeoutMs) {
            snapshotFlow { contentReady() }.first { it }
        }
        delay((HoldMinMs - (SystemClock.uptimeMillis() - holdStart)).coerceAtLeast(0L))
        exitProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(WaveOutMs, easing = FastOutSlowInEasing),
        )
        onSplashComplete()
    }

    val showMigrationCard = uiState is SplashState.MigratingStorage ||
        uiState is SplashState.MigratingJson ||
        uiState is SplashState.ImportingLegacyFolder ||
        uiState is SplashState.RestoringFromCloud
    val showFolderCard = uiState is SplashState.AwaitingLegacyFolder

    val cardTitle = when (uiState) {
        SplashState.MigratingStorage -> migrationTitle
        SplashState.MigratingJson -> jsonMigrationTitle
        SplashState.ImportingLegacyFolder -> migrationTitle
        SplashState.RestoringFromCloud -> cloudRestoreTitle
        SplashState.AwaitingLegacyFolder -> legacyFolderTitle
        else -> migrationTitle
    }
    val cardSubtitle = when (uiState) {
        SplashState.MigratingStorage -> migrationSubtitle
        SplashState.MigratingJson -> jsonMigrationSubtitle
        SplashState.ImportingLegacyFolder -> migrationSubtitle
        SplashState.RestoringFromCloud -> cloudRestoreSubtitle
        SplashState.AwaitingLegacyFolder -> legacyFolderSubtitle
        else -> migrationSubtitle
    }


    Box(
        modifier = modifier
            .fillMaxSize()
            // Пока сплэш на экране, касания не доходят до экрана под ним.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.Center,
    ) {
        SplashWaveBackground(
            progress = { waveProgress.value },
            exitProgress = { exitProgress.value },
        )

        VetroWordmark(
            modifier = Modifier.graphicsLayer {
                // Лёгкий zoom-out логотипа на outro
                val wordmarkScale = 1f + exitProgress.value * 0.12f
                scaleX = wordmarkScale
                scaleY = wordmarkScale
                alpha = splashWaveAlphaAt(
                    xFrac = 0.5f,
                    yFrac = 0.45f,
                    progress = waveProgress.value,
                    exitProgress = exitProgress.value,
                )
            },
        )

        AnimatedVisibility(
            visible = showMigrationCard,
            enter = splashCardEnter(),
            exit = splashCardExit(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .padding(horizontal = 24.dp),
        ) {
            SplashInfoCard(
                title = cardTitle,
                subtitle = cardSubtitle,
                showProgress = true,
            )
        }

        AnimatedVisibility(
            visible = showFolderCard,
            enter = splashCardEnter(),
            exit = splashCardExit(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .padding(horizontal = 24.dp),
        ) {
            SplashFolderCard(
                title = cardTitle,
                subtitle = cardSubtitle,
                actionLabel = legacyFolderAction,
                skipLabel = legacyFolderSkip,
                onPickFolder = onPickLegacyFolder,
                onSkip = onSkipLegacyFolder,
            )
        }
    }
}

private fun splashCardEnter() = slideInVertically(
    initialOffsetY = { it },
    animationSpec = MotionTokens.sheetPresent(),
) + fadeIn(tween(400))

private fun splashCardExit() = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(300))

@Composable
private fun SplashBottomCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isDark = isAppInDarkTheme()
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) DarkSurface else LightSurface,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isDark) Color.White.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
private fun SplashInfoCard(
    title: String,
    subtitle: String,
    showProgress: Boolean,
) {
    SplashBottomCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showProgress) {
                SplashAccentIconBubble {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = BrandOrange,
                        strokeWidth = 2.5.dp,
                        strokeCap = StrokeCap.Round,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                SplashCardText(title = title, subtitle = subtitle)
            }
        }
    }
}

@Composable
private fun SplashFolderCard(
    title: String,
    subtitle: String,
    actionLabel: String,
    skipLabel: String,
    onPickFolder: () -> Unit,
    onSkip: () -> Unit,
) {
    val isDark = isAppInDarkTheme()
    val skipColor = if (isDark) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant

    SplashBottomCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                SplashAccentIconBubble {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = BrandOrange,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    SplashCardText(title = title, subtitle = subtitle)
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = onPickFolder,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(100),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandOrange,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = actionLabel,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = skipLabel,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Medium,
                    color = skipColor,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SplashAccentIconBubble(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(BrandOrange.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun SplashCardText(title: String, subtitle: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = SnProFamily,
            ),
            color = MaterialTheme.colorScheme.secondary,
            lineHeight = 16.sp,
        )
    }
}
