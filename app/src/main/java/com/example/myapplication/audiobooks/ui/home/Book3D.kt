package com.example.myapplication.audiobooks.ui.home

import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.example.myapplication.ui.shared.theme.MotionTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

// ==========================================
// Book3D — «моделька» книги: передняя крышка с корешком и сгибом, блок страниц, выглядывающий
// сверху и справа, задняя крышка и контактная тень. Всё рисуется в долях бокса, поэтому модель
// одинаково читается и в веере полки (≈100 dp), и в листе книги (110 dp), и в сетке.
//
// На переднюю грань ложится что угодно: обложка из сети/файла или арт шаблона. Свет и корешок
// рисуются ПОВЕРХ содержимого грани, так что любая обложка автоматически становится объёмной.
// Прототип геометрии: .scratch/book-models/books.html (числа совпадают один в один).
// ==========================================

/** Передняя крышка: доля ширины бокса, доля высоты и сдвиг вниз (сверху выглядывают страницы). */
private const val FACE_W = 0.94f
private const val FACE_H = 0.95f
private const val FACE_Y = 0.05f

/** Скругление крышек — доля ширины бокса. */
private const val CORNER = 0.10f

private val FaceShape = RoundedCornerShape(percent = 11)

private val PagesTop = Color(0xFFFFF9EE)
private val PagesBottom = Color(0xFFE6DCC9)
private val PageLine = Color(0x38786446)

/**
 * Модель книги. [content] заполняет переднюю грань (обрезан по её форме), свет и корешок ложатся
 * сверху. Бокс должен иметь ограниченный размер (`size`, `aspectRatio` и т.п.).
 *
 * @param coverColor основной цвет крышки: из него же — задняя крышка (темнее) и фон грани под
 *   ещё не загруженной картинкой.
 * @param shadowStrength непрозрачность контактной тени под книгой.
 */
@Composable
internal fun Book3D(
    coverColor: Color,
    modifier: Modifier = Modifier,
    shadowStrength: Float = 0.5f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.drawBehind { drawBookBody(coverColor, shadowStrength) }) {
        Box(
            modifier = Modifier
                .bookFace()
                .clip(FaceShape)
                .background(coverColor)
                .drawWithContent {
                    drawContent()
                    drawFaceLighting()
                },
            content = content,
        )
    }
}

/** Ставит переднюю грань в её место внутри бокса: ширина 94 %, высота 95 %, сдвиг вниз 5 %. */
private fun Modifier.bookFace(): Modifier = layout { measurable, constraints ->
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val placeable = measurable.measure(
        Constraints.fixed((w * FACE_W).roundToInt(), (h * FACE_H).roundToInt()),
    )
    layout(w, h) { placeable.place(0, (h * FACE_Y).roundToInt()) }
}

/** Контактная тень, задняя крышка и блок страниц — всё, что лежит ПОД передней гранью. */
private fun DrawScope.drawBookBody(cover: Color, shadowStrength: Float) {
    val w = size.width
    val h = size.height
    // Контактная тень: эллипс под нижним краем, сплюснутый радиальный градиент.
    val shadowRadius = w * 0.56f
    val shadowCenter = Offset(w * 0.5f, h * 0.985f)
    scale(scaleX = 1f, scaleY = 0.14f, pivot = shadowCenter) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.Black.copy(alpha = shadowStrength), Color.Transparent),
                center = shadowCenter,
                radius = shadowRadius,
            ),
            radius = shadowRadius,
            center = shadowCenter,
        )
    }
    // Задняя крышка — тот же цвет, на 42 % ближе к чёрному.
    drawRoundRect(
        color = lerp(cover, Color.Black, 0.42f),
        topLeft = Offset(w * 0.05f, 0f),
        size = Size(w * 0.95f, h * 0.965f),
        cornerRadius = CornerRadius(w * CORNER),
    )
    // Блок страниц: тёплый кремовый, сверху светлее.
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(PagesTop, PagesBottom), startY = 0f, endY = h * 0.08f),
        topLeft = Offset(w * 0.075f, h * 0.014f),
        size = Size(w * 0.90f, h * 0.93f),
        cornerRadius = CornerRadius(w * 0.07f),
    )
    // Обрез страниц: три тонкие линии по верхнему и правому краю.
    val line = max(1f, w * 0.006f)
    repeat(3) { i ->
        val y = h * (0.026f + i * 0.007f)
        drawLine(PageLine, Offset(w * 0.16f, y), Offset(w * 0.93f, y), strokeWidth = line)
        val x = w * (0.952f + i * 0.007f)
        drawLine(PageLine, Offset(x, h * 0.10f), Offset(x, h * 0.90f), strokeWidth = line)
    }
}

