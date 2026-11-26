package com.example.myapplication.ui.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import com.example.myapplication.ui.shared.PlacedCoordinates
import com.example.myapplication.ui.shared.components.RollingNumberText
import com.example.myapplication.ui.shared.trackPlacement
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.FrostedMaterials
import com.example.myapplication.ui.shared.frostedGlass
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.kyant.backdrop.Backdrop

@Composable
internal fun EpisodeSelectionDock(
    count: Int,
    ru: Boolean,
    backdrop: Backdrop,
    onClear: () -> Unit,
    onWatched: (Boolean) -> Unit,
    onRate: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = if (isAppInDarkTheme()) Color.White else MaterialTheme.colorScheme.onSurface
    val buttonColors = IconButtonDefaults.iconButtonColors(
        contentColor = ink,
        disabledContentColor = ink.copy(alpha = 0.38f),
    )
    val ratePlacement = remember { PlacedCoordinates() }
    val origin = TransformOrigin(1f, 0f)
    AnimatedVisibility(
        visible = count > 0,
        modifier = modifier,
        enter = fadeIn() + scaleIn(MotionTokens.menuPop(), initialScale = 0.7f, transformOrigin = origin),
        exit = fadeOut() + scaleOut(targetScale = 0.7f, transformOrigin = origin),
    ) {
        Row(
            modifier = Modifier.frostedGlass(backdrop, CircleShape, FrostedMaterials.dock())
                .padding(start = 14.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RollingNumberText(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = ink,
                semanticLabel = if (ru) "Выбрано серий: $count" else "Selected episodes: $count",
            )
            IconButton(enabled = count > 0, colors = buttonColors, onClick = { onWatched(true) }) {
                Icon(Icons.Rounded.Visibility, if (ru) "Отметить просмотренными" else "Mark as watched")
            }
            IconButton(enabled = count > 0, colors = buttonColors, onClick = { onWatched(false) }) {
                Icon(Icons.Rounded.VisibilityOff, if (ru) "Отметить непросмотренными" else "Mark as unwatched")
            }
            IconButton(
                modifier = Modifier.trackPlacement(ratePlacement),
                enabled = count > 0,
                onClick = { ratePlacement.boundsInRoot()?.let(onRate) },
            ) {
                Icon(
                    Icons.Rounded.Star,
                    if (ru) "Оценить выбранные серии" else "Rate selected episodes",
                    tint = OverlayThemeTokens.FavoriteGold,
                )
            }
            IconButton(enabled = count > 0, colors = buttonColors, onClick = onClear) {
                Icon(Icons.Rounded.Close, if (ru) "Снять выделение" else "Clear selection")
            }
        }
    }
}
