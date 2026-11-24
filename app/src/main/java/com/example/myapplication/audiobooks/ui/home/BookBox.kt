package com.example.myapplication.audiobooks.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.audiobooks.data.CachedBook
import com.example.myapplication.ui.shared.theme.SnProFamily
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ==========================================
// Книга-коробка. Две видимые грани — обложка и корешок — это настоящие плоскости: углы граней
// считаются в 3D (поворот коробки вокруг вертикальной оси + перспектива камеры), каждая грань
// рисуется как слой с матрицей «прямоугольник → четырёхугольник». Общие рёбра совпадают до пикселя,
// щелей на сгибе нет при любом угле.
//
//   угол 0°   — обложка лицом к зрителю, корешок ребром (его не видно)
//   угол ~16° — книга стоит под углом, слева тонкая полоска корешка
//   угол 90°  — корешком к зрителю, обложка ребром
//
// Торец страниц и задняя крышка при углах 0…90° повёрнуты от зрителя и отсекаются. Лицо грани
// записывается в GraphicsLayer один раз (картинка Coil, текст, градиенты) — на каждый кадр анимации
// остаётся только пересчитать матрицы, никаких перекомпозиций и новых картинок.
// ==========================================

internal val BookCamera = 760.dp

/** Наклон книги на карточке полки (до 3 книг). */
internal const val AngledTilt = 16f

/** Толщина книги под углом: из неё получается ширина видимой полоски корешка. */
internal val AngledThickness = 20.dp

@Immutable
internal data class BookDims(val width: Dp, val height: Dp, val thickness: Dp)

/** Книга корешком к зрителю: обложка 2:3 при данной высоте, толщина — ширина корешка. */
internal fun spineDims(thickness: Dp, height: Dp) = BookDims(width = height * (2f / 3f), height = height, thickness = thickness)

/** Книга под углом: обложка 1:1,5 заданной ширины. */
internal fun angledDims(coverWidth: Dp) = BookDims(width = coverWidth, height = coverWidth * 1.5f, thickness = AngledThickness)

/** Ширина, которую книга занимает в строке при данном угле (обложка + видимая полоска корешка). */
internal fun BookDims.footprintWidth(angleDeg: Float): Dp {
    val a = Math.toRadians(angleDeg.toDouble())
    return width * cos(a).toFloat() + thickness * sin(a).toFloat()
}

private val NoShadow: () -> Float = { 0f }

/** Постоянная тень под книгой на полке. */
internal val FullGroundShadow: () -> Float = { 1f }

/**
 * Книга-коробка. [angle] читается на этапе рисования, поэтому анимация угла не вызывает
 * перекомпозицию. Размер [modifier] задаёт строку раскладки (footprint); коробка рисуется вокруг
 * центра этого прямоугольника и может выходить за него, когда обложка разворачивается.
 */