/** Свет передней грани поверх содержимого: корешок со сгибом, общий свет, фаски, блик кромки. */
private fun DrawScope.drawFaceLighting() {
    val fw = size.width
    val fh = size.height
    val boxW = fw / FACE_W
    // Корешок: тень у самого края, блик на сгибе, канавка и слабый отсвет за ней.
    val spine = boxW * 0.16f
    drawRect(
        brush = Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.34f),
            0.45f to Color.Black.copy(alpha = 0.06f),
            0.62f to Color.White.copy(alpha = 0.20f),
            0.72f to Color.Black.copy(alpha = 0.20f),
            0.82f to Color.White.copy(alpha = 0.08f),
            1f to Color.Transparent,
            startX = 0f,
            endX = spine,
        ),
        size = Size(spine, fh),
    )
    // Общий свет: сверху-слева светлее, снизу-справа темнее.
    drawRect(
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.16f),
            0.45f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.22f),
            start = Offset.Zero,
            end = Offset(fw, fh),
        ),
    )
    // Фаска снизу и справа — крышка «загибается» к краю.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)),
            startY = fh * 0.93f,
            endY = fh,
        ),
        topLeft = Offset(0f, fh * 0.93f),
        size = Size(fw, fh * 0.07f),
    )
    drawRect(
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.20f)),
            startX = fw * 0.95f,
            endX = fw,
        ),
        topLeft = Offset(fw * 0.95f, 0f),
        size = Size(fw * 0.05f, fh),
    )
    // Блик по кромке крышки.
    val rim = max(1.5f, boxW * 0.012f)
    val inset = boxW * 0.006f + rim / 2
    drawRoundRect(
        color = Color.White.copy(alpha = 0.30f),
        topLeft = Offset(inset, inset),
        size = Size(fw - inset * 2, fh - inset * 2),
        cornerRadius = CornerRadius(fw * 0.11f * 0.9f),
        style = Stroke(rim),
    )
}

// ---- Цвет крышки по обложке ----

/** Средний цвет обложки по адресу: считается один раз за процесс, обложек на экране немного. */
private val coverTints = LruCache<String, Int>(256)

/** Нейтральная крышка, пока обложка грузится или её цвет ещё не посчитан. */
internal val NeutralCover = Color(0xFF2A2A2A)

/**
 * Цвет крышки под обложку: средний цвет картинки, приглушённый к тёмному, — задняя крышка и края
 * выглядят продолжением самой обложки, как у настоящей книги. [bitmap] приходит из onSuccess
 * загрузчика; до него — [NeutralCover].
 */
@Composable
internal fun rememberCoverTint(key: String?, bitmap: Bitmap?): Color {
    var tint by remember(key) { mutableStateOf(key?.let { coverTints.get(it) }?.let(::Color)) }
    LaunchedEffect(key, bitmap) {
        if (key == null || bitmap == null || tint != null) return@LaunchedEffect
        val argb = withContext(Dispatchers.Default) { averageColor(bitmap) } ?: return@LaunchedEffect
        coverTints.put(key, argb)
        tint = Color(argb)
    }
    val target = tint?.let { lerp(it, Color.Black, 0.18f) } ?: NeutralCover
    return animateColorAsState(target, MotionTokens.tweenStandard(), label = "coverTint").value
}

private fun averageColor(bitmap: Bitmap): Int? = runCatching {
    // HARDWARE-битмап нельзя читать по пикселям — сначала программная копия.
    val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
    val soft = if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    Bitmap.createScaledBitmap(soft, 1, 1, true).getPixel(0, 0) or (0xFF shl 24)
}.getOrNull()
