package com.example.myapplication.audiobooks.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.toBitmap
import com.example.myapplication.audiobooks.data.CachedBook
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.SnProFamily

/** Размер обложки на полке-ряду и в веере: 2:3 (spec/07 «Геометрия»). */
internal val ShelfCoverWidth = 104.dp
internal val ShelfCoverHeight = 156.dp
internal val CoverRadius = 10.dp

/**
 * Прогресс книги для капсулы на обложке («Глава 8 · 37%»); null — книгу ещё не слушали. Экран книг
 * строит это один раз из списка «в процессе», обложка читает сама — так капсула есть везде, где
 * рисуется обложка (полка, сетка, лист книги), без протаскивания параметра через каждый вызов.
 */
internal val LocalBookProgress = androidx.compose.runtime.staticCompositionLocalOf<(CachedBook) -> String?> { { null } }

/**
 * Книга-моделька ([Book3D]): обложка сама ложится на переднюю грань, корешок, страницы и свет
 * дорисовываются сверху, задняя крышка берёт средний цвет обложки. Без обложки (или если она не
 * загрузилась) — шаблон в фирменной гамме с названием книги на грани.
 *
 * @param elevation сила контактной тени под книгой (6 dp — обычная полка).
 */
@Composable
internal fun BookCover(
    book: CachedBook,
    modifier: Modifier = Modifier,
    elevation: Dp = 6.dp,
) {
    val coverUrl = book.coverUrl
    var loaded by remember(coverUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(coverUrl) { mutableStateOf(false) }
    val style = remember(book.title) { placeholderStyleFor(book.title) }
    val tint = rememberCoverTint(coverUrl, loaded)
    val showArt = coverUrl == null || failed
    Book3D(
        coverColor = if (showArt) style.palette.card else tint,
        modifier = modifier,
        shadowStrength = (0.2f + elevation.value * 0.05f).coerceAtMost(0.6f),
    ) {
        if (showArt) {
            PlaceholderArt(style, title = book.title)
        } else {
            AsyncImage(
                model = coverUrl,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                onSuccess = { state -> loaded = state.result.image.toBitmap() },
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        LocalBookProgress.current(book)?.let { label ->
            Text(
                text = label,
                color = Color.White,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 6.dp, vertical = 7.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** Три карточки с постера «Recommended»: оранжевая, кремовая, тёмная. */
internal enum class PlaceholderStyle { ORANGE, CREAM, DARK }

/** Три цвета заглушек: ими же рисуются пустые книги, пока полка грузится. */
internal val PlaceholderStyles = listOf(PlaceholderStyle.ORANGE, PlaceholderStyle.DARK, PlaceholderStyle.CREAM)

private val PlaceholderCream = Color(0xFFF5ECDC)
private val PlaceholderInk = Color(0xFF1A1A1A)

internal data class PlaceholderPalette(val card: Color, val circle: Color, val line: Color)

internal val PlaceholderStyle.palette: PlaceholderPalette
    get() = when (this) {
        PlaceholderStyle.ORANGE -> PlaceholderPalette(Color(0xFFF55800), PlaceholderInk, PlaceholderCream)
        PlaceholderStyle.CREAM -> PlaceholderPalette(PlaceholderCream, BrandOrange, PlaceholderInk)
        PlaceholderStyle.DARK -> PlaceholderPalette(PlaceholderInk, BrandOrange, PlaceholderCream)
    }

/** Книга без обложки получает стиль по названию: один и тот же на всех экранах. */
internal fun placeholderStyleFor(title: String): PlaceholderStyle =
    PlaceholderStyle.entries[Math.floorMod(title.hashCode(), PlaceholderStyle.entries.size)]

/**
 * Шаблон-моделька для места, где книги ещё нет (загрузка, полка из одной-двух книг): круг и три
 * строки текста, как на постере.
 */
@Composable
internal fun PlaceholderBook(style: PlaceholderStyle, modifier: Modifier = Modifier) {
    Book3D(coverColor = style.palette.card, modifier = modifier) {
        PlaceholderArt(style, title = null)
    }
}

/**
 * Арт передней грани шаблона. Без [title] — круг и три полосы-«строки»; с названием — круг
 * поменьше, а вместо полос само название, чтобы книгу без обложки можно было узнать.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PlaceholderArt(style: PlaceholderStyle, title: String?) {
    val p = style.palette
    // Слева на крышке — сустав переплёта: арт центрируется по остальной её части.
    Canvas(Modifier.fillMaxSize()) {
        val fw = size.width
        val fh = size.height
        val ax = fw * 0.008f
        val aw = fw - ax
        if (title == null) {
            val center = Offset(fw * 0.475f, fh * 0.337f)
            // The printed disc appears nearly circular on the photographed front board.
            val shear = if (fw > 0f) 0.07f * fh / fw else 0f
            val discProjection = Matrix().apply {
                this[0, 1] = shear
                this[3, 1] = -center.x * shear
            }
            withTransform({ transform(discProjection) }) {
                drawCircle(p.circle, radius = fw * 0.23f, center = center)
            }
            val bar = fh * 0.046f
            listOf(0.61f to 0.74f, 0.717f to 0.74f, 0.824f to 0.49f).forEach { (y, width) ->
                drawRoundRect(
                    p.line,
                    topLeft = Offset(ax + aw * 0.13f, fh * y),
                    size = Size(aw * width, bar),
                    cornerRadius = CornerRadius(bar / 2),
                )
            }
        } else {
            drawCircle(p.circle, radius = fw * 0.17f, center = Offset(ax + aw * 0.5f, fh * 0.27f))
        }
    }
    if (title != null) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            Text(
                text = title,
                color = p.line,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (maxWidth.value * 0.105f).sp,
                lineHeight = (maxWidth.value * 0.125f).sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = maxWidth * 0.16f, end = maxWidth * 0.08f, bottom = maxHeight * 0.1f),
            )
        }
    }
}

@Composable
internal fun ProgressLine(fraction: Float, isDark: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background((if (isDark) Color.White else Color.Black).copy(alpha = 0.12f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(BrandOrange),
        )
    }
}
