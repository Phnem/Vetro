package com.example.myapplication.manga.ui

import com.example.myapplication.ui.shared.theme.IosScroll
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowConversionToBitmap
import coil3.request.crossfade
import coil3.request.transformations
import com.example.myapplication.manga.data.ChapterReadingProgress
import com.example.myapplication.manga.data.MangaReaderMode
import com.example.myapplication.manga.data.PageDirection
import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaPage
import com.example.myapplication.ui.shared.components.IosSheetScaffold
import com.example.myapplication.ui.shared.components.LiquidGlassTrack
import com.example.myapplication.ui.shared.loading.BubbleClusterLoader
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Ридер главы: постранично или вебтун-лентой, поверх — скрываемый хром (тап по центру).
 *
 * Экран умышленно не знает ни про источник, ни про привязку тайтла — только про список страниц
 * и оглавление, с которым его открыли.
 */
@Composable
fun MangaReaderScreen(
    state: MangaReaderUiState,
    mode: MangaReaderMode,
    direction: PageDirection,
    cropBorders: Boolean,
    chapters: List<MangaChapter>,
    chapterProgress: Map<String, ChapterReadingProgress>,
    ru: Boolean,
    /** Страница и — только для вебтун-ленты — доля прокрутки внутри неё. */
    onPageChanged: (Int, Float?) -> Unit,
    onLayoutChange: (MangaReaderMode, PageDirection) -> Unit,
    onToggleCrop: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    var chaptersVisible by remember { mutableStateOf(false) }
    val currentChapterKey = (state as? MangaReaderUiState.Ready)?.chapter?.key.orEmpty()

    // Открытая шторка забирает «назад» себе: иначе оглавление закрывало бы ридер целиком.
    BackHandler(enabled = chaptersVisible) { chaptersVisible = false }

    IosSheetScaffold(
        sheetVisible = chaptersVisible,
        onDismiss = { chaptersVisible = false },
        sheetHeightFraction = 0.72f,
        content = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                when (state) {
                    MangaReaderUiState.Loading -> BubbleClusterLoader(
                        modifier = Modifier.align(Alignment.Center).size(46.dp),
                        color = Color.White,
                    )

                    is MangaReaderUiState.Error -> ReaderError(
                        state = state,
                        ru = ru,
                        onRetry = onRetry,
                        onClose = onClose,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    is MangaReaderUiState.Ready -> ReaderContent(
                        state = state,
                        mode = mode,
                        direction = direction,
                        cropBorders = cropBorders,
                        ru = ru,
                        onPageChanged = onPageChanged,
                        onLayoutChange = onLayoutChange,
                        onToggleCrop = onToggleCrop,
                        onChapters = { chaptersVisible = true },
                        onPreviousChapter = onPreviousChapter,
                        onNextChapter = onNextChapter,
                        onClose = onClose,
                    )
                }
            }
        },
        sheetContent = {
            ReaderChaptersSheet(
                chapters = chapters,
                currentKey = currentChapterKey,
                progress = chapterProgress,
                ru = ru,
                onPick = { chapter ->
                    chaptersVisible = false
                    onOpenChapter(chapter.key)
                },
            )
        },
    )
}

