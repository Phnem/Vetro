package com.example.myapplication.audiobooks.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import com.example.myapplication.audiobooks.data.CachedBook
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.SnProFamily
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

// ==========================================
// Новые книжные полки (dev-флаг NEW_SHELVES). Три положения книги, как у реальной полки:
//   1. ≤ 3 книг — тонкие книги стоят под углом: срез обложки вместо корешка, сгиб переплёта,
//      узкая кромка страниц. Угол — настоящая перспектива, как CSS rotateY(-14°) при perspective 760.
//   2. > 3 книг — полка целиком: все книги корешками вперёд, одной ширины, обложка проступает
//      сквозь затемнение, название идёт вдоль корешка.
//   3. Внутри открытой полки — почти плоские книги лицом к зрителю.
// Книга здесь строгая и тонкая: никаких раздутых моделек, один сгиб и одна кромка страниц.
// ==========================================

private val SpineHeight = 168.dp
private val SpineGap = 5.dp
private val SpineMaxWidth = 28.dp
private val SpineMinWidth = 18.dp
private val StripSpineHeight = 118.dp
private val StripSpineWidth = 24.dp
private val SpineShape = RoundedCornerShape(6.dp)

private const val TiltDegrees = -14f
private val CameraDistance = 760.dp

/** Полка до стольких книг показывает их под углом; больше — полная полка корешками. */
internal const val ANGLED_MAX = 3

/** Доля хода морфа, за которую сцена на карточке гаснет, а лента на странице проявляется. */
internal const val StageFadeEnd = 0.3f

/** Высота сцены полки: не прыгает при переходе между «под углом» и «корешками». */
private val StageMinHeight = 176.dp

private val SpineBase = Color(0xFF16181E)
private val SpineInk = Color(0xFF0B0D10)

// ---------- Обложка ----------

/**
 * Обложка книги; без неё (или если не загрузилась) — шаблон в фирменной гамме. [dim] затемняет
 * картинку для корешков, [showTitle] выключают там, где название и так написано отдельно.
 */
@Composable
private fun CoverImage(
    book: CachedBook,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    dim: Float = 0f,
    showTitle: Boolean = true,
) {
    val url = book.coverUrl
    var failed by remember(url) { mutableStateOf(false) }
    val style = remember(book.title) { placeholderStyleFor(book.title) }
    if (url == null || failed) {
        Box(modifier.background(style.palette.card), contentAlignment = Alignment.Center) {
            if (showTitle) {
                Text(
                    text = book.title,
                    color = style.palette.line,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            alignment = alignment,
            onError = { failed = true },
            colorFilter = if (dim > 0f) ColorFilter.tint(Color.Black.copy(alpha = dim), BlendMode.SrcOver) else null,
            modifier = modifier,
        )
    }
}

// ---------- Положение 1: тонкая книга под углом ----------

/**
 * Тонкая книга под углом. [book] == null — пустая заготовка в цвете [style] (полка ещё грузится).
 * Перспектива строится матрицей, а не вращением слоя: тот же результат, что у CSS-эталона, —
 * поворот вокруг левого края, правый край чуть ближе к зрителю.
 */
@Composable
internal fun AngledBook(
    book: CachedBook?,
    style: PlaceholderStyle,
    coverWidth: Dp,
    modifier: Modifier = Modifier,
    /** Метка передней крышки: по ней морф знает, откуда лететь обложке (полки до 3 книг). */
    coverModifier: Modifier = Modifier,
) {
    val coverHeight = coverWidth * 1.5f
    val cameraPx = with(LocalDensity.current) { CameraDistance.toPx() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .height(coverHeight)
                .drawWithContent {
                    val t = Math.toRadians(TiltDegrees.toDouble())
                    // Индексы массива, а не [строка, столбец]: ячейки перспективы названы в самом Matrix.
                    val m = Matrix().apply {
                        values[Matrix.ScaleX] = cos(t).toFloat()
                        values[Matrix.Perspective0] = (sin(t) / cameraPx).toFloat()
                    }
                    withTransform({
                        translate(0f, size.height / 2f)
                        transform(m)
                        translate(0f, -size.height / 2f)
                    }) { this@drawWithContent.drawContent() }
                },
        ) {
            // Корешок: левая полоска обложки, затемнённая, с гранью.
            Box(
                Modifier
                    .width(9.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                    .background(SpineBase),
            ) {
                if (book != null) {
                    CoverImage(book, Modifier.fillMaxSize(), alignment = Alignment.CenterStart, dim = 0.55f, showTitle = false)
                } else {
                    Box(Modifier.fillMaxSize().background(style.palette.card.copy(alpha = 0.6f)))
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            listOf(Color.White.copy(alpha = 0.14f), Color.Transparent, Color.Black.copy(alpha = 0.58f)),
                        ),
                    ),
                )
            }
            // Передняя крышка со сгибом переплёта у левого края.
            Box(
                coverModifier
                    .width(coverWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .background(style.palette.card),
            ) {
                if (book != null) CoverImage(book, Modifier.fillMaxSize())
                Box(
                    Modifier
                        .padding(start = 6.dp)
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Black.copy(alpha = 0.46f), Color.White.copy(alpha = 0.12f)),
                            ),
                        ),
                )
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        val line = 1.dp.toPx()
                        drawRect(Color.White.copy(alpha = 0.08f), size = Size(size.width, line))
                        drawRect(Color.Black.copy(alpha = 0.4f), topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
                    },
                )
            }
            // Кромка страниц: тонкая светлая полоска справа.
            Box(
                Modifier
                    .padding(vertical = 4.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFFC9C4BA), Color(0xFFA5A096))))
                    .alpha(0.75f),
            )
        }
        // Ширина тени считается от книги, а не от родителя: fillMaxWidth(доля) растянул бы
        // колонку книги до доли всей полки и вытолкнул соседние книги за край.
        ContactShadow(Modifier.width((coverWidth + 11.dp) * 0.86f).offset(y = (-2).dp))
    }
}

