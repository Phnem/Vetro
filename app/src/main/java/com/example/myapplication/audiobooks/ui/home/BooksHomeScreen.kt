package com.example.myapplication.audiobooks.ui.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.phnem.vetro.R
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.example.myapplication.audiobooks.data.BooksCatalog
import com.example.myapplication.audiobooks.data.CachedBook
import com.example.myapplication.audiobooks.data.ContinueItem
import com.example.myapplication.audiobooks.ui.LocalBooksPanel
import com.example.myapplication.audiobooks.ui.getAudiobookStrings
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.IosScroll
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

// ==========================================
// Дом «Книги» (spec/08, видео-референс): «Продолжить», витрина и полки-вееры. Тап по полке —
// главный переход раздела: обложки веера разворачиваются в ряд на планке страницы полки, как один
// непрерывный объект (закон 1). Весь морф — одна пружина `progress`, каждый элемент считает из
// неё свой transform в draw-фазе; раскладка страницы полки измеряется один раз.
// ==========================================

@UnstableApi
@Composable
fun BooksHomeScreen(
    language: AppLanguage,
    bottomInset: Dp,
    onOverlayVisibleChange: (Boolean) -> Unit,
    onOpenBook: (source: String, key: String, title: String, cover: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: BooksHomeViewModel = koinViewModel()
    val strings = remember(language) { booksHomeStrings(language) }
    val isDark = isAppInDarkTheme()
    val content by vm.content.collectAsStateWithLifecycle()
    val continueItems by vm.continueItems.collectAsStateWithLifecycle()
    val weekMs by vm.weekListenedMs.collectAsStateWithLifecycle()
    val launch by vm.launch.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    val morph = remember { ShelfMorph() }
    var sheetBook by remember { mutableStateOf<CachedBook?>(null) }
    var showLocal by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(morph.open != null || sheetBook != null || showLocal) {
        onOverlayVisibleChange(morph.open != null || sheetBook != null || showLocal)
    }
    // Ошибки запуска без открытого листа (кнопка «Продолжить») — коротким тостом.
    LaunchedEffect(launch) {
        if (sheetBook != null) return@LaunchedEffect
        val text = when (launch) {
            BooksHomeViewModel.LaunchState.Restricted -> strings.restricted
            BooksHomeViewModel.LaunchState.Unavailable -> strings.unavailable
            else -> null
        }
        if (text != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        vm.consumeLaunch()
    }

    // Все полки на месте с первого кадра: пока выдачи нет, карточка — скелет с пустым веером,
    // а не пустой экран, который «появляется» через несколько секунд.
    val shelves = remember(content, strings) {
        buildList {
            val showcase = content[BooksCatalog.SHOWCASE_KEY]
            add(OpenShelf(BooksCatalog.SHOWCASE_KEY, strings.showcaseTitle, strings.showcaseSubtitle,
                showcase?.books.orEmpty(), showcase?.fetchedAt))
            vm.shelves.forEach { s ->
                val cached = content[s.key]
                add(OpenShelf(s.key, s.title, s.subtitle, cached?.books.orEmpty(), cached?.fetchedAt))
            }
        }
    }

    // Свой фон обязателен: без него сквозь страницу просвечивала «вдавленная» соседняя страница
    // рабочей области. Тот же тёплый градиент, что у настроек и страницы серий.
    val background = remember(isDark) { IosDesign.screenGradient(isDark) }
    Box(
        modifier
            .fillMaxSize()
            .background(background)
            .onPlaced { morph.root = it },
    ) {
        HomeList(
            strings = strings,
            isDark = isDark,
            bottomInset = bottomInset,
            continueItems = continueItems,
            weekMs = weekMs,
            shelves = shelves,
            morph = morph,
            onResume = vm::resume,
            failed = failed,
            onRetry = vm::retry,
            onOpenShelf = { shelf -> morph.expand(shelf, scope, reducedMotion) },
            onAddFolder = { showLocal = true },
        )

        morph.open?.let { shelf ->
            ShelfPage(
                shelf = shelf,
                strings = strings,
                isDark = isDark,
                morph = morph,
                // Книга с источником — полноценная страница книги; без источника (витрина) — лист
                // с честным «пока не нашли».
                onBook = { b -> if (b.playable) onOpenBook(b.source, b.key, b.title, b.coverUrl) else sheetBook = b },
                onClose = { morph.collapse(scope, reducedMotion) },
            )
            FlyingCovers(morph, shelf)
        }

        if (showLocal) {
            BackHandler { showLocal = false }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(background)
                    .statusBarsPadding(),
            ) {
                LocalBooksPanel(
                    getAudiobookStrings(language),
                    Modifier.fillMaxSize(),
                    autoOpenPicker = false,
                    onClose = { showLocal = false },
                )
            }
        }

        BookSheet(
            book = sheetBook,
            strings = strings,
            isDark = isDark,
            launch = launch,
            onListen = vm::play,
            onDismiss = { sheetBook = null; vm.consumeLaunch() },
        )
    }
}

// ---------- Список дома ----------

@Composable
private fun HomeList(
    strings: BooksHomeStrings,
    isDark: Boolean,
    bottomInset: Dp,
    continueItems: List<ContinueItem>,
    weekMs: Long,
    shelves: List<OpenShelf>,
    morph: ShelfMorph,
    failed: Set<String>,
    onRetry: (String) -> Unit,
    onResume: (ContinueItem) -> Unit,
    onOpenShelf: (OpenShelf) -> Unit,
    onAddFolder: () -> Unit,
) {
    val ink = if (isDark) Color.White else Color(0xFF111111)
    val lift = with(LocalDensity.current) { 12.dp.toPx() }
    val now = remember(shelves) { System.currentTimeMillis() }
    LazyColumn(
        flingBehavior = IosScroll.flingBehavior(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomInset + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            // Список под раскрытой полкой гаснет и чуть уходит вниз (видео, 2,0–2,6 с).
            .graphicsLayer {
                val p = morph.progress.value
                alpha = 1f - (p / HOME_FADE_END).coerceIn(0f, 1f)
                translationY = lift * p
            },
    ) {
        item(key = "header") {
            Column(Modifier.statusBarsPadding().padding(top = 16.dp, start = 4.dp, end = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.title, color = ink, fontFamily = SnProFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp)
                    Spacer(Modifier.weight(1f))
                    RoundIconButton(
                        icon = R.drawable.ph_folder_plus,
                        description = strings.addFolder,
                        isDark = isDark,
                        onClick = onAddFolder,
                    )
                }
                val subtitle = when {
                    continueItems.size > 1 -> strings.inProgress(continueItems.size)
                    weekMs >= 60_000 -> strings.thisWeek(strings.duration(weekMs / 1000))
                    else -> strings.subtitleEmpty
                }
                Text(subtitle, color = ink.copy(alpha = 0.55f), fontFamily = SnProFamily, fontSize = 15.sp)
            }
        }
        continueItems.firstOrNull()?.let { first ->
            item(key = "continue") { NowPlayingCard(first, strings, onResume = { onResume(first) }) }
        }
        items(shelves, key = { it.key }) { shelf ->
            val topEnd = if (shelf.key == BooksCatalog.SHOWCASE_KEY) {
                strings.books(shelf.books.count { it.playable })
            } else {
                strings.books(shelf.books.size)
            }
            FanShelfCard(
                title = shelf.title,
                subtitle = shelf.subtitle,
                topStart = when {
                    shelf.fetchedAt != null -> strings.updated(strings.ago(now - shelf.fetchedAt))
                    shelf.key in failed -> strings.shelfFailed
                    else -> strings.loading
                },
                topEnd = if (shelf.books.isEmpty()) "" else topEnd,
                books = shelf.books,
                isDark = isDark,
                coverModifier = { index ->
                    Modifier
                        .onPlaced { morph.fanCoords["${shelf.key}#$index"] = it }
                        .graphicsLayer { alpha = if (morph.hidesFan(shelf.key)) 0f else 1f }
                },
                modifier = Modifier
                    .onPlaced { morph.cardCoords[shelf.key] = it }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = shelf.books.isNotEmpty() || shelf.key in failed,
                    ) { if (shelf.books.isEmpty()) onRetry(shelf.key) else onOpenShelf(shelf) },
            )
        }
    }
}

