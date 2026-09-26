package com.example.myapplication.ui.home.updates

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.FrostedMaterials
import com.example.myapplication.ui.shared.frostedGlass
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.kyant.backdrop.Backdrop
import com.phnem.vetro.R

/**
 * Колокольчик центра уведомлений.
 *
 * [glass] — отдельная капля матового стекла рядом с капсулой плавающего дока (тот же материал и та
 * же высота, что у капсулы). Без него — обычная кнопка шапки, как её соседи сортировка и тип
 * контента: в развёрнутой шапке стеклянных поверхностей нет, и одна капля выглядела бы чужой.
 *
 * Виден всегда; счётчик — только когда есть непрочитанное.
 */
@Composable
fun NotificationBellButton(
    count: Int,
    glass: Boolean,
    backdrop: Backdrop,
    anchor: NotificationBellAnchor,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = BELL_DROP_SIZE,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Нажатие — пространство, значит пружина: быстрое сжатие и мягкий возврат (спека §6).
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = if (pressed) MotionTokens.springPressIn() else MotionTokens.springSnappy(),
        label = "bellPress",
    )
    val tint = if (isAppInDarkTheme()) Color.White else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .size(size)
            .notificationBellAnchor(anchor)
            .then(
                if (glass) {
                    Modifier
                        .clip(CircleShape)
                        .frostedGlass(backdrop = backdrop, shape = CircleShape, material = FrostedMaterials.dock())
                } else {
                    Modifier.clip(CircleShape)
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.dock_bell_24),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                },
        )
        AnimatedVisibility(
            visible = count > 0,
            enter = scaleIn(MotionTokens.springSnappy(), initialScale = 0.5f) +
                fadeIn(tween(MotionTokens.EaseEnterMillis, easing = MotionTokens.EaseEnter)),
            exit = scaleOut(MotionTokens.springExit(), targetScale = 0.5f) +
                fadeOut(tween(MotionTokens.EaseExitMillis, easing = MotionTokens.EaseExit)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-6).dp, y = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .background(BrandOrange, CircleShape)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (count > 99) "99+" else count.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Капля по высоте капсулы плавающего дока: кнопки 44 dp + 6 dp воздуха сверху и снизу. */
val BELL_DROP_SIZE: Dp = 56.dp