/** Мягкая контактная тень под книгой: эллипс, густой в центре. */
@Composable
private fun ContactShadow(modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(7.dp)
            .drawBehind {
                val r = size.width / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                scale(1f, size.height / size.width, c) {
                    drawCircle(
                        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent), c, r),
                        r,
                        c,
                    )
                }
            },
    )
}

// ---------- Положение 2: корешки ----------

/** Заголовок вдоль корешка: текст читается сверху вниз, как writing-mode vertical-rl. */
@Composable
private fun VerticalTitle(text: String, fontSize: TextUnit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TextStyle(
            color = Color(0xFFF4F2EE).copy(alpha = 0.94f),
            fontFamily = FontFamily.Serif,
            fontSize = fontSize,
            letterSpacing = 0.4.sp,
            shadow = Shadow(Color.Black.copy(alpha = 0.72f), Offset(0f, 1f), 2f),
        ),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(
                Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth),
            )
            layout(placeable.height, placeable.width) {
                placeable.placeWithLayer(placeable.height, 0) {
                    rotationZ = 90f
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        },
    )
}

/**
 * Корешок: одна ширина и высота у всех, обложка проступает сквозь затемнение, название вдоль
 * корешка, номер внизу. [active] приподнимает корешок и обводит его.
 */
@Composable
internal fun SpineBook(
    book: CachedBook,
    index: Int,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val style = remember(book.title) { placeholderStyleFor(book.title) }
    val lift by animateDpAsState(if (active) (-5).dp else 0.dp, label = "spineLift")
    val fontSize = (width.value * 0.38f).coerceIn(8f, 10.5f).sp
    Box(
        modifier
            .offset(y = lift)
            .size(width, height)
            .shadow(if (active) 8.dp else 4.dp, SpineShape, clip = false)
            .clip(SpineShape)
            .background(SpineBase),
    ) {
        if (book.coverUrl != null) {
            CoverImage(book, Modifier.fillMaxSize(), dim = 0.45f, showTitle = false)
        } else {
            Box(Modifier.fillMaxSize().background(style.palette.card))
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
        // Грани: светлая слева, тёмная справа, светлая кромка сверху.
        Box(
            Modifier.fillMaxSize().drawBehind {
                val line = 1.dp.toPx()
                drawRect(Color.White.copy(alpha = 0.14f), size = Size(line, size.height))
                drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(size.width - line, 0f), size = Size(line, size.height))
                drawRect(Color.White.copy(alpha = 0.08f), size = Size(size.width, line))
            },
        )
        if (active) {
            Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.48f), SpineShape))
        }
    }
}