// ---------- Морф полки ----------

@Stable
internal class ShelfMorph {
    var open by mutableStateOf<OpenShelf?>(null)
        private set
    val progress = Animatable(0f)

    // Координаты узлов пишутся без состояния (onPlaced), читаются только в момент перехода: так
    // скролл дома не превращается в поток записей в снапшот (урок stage 3.4 «positions on demand»).
    val fanCoords = HashMap<String, LayoutCoordinates>()
    val cardCoords = HashMap<String, LayoutCoordinates>()
    val targetCoords = HashMap<Int, LayoutCoordinates>()
    var root: LayoutCoordinates? = null

    var from by mutableStateOf<List<Rect?>>(emptyList())
    var to by mutableStateOf<List<Rect?>>(emptyList())
    var card by mutableStateOf<Rect?>(null)

    fun hidesFan(key: String): Boolean = open?.key == key && progress.value > 0.001f

    /** Обложки ряда страницы, прилетевшие из веера, до конца полёта нарисованы летящей копией. */
    fun hidesRow(index: Int): Boolean = progress.value < 0.999f && from.getOrNull(index) != null

    fun expand(shelf: OpenShelf, scope: CoroutineScope, reducedMotion: Boolean) {
        if (open != null) return
        from = (0 until FLYING).map { rectOf(fanCoords["${shelf.key}#$it"]) }
        card = rectOf(cardCoords[shelf.key])
        targetCoords.clear()
        open = shelf
        scope.launch {
            // Кадр на композицию страницы и кадр на раскладку — дальше её позиции известны.
            withFrameNanos { }
            withFrameNanos { }
            to = (0 until FLYING).map { rectOf(targetCoords[it]) }
            if (reducedMotion) progress.snapTo(1f) else progress.animateTo(1f, MotionTokens.largeSurfaceEnter())
        }
    }

