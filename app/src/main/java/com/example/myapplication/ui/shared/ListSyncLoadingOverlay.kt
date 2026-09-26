package com.example.myapplication.ui.shared

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.models.UiStrings
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.DarkBackground
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.glassEdge
import com.example.myapplication.ui.shared.theme.glassFill
import com.example.myapplication.ui.shared.theme.glassRim

/**
 * Полноэкранный оверлей синхронизации онлайн-списка: стекло 0.9f, кольцо с градиентом, карточка статуса, прогресс.
 */
@Composable
fun ListSyncLoadingOverlay(
    message: String,
    processed: Int,
    total: Int,
    strings: UiStrings,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isAppInDarkTheme()
    val panelBase = if (isDark) DarkBackground else MaterialTheme.colorScheme.surface
    val panelTint = panelBase.copy(alpha = 0.9f)
    val onCard = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant

    val blue = OverlayThemeTokens.AccentNeonBlue
    val cyan = Color(0xFFFF8A3D)

    val targetFrac = if (total > 0) (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedFrac by animateFloatAsState(
        targetValue = targetFrac,
        animationSpec = tween(durationMillis = 320, easing = LinearEasing),
        label = "syncBar"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        val corner = 28.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(corner))
                .background(panelTint)
                .glassFill(isDark)
                .glassRim(corner, isDark)
                .glassEdge(corner, isDark)
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = onCard.copy(alpha = 0.9f))
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = strings.nottifCloseCd,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 28.dp)
            ) {
                Box(
                    modifier = Modifier.size(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val ringSize = 152.dp
                    if (total > 0) {
                        GradientSyncRing(
                            progress = animatedFrac,
                            strokeWidth = 5.dp,
                            size = ringSize,
                            trackColor = Color.White.copy(alpha = 0.12f),
                            gradient = listOf(blue, cyan, Color(0xFFFFB067))
                        )
                    } else {
                        IndeterminateSyncRing(
                            strokeWidth = 5.dp,
                            size = ringSize,
                            trackColor = Color.White.copy(alpha = 0.12f),
                            gradient = listOf(blue, cyan, Color(0xFFFFB067))
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = message.ifBlank { strings.listSyncLoadingDefaultMessage },
                            color = onCard,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = strings.listSyncLoadingSubtitle,
                            color = muted.copy(alpha = 0.9f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            letterSpacing = 1.2.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                GradientLinearProgress(
                    fraction = if (total > 0) animatedFrac else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    trackColor = Color.White.copy(alpha = 0.10f),
                    colors = listOf(blue, cyan)
                )

                Spacer(Modifier.height(18.dp))

                Text(
                    text = strings.listSyncLoadingFooter,
                    color = muted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun GradientSyncRing(
    progress: Float,
    strokeWidth: Dp,
    size: Dp,
    trackColor: Color,
    gradient: List<Color>
) {
    Canvas(Modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val w = this.size.width
        val h = this.size.height
        val arcSize = Size(w - stroke, h - stroke)
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        val sweep = 360f * progress.coerceIn(0f, 1f)
        if (sweep > 2f) {
            drawArc(
                brush = Brush.sweepGradient(
                    colors = gradient,
                    center = Offset(w / 2f, h / 2f)
                ),
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun IndeterminateSyncRing(
    strokeWidth: Dp,
    size: Dp,
    trackColor: Color,
    gradient: List<Color>
) {
    val infinite = rememberInfiniteTransition(label = "syncRing")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rot"
    )
    Canvas(Modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val w = this.size.width
        val h = this.size.height
        val arcSize = Size(w - stroke, h - stroke)
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            brush = Brush.sweepGradient(
                colors = gradient,
                center = Offset(w / 2f, h / 2f)
            ),
            startAngle = rotation - 90f,
            sweepAngle = 88f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun GradientLinearProgress(
    fraction: Float?,
    modifier: Modifier = Modifier,
    trackColor: Color,
    colors: List<Color>
) {
    val cornerPx = 2.dp
    if (fraction != null) {
        Canvas(
            modifier = modifier.clip(RoundedCornerShape(cornerPx))
        ) {
            val w = size.width
            val h = size.height
            val r = CornerRadius(h / 2f, h / 2f)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = r
            )
            val frac = fraction.coerceIn(0.05f, 1f)
            val pw = (w * frac).coerceAtLeast(h)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = colors,
                    startX = 0f,
                    endX = pw
                ),
                topLeft = Offset.Zero,
                size = Size(pw, h),
                cornerRadius = r
            )
        }
    } else {
        val infinite = rememberInfiniteTransition(label = "barInd")
        val shift by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "sh"
        )
        Canvas(
            modifier = modifier.clip(RoundedCornerShape(cornerPx))
        ) {
            val w = size.width
            val h = size.height
            val r = CornerRadius(h / 2f, h / 2f)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = r
            )
            val segW = w * 0.42f
            val x = (w - segW) * shift
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = colors,
                    startX = x,
                    endX = x + segW
                ),
                topLeft = Offset(x, 0f),
                size = Size(segW, h),
                cornerRadius = r
            )
        }
    }
}