/**
 * Ряд корешков одной ширины. Ширина подбирается так, чтобы все книги влезли; если и при
 * минимальной ширине не помещаются — ряд прокручивается по горизонтали.
 */
@Composable
internal fun SpineRow(
    books: List<CachedBook>,
    maxSpineWidth: Dp,
    spineHeight: Dp,
    modifier: Modifier = Modifier,
    activeKey: String? = null,
    onSpineClick: ((index: Int, book: CachedBook) -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val n = books.size
        val fit = (maxWidth - SpineGap * (n - 1)) / n
        val spineWidth = fit.coerceIn(SpineMinWidth, maxSpineWidth)
        val scrolls = fit < SpineMinWidth
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(
                SpineGap,
                if (scrolls) Alignment.Start else Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.Bottom,
        ) {
            books.forEachIndexed { index, book ->
                SpineBook(
                    book = book,
                    index = index,
                    width = spineWidth,
                    height = spineHeight,
                    active = activeKey != null && bookKey(book) == activeKey,
                    modifier = if (onSpineClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSpineClick(index, book) }
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

internal fun bookKey(b: CachedBook): String = "${b.source}/${b.key}/${b.title}"

/** Линия полки: тонкая светлая кромка и мягкая тень вниз, края растворяются. */
@Composable
internal fun ShelfBaseline(isDark: Boolean, modifier: Modifier = Modifier) {
    val ink = if (isDark) Color.White else Color.Black
    Box(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .drawBehind {
                val line = 1.dp.toPx()
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.14f to ink.copy(alpha = if (isDark) 0.16f else 0.22f),
                        0.86f to ink.copy(alpha = if (isDark) 0.16f else 0.22f),
                        1f to Color.Transparent,
                    ),
                    size = Size(size.width, line),
                )
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = if (isDark) 0.5f else 0.14f), Color.Transparent),
                        startY = line,
                        endY = size.height,
                    ),
                    topLeft = Offset(size.width * 0.08f, line),
                    size = Size(size.width * 0.84f, size.height - line),
                )
            },
    )
}

// ---------- Карточка полки на главной ----------

/**
 * Карточка полки в списке: подписи сверху, ниже сцена. До 3 книг — под углом, больше — корешками
 * вперёд (все). [stageModifier] нужен морфу: по нему узнаётся, откуда «взлетает» шапка корешков.
 */
@Composable
internal fun ShelfCardV2(
    title: String,
    subtitle: String,
    topStart: String,
    topEnd: String,
    books: List<CachedBook>,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    stageModifier: Modifier = Modifier,
    /** Метка обложки книги [index] на сцене до 3 книг: оттуда стартует полёт обложки в полку. */
    coverModifier: (index: Int) -> Modifier = { Modifier },
) {
    val ink = if (isDark) Color.White else Color(0xFF111111)
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(IosDesign.groupRowBackground(isDark))
            .border(Dp.Hairline, ink.copy(alpha = 0.08f), shape)
            .padding(top = 14.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
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
        Spacer(Modifier.height(18.dp))
        Box(
            stageModifier.fillMaxWidth().heightIn(min = StageMinHeight),
            contentAlignment = Alignment.BottomCenter,
        ) {
            ShelfStage(books, coverModifier)
        }
        ShelfBaseline(isDark)
    }
}