    fun collapse(scope: CoroutineScope, reducedMotion: Boolean) {
        val shelf = open ?: return
        from = (0 until FLYING).map { rectOf(fanCoords["${shelf.key}#$it"]) }
        to = (0 until FLYING).map { rectOf(targetCoords[it]) }
        scope.launch {
            if (reducedMotion) progress.snapTo(0f) else progress.animateTo(0f, MotionTokens.largeSurfaceExit())
            open = null
        }
    }

    /** Прямоугольник в координатах этого экрана (не всего окна: страница пейджера сдвинута). */
    private fun rectOf(coords: LayoutCoordinates?): Rect? {
        val r = root ?: return null
        if (coords == null || !coords.isAttached || !r.isAttached) return null
        val bounds = coords.boundsInRoot()
        val origin = r.boundsInRoot().topLeft
        return bounds.translate(-origin)
    }

    companion object {
        const val FLYING = 3
    }
}

internal data class OpenShelf(
    val key: String,
    val title: String,
    val subtitle: String,
    val books: List<CachedBook>,
    /** null — выдачи ещё нет, карточка показывается скелетом. */
    val fetchedAt: Long?,
)

/**
 * Три обложки веера в полёте. Размер — финальный (ряд полки), путь и поворот — transform, так что
 * картинка не перемеряется. Пока полёт не набрал ход, обложки обрезаны карточкой, как в веере.
 */
@Composable
private fun BoxScope.FlyingCovers(morph: ShelfMorph, shelf: OpenShelf) {
    val density = LocalDensity.current
    Box(
        Modifier
            .matchParentSize()
            .drawWithContent {
                val p = morph.progress.value
                if (p <= 0.001f || p >= 0.999f) return@drawWithContent
                val full = Rect(Offset.Zero, size)
                val clip = morph.card?.let { lerp(it, full, (p / 0.3f).coerceIn(0f, 1f)) } ?: full
                clipRect(clip.left, clip.top, clip.right, clip.bottom) { this@drawWithContent.drawContent() }
            },
    ) {
        for (i in 0 until ShelfMorph.FLYING) {
            val book = shelf.books.getOrNull(i) ?: continue
            val from = morph.from.getOrNull(i) ?: continue
            val to = morph.to.getOrNull(i) ?: continue
            val rotation = FanPoses[FanBookIndex.indexOf(i)].rotation
            BookCover(
                book = book,
                modifier = Modifier
                    .size(with(density) { to.width.toDp() }, with(density) { to.height.toDp() })
                    .graphicsLayer {
                        val p = morph.progress.value
                        val c = lerp(from.center, to.center, p)
                        translationX = c.x - to.width / 2f
                        translationY = c.y - to.height / 2f
                        val s = lerp(from.width / to.width, 1f, p)
                        scaleX = s
                        scaleY = s
                        rotationZ = lerp(rotation, 0f, p)
                    },
            )
        }
    }
}

