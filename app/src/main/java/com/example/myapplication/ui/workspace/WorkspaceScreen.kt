package com.example.myapplication.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.core.net.toUri
import com.example.myapplication.NotificationSyncOverlay
import com.example.myapplication.ui.home.HomeScreen
import com.example.myapplication.ui.home.HomeViewModel
import com.example.myapplication.ui.home.CapsuleDockInset
import com.example.myapplication.ui.home.StatsOverlay
import com.example.myapplication.ui.settings.SettingsScreen
import com.example.myapplication.ui.settings.SettingsViewModel
import com.example.myapplication.ui.navigation.navigateToAddEdit
import com.example.myapplication.ui.navigation.navigateToInspect
import com.example.myapplication.ui.navigation.navigateToWelcome
import com.example.myapplication.ui.shared.DONATION_URL
import com.example.myapplication.ui.shared.LocalAdaptiveGlassScrollInProgress
import com.example.myapplication.ui.shared.LocalBackdropPinned
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.utils.getStrings
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Рабочая область — корень приложения при включённом флаге `SELECT_DOCK_NAVIGATION`.
 *
 * Пять равноправных разделов ([WorkspacePage]) живут страницами одного пейджера; переключение —
 * свайпом или тапом по [WorkspaceDock]. Details и редактирование по id остаются push-экранами
 * поверх рабочей области и открываются через тот же [navController].
 *
 * TICKET-01: переход пока дефолтный пейджерный, «наезд» — TICKET-03; стекло и автоскрытие дока —
 * TICKET-04; статистика — TICKET-05.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun WorkspaceScreen(
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    val language by homeViewModel.uiLanguage.collectAsStateWithLifecycle()

    // Выделенная долгим удержанием карточка запирает рабочую область: страницы не листаются,
    // док гаснет и не нажимается. Скрим самого меню лежит внутри главной и до дока не достаёт —
    // он ей сосед, а не потомок, поэтому состояние приходит наверх колбэком.
    var cardSelectionActive by remember { mutableStateOf(false) }
    // Любая шторка или диалог на странице → док уезжает вниз. Страницы сообщают об этом
    // колбэком: док им сосед, а не потомок, и сам про их состояние не знает.
    var homeOverlayVisible by remember { mutableStateOf(false) }
    var settingsOverlayVisible by remember { mutableStateOf(false) }
    // Автоскрытие дока (D9) и экономный режим его стекла живут здесь по той же причине: страницы
    // доку не родители, `LocalAdaptiveGlassScrollInProgress` внутри них до него не достаёт.
    var pageDockVisible by remember { mutableStateOf(true) }
    var pageScrollInProgress by remember { mutableStateOf(false) }
    var showSyncPanel by remember { mutableStateOf(false) }
    // Меню последнего гнезда дока раскрывается поверх страницы, поэтому его состояние живёт
    // рядом с доком, а не внутри какой-либо страницы.
    var menuOpen by remember { mutableStateOf(false) }
    var menuOrigin by remember { mutableStateOf<Rect?>(null) }
    // Статистика переехала из верхнего дока в меню, поэтому и рисуется теперь здесь: страница
    // коллекции к ней больше отношения не имеет.
    var showStats by remember { mutableStateOf(false) }
    // Какое окно открыто пунктом меню — его корень морфит из гнезда «Ещё» и обратно. Saveable:
    // пока окно на экране, рабочая область снята с композиции, а на возврате ключ обязан быть
    // тем же, иначе окну не во что схлопнуться.
    var menuWindowKey by rememberSaveable { mutableStateOf<String?>(null) }
    val syncPanelState = remember { MutableTransitionState(false) }
    syncPanelState.targetState = showSyncPanel
    val dockHidden = homeOverlayVisible || settingsOverlayVisible || showSyncPanel || !pageDockVisible

    // Страницы резервируют место под док сами — ровно под капсулу, без запаса на всякий случай.
    val dockInset = CapsuleDockInset

    val pagerState = rememberPagerState(
        initialPage = WorkspacePage.Start.index,
        pageCount = { WorkspacePage.PageCount },
    )

    // Стекло дока на время движения: и скролл списка страницы, и сам «наезд» между страницами
    // переводят его в статичный режим (D10) — режим меняется свойствами узла, структура прежняя.
    val pagerScrolling by remember { derivedStateOf { pagerState.isScrollInProgress } }
    val dockGlassStatic = pageScrollInProgress || pagerScrolling

    val goTo: (WorkspacePage) -> Unit = { page ->
        scope.launch {
            pagerState.animateScrollToPage(page.index, animationSpec = MotionTokens.sheetPresent())
        }
    }

    // targetPage, а не currentPage: во время броска пальцем «назад» должен считаться от того,
    // куда мы едем, иначе Back посреди жеста уводит не туда.
    val backTarget = WorkspacePage.backTargetFrom(WorkspacePage.ofIndex(pagerState.targetPage))
    // Открытое меню перехватывает «назад» первым: оно верхнее на экране, и листать страницу
    // под ним было бы неожиданностью.
    BackHandler(enabled = menuOpen) { menuOpen = false }
    BackHandler(enabled = !menuOpen && backTarget != null) {
        backTarget?.let { target ->
            performHaptic(view, "light")
            goTo(target)
        }
    }

    // Живой бэкдроп для дока — тот же приём, что в Details: страницы лежат ПОД узлом
    // layerBackdrop, и капсула дока преломляет их содержимое. Узел не размонтируется.
    val screenBg = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(screenBg)
        drawContent()
    }

    // Пока страницы едут, их стекло неподвижно относительно собственных бэкдропов — координаты
    // ему не нужны, и kyant не должен пересчитывать его на каждом кадре (см. PinnableBackdrop).
    val pinnedDuringSwipe = remember(pagerState) { { pagerState.isScrollInProgress } }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
        CompositionLocalProvider(LocalBackdropPinned provides pinnedDuringSwipe) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Соседняя страница обязана быть готова заранее: иначе переход показывает пустой кадр.
            beyondViewportPageCount = 1,
            userScrollEnabled = !cardSelectionActive,
        ) { index ->
            Box(modifier = Modifier.workspacePageMotion(pagerState, index)) {
            when (WorkspacePage.ofIndex(index)) {
                WorkspacePage.HOME -> HomeScreen(
                    navController = navController,
                    viewModel = homeViewModel,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    hostedInWorkspace = true,
                    onCardSelectionChange = { cardSelectionActive = it },
                    onOverlayVisibleChange = { homeOverlayVisible = it },
                    onContentScrollChange = { pageScrollInProgress = it },
                    onDockVisibleChange = { pageDockVisible = it },
                )

                WorkspacePage.BOOKS -> BooksScreen(
                    language = language,
                    bottomInset = dockInset,
                )

                WorkspacePage.SETTINGS -> SettingsScreen(
                    navController = navController,
                    viewModel = settingsViewModel,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    bottomInset = dockInset,
                    onOpenSyncPanel = { showSyncPanel = true },
                    onOverlayVisibleChange = { settingsOverlayVisible = it },
                    onContentScrollChange = { pageScrollInProgress = it },
                    onDockVisibleChange = { pageDockVisible = it },
                )
            }
            }
        }
        }
        }

        // Панель подключения и синхронизации: раньше жила в верхнем доке главной, теперь
        // открывается пунктом настроек. Рендерим её здесь, поверх пейджера, — своим скримом
        // она накрывает и док.
        if (syncPanelState.currentState || syncPanelState.targetState) {
            // Подписки — только пока панель на экране: состояние главной меняется на каждую букву
            // поиска, и держать его в корне значило пересобирать рабочую область вместе с доком.
            val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()
            val syncReport by homeViewModel.syncReport.collectAsStateWithLifecycle()
            Box(modifier = Modifier.fillMaxSize().zIndex(8f)) {
                NotificationSyncOverlay(
                    syncCoordinator = koinInject(),
                    authRepository = koinInject(),
                    visibleState = syncPanelState,
                    strings = getStrings(language),
                    syncReport = syncReport,
                    updates = homeUiState.updates,
                    isCheckingUpdates = homeUiState.isCheckingUpdates,
                    currentLanguage = language,
                    onDismiss = { showSyncPanel = false },
                    onLogout = { navController.navigateToWelcome() },
                    onCheckUpdates = {
                        performHaptic(view, "light")
                        homeViewModel.checkForUpdates(force = true)
                    },
                )
            }
        }

        if (showStats) {
            val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { homeViewModel.loadStatsAnimeList() }
            StatsOverlay(
                animeList = homeUiState.statsAnimeList,
                strings = getStrings(language),
                appLanguage = language,
                footerPhrase = homeUiState.statsFooterPhrase,
                onDismiss = { showStats = false },
            )
        }

        TtmMenu(
            expanded = menuOpen,
            origin = menuOrigin,
            backdrop = backdrop,
            onDismiss = { menuOpen = false },
            items = remember(language) { ttmMenuItems(
                language = language,
                onStats = { showStats = true },
                onFrame = {
                    menuWindowKey = MENU_WINDOW_INSPECT
                    navController.navigateToInspect()
                },
                onSync = { showSyncPanel = true },
                onAdd = {
                    menuWindowKey = MENU_WINDOW_ADD
                    navController.navigateToAddEdit()
                },
                onDonate = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, DONATION_URL.toUri()))
                    }
                },
            ) },
        )

        CompositionLocalProvider(LocalAdaptiveGlassScrollInProgress provides dockGlassStatic) {
        WorkspaceDock(
            backdrop = backdrop,
            hidden = dockHidden,
            menuOpen = menuOpen,
            onOpenMenu = {
                performHaptic(view, "light")
                menuOpen = true
            },
            onMenuBounds = { menuOrigin = it },
            menuWindowMorph = remember(menuWindowKey, sharedTransitionScope, animatedVisibilityScope) {
                MenuWindowMorph(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    key = menuWindowKey,
                )
            },
            // targetPage, а не settledPage: как только жест перешёл порог, пилюля уже едет к
            // новому разделу. С settledPage она стояла бы на старом до конца анимации и потом
            // прыгала.
            selected = WorkspacePage.ofIndex(pagerState.targetPage),
            language = language,
            dimmed = cardSelectionActive,
            onSelect = { page ->
                performHaptic(view, "light")
                goTo(page)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .zIndex(6f),
        )
        }
    }
}