@Composable
internal fun BookBox(
    book: CachedBook?,
    style: PlaceholderStyle,
    index: Int,
    dims: BookDims,
    angle: () -> Float,
    modifier: Modifier = Modifier,
    groundShadow: () -> Float = NoShadow,
    /** Тень обложки, ложащейся плоско (в конце полёта в сетку); 0 — нет. */
    coverShadow: () -> Float = NoShadow,
    /** Ширина «следа» книги в строке для тени; по умолчанию — ширина самой коробки. */
    shadowSpan: Dp? = null,
    /**
     * Готовые слои граней чужой книги. Если они заданы, коробка ничего не собирает сама (ни картинок,
     * ни текста) и только рисует эти слои со своей матрицей: так летящая копия совпадает со стоящей
     * книгой до пикселя и не тратит кадр на повторную сборку.
     */
    faces: BookFaceLayers? = null,
    /** Сообщает слои собственных граней: их берёт летящая копия. */
    onFaces: ((BookFaceLayers) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val shadowSpanPx = shadowSpan?.let { with(density) { it.toPx() } }
    val camera = with(density) { BookCamera.toPx() }
    val widthPx = with(density) { dims.width.toPx() }
    val heightPx = with(density) { dims.height.toPx() }
    val thicknessPx = with(density) { dims.thickness.toPx() }
    val own = if (faces == null) BookFaceLayers(rememberGraphicsLayer(), rememberGraphicsLayer()) else null
    val coverLayer = (faces ?: own!!).cover
    val spineLayer = (faces ?: own!!).spine
    if (own != null && onFaces != null) SideEffect { onFaces(own) }
    val matrix = remember { android.graphics.Matrix() }
    val src = remember { FloatArray(8) }
    val dst = remember { FloatArray(8) }

    Box(
        modifier.drawWithContent {
            // Сначала содержимое: именно в этот момент грани записываются в свои слои.
            drawContent()
            val a = Math.toRadians(angle().toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            val cx = size.width / 2f
            val cy = size.height / 2f
            val hw = widthPx / 2f
            val hh = heightPx / 2f
            val ht = thicknessPx / 2f

            fun put(out: FloatArray, k: Int, x: Float, y: Float, z: Float) {
                val xr = x * c + z * s
                val zr = -x * s + z * c
                val f = camera / (camera - zr)
                out[2 * k] = cx + xr * f
                out[2 * k + 1] = cy + y * f
            }

            val shadow = groundShadow()
            if (shadow > 0.01f) {
                val w = (shadowSpanPx ?: size.width) * 0.9f + thicknessPx * 0.6f
                val r = w / 2f
                val center = Offset(cx, cy + hh)
                scale(1f, 7.dp.toPx() / w, center) {
                    drawCircle(
                        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.72f * shadow), Color.Transparent), center, r),
                        r,
                        center,
                    )
                }
            }

            drawSoftCoverShadow(cx - hw, cy - hh, widthPx, heightPx, coverShadow())

            // Обложка: передняя грань (z = +T/2).
            put(dst, 0, -hw, -hh, ht)
            put(dst, 1, hw, -hh, ht)
            put(dst, 2, hw, hh, ht)
            put(dst, 3, -hw, hh, ht)
            drawFace(coverLayer, widthPx, heightPx, dst, src, matrix, shade = 0.22f * (1f - c))

            // Корешок: левая грань (x = -W/2), слева направо — от задней крышки к передней.
            put(dst, 0, -hw, -hh, -ht)
            put(dst, 1, -hw, -hh, ht)
            put(dst, 2, -hw, hh, ht)
            put(dst, 3, -hw, hh, -ht)
            drawFace(spineLayer, thicknessPx, heightPx, dst, src, matrix, shade = 0.3f * (1f - s))
        },
        contentAlignment = Alignment.Center,
    ) {
        if (faces == null) {
            Box(Modifier.requiredSize(dims.width, dims.height).recordInto(coverLayer)) { CoverFace(book, style) }
            Box(Modifier.requiredSize(dims.thickness, dims.height).recordInto(spineLayer)) {
                SpineFace(book, style, index, dims.thickness)
            }
        }
    }
}

/** Слои двух граней книги-коробки: обложка и корешок. */
internal class BookFaceLayers(val cover: GraphicsLayer, val spine: GraphicsLayer)

/** Грань рисуется, только если повёрнута к зрителю (знак площади четырёхугольника). */
private fun DrawScope.drawFace(
    layer: GraphicsLayer,
    w: Float,
    h: Float,
    dst: FloatArray,
    src: FloatArray,
    matrix: android.graphics.Matrix,
    shade: Float,
) {
    var area = 0f
    for (i in 0 until 4) {
        val j = (i + 1) % 4
        area += dst[2 * i] * dst[2 * j + 1] - dst[2 * j] * dst[2 * i + 1]
    }
    if (area / 2f <= 0.5f) return
    src[0] = 0f; src[1] = 0f
    src[2] = w; src[3] = 0f
    src[4] = w; src[5] = h
    src[6] = 0f; src[7] = h
    matrix.setPolyToPoly(src, 0, dst, 0, 4)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        native.concat(matrix)
        drawLayer(layer)
        if (shade > 0.005f) drawRect(Color.Black.copy(alpha = shade.coerceAtMost(0.6f)), size = Size(w, h))
        native.restore()
    }
}

/** Содержимое пишется в [layer] и на месте не рисуется: его показывает [BookBox] с матрицей грани. */
private fun Modifier.recordInto(layer: GraphicsLayer): Modifier =
    this.graphicsLayer { }.drawWithContent {
        val outer = this
        layer.record(outer, layoutDirection, IntSize(size.width.roundToInt(), size.height.roundToInt())) {
            // drawContent() рисует на холст своего же DrawScope, то есть на месте. Подменяем его на
            // холст слоя на время вызова, иначе грань остаётся на месте без перспективы, а слой пуст.
            val target = drawContext.canvas
            val saved = outer.drawContext.canvas
            outer.drawContext.canvas = target
            try {
                outer.drawContent()
            } finally {
                outer.drawContext.canvas = saved
            }
        }
    }

