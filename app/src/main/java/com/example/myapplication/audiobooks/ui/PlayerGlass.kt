package com.example.myapplication.audiobooks.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.sin
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
import androidx.compose.foundation.Image
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/**
 * Иконка «звуковая дорожка» (те же пять полос, что у `ph_waveform`), которая играет: полосы качаются,
 * пока [playing], и плавно возвращаются к статичному виду на паузе.
 */
@Composable
internal fun PlayingBars(playing: Boolean, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    val live by animateFloatAsState(if (playing) 1f else 0f, tween(300), label = "barsLive")
    val transition = rememberInfiniteTransition(label = "bars")
    val phases = BAR_PERIODS_MS.mapIndexed { i, period ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart),
            label = "bar$i",
        )
    }
    Canvas(modifier.size(size)) {
        val unit = this.size.width / 256f
        BAR_BASE.forEachIndexed { i, base ->
            // Синус со своим периодом на каждую полосу — ритм не повторяется синхронно.
            val wave = 0.5f + 0.5f * sin(phases[i].value * 2f * PI.toFloat() + i * 1.3f)
            val h = (base + (BAR_MIN + (BAR_MAX - BAR_MIN) * wave - base) * live) * unit
            val x = (BAR_X[i] - BAR_W / 2f) * unit
            drawRoundRect(
                color = tint,
                topLeft = Offset(x, (this.size.height - h) / 2f),
                size = Size(BAR_W * unit, h),
                cornerRadius = CornerRadius(BAR_W / 2f * unit),
            )
        }
    }
}

// Геометрия `ph_waveform` (viewport 256): центры полос, ширина и статичные высоты.
private val BAR_X = floatArrayOf(48f, 88f, 128f, 168f, 208f)
private val BAR_BASE = floatArrayOf(64f, 208f, 144f, 64f, 96f)
private val BAR_PERIODS_MS = listOf(900, 700, 1100, 800, 1000)
private const val BAR_W = 16f
private const val BAR_MIN = 40f
private const val BAR_MAX = 208f

/**
 * Обложка на всю поверхность; без картинки — тёмный градиент, а не выдуманный арт. Однотонные поля
 * по краям картинки (серая щель внизу у части источников) срезаются, чтобы обложка дотягивалась до края.
 */
@Composable
internal fun BookArt(uri: String?, modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.verticalGradient(listOf(Color(0xFF2A1A14), Color(0xFF15100E), Color(0xFF0B0908))),
        ),
    ) {
        if (uri != null) {
            val painter = rememberAsyncImagePainter(uri)
            val state by painter.state.collectAsState()
            val trim by produceState(CoverTrim(), state) {
                val image = (state as? AsyncImagePainter.State.Success)?.result?.image ?: return@produceState
                value = withContext(Dispatchers.Default) {
                    runCatching {
                        val bmp = image.toBitmap(TRIM_PROBE, TRIM_PROBE)
                        val px = IntArray(TRIM_PROBE * TRIM_PROBE)
                        bmp.getPixels(px, 0, TRIM_PROBE, 0, 0, TRIM_PROBE, TRIM_PROBE)
                        CoverTrim.detect(px, TRIM_PROBE, TRIM_PROBE)
                    }.getOrDefault(CoverTrim())
                }
            }
            val shown = remember(painter, trim) { if (trim.isEmpty) painter else TrimmedPainter(painter, trim) }
            Image(
                painter = shown,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

private const val TRIM_PROBE = 96