@Composable
private fun ReaderContent(
    state: MangaReaderUiState.Ready,
    mode: MangaReaderMode,
    direction: PageDirection,
    cropBorders: Boolean,
    ru: Boolean,
    /** Страница и — только для вебтун-ленты — доля прокрутки внутри неё. */
    onPageChanged: (Int, Float?) -> Unit,
    onLayoutChange: (MangaReaderMode, PageDirection) -> Unit,
    onToggleCrop: () -> Unit,
    onChapters: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onClose: () -> Unit,
) {
    var chromeVisible by remember { mutableStateOf(false) }
    var currentPage by remember(state.chapter.key) { mutableIntStateOf(state.startPage) }
    val scope = rememberCoroutineScope()
    // Высота заглушки в пикселях — по ней восстановление докрутки понимает, загрузилась ли уже
    // страница: пока картинки нет, элемент ленты ровно этой высоты (см. restoreScrollOffset).
    val placeholderPx = with(LocalDensity.current) { PagePlaceholderHeight.roundToPx() }

    // Перемотку хром получает функцией, а не самим pagerState/listState: хром живёт СНАРУЖИ
    // key(...) и не имеет права пересобираться вместе с пейджером (иначе стеклянный док терял бы
    // свой слой-задник). Текущий скроллер регистрирует себя сам при создании.
    val seek = remember { mutableStateOf<suspend (Int) -> Unit>({ }) }

    // Слой-задник для стекла дока: захватывает ТОЛЬКО страницы, поэтому док преломляет саму
    // мангу, а не собственный хром. Узел с layerBackdrop лежит над key(...) и никогда не
    // размонтируется — смена режима или главы его не трогает.
    val pagesBackdrop = rememberLayerBackdrop {
        drawRect(Color.Black)
        drawContent()
    }

    // Что именно поменялось под доком: страница либо докрутка внутри неё (вебтун-полоса бывает в
    // несколько экранов, и её середина по яркости не равна началу). Значение — ключ снимка, а не
    // таймер: страница статична, опрашивать её по расписанию нечего.
    var scrollStep by remember(state.chapter.key) { mutableIntStateOf(0) }
    val ambient = rememberReaderAmbient(
        layer = pagesBackdrop.graphicsLayer,
        active = chromeVisible,
        revision = currentPage to scrollStep,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(pagesBackdrop),
        ) {
            // Перезапуск пейджера/ленты при смене главы: иначе новая глава открывается на старой
            // странице. Направление тоже в ключе — HorizontalPager читает layout direction только
            // при создании, без пересборки переключатель «классика/комикс» выглядел бы сломанным.
            // В вебтуне направление из ключа выключаем: ленте оно безразлично, а лишняя пересборка
            // сбросила бы позицию скролла.
            key(state.chapter.key, mode, direction.takeIf { mode == MangaReaderMode.Paged }) {
                when (mode) {
                    MangaReaderMode.Paged -> {
                        val pagerState = rememberPagerState(
                            initialPage = state.startPage,
                            pageCount = { state.pages.size },
                        )
                        LaunchedEffect(pagerState) {
                            seek.value = { page -> pagerState.scrollToPage(page) }
                            snapshotFlow { pagerState.currentPage }
                                .distinctUntilChanged()
                                .collect { page ->
                                    currentPage = page
                                    onPageChanged(page, null)
                                }
                        }
                        // Направление задаём только пейджеру, не всему экрану: список страниц
                        // остаётся в исходном порядке, поэтому сохранённый pageIndex продолжает
                        // указывать на ту же страницу, а хром и слайдер не переворачиваются
                        // вместе с ней.
                        CompositionLocalProvider(
                            LocalLayoutDirection provides when (direction) {
                                PageDirection.Rtl -> LayoutDirection.Rtl
                                PageDirection.Ltr -> LayoutDirection.Ltr
                            },
                        ) {
                            // «Вперёд» — туда, куда уезжает страница при свайпе: у классики
                            // (справа налево) это ЛЕВЫЙ край экрана, у комикса — правый. Индексы
                            // страниц при этом остаются в исходном порядке, переворачивается
                            // только раскладка пейджера.
                            val turn: (Int) -> Unit = { delta ->
                                scope.launch {
                                    val target = (pagerState.currentPage + delta)
                                        .coerceIn(0, (state.pages.size - 1).coerceAtLeast(0))
                                    if (target != pagerState.currentPage) {
                                        pagerState.animateScrollToPage(target)
                                    }
                                }
                            }
                            val forward = if (direction == PageDirection.Rtl) 1 else -1
                            HorizontalPager(
                                flingBehavior = IosScroll.pagerFlingBehavior(pagerState),
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                                beyondViewportPageCount = 1,
                            ) { index ->
                                ZoomablePage(
                                    page = state.pages[index],
                                    cropBorders = cropBorders,
                                    ru = ru,
                                    onToggleChrome = { chromeVisible = !chromeVisible },
                                    onTapLeft = { turn(forward) },
                                    onTapRight = { turn(-forward) },
                                )
                            }
                        }
                    }

                    MangaReaderMode.Webtoon -> {
                        val listState =
                            rememberLazyListState(initialFirstVisibleItemIndex = state.startPage)
                        LaunchedEffect(listState) {
                            seek.value = { page -> listState.scrollToItem(page) }
                            restoreScrollOffset(
                                listState = listState,
                                page = state.startPage,
                                fraction = state.startOffsetFraction,
                                placeholderPx = placeholderPx,
                            )
                            // Позиция в ленте — это не только номер страницы: одна вебтун-полоса
                            // бывает в несколько экранов, поэтому вместе с индексом ведём и долю
                            // прокрутки внутри неё.
                            snapshotFlow { listState.readingPosition() }
                                .distinctUntilChanged()
                                .collect { (page, offsetStep) ->
                                    currentPage = page
                                    scrollStep = offsetStep
                                    onPageChanged(page, offsetStep.toFloat() / OffsetSteps)
                                }
                        }
                        ZoomableWebtoon(
                            onToggleChrome = { chromeVisible = !chromeVisible },
                        ) { zoomModifier ->
                            LazyColumn(
                                flingBehavior = IosScroll.flingBehavior(),
                                state = listState,
                                modifier = zoomModifier.fillMaxSize(),
                            ) {
                                items(state.pages.size) { index ->
                                    PageImage(
                                        page = state.pages[index],
                                        contentScale = ContentScale.FillWidth,
                                        cropBorders = cropBorders,
                                        ru = ru,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        ReaderChrome(
            visible = chromeVisible,
            backdrop = pagesBackdrop,
            ambient = ambient,
            state = state,
            mode = mode,
            direction = direction,
            cropBorders = cropBorders,
            ru = ru,
            currentPage = currentPage,
            onSeek = { target -> scope.launch { seek.value(target) } },
            onLayoutChange = onLayoutChange,
            onToggleCrop = onToggleCrop,
            onChapters = onChapters,
            onPreviousChapter = onPreviousChapter,
            onNextChapter = onNextChapter,
            onClose = onClose,
        )
    }
}

/**
 * Вернуть ленту на ту долю страницы, где чтение остановилось.
 *
 * Задать оффсет прямо при создании `listState` нельзя: он в пикселях, а высота вебтун-полосы
 * известна только после загрузки картинки — до этого элемент занимает высоту заглушки. Поэтому
 * ждём, пока элемент перестанет быть заглушкой, и только тогда доводим позицию.
 *
 * Любое движение читателя за это время отменяет восстановление: дёрнуть ленту под пальцем хуже,
 * чем не восстановить полэкрана прокрутки.
 */
private suspend fun restoreScrollOffset(
    listState: LazyListState,
    page: Int,
    fraction: Float,
    placeholderPx: Int,
) {
    if (fraction <= 0f) return
    repeat(RestoreAttempts) {
        if (listState.firstVisibleItemIndex != page || listState.firstVisibleItemScrollOffset != 0) {
            return
        }
        val height = listState.itemHeight(page)
        if (height > 0 && height != placeholderPx) {
            listState.scrollToItem(page, (height * fraction).roundToInt())
            return
        }
        delay(RestorePollMs)
    }
}

/**
 * Позиция чтения в ленте: номер страницы и доля прокрутки внутри неё, квантованная по
 * [OffsetSteps]. Без квантования доля менялась бы каждый кадр, и `distinctUntilChanged` выпускал
 * бы наружу весь фling целиком — то есть сотню запросов на сохранение прогресса.
 */
private fun LazyListState.readingPosition(): Pair<Int, Int> {
    val index = firstVisibleItemIndex
    val height = itemHeight(index)
    if (height <= 0) return index to 0
    val step = (firstVisibleItemScrollOffset.toFloat() / height * OffsetSteps).roundToInt()
    return index to step.coerceIn(0, OffsetSteps)
}

private fun LazyListState.itemHeight(index: Int): Int =
    layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0

/**
 * Страница постраничного режима.
 *
 * Две раскладки, и выбор между ними не настройка, а свойство самой картинки. Обычная страница
 * комикса вписывается целиком ([ContentScale.Fit]) — так её и читают. Но источники сплошь и рядом
 * отдают ВЕБТУН-полосу: одна картинка высотой в десяток экранов. Вписанная целиком, она
 * превращается в нечитаемую вертикальную «сосиску» шириной в треть экрана — формально верный
 * `Fit`, практически бесполезный. Такие страницы разворачиваем по ширине и листаем вертикально
 * внутри страницы, как это делают все ридеры; горизонтальное листание пейджера при этом остаётся.
 *
 * Порог [StripRatioFactor] считается от пропорций ВЬЮПОРТА, а не от абсолютного соотношения
 * сторон: «намного выше экрана» на планшете и на телефоне — разные числа.
 */
@Composable
private fun ZoomablePage(
    page: MangaPage,
    cropBorders: Boolean,
    ru: Boolean,
    onToggleChrome: () -> Unit,
    onTapLeft: () -> Unit,
    onTapRight: () -> Unit,
) {
    // Пропорции узнаём от самой картинки: до загрузки это 0 и раскладка обычная.
    var pageRatio by remember(page.url) { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportRatio =
            if (constraints.maxWidth > 0) constraints.maxHeight.toFloat() / constraints.maxWidth else 1f
        val isStrip = pageRatio > 0f && pageRatio > viewportRatio * StripRatioFactor

        if (isStrip) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
                    .pageTurnTaps(page.url, onToggleChrome, onTapLeft, onTapRight),
            ) {
                PageImage(
                    page = page,
                    contentScale = ContentScale.FillWidth,
                    cropBorders = cropBorders,
                    ru = ru,
                    onIntrinsicRatio = { pageRatio = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            FittedZoomablePage(
                page = page,
                cropBorders = cropBorders,
                ru = ru,
                onToggleChrome = onToggleChrome,
                onTapLeft = onTapLeft,
                onTapRight = onTapRight,
                onIntrinsicRatio = { pageRatio = it },
            )
        }
    }
}

/** Обычная страница: вписана целиком, пинч-зум и панорама; двойной тап — быстрый зум/сброс. */
@Composable
private fun FittedZoomablePage(
    page: MangaPage,
    cropBorders: Boolean,
    ru: Boolean,
    onToggleChrome: () -> Unit,
    onTapLeft: () -> Unit,
    onTapRight: () -> Unit,
    onIntrinsicRatio: (Float) -> Unit,
) {
    var scale by remember(page.url) { mutableFloatStateOf(1f) }
    var offsetX by remember(page.url) { mutableFloatStateOf(0f) }
    var offsetY by remember(page.url) { mutableFloatStateOf(0f) }
    // Читается только внутри обработчика жеста — держим в лямбде, чтобы не пересоздавать
    // pointerInput на каждое изменение масштаба (иначе жест обрывался бы посреди пинча).
    val currentScale by rememberUpdatedState(scale)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(page.url) {
                val maxOffset = { s: Float ->
                    Offset(size.width * (s - 1f) / 2f, size.height * (s - 1f) / 2f)
                }
                zoomAndPan(
                    scaleProvider = { currentScale },
                    onTransform = { zoomChange, panChange ->
                        scale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                        if (scale > 1f) {
                            val limit = maxOffset(scale)
                            offsetX = (offsetX + panChange.x).coerceIn(-limit.x, limit.x)
                            offsetY = (offsetY + panChange.y).coerceIn(-limit.y, limit.y)
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    },
                )
            }
            .pageTurnTaps(
                key = page.url,
                onToggleChrome = onToggleChrome,
                onTapLeft = onTapLeft,
                onTapRight = onTapRight,
                onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    } else {
                        scale = DOUBLE_TAP_SCALE
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        PageImage(
            page = page,
            contentScale = ContentScale.Fit,
            cropBorders = cropBorders,
            ru = ru,
            onIntrinsicRatio = onIntrinsicRatio,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
        )
    }
}

/**
 * Щипок по вебтун-ленте.
 *
 * Постраничный режим увеличивает одну страницу ([FittedZoomablePage]), у ленты страницы нет —
 * полоса тянется на несколько экранов и рвать её на куски ради зума нельзя. Поэтому масштабируется
 * сам список целиком, вокруг центра: раскладка от этого не меняется, а прокрутка остаётся
 * прокруткой — просто проезжает по увеличенной картинке быстрее.
 *
 * Разделение пальцев жёсткое и намеренное: щипок и горизонтальная панорама — только двумя
 * пальцами, один палец всегда достаётся `LazyColumn`. Иначе увеличенная лента переставала
 * скроллиться — ровно та ловушка, из-за которой в постраничном режиме не годится
 * `Modifier.transformable` (см. [zoomAndPan]).
 *
 * Вертикаль панораме не отдаём вовсе: по ней ходит скролл, и второй источник сдвига по той же оси
 * дрался бы с ним за одно и то же движение.
 *
 * @param content получает модификатор с жестами и трансформацией; его обязан носить сам список,
 *   чтобы узел не появлялся и не исчезал по ходу зума (над ним лежит `layerBackdrop` дока).
 */
@Composable
private fun ZoomableWebtoon(
    onToggleChrome: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    content(
        Modifier
            .pointerInput(Unit) {
                webtoonPinch(
                    scaleProvider = { scale },
                    onTransform = { zoomChange, panX ->
                        scale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                        offsetX = if (scale > 1f) {
                            val limit = size.width * (scale - 1f) / 2f
                            (offsetX + panX).coerceIn(-limit, limit)
                        } else {
                            0f
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                        } else {
                            scale = DOUBLE_TAP_SCALE
                        }
                    },
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
            },
    )
}

/**
 * Щипок и горизонтальная панорама, не отбирающие прокрутку у ленты.
 *
 * Два отличия от постраничного [zoomAndPan], и оба вынужденные.
 *
 * 1. **Разбор идёт на [PointerEventPass.Initial].** Жесты доходят до обработчиков изнутри наружу,
 *    а этот модификатор носит сам `LazyColumn` — значит на обычном проходе список видит движение
 *    ПЕРВЫМ и, перевалив порог вертикального скролла, потребляет его. Щипок после этого упирался
 *    бы в проверку «событие уже потреблено» и срывался, а сработает он или нет — решала бы
 *    разница в доли секунды между касаниями двух пальцев. У пейджера этой беды нет: его ось
 *    перпендикулярна.
 * 2. **Потребляем только то, чем реально воспользовались.** Два пальца, положенные на экран без
 *    щипка (обычная привычка листать с планшета), иначе замораживали бы ленту: событие съедено,
 *    а не сделано ничего.
 *
 * Одиночный палец не трогаем ни при каком масштабе: в ленте он всегда означает прокрутку.
 */
private suspend fun PointerInputScope.webtoonPinch(
    scaleProvider: () -> Float,
    onTransform: (zoom: Float, panX: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } < 2) continue
            val zoom = event.calculateZoom()
            val panX = event.calculatePan().x
            val panned = panX != 0f && scaleProvider() > 1f
            if (zoom == 1f && !panned) continue
            onTransform(zoom, panX)
            // Потребляем всё движение щипка, а не только сработавшую часть: остаток иначе доедет
            // до списка и прокрутит его на полглавы.
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Зоны листания по краям страницы + переключение хрома по центру.
 *
 * Тап по краю — обязательный для ридера способ листать: одной рукой с телефона свайпать поперёк
 * экрана неудобно, и во всех читалках манги края работают как кнопки.
 */
private fun Modifier.pageTurnTaps(
    key: Any,
    onToggleChrome: () -> Unit,
    onTapLeft: () -> Unit,
    onTapRight: () -> Unit,
    onDoubleTap: (() -> Unit)? = null,
): Modifier = this.pointerInput(key, onTapLeft, onTapRight) {
    detectTapGestures(
        onTap = { offset ->
            val width = size.width.toFloat()
            when {
                width <= 0f -> onToggleChrome()
                offset.x < width * TapZoneFraction -> onTapLeft()
                offset.x > width * (1f - TapZoneFraction) -> onTapRight()
                else -> onToggleChrome()
            }
        },
        onDoubleTap = onDoubleTap?.let { action -> { _ -> action() } },
    )
}

/**
 * Пинч-зум и панорама, НЕ отбирающие листание у пейджера.
 *
 * Штатный `Modifier.transformable` не годится: он построен на `detectTransformGestures`, который
 * считает панорамой и одиночный палец, и потребляет событие. Поэтому в постраничном режиме
 * горизонтальный свайп до `HorizontalPager` не доходил вовсе — страница не листалась ни свайпом,
 * ни (за отсутствием зон) тапом. Здесь событие потребляется только когда пальцев больше одного
 * (это заведомо пинч) либо страница уже увеличена (тогда таскание — панорама по ней).
 */
private suspend fun PointerInputScope.zoomAndPan(
    scaleProvider: () -> Float,
    onTransform: (zoom: Float, pan: Offset) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break
            val pressed = event.changes.count { it.pressed }
            if (pressed < 2 && scaleProvider() <= 1f) continue
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            if (zoom != 1f || pan != Offset.Zero) {
                onTransform(zoom, pan)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Одна страница с собственным retry: битая картинка не должна утаскивать за собой главу.
 *
 * Общая точка отрисовки для обоих режимов, поэтому заглушка автоматически работает и в пейджере,
 * и в вебтун-ленте.
 */
@Composable
private fun PageImage(
    page: MangaPage,
    contentScale: ContentScale,
    cropBorders: Boolean,
    ru: Boolean,
    modifier: Modifier = Modifier,
    /** Высота/ширина загруженной картинки — по ней постраничный режим узнаёт вебтун-полосу. */
    onIntrinsicRatio: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    // Номер попытки живёт вместе со страницей и служит cache-bust'ом при перезагрузке.
    var attempt by remember(page.url) { mutableIntStateOf(0) }
    var failed by remember(page.url) { mutableStateOf(false) }
    var loading by remember(page.url) { mutableStateOf(true) }

    val model = remember(page.url, attempt, cropBorders) {
        ImageRequest.Builder(context)
            .data(page.url)
            .crossfade(true)
            .apply {
                // Источники, отдающие картинки только со своим Referer, иначе вернут 403.
                if (page.headers.isNotEmpty()) {
                    val headers = NetworkHeaders.Builder().apply {
                        page.headers.forEach { (name, value) -> add(name, value) }
                    }.build()
                    httpHeaders(headers)
                }
                // Сам URL не трогаем: у части источников он подписан, и лишний query-параметр
                // сломал бы подпись. Попытки различаем ключами кэша — этого достаточно, чтобы
                // Coil пошёл в сеть заново, а не отдал прошлый промах.
                if (attempt > 0) {
                    memoryCacheKey("${page.url}#$attempt")
                    diskCacheKey("${page.url}#$attempt")
                }
                if (cropBorders) {
                    transformations(EdgeCropTransformation)
                    // Тайловую вебтун-полосу из RegionBitmapDecoder Coil ради трансформации
                    // схлопнул бы в один гигантский битмап — тот самый OOM, от которого тайлы и
                    // спасают. С этим флагом обрезка на таких страницах просто не применяется.
                    allowConversionToBitmap(false)
                }
            }
            .build()
    }

    Box(contentAlignment = Alignment.Center) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = contentScale,
            onState = { state ->
                loading = state is AsyncImagePainter.State.Loading
                failed = state is AsyncImagePainter.State.Error
                if (state is AsyncImagePainter.State.Success) {
                    val size = state.painter.intrinsicSize
                    if (size.isSpecified && size.width > 0f) {
                        onIntrinsicRatio(size.height / size.width)
                    }
                }
            },
            modifier = modifier,
        )
        // Пока картинки нет, AsyncImage схлопывается в нулевую высоту — в вебтун-ленте это
        // дёргало бы скролл. Заглушка держит место сама и задаёт высоту элементу.
        if (failed) {
            PageLoadError(ru = ru, onRetry = { attempt++ })
        } else if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PagePlaceholderHeight),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = BrandOrange, strokeWidth = 2.dp)
            }
        }
    }
}

/** Заглушка вместо не загрузившейся страницы: соседние страницы при этом остаются рабочими. */
@Composable
private fun PageLoadError(
    ru: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(PagePlaceholderHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (ru) "Страница не загрузилась" else "This page failed to load",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onRetry)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = BrandOrange,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (ru) "Обновить" else "Retry",
                color = BrandOrange,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ReaderChrome(
    visible: Boolean,
    backdrop: Backdrop,
    ambient: ReaderAmbient,
    state: MangaReaderUiState.Ready,
    mode: MangaReaderMode,
    direction: PageDirection,
    cropBorders: Boolean,
    ru: Boolean,
    currentPage: Int,
    onSeek: (Int) -> Unit,
    onLayoutChange: (MangaReaderMode, PageDirection) -> Unit,
    onToggleCrop: () -> Unit,
    onChapters: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onClose: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Градиент вместо плоской заливки: под доком должна просвечивать страница,
                    // иначе преломлять ему нечего — сплошная чёрная плашка выглядит как рамка.
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.75f),
                            1f to Color.Transparent,
                        ),
                    )
                    // Не statusBarsPadding: активность прячет системные бары
                    // (MangaReaderActivity.hideSystemBars), и этот инсет схлопывается в ноль —
                    // а вырез камеры в statusBars не входит и никуда не девается. Отступаем по
                    // объединению, иначе название главы уезжает под фронталку.
                    // Горизонталь нужна для ландшафта, где вырез уходит вбок; низ исключаем —
                    // бар прижат к верху, и нижний инсет только раздул бы ему высоту.
                    .windowInsetsPadding(
                        WindowInsets.systemBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.chapter.readerTitle(ru),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${currentPage + 1} / ${state.pages.size}",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                ReaderDock(
                    backdrop = backdrop,
                    ambient = ambient,
                    mode = mode,
                    direction = direction,
                    cropBorders = cropBorders,
                    ru = ru,
                    onChapters = onChapters,
                    onLayoutChange = onLayoutChange,
                    onToggleCrop = onToggleCrop,
                )
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (state.pages.size > 1) {
                    ReaderPageSlider(
                        page = currentPage,
                        pageCount = state.pages.size,
                        onSeek = onSeek,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChapterNavButton(
                        icon = Icons.AutoMirrored.Filled.NavigateBefore,
                        label = if (ru) "Предыдущая" else "Previous",
                        enabled = state.hasPrevious,
                        onClick = onPreviousChapter,
                    )
                    ChapterNavButton(
                        icon = Icons.AutoMirrored.Filled.NavigateNext,
                        label = if (ru) "Следующая" else "Next",
                        enabled = state.hasNext,
                        onClick = onNextChapter,
                        iconFirst = false,
                    )
                }
            }
        }
    }
}

/**
 * Перемотка по страницам на общем стеклянном треке. Значение здесь дискретное — номер страницы,
 * поэтому отклик тоже дискретный: тик на каждый снап, а не непрерывная вибрация под пальцем.
 */
@Composable
private fun ReaderPageSlider(
    page: Int,
    pageCount: Int,
    onSeek: (Int) -> Unit,
) {
    val view = LocalView.current
    val lastIndex = (pageCount - 1).coerceAtLeast(1)
    // Последний отданный снап: без него тик срабатывал бы на каждое движение пальца внутри
    // одной и той же страницы.
    var lastSnap by remember { mutableIntStateOf(page) }

    LiquidGlassTrack(
        fraction = page.toFloat() / lastIndex,
        ticks = if (pageCount <= MaxSliderTicks) pageCount else 0,
        onScrubStart = { lastSnap = page },
        onScrub = { fraction ->
            val target = (fraction * lastIndex).roundToInt().coerceIn(0, lastIndex)
            if (target != lastSnap) {
                lastSnap = target
                performHaptic(view, "tick")
                onSeek(target)
            }
        },
        onScrubEnd = { fraction ->
            val target = (fraction * lastIndex).roundToInt().coerceIn(0, lastIndex)
            performHaptic(view, "light")
            onSeek(target)
        },
        thumbContent = {
            Text(
                text = "${page + 1}",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        },
    )
}

@Composable
private fun ChapterNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    iconFirst: Boolean = true,
) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (iconFirst) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = tint, fontSize = 13.sp)
        } else {
            Text(label, color = tint, fontSize = 13.sp)
            Spacer(Modifier.width(4.dp))
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ReaderError(
    state: MangaReaderUiState.Error,
    ru: Boolean,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.message.readerErrorText(ru),
            color = Color.White,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = BrandOrange)
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }
    }
}