// ---------- Лица граней ----------

/** Форма обложки: радиусы заданы долей ширины, поэтому у летящей копии и у обложки в сетке углы совпадают при любом масштабе. */
internal val CoverShape = RoundedCornerShape(topStartPercent = 3, bottomStartPercent = 3, topEndPercent = 5, bottomEndPercent = 5)

/**
 * Сгиб переплёта и кромки обложки. Все размеры — доли ширины: копия, увеличенная до размера обложки
 * в сетке, выглядит так же, как сама обложка, нарисованная в этом размере.
 */
@Composable
internal fun CoverDecor() {
    Box(
        Modifier.fillMaxSize().drawBehind {
            val w = size.width
            val hingeX = w * 0.045f
            val hingeW = maxOf(1f, w * 0.014f)
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = 0.43f), Color.White.copy(alpha = 0.12f)),
                    startX = hingeX,
                    endX = hingeX + hingeW,
                ),
                topLeft = Offset(hingeX, 0f),
                size = Size(hingeW, size.height),
            )
            val line = maxOf(1f, w * 0.011f)
            drawRect(Color.White.copy(alpha = 0.08f), size = Size(w, line))
            drawRect(Color.Black.copy(alpha = 0.4f), topLeft = Offset(0f, size.height - line), size = Size(w, line))
        },
    )
}

/**
 * Мягкая тень обложки, ложащейся плоско (вместо системной elevation-тени): размеры — доли ширины,
 * поэтому она одинакова у летящей копии и у обложки в сетке.
 */
internal fun DrawScope.drawSoftCoverShadow(left: Float, top: Float, w: Float, h: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    val radius = w * 0.05f
    val spread = w * 0.06f
    val dy = w * 0.045f
    for (i in 0 until 6) {
        val grow = spread * (1f - i / 5f)
        drawRoundRect(
            Color.Black.copy(alpha = alpha * 0.045f),
            topLeft = Offset(left - grow, top - grow * 0.6f + dy),
            size = Size(w + 2f * grow, h + 1.8f * grow),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius + grow),
        )
    }
}

@Composable
private fun CoverFace(book: CachedBook?, style: PlaceholderStyle) {
    Box(Modifier.fillMaxSize().clip(CoverShape).background(style.palette.card)) {
        if (book != null) CoverImage(book, Modifier.fillMaxSize())
        CoverDecor()
    }
}

private val SpineInk = Color(0xFF0B0D10)

@Composable
private fun SpineFace(book: CachedBook?, style: PlaceholderStyle, index: Int, thickness: Dp) {
    val shape = RoundedCornerShape(3.dp)
    val fontSize = (thickness.value * 0.38f).coerceIn(8f, 10.5f).sp
    Box(Modifier.fillMaxSize().clip(shape).background(Color(0xFF16181E))) {
        if (book != null && book.coverUrl != null) {
            CoverImage(book, Modifier.fillMaxSize(), alignment = Alignment.CenterStart, dim = 0.5f, showTitle = false)
        } else {
            Box(Modifier.fillMaxSize().background(style.palette.card.copy(alpha = 0.85f)))
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.06f),
                        SpineInk.copy(alpha = 0.28f),
                        SpineInk.copy(alpha = 0.38f),
                        Color.Black.copy(alpha = 0.56f),
                    ),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(SpineInk.copy(alpha = 0.52f), SpineInk.copy(alpha = 0.22f), SpineInk.copy(alpha = 0.68f)),
                ),
            ),
        )
        if (book != null) {
            Column(Modifier.fillMaxSize().padding(bottom = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                VerticalTitle(
                    text = book.title,
                    fontSize = fontSize,
                    modifier = Modifier.padding(top = 12.dp).weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f, fill = true))
                Text(
                    text = "%02d".format(index + 1),
                    color = Color(0xFFF4F2EE).copy(alpha = 0.62f),
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 7.5.sp,
                )
            }
        }
        // Грани: светлая слева, тёмная справа, светлая кромка сверху.
        Box(
            Modifier.fillMaxSize().drawBehind {
                val line = 1.dp.toPx()
                drawRect(Color.White.copy(alpha = 0.14f), size = Size(line, size.height))
                drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(size.width - line, 0f), size = Size(line, size.height))
                drawRect(Color.White.copy(alpha = 0.08f), size = Size(size.width, line))
            },
        )
    }
}
