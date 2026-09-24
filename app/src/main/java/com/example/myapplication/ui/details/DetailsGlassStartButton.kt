package com.example.myapplication.ui.details

import com.example.myapplication.ui.shared.glassControl
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Detached from the centered dock on purpose: DetailsScreen positions this bubble independently.
 * Glass recipe mirrors the Share FAB in SettingsScreen.
 */
@Composable
fun DetailsGlassStartButton(
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .size(58.dp)
            .clip(CircleShape)
            .glassControl(backdrop, CircleShape) {
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(2f.dp.toPx())
                        lens(16f.dp.toPx(), 44f.dp.toPx())
                    },
                )
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.22f),
                            0.5f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.10f),
                        ),
                        CircleShape,
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = "Start watching",
            tint = contentColor,
            modifier = Modifier.size(28.dp).offset(x = 1.dp),
        )
    }
}
