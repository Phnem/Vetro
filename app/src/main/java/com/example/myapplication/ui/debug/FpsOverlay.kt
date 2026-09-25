package com.example.myapplication.ui.debug

import android.view.Choreographer
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.ui.shared.theme.BrandDeepRed
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily

@Composable
fun FpsOverlay(modifier: Modifier = Modifier) {
    var fps by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val choreographer = Choreographer.getInstance()
        var frameCount = 0
        var secondStartNanos = 0L

        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (secondStartNanos == 0L) {
                    secondStartNanos = frameTimeNanos
                }
                frameCount++
                val elapsed = frameTimeNanos - secondStartNanos
                if (elapsed >= 1_000_000_000L) {
                    fps = ((frameCount * 1_000_000_000L) / elapsed).toInt()
                    frameCount = 0
                    secondStartNanos = frameTimeNanos
                }
                choreographer.postFrameCallback(this)
            }
        }

        choreographer.postFrameCallback(callback)
        onDispose { choreographer.removeFrameCallback(callback) }
    }

    val isDark = isAppInDarkTheme()
    val accent = if (isDark) BrandOrangeBright else BrandDeepRed
    val bg =
        if (isDark) OverlayThemeTokens.TileBackgroundDark
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
    val pill = CircleShape

    Box(
        modifier = modifier
            .statusBarsPadding()
            .padding(start = 12.dp, top = 12.dp)
    ) {
        Surface(
            shape = pill,
            color = bg,
            tonalElevation = 0.dp,
            modifier = Modifier.border(width = 1.5.dp, color = accent, shape = pill)
        ) {
            Text(
                text = "FPS: $fps",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = SnProFamily,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
