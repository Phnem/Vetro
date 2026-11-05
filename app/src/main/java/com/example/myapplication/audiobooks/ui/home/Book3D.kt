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

/**
 * Передняя крышка в долях бокса: слева её место занимает переплёт (корешок-цилиндр во всю высоту),
 * сверху — обрез страниц.
 */
private const val FACE_X = 0.13f
private const val FACE_Y = 0.065f
private const val FACE_W = 0.85f
private const val FACE_H = 0.935f

/** Ширина корешка — доля ширины бокса. */
private const val SPINE_W = 0.19f

/** Скругление крышки: около 7 процентов ширины бокса (8 — её собственной ширины). */
private val FaceShape = RoundedCornerShape(percent = 8)

private val PagesTop = Color(0xFFFFFBF2)
private val PagesBottom = Color(0xFFD9CDB6)
private val PageLine = Color(0x40786446)

/** Цвет светлее (k > 0, к белому) или темнее (k < 0, к чёрному). */
private fun Color.shade(k: Float): Color = if (k >= 0f) lerp(this, Color.White, k) else lerp(this, Color.Black, -k)

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

/** Ставит переднюю крышку в её место внутри бокса: правее корешка, ниже обреза страниц. */
private fun Modifier.bookFace(): Modifier = layout { measurable, constraints ->
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val placeable = measurable.measure(
        Constraints.fixed((w * FACE_W).roundToInt(), (h * FACE_H).roundToInt()),
    )
    layout(w, h) { placeable.place((w * FACE_X).roundToInt(), (h * FACE_Y).roundToInt()) }
}

/** Всё, что лежит ПОД передней крышкой: тень, задняя крышка, обрез страниц и переплёт. */
private fun DrawScope.drawBookBody(cover: Color, shadowStrength: Float) {
    val w = size.width
    val h = size.height
    // Контактная тень: эллипс под нижним краем, сплюснутый радиальный градиент.
    val shadowRadius = w * 0.58f
    val shadowCenter = Offset(w * 0.52f, h * 0.99f)
    scale(scaleX = 1f, scaleY = 0.13f, pivot = shadowCenter) {
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
    // Задняя крышка: выглядывает над страницами и справа.
    drawRoundRect(
        color = cover.shade(-0.38f),
        topLeft = Offset(w * 0.08f, 0f),
        size = Size(w * 0.92f, h * 0.95f),
        cornerRadius = CornerRadius(w * 0.08f),
    )
    // Блок страниц: верхний обрез, свет сверху, тень к крышке.
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(PagesTop, PagesBottom), startY = h * 0.02f, endY = h * 0.09f),
        topLeft = Offset(w * 0.11f, h * 0.022f),
        size = Size(w * 0.86f, h * 0.90f),
        cornerRadius = CornerRadius(w * 0.05f),
    )
    val line = max(1f, w * 0.005f)
    repeat(4) { i ->
        val y = h * (0.034f + i * 0.008f)
        drawLine(PageLine, Offset(w * 0.2f, y), Offset(w * 0.95f, y), strokeWidth = line)
    }
    // Переплёт: цилиндр корешка во всю высоту книги — тёмный край, блик, тень к суставу.
    val spineW = w * SPINE_W
    val spineTop = Offset(0f, h * 0.004f)
    val spineSize = Size(spineW, h * 0.992f)
    val spineCorner = CornerRadius(w * 0.085f)
    drawRoundRect(
        brush = Brush.horizontalGradient(
            0f to cover.shade(-0.55f),
            0.16f to cover.shade(-0.15f),
            0.32f to cover.shade(0.22f),
            0.5f to cover,
            0.85f to cover.shade(-0.30f),
            1f to cover.shade(-0.45f),
            startX = 0f,
            endX = spineW,
        ),
        topLeft = spineTop,
        size = spineSize,
        cornerRadius = spineCorner,
    )
    // Капталы: свет на верхнем скруглении корешка, тень на нижнем.
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.30f),
            0.05f to Color.Transparent,
            0.93f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.35f),
            startY = 0f,
            endY = h,
        ),
        topLeft = spineTop,
        size = spineSize,
        cornerRadius = spineCorner,
    )
}

/** Свет передней крышки поверх содержимого: сустав переплёта, общий свет, толщина, блик кромки. */
private fun DrawScope.drawFaceLighting() {
    val fw = size.width
    val fh = size.height
    val boxW = fw / FACE_W
    // Сустав: крышка выходит из корешка — тень, канавка, отсвет за ней.
    val hinge = boxW * 0.09f
    drawRect(
        brush = Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.42f),
            0.42f to Color.Black.copy(alpha = 0.10f),
            0.50f to Color.Black.copy(alpha = 0.30f),
            0.58f to Color.White.copy(alpha = 0.18f),
            1f to Color.Transparent,
            startX = 0f,
            endX = hinge,
        ),
        size = Size(hinge, fh),
    )
    // Общий свет: сверху-слева светлее, снизу-справа темнее.
    drawRect(
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.14f),
            0.5f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.20f),
            start = Offset.Zero,
            end = Offset(fw, fh),
        ),
    )
    // Толщина крышки: жёсткая тёмная кромка снизу и справа.
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            0.15f to Color.Black.copy(alpha = 0.32f),
            1f to Color.Black.copy(alpha = 0.45f),
            startY = fh * 0.955f,
            endY = fh,
        ),
        topLeft = Offset(0f, fh * 0.955f),
        size = Size(fw, fh * 0.045f),
    )
    drawRect(
        brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.2f to Color.Black.copy(alpha = 0.25f),
            1f to Color.Black.copy(alpha = 0.40f),
            startX = fw * 0.965f,
            endX = fw,
        ),
        topLeft = Offset(fw * 0.965f, 0f),
        size = Size(fw * 0.035f, fh),
    )
    // Блик верхней кромки.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.38f), Color.Transparent),
            startY = 0f,
            endY = fh * 0.03f,
        ),
        size = Size(fw, fh * 0.03f),
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