@Composable
private fun ShelfStage(books: List<CachedBook>, coverModifier: (Int) -> Modifier) {
    when {
        books.isEmpty() -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Полка ещё грузится: три пустые книги в фирменных цветах вместо дыры.
            val gap = 14.dp
            val coverWidth = ((maxWidth - gap * 2) / 3 - 11.dp).coerceIn(56.dp, 104.dp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom,
            ) {
                PlaceholderStyles.take(3).forEach { AngledBook(null, it, coverWidth) }
            }
        }
        books.size <= ANGLED_MAX -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            val n = books.size
            val gap = if (n == 3) 14.dp else 24.dp
            val coverWidth = ((maxWidth - gap * (n - 1)) / n - 11.dp).coerceIn(56.dp, 104.dp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom,
            ) {
                books.forEachIndexed { index, book ->
                    AngledBook(
                        book,
                        remember(book.title) { placeholderStyleFor(book.title) },
                        coverWidth,
                        coverModifier = coverModifier(index),
                    )
                }
            }
        }
        else -> SpineRow(books, maxSpineWidth = SpineMaxWidth, spineHeight = SpineHeight)
    }
}

// ---------- Положение 3: почти плоская книга внутри полки ----------

/**
 * Лицо плоской книги: обложка со сгибом слева. Одно и то же рисуется в сетке и в полёте с карточки,
 * поэтому в момент посадки летящая копия совпадает с настоящей без швов.
 */
@Composable
internal fun FlatCover(book: CachedBook, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val style = remember(book.title) { placeholderStyleFor(book.title) }
    Box(
        modifier
            .shadow(8.dp, shape, clip = false)
            .clip(shape)
            .background(style.palette.card),
    ) {
        CoverImage(book, Modifier.fillMaxSize())
        Box(
            Modifier
                .padding(start = 5.dp)
                .width(1.5.dp)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.4f), Color.White.copy(alpha = 0.12f)),
                    ),
                ),
        )
        Box(
            Modifier.fillMaxSize().drawBehind {
                val line = 1.dp.toPx()
                drawRect(Color.White.copy(alpha = 0.1f), size = Size(line, size.height))
                drawRect(Color.White.copy(alpha = 0.07f), size = Size(size.width, line))
            },
        )
    }
}

/** Почти плоская книга лицом к зрителю: карточка 2:3, сгиб слева, узкая кромка страниц справа. */
@Composable
internal fun FlatBook(book: CachedBook, modifier: Modifier = Modifier, coverModifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
        FlatCover(book, coverModifier.weight(1f).fillMaxHeight())
        Box(
            Modifier
                .padding(vertical = 5.dp)
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFFCBC6BC), Color(0xFFA8A399))))
                .alpha(0.78f),
        )
    }
}

/**
 * Страница открытой полки: шапка, лента корешков (навигатор) и сетка плоских книг. Лента «прилетает»
 * с карточки: стартует там, где стояла сцена полки, и садится на место по ходу морфа.
 */
