package com.example.myapplication.audiobooks.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import com.example.myapplication.ui.shared.FrostedMaterials
import com.example.myapplication.ui.shared.frostedGlass
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.kyant.backdrop.Backdrop

/**
 * Стеклянная поверхность поверх обложки: круглая кнопка, плитка, капсула. Материал один —
 * [FrostedMaterials.playerControl]; нажатие «продавливает» на 0,96 (UNIVERSAL_MOTION_SPEC §6).
 */
@Composable
internal fun GlassSurface(
    backdrop: Backdrop,
    shape: Shape,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = if (pressed) MotionTokens.springPressIn() else MotionTokens.springSnappy(),
        label = "glassPress",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .frostedGlass(backdrop = backdrop, shape = shape, material = FrostedMaterials.playerControl())
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
internal fun GlassCircleButton(
    backdrop: Backdrop,
    @DrawableRes icon: Int,
    description: String,
    size: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    GlassSurface(
        backdrop = backdrop,
        shape = CircleShape,
        modifier = modifier.size(size),
        description = description,
        enabled = enabled,
        onClick = onClick,
    ) {
        PhIcon(icon, iconSize, tint)
    }
}

@Composable
internal fun PhIcon(@DrawableRes icon: Int, size: Dp, tint: Color = Color.White, modifier: Modifier = Modifier) {
    Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** Обложка на всю поверхность; без картинки — тёмный градиент, а не выдуманный арт. */
@Composable
internal fun BookArt(uri: String?, modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.verticalGradient(listOf(Color(0xFF2A1A14), Color(0xFF15100E), Color(0xFF0B0908))),
        ),
    ) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
