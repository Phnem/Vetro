package com.example.myapplication.audiobooks.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.myapplication.audiobooks.data.CachedBook
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.SnProFamily

/** Размер обложки на полке-ряду и в веере: 2:3 (spec/07 «Геометрия»). */
internal val ShelfCoverWidth = 104.dp
internal val ShelfCoverHeight = 156.dp
internal val CoverRadius = 10.dp

/**
 * Обложка книги: 2:3, радиус 10, мягкая тень. Без картинки — нейтральная плашка с названием, а не
 * выдуманный арт (issues/39).
 */
@Composable
internal fun BookCover(
    book: CachedBook,
    modifier: Modifier = Modifier,
    radius: Dp = CoverRadius,
    elevation: Dp = 6.dp,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .shadow(elevation, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Color(0xFF1C1C1E)),
        contentAlignment = Alignment.Center,
    ) {
        if (book.coverUrl != null) {
            AsyncImage(
                model = book.coverUrl,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = book.title,
                color = Color.White.copy(alpha = 0.72f),
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

/** Поза обложки в веере: крайние повёрнуты на ∓5°, средняя выше на 4 dp и поверх (spec/08). */
internal data class FanPose(val dx: Dp, val dy: Dp, val rotation: Float)

internal val FanPoses = listOf(
    FanPose(dx = (-70).dp, dy = 6.dp, rotation = -5f),
    FanPose(dx = 70.dp, dy = 6.dp, rotation = 5f),
    FanPose(dx = 0.dp, dy = (-4).dp, rotation = 0f),
)

/** Порядок в веере → индекс книги: средняя (третья по отрисовке) — первая книга полки. */
internal val FanBookIndex = listOf(1, 2, 0)

/**
 * Карточка-полка с веером из трёх обложек, как «коллекции» из видео: «обновлено…» и счётчик
 * сверху, название по центру, веер обрезан нижним краем карточки.
 *
 * @param coverModifier модификатор каждой обложки веера по индексу книги — через него морф
 *   узнаёт стартовые позиции и прячет оригиналы на время полёта.
 */
@Composable
internal fun FanShelfCard(
    title: String,
    subtitle: String,
    topStart: String,
    topEnd: String,
    books: List<CachedBook>,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    coverModifier: (bookIndex: Int) -> Modifier = { Modifier },
) {
    val ink = if (isDark) Color.White else Color(0xFF111111)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(IosDesign.rowBackground(isDark))
            .padding(top = 14.dp, start = 16.dp, end = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(topStart, color = ink.copy(alpha = 0.45f), fontSize = 12.sp, fontFamily = SnProFamily)
            Spacer(Modifier.weight(1f))
            Text(topEnd, color = ink.copy(alpha = 0.45f), fontSize = 12.sp, fontFamily = SnProFamily)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            color = ink,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = subtitle,
            color = ink.copy(alpha = 0.55f),
            fontFamily = SnProFamily,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        // Веер выше своей коробки: нижний край обрезает клип карточки, как на видео.
        Box(Modifier.fillMaxWidth().height(118.dp), contentAlignment = Alignment.TopCenter) {
            FanPoses.forEachIndexed { slot, pose ->
                val bookIndex = FanBookIndex[slot]
                val book = books.getOrNull(bookIndex) ?: return@forEachIndexed
                BookCover(
                    book = book,
                    modifier = Modifier
                        .offset(x = pose.dx, y = pose.dy)
                        .size(ShelfCoverWidth * 0.86f, ShelfCoverHeight * 0.86f)
                        .then(coverModifier(bookIndex))
                        .graphicsLayer { rotationZ = pose.rotation },
                )
            }
        }
    }
}

/**
 * Планка полки: полоса 6 dp, тёплый оранжевый на низкой альфе, светлая кромка в 1 физ. пиксель
 * сверху и мягкая тень вниз (spec/08 «Страница полки»). Книги стоят на ней без зазора.
 */
@Composable
internal fun ShelfPlank(isDark: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .drawBehind {
                val plank = 6.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = if (isDark) 0.45f else 0.14f),
                        1f to Color.Transparent,
                        startY = plank,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, plank),
                )
                drawRect(BrandOrange.copy(alpha = if (isDark) 0.22f else 0.30f), size = size.copy(height = plank))
                drawRect(Color.White.copy(alpha = 0.08f), size = size.copy(height = 1f))
            },
    )
}

@Composable
internal fun SectionLabel(text: String, isDark: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = (if (isDark) Color.White else Color(0xFF111111)).copy(alpha = 0.6f),
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = modifier,
    )
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