// ---------- Страница полки ----------

@Composable
private fun ShelfPage(
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
    var drag by remember { mutableStateOf(0f) }
    BackHandler(onBack = onClose)

    // Содержимое страницы проявляется, когда обложки уже почти на планке (spec/08, t ≥ 0,4).
    fun contentAlpha(p: Float) = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = drag }
            // Пустые места страницы не пропускают касания к невидимому дому под ней.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                // Шапка — ручка листа: тянется вниз и закрывает страницу (spec/08).
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { d -> drag = (drag + d).coerceAtLeast(0f) },
                            onDragStopped = { v ->
                                val close = MotionTokens.willDismiss(
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
                    Text(shelf.title, color = ink, fontFamily = SnProFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp,
                        textAlign = TextAlign.Center)
                    val totalSec = shelf.books.sumOf { it.durationSec ?: 0L }
                    val sub = listOfNotNull(
                        strings.books(shelf.books.size),
                        totalSec.takeIf { it > 0 }?.let { strings.duration(it) },
                    ).joinToString(" · ")
                    Text(sub, color = ink.copy(alpha = 0.55f), fontFamily = SnProFamily, fontSize = 14.sp)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionLabel(
                    strings.bestOnShelf, isDark,
                    Modifier.graphicsLayer { alpha = contentAlpha(morph.progress.value) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Bottom,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        itemsIndexed(shelf.books.take(TOP_ON_SHELF)) { index, book ->
                            BookCover(
                                book = book,
                                modifier = Modifier
                                    .size(ShelfCoverWidth, ShelfCoverHeight)
                                    .onPlaced { if (index < ShelfMorph.FLYING) morph.targetCoords[index] = it }
                                    .graphicsLayer {
                                        alpha = when {
                                            morph.hidesRow(index) -> 0f
                                            index < ShelfMorph.FLYING -> 1f
                                            // Остальные заезжают из-за правого края каскадом (spec/08, t 0,1).
                                            else -> contentAlpha(morph.progress.value)
                                        }
                                    }
                                    .clickable { onBook(book) },
                            )
                        }
                    }
                    // Планка проявляется от центра (scaleX 0,6 → 1), книги стоят на ней без зазора.
                    ShelfPlank(
                        isDark,
                        Modifier.graphicsLayer {
                            val a = ((morph.progress.value - 0.35f) / 0.4f).coerceIn(0f, 1f)
                            alpha = a
                            scaleX = lerp(0.6f, 1f, a)
                        },
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionLabel(strings.all, isDark, Modifier.graphicsLayer { alpha = contentAlpha(morph.progress.value) })
            }
            itemsIndexed(shelf.books, key = { _, b -> "${b.source}/${b.key}/${b.title}" }) { _, book ->
                Column(
                    Modifier
                        .graphicsLayer {
                            val a = contentAlpha(morph.progress.value)
                            alpha = a
                            translationY = rise * (1f - a)
                        }
                        .clickable { onBook(book) },
                ) {
                    BookCover(book, Modifier.fillMaxWidth().aspectRatio(2f / 3f), radius = 14.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(book.title, color = ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(book.authors.joinToString(", "), color = ink.copy(alpha = 0.55f), fontFamily = SnProFamily,
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------- Мелочи ----------

@Composable
internal fun OrangeButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (enabled) BrandOrange else BrandOrange.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** Круглая кнопка шапки: полупрозрачная над градиентом, как хабы настроек; иконка Phosphor 22 dp. */
@Composable
internal fun RoundIconButton(
    @DrawableRes icon: Int,
    description: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(IosDesign.groupRowBackground(isDark))
            .clickable(onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (isDark) Color.White else Color(0xFF111111),
            modifier = Modifier.size(22.dp),
        )
    }
}

private const val TOP_ON_SHELF = 8

/**
 * Список дома гаснет раньше, чем проявляется содержимое страницы полки (с t 0,4): у страницы нет
 * своей заливки — под обоими общий градиент раздела, и на обратном ходе не остаётся «чёрного окна».
 */
private const val HOME_FADE_END = 0.4f