private fun String.readerErrorText(ru: Boolean): String = when (this) {
    "pages_empty" -> if (ru) "Источник не отдал страницы этой главы" else "Source returned no pages"
    "chapter_missing" -> if (ru) "Глава больше недоступна" else "Chapter is no longer available"
    else -> if (ru) "Не удалось открыть главу" else "Failed to open the chapter"
}

/** «Гл. 12 — Название» / «Ch. 12 — Title»; у безномерных экстр остаётся только название. */
fun MangaChapter.readerTitle(ru: Boolean): String {
    val number = numberLabel?.let { if (ru) "Гл. $it" else "Ch. $it" }
    val name = title?.takeIf { it.isNotBlank() }
    return listOfNotNull(number, name).joinToString(" — ")
        .ifBlank { if (ru) "Глава" else "Chapter" }
}

/** Высота места под ещё не загруженную или битую страницу — чтобы лента вебтуна не дёргалась. */
private val PagePlaceholderHeight = 220.dp

/** Насечки под страницы имеют смысл только у коротких глав; дальше это сплошная штриховка. */
private const val MaxSliderTicks = 20

/** Шаг сохранения докрутки — 1/20 высоты страницы: мельче читатель разницы не заметит. */
private const val OffsetSteps = 20

/** Потолок ожидания загрузки стартовой страницы: ~3 с, дальше восстанавливать уже некуда. */
private const val RestoreAttempts = 25
private const val RestorePollMs = 120L

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 4f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Во сколько раз страница должна быть длиннее вьюпорта, чтобы считаться вебтун-полосой.
 *
 * 1.6 — с запасом мимо обычных страниц: разворот на две полосы и высокая обложка дают ~1.3–1.4
 * от пропорций телефона и должны по-прежнему вписываться целиком.
 */
private const val StripRatioFactor = 1.6f

/** Доля ширины под краевые зоны листания; середина остаётся переключателем хрома. */
private const val TapZoneFraction = 0.28f
