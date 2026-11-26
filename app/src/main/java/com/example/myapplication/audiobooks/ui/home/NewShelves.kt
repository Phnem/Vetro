package com.example.myapplication.audiobooks.ui.home

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.ui.geometry.lerp as lerpOffset
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import com.example.myapplication.audiobooks.data.CachedBook
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.SnProFamily
import kotlin.math.cos
import kotlin.math.sin

// ==========================================
// Книжные полки. Книга — настоящая коробка (см. BookBox.kt): обложка и
// корешок как плоскости с перспективой. Три положения:
//   1. ≤ 3 книг — книги стоят под углом: обложка и тонкая полоска корешка.
//   2. > 3 книг — полка целиком: все книги корешками вперёд, одной ширины.
//   3. Внутри открытой полки — почти плоские книги лицом к зрителю.
// При открытии книги поворачиваются из положения 1 или 2 в плоское и разлетаются на свои места в сетке.
// ==========================================

private val SpineHeight = 168.dp
private val SpineGap = 5.dp
private val SpineMaxWidth = 28.dp
private val SpineMinWidth = 18.dp

/** Высота сцены полки: не прыгает при переходе между «под углом» и «корешками». */
private val StageMinHeight = 176.dp

/** Полка до стольких книг показывает их под углом; больше — полная полка корешками. */
internal const val ANGLED_MAX = 3

private val RestTilt: () -> Float = { AngledTilt }
private val RestSpine: () -> Float = { 90f }

// ---------- Обложка ----------

/**
 * Обложка книги; без неё (или если не загрузилась) — шаблон в фирменной гамме. [dim] затемняет
 * картинку для корешков, [showTitle] выключают там, где название и так написано отдельно.
 */
@Composable
internal fun CoverImage(
    book: CachedBook,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    dim: Float = 0f,
    showTitle: Boolean = true,
) {
    val url = book.coverUrl
    var failed by remember(url) { androidx.compose.runtime.mutableStateOf(false) }
    val style = remember(book.title) { placeholderStyleFor(book.title) }
    if (url == null || failed) {
        // Размер подписи и поля — доли ширины обложки: копия, выросшая до размера обложки в сетке,
        // даёт тот же текст, что сама обложка в этом размере (нет скачка размера букв при посадке).
        BoxWithConstraints(modifier.background(style.palette.card), contentAlignment = Alignment.Center) {
            if (showTitle) {
                val w = maxWidth.value
                Text(
                    text = book.title,
                    color = style.palette.line,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = (w * 0.1236f).sp,
                    lineHeight = (w * 0.146f).sp,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = (w * 0.112f).dp),
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

/** Заголовок вдоль корешка: текст читается сверху вниз, как writing-mode vertical-rl. */
@Composable
internal fun VerticalTitle(text: String, fontSize: TextUnit, modifier: Modifier = Modifier) {
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

// ---------- Ряд корешков ----------

/**
 * Ряд корешков одной ширины. Ширина подбирается так, чтобы все книги влезли; если и при
 * минимальной ширине не помещаются — ряд прокручивается по горизонтали. [bookModifier] даёт
 * модификатор книги [index]: по нему морф узнаёт, откуда лететь.
 */
@Composable
internal fun SpineRow(
    books: List<CachedBook>,
    maxSpineWidth: Dp,
    spineHeight: Dp,
    modifier: Modifier = Modifier,
    bookModifier: (index: Int) -> Modifier = { Modifier },
    onFaces: (index: Int, faces: BookFaceLayers) -> Unit = { _, _ -> },
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val n = books.size
        val fit = (maxWidth - SpineGap * (n - 1)) / n
        val spineWidth = fit.coerceIn(SpineMinWidth, maxSpineWidth)
        val scrolls = fit < SpineMinWidth
        val dims = remember(spineWidth, spineHeight) { spineDims(spineWidth, spineHeight) }
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
                BookBox(
                    book = book,
                    style = remember(book.title) { placeholderStyleFor(book.title) },
                    index = index,
                    dims = dims,
                    angle = RestSpine,
                    groundShadow = FullGroundShadow,
                    modifier = Modifier.size(spineWidth, spineHeight).then(bookModifier(index)),
                    onFaces = { onFaces(index, it) },
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
 * вперёд (все). [stageModifier] нужен морфу: по нему узнаётся сцена; [bookModifier] — отдельная книга.
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
    bookModifier: (index: Int) -> Modifier = { Modifier },
    onFaces: (index: Int, faces: BookFaceLayers) -> Unit = { _, _ -> },
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
            ShelfStage(books, bookModifier, onFaces)
        }
        ShelfBaseline(isDark)
    }
}

@Composable
private fun ShelfStage(
    books: List<CachedBook>,
    bookModifier: (Int) -> Modifier,
    onFaces: (Int, BookFaceLayers) -> Unit,
) {
    val tilt = Math.toRadians(AngledTilt.toDouble())
    val sinT = sin(tilt).toFloat()
    val cosT = cos(tilt).toFloat()
    when {
        books.isEmpty() -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Полка ещё грузится: три пустые книги в фирменных цветах вместо дыры.
            val gap = 14.dp
            val footprint = (maxWidth - gap * 2) / 3
            val cover = ((footprint - AngledThickness * sinT) / cosT).coerceIn(56.dp, 104.dp)
            val dims = angledDims(cover)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom,
            ) {
                PlaceholderStyles.take(3).forEachIndexed { index, style ->
                    BookBox(
                        book = null,
                        style = style,
                        index = index,
                        dims = dims,
                        angle = RestTilt,
                        groundShadow = FullGroundShadow,
                        modifier = Modifier.size(dims.footprintWidth(AngledTilt), dims.height),
                    )
                }
            }
        }
        books.size <= ANGLED_MAX -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            val n = books.size
            val gap = if (n == 3) 14.dp else 24.dp
            val footprint = (maxWidth - gap * (n - 1)) / n
            val cover = ((footprint - AngledThickness * sinT) / cosT).coerceIn(56.dp, 104.dp)
            val dims = angledDims(cover)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom,
            ) {
                books.forEachIndexed { index, book ->
                    BookBox(
                        book = book,
                        style = remember(book.title) { placeholderStyleFor(book.title) },
                        index = index,
                        dims = dims,
                        angle = RestTilt,
                        groundShadow = FullGroundShadow,
                        modifier = Modifier
                            .size(dims.footprintWidth(AngledTilt), dims.height)
                            .then(bookModifier(index)),
                        onFaces = { onFaces(index, it) },
                    )
                }
            }
        }
        else -> SpineRow(
            books,
            maxSpineWidth = SpineMaxWidth,
            spineHeight = SpineHeight,
            bookModifier = bookModifier,
            onFaces = onFaces,
        )
    }
}

