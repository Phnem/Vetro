package com.example.myapplication.ui.shared.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.MotionTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One reveal clock per section; off-screen rows remain lazy and join the current pose. */
@Stable
internal class SectionRevealState {
    val progress = Animatable(0f)
    /** Keep lazy rows mounted until their reverse animation has actually finished. */
    val visible by derivedStateOf { progress.value > 0f }

    fun rowModifier(index: Int): Modifier = Modifier.graphicsLayer {
        val delay = index.coerceAtMost(4) * 0.035f
        val p = ((progress.value - delay) / (1f - delay)).coerceIn(0f, 1f)
        alpha = p
        translationY = -((14 + index.coerceAtMost(3) * 4).dp.toPx()) * (1f - p)
        scaleX = 0.94f + 0.06f * p
        scaleY = 0.94f + 0.06f * p
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}

@Composable
internal fun rememberSectionReveal(
    expanded: Boolean,
    headerKey: String,
    listState: LazyListState,
): SectionRevealState {
    val reveal = remember(headerKey) { SectionRevealState() }
    val previewPx = with(LocalDensity.current) { 112.dp.toPx() }
    LaunchedEffect(expanded) {
        if (expanded) {
            // animateTo reverses an interrupted close from its current value and velocity.
            // Resetting to zero here flashed already-visible rows back to their collapsed pose.
            launch { reveal.progress.animateTo(1f, MotionTokens.seasonExpansion()) }
            launch {
                // Let LazyColumn see the newly inserted rows before asking for scroll space.
                withFrameNanos { }
                val layout = listState.layoutInfo
                val header = layout.visibleItemsInfo.firstOrNull { it.key == headerKey } ?: return@launch
                val bottom = layout.viewportEndOffset - layout.afterContentPadding
                val preview = previewPx.coerceAtMost((layout.viewportEndOffset - layout.viewportStartOffset) / 3f)
                val missing = header.offset + header.size + preview - bottom
                if (missing > 0f) {
                    try {
                        listState.animateScrollBy(missing, MotionTokens.seasonExpansion())
                    } catch (_: CancellationException) {
                        // A user's scroll takes precedence without cancelling the row reveal.
                    }
                }
            }
        } else {
            reveal.progress.animateTo(0f, MotionTokens.seasonExpansion())
        }
    }
    return reveal
}