/**
 * «Наезд» между страницами (TICKET-03).
 *
 * Страница с бо́льшим индексом лежит выше по `zIndex` и едет за пальцем один в один; соседка
 * слева уходит вглубь и параллаксит. Значения считает чистая [workspacePageTransform].
 *
 * Состояние пейджера читается ВНУТРИ лямбд `graphicsLayer` и `drawWithContent`, а не в
 * композиции: иначе каждый кадр жеста перекомпоновывал бы обе страницы целиком. Сами модификаторы
 * при этом не появляются и не исчезают — узел над `layerBackdrop` остаётся неизменным.
 */
private fun Modifier.workspacePageMotion(pagerState: PagerState, index: Int): Modifier = this
    .zIndex(index.toFloat())
    .graphicsLayer {
        val transform = pagerState.transformFor(index)
        scaleX = transform.scale
        scaleY = transform.scale
        translationX = transform.translationXFraction * size.width
        shape = RoundedCornerShape(transform.cornerDp.dp)
        clip = transform.cornerDp > 0f
    }
    .drawWithContent {
        // Второй расчёт вместо общего состояния: функция чистая и дешёвая, а лишний
        // `mutableStateOf` между слоем и отрисовкой добавил бы кадр рассинхрона.
        val transform = pagerState.transformFor(index)
        // Тень по ведущему краю наезжающей страницы — градиентом слева от её границы. Раньше это
        // была `shadowElevation` слоя на весь экран: RenderThread заново строил мягкую тень
        // полноэкранного контура на каждом кадре свайпа. У наезжающей страницы клипа нет
        // (скругление только у уходящей), поэтому рисовать за её левым краем можно.
        if (transform.shadowAlpha > 0f) {
            val width = PAGE_SHADOW_WIDTH.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = PAGE_SHADOW_MAX_ALPHA * transform.shadowAlpha),
                    startX = -width,
                    endX = 0f,
                ),
                topLeft = Offset(-width, 0f),
                size = Size(width, size.height),
            )
        }
        drawContent()
        if (transform.dimAlpha > 0f) drawRect(color = Color.Black, alpha = transform.dimAlpha)
    }
    // Содержимое страницы — в собственном offscreen-слое: растеризуется в текстуру, когда
    // меняется само, а на кадрах свайпа только масштабируется и сдвигается.
    //
    // Без этого содержимое заново проигрывалось на каждом кадре свайпа под масштабом уходящей
    // страницы, а скругления-сквирклы (произвольный контур) Skia на каждом таком проходе
    // растеризует маской на CPU и грузит текстурой: в трассе ~12 загрузок на кадр, 58 % кадров
    // длиннее 16,7 мс. Включать слой только на время свайпа пробовали: первый кадр растеризует
    // обе страницы сразу и даёт рывок в начале жеста (4,5 % против 1,4 % кадров > 16,7 мс), а
    // выигрыш в обычном скролле в пределах шума. Цена — текстура размером со страницу.
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)

private fun PagerState.transformFor(index: Int): PageTransform =
    workspacePageTransform(workspacePageOffset(index, currentPage, currentPageOffsetFraction))

/** Ширина и предельная плотность тени по ведущему краю наезжающей страницы. */
private val PAGE_SHADOW_WIDTH = 24.dp
private const val PAGE_SHADOW_MAX_ALPHA = 0.22f

/** Ключи shared-bounds корней окон — те же, что ждут InspectScreen и AddEditScreen. */
private const val MENU_WINDOW_INSPECT = "inspect_container"
private const val MENU_WINDOW_ADD = "fab_container"

/** Ключ Koin для формы-страницы: отделяет её ViewModel от push-экрана редактирования. */