// ---------- Положение 3: почти плоская книга внутри полки ----------

/**
 * Лицо плоской книги: обложка со сгибом слева. Одно и то же рисуется в сетке и в полёте с карточки,
 * поэтому в момент посадки летящая копия совпадает с настоящей без швов.
 */
@Composable
internal fun FlatCover(book: CachedBook, modifier: Modifier = Modifier) {
    val style = remember(book.title) { placeholderStyleFor(book.title) }
    Box(
        modifier
            // Обычная elevation-тень (shadow()) отличается от тени летящей копии: рисуем ту же, что у копии.
            .drawBehind { drawSoftCoverShadow(0f, 0f, size.width, size.height, 1f) }
            .clip(CoverShape)
            .background(style.palette.card),
    ) {
        CoverImage(book, Modifier.fillMaxSize())
        CoverDecor()
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
 * Страница открытой полки: шапка и сетка плоских книг. Книги сюда прилетают с карточки
 * ([FlyingBooks]): пока идёт полёт, книга в сетке скрыта, а место посадки измеряется по её обложке.
 */
@Composable
internal fun ShelfPageV2(
    shelf: OpenShelf,
    strings: BooksHomeStrings,
    isDark: Boolean,
    morph: ShelfMorph,
    onBook: (CachedBook) -> Unit,
    onClose: () -> Unit,
) {
    val ink = if (isDark) Color.White else Color(0xFF111111)
    val density = LocalDensity.current
    val rise = with(density) { 24.dp.toPx() }
    val captionRise = with(density) { 6.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    BackHandler(onBack = onClose)

    fun contentAlpha(p: Float) = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = drag }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
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
                    Spacer(Modifier.height(14.dp))
                }
            }
            itemsIndexed(shelf.books, key = { _, b -> bookKey(b) }) { index, book ->
                val cardShape = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .graphicsLayer {
                            val a = contentAlpha(morph.progress.value)
                            alpha = a
                            // Карточки с летящими книгами не сдвигаются: место посадки измеряется до
                            // начала полёта, и сдвиг на 24 dp на старте уводил бы копию мимо места, где
                            // обложка окажется в конце (это и был щелчок при посадке).
                            if (index >= ShelfMorph.TRACKED) translationY = rise * (1f - a)
                        }
                        .clip(cardShape)
                        .background(ink.copy(alpha = 0.02f))
                        .clickable { onBook(book) }
                        .padding(10.dp),
                ) {
                    FlatBook(
                        book,
                        // Обложка и кромка страниц появляются вместе при посадке.
                        modifier = if (index < ShelfMorph.TRACKED) {
                            Modifier.graphicsLayer { alpha = morph.gridCoverAlpha(index) }
                        } else {
                            Modifier
                        },
                        // Летящая копия совмещается именно с обложкой, без учёта кромки страниц.
                        coverModifier = if (index < ShelfMorph.TRACKED) {
                            Modifier.onPlaced { morph.targetCoords[index] = it }
                        } else {
                            Modifier
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    // Подпись держится прозрачной, пока книга летит, и проявляется на последних 20%
                    // её пути: садящаяся книга не перекрывает уже видимый текст.
                    Column(
                        Modifier.graphicsLayer {
                            val a = if (index < ShelfMorph.TRACKED) morph.captionAlpha(index) else 1f
                            alpha = a
                            translationY = captionRise * (1f - a)
                        },
                    ) {
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
}

// ---------- Полёт книг ----------

/**
 * Книги в полёте: отдельный слой поверх дома и страницы на всё время перехода. Каждая книга
 * (коробка из [BookBox]) стартует там, где стояла на карточке, поворачивается из угла полки
 * (под углом или корешком) в плоское положение и садится на своё место в сетке. Книги стартуют
 * с небольшой задержкой друг за другом, поэтому полка не «схлопывается» разом, а разлетается.
 */
@Composable
internal fun BoxScope.FlyingBooks(morph: ShelfMorph, shelf: OpenShelf) {
    val density = LocalDensity.current
    val spined = shelf.books.size > ANGLED_MAX
    val restAngle = if (spined) 90f else AngledTilt
    Box(Modifier.matchParentSize()) {
        for (i in 0 until minOf(shelf.books.size, ShelfMorph.TRACKED)) {
            val book = shelf.books[i]
            val from = morph.from.getOrNull(i) ?: continue
            // Грани берутся готовыми со стоящей на карточке книги: летящая копия не собирает ни
            // картинки, ни текст заново, поэтому совпадает с ней до пикселя и не тратит кадр на сборку.
            val faces = morph.faceLayers["${shelf.key}#$i"]?.takeUnless { it.cover.isReleased || it.spine.isReleased }
                ?: continue
            val fromWidth = with(density) { from.width.toDp() }
            val fromHeight = with(density) { from.height.toDp() }
            val dims = if (spined) spineDims(fromWidth, fromHeight) else angledDims(fromHeight / 1.5f)
            val style = remember(book.title) { placeholderStyleFor(book.title) }
            val delay = minOf(i, 8) * 0.028f
            val coverWidthPx = with(density) { dims.width.toPx() }
            // Узел больше самой книги: при растворении (alpha < 1) слой не должен обрезать обложку,
            // которая шире, чем след корешка, и перспективу у краёв.
            val nodeWidth = maxOf(dims.width, fromWidth) + 16.dp
            val nodeHeight = dims.height + 16.dp
            val nodeWidthPx = with(density) { nodeWidth.toPx() }
            val nodeHeightPx = with(density) { nodeHeight.toPx() }
            // Клип по карточке: на подходе к полке (и на старте) книга не вылезает ниже её нижней
            // кромки, дальше от полки окно плавно раскрывается на весь экран.
            Box(
                Modifier.matchParentSize().drawWithContent {
                    val card = morph.card
                    val k = ((morph.bookProgress(i) - 0.35f) / 0.3f).coerceIn(0f, 1f)
                    val spread = k * k * (3f - 2f * k)
                    val bottom = if (card == null) size.height else lerp(card.bottom, size.height, spread)
                    clipRect(left = 0f, top = 0f, right = size.width, bottom = bottom) {
                        this@drawWithContent.drawContent()
                    }
                },
            ) {
                BookBox(
                    book = book,
                    style = style,
                    index = i,
                    dims = dims,
                    angle = {
                        val p = ((morph.progress.value - delay) / (1f - delay)).coerceIn(0f, 1f)
                        // Поворот заканчивается на 88% пути: последние кадры книга доезжает уже
                        // плоской, без сброса перспективы на посадке.
                        val t = (p / 0.88f).coerceIn(0f, 1f)
                        restAngle * (1f - t * t * (3f - 2f * t))
                    },
                    groundShadow = { 1f - morph.bookProgress(i) },
                    // К посадке копия отбрасывает ту же тень, что обложка в сетке.
                    coverShadow = {
                        val t = ((morph.bookProgress(i) - 0.5f) / 0.5f).coerceIn(0f, 1f)
                        t * t * (3f - 2f * t)
                    },
                    shadowSpan = fromWidth,
                    faces = faces,
                    modifier = Modifier
                        .size(nodeWidth, nodeHeight)
                        .graphicsLayer {
                            val to = morph.to.getOrNull(i)
                            val p = ((morph.progress.value - delay) / (1f - delay)).coerceAtLeast(0f)
                            val target = to ?: from
                            val c = lerpOffset(from.center, target.center, if (to == null) 0f else p)
                            translationX = c.x - nodeWidthPx / 2f
                            translationY = c.y - nodeHeightPx / 2f
                            if (to != null) {
                                scaleX = lerp(1f, to.width / coverWidthPx, p)
                                scaleY = lerp(1f, to.height / from.height, p)
                            }
                            // Копия проявляется поверх стоящей книги и растворяется поверх настоящей
                            // обложки сетки; в остальное время её не видно.
                            alpha = if (to == null) 0f else morph.overlayAlpha(i)
                        },
                )
            }
        }
    }
}
