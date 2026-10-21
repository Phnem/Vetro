package com.example.myapplication.ui.shared.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * Глубина bottom sheet: контраст даёт ЗАТЕМНЁННЫЙ ФОН за шторкой (scrim, см. `SheetStyle.scrimFactor`),
 * а сама панель — чуть светлее базы, с едва заметным вертикальным градиентом (низ = база, верх чуть
 * светлее), тонкой светлой линией по периметру и мягкой широкой тенью. Спеков и фасок нет.
 */

/** Контейнер шторки: мягкая тень → clip → база → едва заметный градиент и окантовка. */
fun Modifier.iosSheetContainer(
    shape: Shape,
    isDark: Boolean,
    base: Color,
): Modifier = this
    .iosSheetTopShadow(isDark)
    .clip(shape)
    .drawBehind { drawRect(sheetBase(base)) }
    .iosSheetGlass(shape, base)

/** Панель чуть светлее базы. */
private fun sheetBase(base: Color): Color = lerp(base, Color.White, 0.045f)

/**
 * Мягкая тень парящей панели — нарисованный градиент вокруг кромки, а не нативная тень: широкая
 * нативная тень (30+ dp) пересчитывается размытием на каждом кадре выезда и жеста и заметно роняла fps.
 */
fun Modifier.iosSheetTopShadow(isDark: Boolean): Modifier = this.drawBehind { drawFloatingGlow(isDark) }

/**
 * Мягкая тень парящей панели: узкие полосы-градиенты вдоль каждой стороны и четверть-радиальные
 * градиенты в углах — только по кромке, а не по всей площади. Раньше тень строилась из восьми
 * растущих скруглённых прямоугольников во весь лист; их перерисовка (восемь слоёв ~2 МП с альфой на
 * каждый кадр) была самой дорогой частью шторки. Над панелью тень шире, чем по бокам и под ней.
 */
private fun DrawScope.drawFloatingGlow(isDark: Boolean) {
    val w = size.width
    val h = size.height
    val r = SheetStyle.FloatCorner.toPx()
    val up = 54.dp.toPx()
    val side = 34.dp.toPx()
    val down = 30.dp.toPx()
    val alpha = if (isDark) 0.34f else 0.16f
    // Плавный спад: плотный у кромки, к краю полосы — в ноль (три промежуточные точки, чтобы не было «ступеньки»).
    fun fade() = listOf(
        Color.Black.copy(alpha = alpha),
        Color.Black.copy(alpha = alpha * 0.45f),
        Color.Black.copy(alpha = alpha * 0.12f),
        Color.Transparent,
    )
    drawRect(Brush.verticalGradient(fade().reversed(), startY = -up, endY = 0f), Offset(r, -up), Size(w - 2 * r, up))
    drawRect(Brush.verticalGradient(fade(), startY = h, endY = h + down), Offset(r, h), Size(w - 2 * r, down))
    drawRect(Brush.horizontalGradient(fade().reversed(), startX = -side, endX = 0f), Offset(-side, r), Size(side, h - 2 * r))
    drawRect(Brush.horizontalGradient(fade(), startX = w, endX = w + side), Offset(w, r), Size(side, h - 2 * r))

    fun corner(cx: Float, cy: Float, extent: Float, left: Float, top: Float) {
        val radius = r + extent
        val inner = r / radius
        val brush = Brush.radialGradient(
            0f to Color.Black.copy(alpha = alpha),
            inner to Color.Black.copy(alpha = alpha),
            inner + (1f - inner) * 0.35f to Color.Black.copy(alpha = alpha * 0.45f),
            inner + (1f - inner) * 0.7f to Color.Black.copy(alpha = alpha * 0.12f),
            1f to Color.Transparent,
            center = Offset(cx, cy),
            radius = radius,
        )
        drawRect(brush, Offset(left, top), Size(r + extent, r + extent))
    }
    corner(r, r, up, -up, -up)
    corner(w - r, r, up, w - r, -up)
    corner(r, h - r, down, -down, h - r)
    corner(w - r, h - r, down, w - r, h - r)
}

/** Едва заметный вертикальный градиент внутри панели (верх чуть светлее базы, низ = база) и окантовка по периметру. */
fun Modifier.iosSheetGlass(
    shape: Shape,
    base: Color,
): Modifier = this.drawWithCache {
    val top = sheetBase(base)
    val gradient = Brush.verticalGradient(
        0f to lerp(top, Color.White, 0.05f),
        1f to top,
    )
    // Тонкая полупрозрачная светлая окантовка ПО ВСЕМУ периметру, как у референсов: чуть ярче вверху,
    // к низу слабее, но не гаснет. Одна обводка — широкая размытая полоса под ней (обводка контура в
    // 9 dp) убрана как дорогая. Обводка центрирована на контуре, а клип панели срезает наружную половину.
    val outline = shape.createOutline(size, layoutDirection, this)
    val rimPath = (outline as? Outline.Generic)?.path ?: Path().apply { addOutline(outline) }
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.22f),
        0.5f to Color.White.copy(alpha = 0.18f),
        1f to Color.White.copy(alpha = 0.15f),
    )
    onDrawBehind {
        // Панель уже обрезана по форме (clip выше по цепочке), поэтому градиент заливается обычным
        // прямоугольником, а не контуром: заливка сложного пути с шейдером на весь лист стоила заметно дороже.
        drawRect(gradient)
        drawPath(rimPath, brush = rim, style = Stroke(width = 0.8.dp.toPx()))
    }
}
