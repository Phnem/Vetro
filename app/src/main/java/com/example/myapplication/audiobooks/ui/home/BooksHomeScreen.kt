package com.example.myapplication.audiobooks.ui.home

import com.example.myapplication.ui.shared.mininotif.MiniNotif
import androidx.compose.ui.unit.toSize
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
import androidx.compose.runtime.CompositionLocalProvider
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
    // Открытая полка переживает уход на страницу книги: экран при этом покидает композицию, и
    // «назад» возвращал на общий список полок вместо той, с которой открыли книгу.
    var openShelfKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var sheetBook by remember { mutableStateOf<CachedBook?>(null) }
    val context = LocalContext.current
    val library by vm.library.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    // «Добавить» сразу открывает системный выбор папки; выбранная папка уходит в библиотеку.
    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(vm::importFolder) }

    LaunchedEffect(morph.open != null || sheetBook != null) {
        onOverlayVisibleChange(morph.open != null || sheetBook != null)
    }
    // Ошибки запуска без открытого листа (кнопка «Продолжить») — коротким тостом.
    LaunchedEffect(launch) {
        if (sheetBook != null) return@LaunchedEffect
        val text = when (launch) {
            BooksHomeViewModel.LaunchState.Restricted -> strings.restricted
            BooksHomeViewModel.LaunchState.Unavailable -> strings.unavailable
            else -> null
        }
        if (text != null) MiniNotif.show(context, text)
        vm.consumeLaunch()
    }

    // Все полки на месте с первого кадра: пока выдачи нет, карточка — скелет с пустым веером,
    // а не пустой экран, который «появляется» через несколько секунд.
    // С первой своей книгой дом — это библиотека: полка на автора. До того — витрина источников.
    val ownLibrary = library?.takeIf { books -> books.any { it.own } }
    val libraryByKey = remember(ownLibrary) {
        ownLibrary.orEmpty().mapNotNull { b -> b.variantId?.let { it.value to b } }.toMap()
    }
    val shelves = remember(content, strings, ownLibrary) {
        if (ownLibrary != null) return@remember authorShelves(ownLibrary, strings)
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

    // Вернулись со страницы книги — полка снова открыта, сразу, без полёта обложек.
    LaunchedEffect(shelves, openShelfKey) {
        val key = openShelfKey ?: return@LaunchedEffect
        if (morph.open != null) return@LaunchedEffect
        shelves.firstOrNull { it.key == key && it.books.isNotEmpty() }?.let { morph.restore(it) }
    }

    // Свой фон обязателен: без него сквозь страницу просвечивала «вдавленная» соседняя страница
    // рабочей области. Тот же тёплый градиент, что у настроек и страницы серий.
    val background = remember(isDark) { IosDesign.screenGradient(isDark) }
    // «Глава 8 · 37%» на обложках начатых книг — по сохранённому месту (source:key → прогресс).
    val progressLabels = remember(continueItems, strings) {
        continueItems.mapNotNull { item ->
            val key = item.variantId?.value ?: return@mapNotNull null
            val percent = item.totalMs?.takeIf { it > 0 }?.let { (item.globalMs * 100 / it).coerceIn(0, 100) }
            val label = buildString {
                append(strings.chapter(item.chapterIndex.coerceAtLeast(0) + 1))
                percent?.let { append(" · ").append(it).append('%') }
            }
            key to label
        }.toMap()
    }
    CompositionLocalProvider(LocalBookProgress provides { book -> progressLabels["${book.source}:${book.key}"] }) {
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
            onOpenShelf = { shelf ->
                openShelfKey = shelf.key
                morph.expand(shelf, scope, reducedMotion)
            },
            onAddFolder = { runCatching { folderPicker.launch(null) } },
            importing = importing > 0,
        )

        morph.open?.let { shelf ->
            val openBook: (CachedBook) -> Unit = { b ->
                val own = libraryByKey["${b.source}:${b.key}"]
                when {
                    // Своя папка: страница собирается из БД и файлов, у сайта её нет.
                    own != null && own.local && own.variantId != null ->
                        onOpenBook(com.example.myapplication.audiobooks.data.LOCAL_SOURCE_ID, own.variantId.value, own.title, own.coverUrl)
                    own != null && own.local -> vm.playLibrary(own)
                    b.playable -> onOpenBook(b.source, b.key, b.title, b.coverUrl)
                    else -> sheetBook = b
                }
            }
            val closeShelf: () -> Unit = {
                openShelfKey = null
                morph.collapse(scope, reducedMotion)
            }
            // Книги (стоящие под углом или корешками) поворачиваются в плоское положение и
            // разлетаются на свои места в сетке.
            ShelfPageV2(
                shelf = shelf,
                strings = strings,
                isDark = isDark,
                morph = morph,
                onBook = openBook,
                onClose = closeShelf,
            )
            FlyingBooks(morph, shelf)
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
    /** Второй аргумент — сразу открыть полный плеер (тап по карточке), а не только запустить. */
    onResume: (ContinueItem, Boolean) -> Unit,
    onOpenShelf: (OpenShelf) -> Unit,
    onAddFolder: () -> Unit,
    importing: Boolean = false,
) {
    val ink = if (isDark) Color.White else Color(0xFF111111)
    val now = remember(shelves) { System.currentTimeMillis() }
    LazyColumn(
        flingBehavior = IosScroll.flingBehavior(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomInset + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            // Список под раскрытой полкой гаснет; при закрытии возвращается раньше (с 65% пути), чтобы
            // к посадке книг подложка карточки и нижняя панель были уже видны. Список не сдвигается:
            // место возврата книги измеряется на старте закрытия.
            .graphicsLayer {
                val end = if (morph.closing) HOME_FADE_CLOSE_END else HOME_FADE_END
                alpha = 1f - (morph.progress.value / end).coerceIn(0f, 1f)
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
                    importing -> strings.adding
                    continueItems.size > 1 -> strings.inProgress(continueItems.size)
                    weekMs >= 60_000 -> strings.thisWeek(strings.duration(weekMs / 1000))
                    else -> strings.subtitleEmpty
                }
                Text(subtitle, color = ink.copy(alpha = 0.55f), fontFamily = SnProFamily, fontSize = 15.sp)
            }
        }
        continueItems.firstOrNull()?.let { first ->
            item(key = "continue") {
                NowPlayingCard(
                    first,
                    strings,
                    onResume = { onResume(first, false) },
                    onOpen = { onResume(first, true) },
                )
            }
        }
        items(shelves, key = { it.key }) { shelf ->
            val topEnd = if (shelf.key == BooksCatalog.SHOWCASE_KEY) {
                strings.books(shelf.books.count { it.playable })
            } else {
                strings.books(shelf.books.size)
            }
            val cardTopStart = when {
                shelf.library -> ""
                shelf.fetchedAt != null -> strings.updated(strings.ago(now - shelf.fetchedAt))
                shelf.key in failed -> strings.shelfFailed
                else -> strings.loading
            }
            val cardClick = Modifier
                .onPlaced { morph.cardCoords[shelf.key] = it }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = shelf.books.isNotEmpty() || shelf.key in failed,
                ) { if (shelf.books.isEmpty()) onRetry(shelf.key) else onOpenShelf(shelf) }
            // До 3 книг под углом, больше — корешками вперёд, внутри полки — почти плоско.
            ShelfCardV2(
                title = shelf.title,
                subtitle = shelf.subtitle,
                topStart = cardTopStart,
                topEnd = if (shelf.books.isEmpty()) "" else topEnd,
                books = shelf.books,
                isDark = isDark,
                // Каждая книга сообщает морфу, откуда ей лететь, и скрывается на время полёта:
                // вместо неё по экрану летит её копия (FlyingBooks).
                bookModifier = { index ->
                    Modifier
                        .onPlaced { morph.fanCoords["${shelf.key}#$index"] = it }
                        .graphicsLayer { alpha = morph.cardBookAlpha(shelf.key, index) }
                },
                onFaces = { index, faces -> morph.faceLayers["${shelf.key}#$index"] = faces },
                modifier = cardClick,
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

    /** Слои граней книг на карточках (новые полки): летящая копия рисует именно их. */
    val faceLayers = HashMap<String, BookFaceLayers>()
    val cardCoords = HashMap<String, LayoutCoordinates>()
    val targetCoords = HashMap<Int, LayoutCoordinates>()
    var root: LayoutCoordinates? = null

    var from by mutableStateOf<List<Rect?>>(emptyList())
    var to by mutableStateOf<List<Rect?>>(emptyList())
    var card by mutableStateOf<Rect?>(null)

    /** Закрытие идёт: список возвращается раньше (с 65% пути), а не после остановки книг. */
    var closing by mutableStateOf(false)
        private set

    /**
     * Передача между летящей копией и настоящей обложкой сетки: 0 — виден только полёт, 1 — только
     * сетка. Идёт по времени уже после остановки книг (книги и обложки стоят на одном месте), а не
     * по хвосту пружины: хвост растягивает кроссфейд и даёт двойной контур.
     */
    val settle = Animatable(0f)

    // ---- Новые полки: прогресс каждой книги и передача между слоями -------------------------
    // Книги стартуют по очереди (задержка по индексу), поэтому «приземлилась» у каждой своё время.
    // Всё, что зависит от посадки или старта (подмена слоёв, подписи), считается от прогресса
    // именно этой книги, а не общего.

    private fun bookDelay(index: Int): Float = minOf(index, 8) * 0.028f

    fun bookProgress(index: Int): Float {
        val d = bookDelay(index)
        return ((progress.value - d) / (1f - d)).coerceIn(0f, 1f)
    }

    private fun flies(index: Int): Boolean = from.getOrNull(index) != null && to.getOrNull(index) != null

    /** Настоящая книга на карточке: скрыта, пока над ней летит копия. */
    fun cardBookAlpha(key: String, index: Int): Float =
        if (open?.key == key && flies(index) && bookProgress(index) >= DOCK) 0f else 1f

    /**
     * Летящая копия растворяется поверх настоящей обложки сетки уже после остановки книг ([settle],
     * по времени): подмена слоёв не происходит за один кадр. На карточке подмена жёсткая и совпадает
     * со стоящей книгой до пикселя.
     */
    fun overlayAlpha(index: Int): Float {
        val bp = bookProgress(index)
        // На карточке копия и стоящая книга рисуют одни и те же слои, поэтому подмена там жёсткая,
        // но только когда копия отошла от места меньше чем на пиксель (начало и конец пути).
        val rise = if (bp >= DOCK) 1f else 0f
        return rise * (1f - settle.value)
    }

    /**
     * Настоящая обложка в сетке: скрыта, пока книга летит, и сразу полностью видна под копией, как
     * только та села. Копия при этом ещё непрозрачна и растворяется сверху: пара «настоящая под
     * растворяющейся» всё время непрозрачна, а парный кроссфейд (одна гаснет, другая проявляется)
     * на середине просвечивал бы фон и бледнел.
     */
    fun gridCoverAlpha(index: Int): Float =
        if (flies(index)) (if (bookProgress(index) >= 0.995f || settle.value > 0f) 1f else 0f) else 1f

    /** Подпись под книгой в сетке: только на последних 20% пути этой книги, пока она не села. */
    fun captionAlpha(index: Int): Float =
        if (flies(index)) ((bookProgress(index) - 0.8f) / 0.2f).coerceIn(0f, 1f) else 1f


    fun expand(shelf: OpenShelf, scope: CoroutineScope, reducedMotion: Boolean) {
        if (open != null) return
        closing = false
        from = (0 until TRACKED).map { rectOf(fanCoords["${shelf.key}#$it"]) }
        card = rectOf(cardCoords[shelf.key])
        targetCoords.clear()
        open = shelf
        scope.launch {
            // Кадр на композицию страницы и кадр на раскладку — дальше её позиции известны.
            withFrameNanos { }
            withFrameNanos { }
            to = (0 until TRACKED).map { rectOf(targetCoords[it]) }
            settle.snapTo(0f)
            when {
                reducedMotion -> {
                    progress.snapTo(1f)
                    settle.snapTo(1f)
                }
                // Новые полки: критическое затухание без отскока, книги не перелетают место посадки.
                // Порог видимости мелкий: стандартный (0,01) обрывает пружину за ~1% до цели и
                // последний шаг (несколько пикселей) делается одним кадром — это и есть щелчок.
                else -> {
                    progress.animateTo(1f, androidx.compose.animation.core.spring(1f, 380f, 0.0005f))
                    settle.animateTo(1f, androidx.compose.animation.core.tween(SETTLE_MS))
                }
            }
        }
    }

    /** Открыть полку без анимации — возврат на неё со страницы книги. */
    suspend fun restore(shelf: OpenShelf) {
        if (open != null) return
        from = emptyList()
        to = emptyList()
        open = shelf
        progress.snapTo(1f)
        settle.snapTo(1f)
    }

    fun collapse(scope: CoroutineScope, reducedMotion: Boolean) {
        val shelf = open ?: return
        closing = true
        from = (0 until TRACKED).map { rectOf(fanCoords["${shelf.key}#$it"]) }
        to = (0 until TRACKED).map { rectOf(targetCoords[it]) }
        // Копия сразу ложится поверх обложки сетки (они совпадают до пикселя, пока книги стоят).
        scope.launch { settle.snapTo(0f) }
        scope.launch {
            when {
                reducedMotion -> progress.snapTo(0f)
                else -> progress.animateTo(0f, androidx.compose.animation.core.spring(1f, 438.6f, 0.0005f))
            }
            open = null
        }
    }

    /**
     * Прямоугольник в координатах этого экрана (не всего окна: страница пейджера сдвинута).
     * Необрезанный: boundsInRoot срезает обложку веера клипом карточки, и полёт стартовал
     * с укороченной, сдвинутой вверх рамки — в конце закрытия это давало рывок.
     */
    private fun rectOf(coords: LayoutCoordinates?): Rect? {
        val r = root ?: return null
        if (coords == null || !coords.isAttached || !r.isAttached) return null
        val topLeft = r.localPositionOf(coords, Offset.Zero)
        return Rect(topLeft, coords.size.toSize())
    }

    companion object {
        /** Новые полки: сколько книг полки отслеживает морф (откуда летят и куда садятся). */
        const val TRACKED = 24

        /** Длительность передачи между летящей копией и обложкой в сетке, мс. */
        const val SETTLE_MS = 110

        /** Доля пути, в пределах которой копия ещё неотличима от стоящей на карточке книги (< 1 пикселя). */
        const val DOCK = 0.0015f
    }
}

internal data class OpenShelf(
    val key: String,
    val title: String,
    val subtitle: String,
    val books: List<CachedBook>,
    /** null — выдачи ещё нет, карточка показывается скелетом. */
    val fetchedAt: Long?,
    /** Полка библиотеки пользователя (автор), а не витрина источника: у неё нет «обновлено». */
    val library: Boolean = false,
)

/**
 * Полки библиотеки: одна на автора (по первому автору книги), крупные авторы первыми, без автора —
 * отдельной полкой в конце. Внутри — книги в порядке добавления.
 */
internal fun authorShelves(books: List<com.example.myapplication.audiobooks.data.LibraryBook>, strings: BooksHomeStrings): List<OpenShelf> {
    val byAuthor = books.groupBy { it.authors.firstOrNull()?.trim()?.takeIf(String::isNotEmpty) }
    return byAuthor.entries
        .sortedWith(compareBy<Map.Entry<String?, List<com.example.myapplication.audiobooks.data.LibraryBook>>> { it.key == null }.thenByDescending { it.value.size })
        .map { (author, list) ->
            OpenShelf(
                key = "author:" + (author ?: "?"),
                title = author ?: strings.unknownAuthor,
                // Число книг уже в углу карточки — под названием общая длительность полки.
                subtitle = list.sumOf { it.durationMs ?: 0L }.takeIf { it > 0 }?.let { strings.duration(it / 1000) }.orEmpty(),
                books = list.map { b ->
                    val variant = b.variantId?.value.orEmpty()
                    CachedBook(
                        source = variant.substringBefore(':', ""),
                        key = variant.substringAfter(':', ""),
                        title = b.title,
                        authors = b.authors,
                        narrators = b.narrators,
                        coverUrl = b.coverUrl,
                        durationSec = b.durationMs?.div(1000),
                        narrations = 1,
                    )
                },
                fetchedAt = null,
                library = true,
            )
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


/**
 * Список дома гаснет раньше, чем проявляется содержимое страницы полки (с t 0,4): у страницы нет
 * своей заливки — под обоими общий градиент раздела, и на обратном ходе не остаётся «чёрного окна».
 */
private const val HOME_FADE_END = 0.4f
private const val HOME_FADE_CLOSE_END = 0.65f
