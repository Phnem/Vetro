package com.example.myapplication.ui.addedit

import com.example.myapplication.ui.shared.theme.BrandSand
import com.example.myapplication.ui.shared.theme.BrandOrange
import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.lightTileShadowInLightTheme

// Монохромно-оранжевая брендовая гамма: категории различаются светлотой/температурой
// внутри палитры #E85002 / #C10801 / #D9C3AB + серые.
object AddEditColors {
    val CoverGradientStart = Color(0xFF262626)
    val CoverGradientEnd = Color(0xFF3A1E0D)
    val CoverIconCircle = Color(0xFF9E4A1F)

    val FormatAnimeGradientStart = Color(0xFF3A1E0D)
    val FormatAnimeGradientEnd = Color(0xFF1A0D06)
    val FormatAnimeIconBg = BrandOrange

    val FormatMoviesGradientStart = Color(0xFF262626)
    val FormatMoviesGradientEnd = Color(0xFF101010)
    val FormatMoviesIconBg = Color(0xFF8A8A8E)

    val FormatSeriesGradientStart = Color(0xFF383021)
    val FormatSeriesGradientEnd = Color(0xFF1A160E)
    val FormatSeriesIconBg = BrandSand

    val QuickSelectGlow = BrandOrange
    val QuickSelectActiveBg = BrandOrange

    val PillBackground = Color(0xFF1C1C1C)
    val PillBackgroundLight = Color(0xFFE8E8E8)

    val SectionLabel = Color(0xFF8E8E93)
}

/** Мягкая тень под «менюшными» элементами в светлой теме (как у плиток в настройках). */
fun Modifier.addEditMenuTileShadow(isDark: Boolean, shape: Shape): Modifier =
    lightTileShadowInLightTheme(isDark, shape)

val CoverGradientBrush = Brush.linearGradient(
    colors = listOf(AddEditColors.CoverGradientStart, AddEditColors.CoverGradientEnd),
    start = Offset(0f, 0f),
    end = Offset(0f, Float.POSITIVE_INFINITY)
)

fun formatCategoryBrush(categoryKey: String): Brush = when (categoryKey) {
    "Anime" -> Brush.horizontalGradient(
        listOf(AddEditColors.FormatAnimeGradientStart, AddEditColors.FormatAnimeGradientEnd)
    )
    "Movies" -> Brush.horizontalGradient(
        listOf(AddEditColors.FormatMoviesGradientStart, AddEditColors.FormatMoviesGradientEnd)
    )
    "Series" -> Brush.horizontalGradient(
        listOf(AddEditColors.FormatSeriesGradientStart, AddEditColors.FormatSeriesGradientEnd)
    )
    else -> Brush.horizontalGradient(
        listOf(AddEditColors.FormatMoviesGradientStart, AddEditColors.FormatMoviesGradientEnd)
    )
}

fun formatCategoryIconBg(categoryKey: String): Color = when (categoryKey) {
    "Anime" -> AddEditColors.FormatAnimeIconBg
    "Movies" -> AddEditColors.FormatMoviesIconBg
    "Series" -> AddEditColors.FormatSeriesIconBg
    else -> AddEditColors.FormatMoviesIconBg
}

/** Horizontal pill: accent-tinted left bleeding into near-black (reference layout). */
fun formatCategoryPillBrush(categoryKey: String): Brush {
    val accent = formatCategoryIconBg(categoryKey)
    val deep = Color(0xFF0A0A0A)
    return Brush.horizontalGradient(
        colors = listOf(
            lerp(accent, deep, 0.62f).copy(alpha = 0.88f),
            deep.copy(alpha = 0.94f)
        ),
        startX = 0f,
        endX = Float.POSITIVE_INFINITY
    )
}

/** Light theme: soft accent wash into surface variant (matches system cards). */
fun formatCategoryPillBrushLight(accent: Color, surfaceVariant: Color): Brush =
    Brush.horizontalGradient(
        colors = listOf(
            lerp(accent, surfaceVariant, 0.88f),
            surfaceVariant
        ),
        startX = 0f,
        endX = Float.POSITIVE_INFINITY
    )

/**
 * Draws a colored neon glow behind the content using FrameworkPaint + BlurMaskFilter.
 * Works on all API levels (MaskFilter path; no RenderEffect needed).
 */
fun Modifier.neonGlow(
    color: Color,
    radius: Dp = 12.dp,
    alpha: Float = 0.55f
): Modifier = this.drawBehind {
    val blurPx = radius.toPx()
    drawIntoCanvas { canvas ->
        val paint = Paint().also {
            val frameworkPaint = it.asFrameworkPaint()
            frameworkPaint.maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            frameworkPaint.color = color.copy(alpha = alpha).toArgb()
        }
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f
        canvas.drawCircle(
            center = Offset(cx, cy),
            radius = r,
            paint = paint
        )
    }
}