@Composable
internal fun ShelfPageV2(
    shelf: OpenShelf,
    strings: BooksHomeStrings,
    isDark: Boolean,
    morph: ShelfMorph,
    onBook: (CachedBook) -> Unit,
    onClose: () -> Unit,
    /** До 3 книг: без ленты корешков, обложки прилетают с карточки на свои места в сетке. */
    flying: Boolean = false,
) {
    val ink = if (isDark) Color.White else Color(0xFF111111)
    val density = LocalDensity.current
    val rise = with(density) { 24.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var stripTop by remember { mutableFloatStateOf(Float.NaN) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    fun contentAlpha(p: Float) = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = drag }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { d -> drag = (drag + d).coerceAtLeast(0f) },
                            onDragStopped = { v ->
                                val close = com.example.myapplication.ui.shared.theme.MotionTokens.willDismiss(
                                    offset = drag, containerSize = with(density) { 600.dp.toPx() },
                                    velocity = v / density.density,
                                )
                                drag = 0f
                                if (close) onClose()
                            },
                        )
                        .graphicsLayer {
                            val a = contentAlpha(morph.progress.value)
                            alpha = a
                            translationY = rise * (1f - a)
                        },
                ) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .size(36.dp, 5.dp)
                            .clip(CircleShape)
                            .background(ink.copy(alpha = 0.25f))
                            .clickable(onClick = onClose),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        shelf.title, color = ink, fontFamily = SnProFamily, fontWeight = FontWeight.Bold,
                        fontSize = 28.sp, textAlign = TextAlign.Center,
                    )
                    val totalSec = shelf.books.sumOf { it.durationSec ?: 0L }
                    val sub = listOfNotNull(
                        strings.books(shelf.books.size),
                        totalSec.takeIf { it > 0 }?.let { strings.duration(it) },
                    ).joinToString(" · ")
                    Text(sub, color = ink.copy(alpha = 0.55f), fontFamily = SnProFamily, fontSize = 14.sp)
                }
            }
            if (!flying) item(span = { GridItemSpan(maxLineSpan) }) {
                val stripShape = RoundedCornerShape(16.dp)
                Column(
                    Modifier
                        .padding(top = 6.dp, bottom = 6.dp)
                        .fillMaxWidth()
                        .onPlaced { c ->
                            val root = morph.root
                            stripTop = if (root != null && root.isAttached && c.isAttached) {
                                root.localPositionOf(c, Offset.Zero).y + c.size.height / 2f
                            } else {
                                Float.NaN
                            }
                        }
                        .graphicsLayer {
                            val p = morph.progress.value
                            val stage = morph.stage
                            if (stage != null) {
                                // Лента растёт из сцены карточки: центр едет вверх, прозрачность
                                // набирается с нуля, пока сцена на карточке гаснет (alpha без скачка в
                                // первом кадре; пока позиция не известна, ленты ещё не видно).
                                translationY = if (stripTop.isNaN()) 0f else (stage.center.y - stripTop) * (1f - p)
                                val s = lerp(0.9f, 1f, p.coerceIn(0f, 1f))
                                scaleX = s
                                scaleY = s
                                alpha = if (stripTop.isNaN()) 0f else (p / StageFadeEnd).coerceIn(0f, 1f)
                            } else {
                                val a = contentAlpha(p)
                                alpha = a
                                translationY = rise * (1f - a)
                            }
                        }
                        .clip(stripShape)
                        .background(ink.copy(alpha = if (isDark) 0.05f else 0.04f))
                        .border(Dp.Hairline, ink.copy(alpha = 0.1f), stripShape)
                        .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
                ) {
                    Box(Modifier.fillMaxWidth().heightIn(min = StripSpineHeight), contentAlignment = Alignment.BottomCenter) {
                        SpineRow(
                            books = shelf.books,
                            maxSpineWidth = StripSpineWidth,
                            spineHeight = StripSpineHeight,
                            activeKey = selectedKey,
                            onSpineClick = { index, book ->
                                selectedKey = bookKey(book)
                                // Две строки до книг: шапка и лента.
                                scope.launch { gridState.animateScrollToItem(2 + index) }
                            },
                        )
                    }
                    ShelfBaseline(isDark)
                }
            }
            itemsIndexed(shelf.books, key = { _, b -> bookKey(b) }) { index, book ->
                val selected = selectedKey == bookKey(book)
                val cardShape = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .graphicsLayer {
                            val a = contentAlpha(morph.progress.value)
                            alpha = a
                            translationY = rise * (1f - a)
                        }
                        .clip(cardShape)
                        .background(ink.copy(alpha = if (selected) 0.065f else 0.02f))
                        .border(1.dp, if (selected) ink.copy(alpha = 0.24f) else Color.Transparent, cardShape)
                        .clickable { onBook(book) }
                        .padding(10.dp),
                ) {
                    FlatBook(
                        book,
                        Modifier.fillMaxWidth(),
                        // Место посадки летящей обложки; до конца полёта настоящая скрыта.
                        coverModifier = if (flying && index < ShelfMorph.FLYING) {
                            Modifier
                                .onPlaced { morph.targetCoords[index] = it }
                                .graphicsLayer { alpha = if (morph.hidesRow(index)) 0f else 1f }
                        } else {
                            Modifier
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        book.title, color = ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        book.authors.joinToString(", "), color = ink.copy(alpha = 0.55f), fontFamily = SnProFamily,
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
