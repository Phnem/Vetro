package com.example.myapplication.ui.home

import androidx.activity.compose.ReportDrawnWhen
import com.example.myapplication.ui.shared.theme.IosScroll
import com.example.myapplication.ui.home.updates.notificationStrings
import com.example.myapplication.ui.home.updates.NotificationCenter
import com.example.myapplication.ui.home.updates.NotificationBellButton
import com.example.myapplication.ui.home.updates.NotificationBellAnchor
import com.example.myapplication.ui.home.updates.EpisodeNotificationTray
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.tween
import com.example.myapplication.ui.shared.frostedGlass
import com.example.myapplication.ui.shared.FrostedMaterials
import com.example.myapplication.ui.shared.LocalModernUi
import com.example.myapplication.ui.shared.LocalAdaptiveGlassEnabled
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.example.myapplication.ui.shared.components.pulltorefresh.PullRefreshIndicator
import com.example.myapplication.ui.shared.components.pulltorefresh.rememberPullRefreshController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.myapplication.ui.shared.GlassBackdropRecovery
import com.example.myapplication.ui.shared.ListSyncLoadingOverlay
import com.example.myapplication.ui.shared.LocalAdaptiveGlassScrollInProgress
import com.example.myapplication.ui.shared.rememberDockAutoHide

import com.example.myapplication.GlassActionDock
import com.example.myapplication.GlassBottomNavigation
import com.example.myapplication.GenreFilterOverlay
import com.example.myapplication.NotificationSyncOverlay
import com.example.myapplication.SimpGlassCard
import com.example.myapplication.SortFilterOverlay
import com.example.myapplication.data.models.*
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.domain.seasons.franchiseEpisodeTotal
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.sync.ExternalListSyncCoordinator
import com.example.myapplication.sync.supabase.CollectionImageRestoreCoordinator
import com.example.myapplication.sync.supabase.SupabaseSyncCoordinator
import com.example.myapplication.utils.getCloudSyncPillStrings
import com.example.myapplication.utils.getStrings
import com.example.myapplication.utils.performHaptic
import com.example.myapplication.ui.home.recommendations.DiscoveryCard
import com.example.myapplication.ui.home.recommendations.RecommendationsSheet
import com.example.myapplication.ui.home.recommendations.RecommendationsUiState
import com.example.myapplication.ui.home.recommendations.RecommendationsViewModel
import com.example.myapplication.ui.home.recommendations.getRecommendationsStrings
import com.example.myapplication.ui.home.cardmenu.CardActionMenuOverlay
import com.example.myapplication.ui.home.cardmenu.CardMenuTarget
import com.example.myapplication.ui.home.updates.EpisodeUpdateStack
import com.example.myapplication.ui.shared.LocalWorkspaceSearch
import com.example.myapplication.ui.navigation.navigateToAddEdit
import com.example.myapplication.ui.navigation.navigateToDetails
import com.example.myapplication.ui.navigation.navigateToInspect
import com.example.myapplication.ui.navigation.navigateToWelcome
import com.example.myapplication.ui.navigation.navigateToSettings
import com.example.myapplication.ui.shared.theme.*
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    /**
     * true — главная показана страницей рабочей области. Отсюда следует всё сразу:
     * свой нижний док она не рисует (навигацией занят док рабочей области), действия над
     * карточкой открываются долгим удержанием вместо свайпов, а средняя кнопка верхнего дока
     * открывает статистику (панель подключения переехала в настройки).
     *
     * Значение постоянно на всё время жизни вызова (маршрут — всегда false, страница — всегда
     * true): переключать его на лету нельзя, размонтирование потребителя роняет запись
     * [layerBackdrop] в плоскую заливку.
     */
    hostedInWorkspace: Boolean = false,
    /** Сообщает наружу, что карточка выделена: хозяин запирает свайп страниц. */
    onCardSelectionChange: (Boolean) -> Unit = {},
    /** Сообщает наружу, что открыт любой оверлей/шторка/диалог: хозяин прячет док. */
    onOverlayVisibleChange: (Boolean) -> Unit = {},
    /**
     * Список скроллится (жест или инерция). Хозяин переводит стекло своего дока в экономный
     * режим: `LocalAdaptiveGlassScrollInProgress` предоставляется внутри этой страницы и до
     * соседнего дока не достаёт.
     */
    onContentScrollChange: (Boolean) -> Unit = {},
    /** Автоскрытие (D9): скролл вниз прячет док хозяина, скролл вверх возвращает. */
    onDockVisibleChange: (Boolean) -> Unit = {},
) {
    val genreRepository: GenreRepository = koinInject()
    val listSyncCoordinator: ExternalListSyncCoordinator = koinInject()
    val supabaseSyncCoordinator: SupabaseSyncCoordinator = koinInject()
    val collectionImageRestoreCoordinator: CollectionImageRestoreCoordinator = koinInject()
    val listSyncUi by listSyncCoordinator.syncUiState.collectAsStateWithLifecycle()
    val isSupabaseSyncing by supabaseSyncCoordinator.isSyncing.collectAsStateWithLifecycle()
    val isCloudImageRestoring by collectionImageRestoreCoordinator.isRestoring.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.uiLanguage.collectAsStateWithLifecycle()
    val strings = getStrings(currentLanguage)
    val cloudSyncPillStrings = getCloudSyncPillStrings(currentLanguage)
    val syncReport by viewModel.syncReport.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val list by viewModel.animeListFlow.collectAsStateWithLifecycle()
    // Старт «полностью отрисован», когда коллекция на экране: система пишет это время в лог
    // (Fully drawn), и по нему считается холодный старт, а не по первому кадру сплэша.
    ReportDrawnWhen { uiState.isListLoaded }
    val playerPromoDismissed by viewModel.playerPromoDismissed.collectAsStateWithLifecycle()
    val playerPromoDeferred by viewModel.playerPromoDeferredThisSession.collectAsStateWithLifecycle()
    val webLinksMap by viewModel.webLinks.collectAsStateWithLifecycle()
    val airingMap by viewModel.airingProgress.collectAsStateWithLifecycle()
    val watchedMap by viewModel.watchedEpisodes.collectAsStateWithLifecycle()
    val mangaReadingMap by viewModel.mangaReading.collectAsStateWithLifecycle()
    val seasonLayoutMap by viewModel.seasonLayouts.collectAsStateWithLifecycle()
    var cloudSyncPillDismissed by remember { mutableStateOf(false) }
    val showCloudSyncPill =
        uiState.isListLoaded &&
            !cloudSyncPillDismissed &&
            (isSupabaseSyncing || isCloudImageRestoring)
    val dismissCloudSyncPill = { cloudSyncPillDismissed = true }
    val kbd = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val ctx = LocalContext.current
    var showCSheet by remember { mutableStateOf(false) }
    var showRecsSheet by remember { mutableStateOf(false) }
    /** Инкремент перемонтирует LazyColumn+layerBackdrop после закрытия оверлеев (см. GlassBackdropRecovery). */
    var layerBackdropResetKey by remember { mutableIntStateOf(0) }
    val recommendationsViewModel: RecommendationsViewModel = koinViewModel()
    val recsState by recommendationsViewModel.uiState.collectAsStateWithLifecycle()
    val recsStrings = getRecommendationsStrings(currentLanguage)
    var isSearchVisible by remember { mutableStateOf(false) }
    var showNotificationsOverlay by remember { mutableStateOf(false) }
    var notificationsBlockingChildDialog by remember { mutableStateOf(false) }
    val notifVisibleState = remember { MutableTransitionState(false) }
    notifVisibleState.targetState = showNotificationsOverlay

    var showSortOverlay by remember { mutableStateOf(false) }
    val sortVisibleState = remember { MutableTransitionState(false) }
    sortVisibleState.targetState = showSortOverlay

    var showMediaTypeFilterOverlay by remember { mutableStateOf(false) }
    val mediaTypeFilterVisibleState = remember { MutableTransitionState(false) }
    mediaTypeFilterVisibleState.targetState = showMediaTypeFilterOverlay

    val genreFilterVisibleState = remember { MutableTransitionState(false) }
    genreFilterVisibleState.targetState = uiState.isGenreFilterVisible

    val searchFocusRequester = remember { FocusRequester() }
    var animeToDelete by remember { mutableStateOf<Anime?>(null) }
    var animeToFavorite by remember { mutableStateOf<Anime?>(null) }
    var pendingSwipeReset by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    /** Карточка, поднятая долгим удержанием (контекстное меню). */
    var cardMenuTarget by remember { mutableStateOf<CardMenuTarget?>(null) }
    // Отдельно от цели: меню обязано дожить до конца своего закрытия, см. CardActionMenuOverlay.
    var cardMenuVisible by remember { mutableStateOf(false) }
    LaunchedEffect(cardMenuTarget != null) { onCardSelectionChange(cardMenuTarget != null) }
    DisposableEffect(Unit) {
        onDispose {
            onCardSelectionChange(false)
            onOverlayVisibleChange(false)
        }
    }
    // Запись пропала из списка (удалена, отфильтрована, сменился поиск) — выделение снимаем:
    // держать поднятой карточку, которой больше нет, нельзя.
    LaunchedEffect(list) {
        val id = cardMenuTarget?.state?.id ?: return@LaunchedEffect
        if (list.none { it.id == id }) cardMenuTarget = null
    }
    val scope = rememberCoroutineScope()
    val playerPromoTarget = remember(list) {
        val playable = list.filter { it.mediaType == MediaType.ANIME && it.episodes > 0 }
        (playable.ifEmpty { list.filter { it.episodes > 0 } })
            .maxWithOrNull(compareBy<Anime> { it.rating }.thenBy { it.episodes })
    }
    if (
        uiState.isListLoaded &&
        playerPromoTarget != null &&
        !playerPromoDismissed &&
        !playerPromoDeferred
    ) {
        PlayerPowerPromoDialog(
            language = currentLanguage,
            onTry = {
                val target = playerPromoTarget ?: return@PlayerPowerPromoDialog
                performHaptic(view, "light")
                viewModel.dismissPlayerPromoPermanently()
                navController.navigateToDetails(target.id, openEpisodes = true)
            },
            onLater = {
                performHaptic(view, "light")
                viewModel.deferPlayerPromoForSession()
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val activity = ctx as? Activity ?: return@LaunchedEffect
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.checkForUpdates()
    }

    // Пока приложение на переднем плане — системные пуши обновлений не нужны
    // (показываем in-app стопкой). Снимаем их из шторки при каждом ON_START.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                viewModel.clearSystemUpdateNotifications()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isSupabaseSyncing, isCloudImageRestoring) {
        if (!isSupabaseSyncing && !isCloudImageRestoring) {
            cloudSyncPillDismissed = false
        }
    }

    // Правило автоскрытия общее с доком рабочей области — оно живёт в `rememberDockAutoHide`.
    val dockAutoHide = rememberDockAutoHide()
    // Поиск в рабочей области открывается кнопкой дока, а док живёт снаружи пейджера —
    // общаются они заявками (см. WorkspaceSearchState). В классическом режиме связи нет:
    // там кнопка поиска стоит в том же доке, что и всё остальное.
    val workspaceSearch = LocalWorkspaceSearch.current
    // Рабочая область всегда с капсульным доком: статистика и синхронизация живут в его меню.
    val mergedDockMode = hostedInWorkspace
    if (hostedInWorkspace) {
        LaunchedEffect(isSearchVisible) { workspaceSearch.report(isSearchVisible) }
        // Уехали на дальний раздел — пейджер выбрасывает страницу из композиции вместе с её
        // состоянием поиска. Не сообщить об этом значит оставить кнопку дока подсвеченной над
        // поиском, которого больше нет: первое нажатие тогда уходило бы впустую, на «закрыть».
        DisposableEffect(Unit) { onDispose { workspaceSearch.report(false) } }
        LaunchedEffect(workspaceSearch.pending) {
            when (workspaceSearch.consume()) {
                true -> isSearchVisible = true
                false -> {
                    isSearchVisible = false
                    viewModel.updateSearchQuery("")
                    focusManager.clearFocus()
                    kbd?.hide()
                }
                null -> Unit
            }
        }
    }

    val finalDockVisible = dockAutoHide.visible || isSearchVisible

    BackHandler(enabled = isSearchVisible || uiState.searchQuery.isNotEmpty()) {
        performHaptic(view, "light")
        isSearchVisible = false
        viewModel.updateSearchQuery("")
        focusManager.clearFocus()
        kbd?.hide()
    }

    val listState = rememberLazyListState()
    // Флаг скролла читается подпиской, а не в композиции: иначе начало и конец каждого жеста
    // пересобирали бы всю главную.
    val currentOnContentScrollChange by rememberUpdatedState(onContentScrollChange)
    // Новый интерфейс: обновления серий показываются стопкой только до первого действия
    // пользователя, дальше живут в колокольчике у верхнего дока (см. EpisodeNotificationTray).
    val notificationTray: EpisodeNotificationTray = koinInject()
    val bellAnchor = remember { NotificationBellAnchor() }
    var notificationCenterOpen by remember { mutableStateOf(false) }
    var updateStackGone by remember { mutableStateOf(notificationTray.collapsed) }
    val collapseUpdatesToBell: () -> Unit = { if (hostedInWorkspace) notificationTray.collapse() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (inProgress) {
                cloudSyncPillDismissed = true
                collapseUpdatesToBell()
            }
            currentOnContentScrollChange(inProgress)
        }
    }

    val isHeaderFloating by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 20 } }
    val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }
    val bgColor = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(bgColor)
        drawContent()
    }

    // showRecsSheet НЕ в shouldBlur: RenderEffect на родителе layerBackdrop портит запись
    // стекла → после закрытия доки заливаются сплошным bgColor. У шторки свой scrim.
    val shouldBlur = (isSearchVisible && uiState.searchQuery.isBlank()) ||
            showCSheet || animeToDelete != null || animeToFavorite != null ||
            uiState.isGenreFilterVisible || showNotificationsOverlay || showSortOverlay ||
            showMediaTypeFilterOverlay || listSyncUi.isRunning || cardMenuTarget != null
    // Анимированные значения держим как State и читаем в лямбдах слоя/раскладки: чтение в
    // композиции пересобирало всю главную на каждом кадре открытия и закрытия оверлея.
    val blurAmount = animateDpAsState(
        targetValue = when {
            notificationsBlockingChildDialog -> 20.dp
            shouldBlur -> 10.dp
            else -> 0.dp
        },
        label = "blur"
    )

    // Кнопка «вверх» опускается к низу, когда док скрыт (как кнопка поиска), и поднимается вместе с доком.
    // При видимом доке держим её выше плавающей кнопки поиска (её верх ≈162dp над нав-панелью),
    // чтобы кнопки не слипались.
    val scrollToTopLift = animateDpAsState(
        targetValue = when {
            !finalDockVisible -> 88.dp
            // В рабочей области своего нижнего дока у главной нет, а чужой ниже и тоньше:
            // 180dp держали пустоту под несуществующей кнопкой поиска.
            hostedInWorkspace -> 112.dp
            else -> 180.dp
        },
        animationSpec = MotionTokens.standard(),
        label = "scrollToTopBottom"
    )

    val openWorkspaceSort: () -> Unit = {
        performHaptic(view, "light")
        dismissCloudSyncPill()
        showSortOverlay = !showSortOverlay
        if (showSortOverlay) {
            showNotificationsOverlay = false
            showMediaTypeFilterOverlay = false
            viewModel.setGenreFilterVisible(false)
        }
    }
    val openWorkspaceNotifications: () -> Unit = {
        performHaptic(view, "light")
        dismissCloudSyncPill()
        showNotificationsOverlay = !showNotificationsOverlay
        if (showNotificationsOverlay) {
            showSortOverlay = false
            showMediaTypeFilterOverlay = false
            viewModel.setGenreFilterVisible(false)
        }
    }
    val notificationStrings = remember(currentLanguage) { notificationStrings(currentLanguage) }
    val openNotificationCenter: () -> Unit = {
        performHaptic(view, "light")
        dismissCloudSyncPill()
        notificationTray.collapse()
        notificationCenterOpen = !notificationCenterOpen
    }
    val openMediaTypeFilter: () -> Unit = {
        performHaptic(view, "light")
        dismissCloudSyncPill()
        showMediaTypeFilterOverlay = !showMediaTypeFilterOverlay
        if (showMediaTypeFilterOverlay) {
            showSortOverlay = false
            showNotificationsOverlay = false
            viewModel.setGenreFilterVisible(false)
        }
    }

    // «Вдавливание» контента Home под открытой bottom-sheet шторкой (iOS §2.4).
    // showRecsSheet исключён: scale+clip на родителе layerBackdrop ломает стекло так же,
    // как RenderEffect (см. shouldBlur). Шторка рекомендаций перекрывает экран своим scrim.
    val anyHomeSheetOpen = showMediaTypeFilterOverlay || showSortOverlay ||
        uiState.isGenreFilterVisible || showNotificationsOverlay || showCSheet
    val homePushProgress = animateFloatAsState(
        targetValue = if (anyHomeSheetOpen) 1f else 0f,
        animationSpec = MotionTokens.sheetPresent(),
        label = "homePush",
    )
    // Для восстановления стекла важен только момент «анимации над layerBackdrop доиграли».
    val effectsSettled by remember {
        derivedStateOf { blurAmount.value <= 0.dp && homePushProgress.value <= 0.001f }
    }

    // Любая шторка/диалог/оверлей поверх главной → док рабочей области уезжает вниз: он
    // соседний узел, сам про эти состояния не знает.
    val anyOverlayVisible = shouldBlur || anyHomeSheetOpen || showRecsSheet || isSearchVisible ||
        notificationCenterOpen
    LaunchedEffect(anyOverlayVisible) {
        onOverlayVisibleChange(anyOverlayVisible)
        if (anyOverlayVisible) collapseUpdatesToBell()
    }
    LaunchedEffect(finalDockVisible) { onDockVisibleChange(finalDockVisible) }
    // Уход со страницы посреди скролла не должен оставить чужой док спрятанным или его стекло
    // в экономном режиме навсегда.
    DisposableEffect(Unit) {
        onDispose {
            onContentScrollChange(false)
            onDockVisibleChange(true)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {},
        floatingActionButton = {},
        // Иначе system bar insets добавляются в paddingValues — фон не заливает полосы сверху/снизу
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
    ) { paddingValues ->
        // Экономный режим стекла на время скролла нужен только адаптивному жидкому стеклу; без
        // него флаг не читаем вовсе, чтобы жест не пересобирал корень главной.
        val glassScrollInProgress = if (LocalAdaptiveGlassEnabled.current && !LocalModernUi.current) {
            val inProgress by remember { derivedStateOf { listState.isScrollInProgress } }
            inProgress
        } else {
            false
        }
        CompositionLocalProvider(LocalAdaptiveGlassScrollInProgress provides glassScrollInProgress) {
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Box(modifier = Modifier.fillMaxSize().background(bgColor))

            if (notifVisibleState.currentState || notifVisibleState.targetState) {
                Box(modifier = Modifier.zIndex(5f).fillMaxSize()) {
                    NotificationSyncOverlay(
                        syncCoordinator = koinInject(),
                        authRepository = koinInject(),
                        visibleState = notifVisibleState,
                        strings = getStrings(currentLanguage),
                        syncReport = syncReport,
                        updates = uiState.updates,
                        isCheckingUpdates = uiState.isCheckingUpdates,
                        currentLanguage = currentLanguage,
                        onDismiss = { showNotificationsOverlay = false },
                        onLogout = {
                            navController.navigateToWelcome()
                        },
                        onCheckUpdates = {
                            performHaptic(view, "light")
                            viewModel.checkForUpdates(force = true)
                        },
                        onBlockingChildDialogChange = { notificationsBlockingChildDialog = it }
                    )
                }
            }

            if (sortVisibleState.currentState || sortVisibleState.targetState) {
                Box(modifier = Modifier.zIndex(5f).fillMaxSize()) {
                    SortFilterOverlay(
                        visibleState = sortVisibleState,
                        strings = getStrings(currentLanguage),
                        sortOption = uiState.sortOption,
                        sortAscending = uiState.sortAscending,
                        filterSelectedTags = uiState.filterTags,
                        onDismiss = { showSortOverlay = false },
                        onApplySort = { option, isAscending ->
                            performHaptic(view, "light")
                            viewModel.applySort(option, isAscending)
                        },
                        onApplyOpenGenreFilter = {
                            performHaptic(view, "light")
                            viewModel.toggleGenreFilter()
                        }
                    )
                }
            }

            if (genreFilterVisibleState.currentState || genreFilterVisibleState.targetState) {
                Box(modifier = Modifier.zIndex(5f).fillMaxSize()) {
                    GenreFilterOverlay(
                        visibleState = genreFilterVisibleState,
                        strings = getStrings(currentLanguage),
                        filterSelectedTags = uiState.filterTags,
                        filterCategoryType = uiState.filterCategory,
                        currentLanguage = currentLanguage,
                        onTagToggle = { tag, categoryType ->
                            val currentTags = uiState.filterTags.toMutableList()
                            if (currentTags.contains(tag)) {
                                currentTags.remove(tag)
                                val newCategory = if (currentTags.isEmpty()) "" else uiState.filterCategory
                                viewModel.updateFilterTags(currentTags, newCategory)
                            } else {
                                if (currentTags.size < 3 && (uiState.filterCategory.isEmpty() || uiState.filterCategory == categoryType)) {
                                    currentTags.add(tag)
                                    viewModel.updateFilterTags(currentTags, categoryType)
                                }
                            }
                        },
                        onDismiss = { viewModel.toggleGenreFilter() }
                    )
                }
            }

            if (mediaTypeFilterVisibleState.currentState || mediaTypeFilterVisibleState.targetState) {
                Box(modifier = Modifier.zIndex(5f).fillMaxSize()) {
                    MediaTypeFilterOverlay(
                        visibleState = mediaTypeFilterVisibleState,
                        strings = getStrings(currentLanguage),
                        selected = uiState.libraryMediaTypeFilter,
                        onSelect = { viewModel.setLibraryMediaTypeFilter(it) },
                        onDismiss = { showMediaTypeFilterOverlay = false },
                    )
                }
            }

            if (listSyncUi.isRunning) {
                Box(
                    modifier = Modifier
                        .zIndex(9f)
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = OverlayThemeTokens.ScrimAlpha)),
                    contentAlignment = Alignment.Center
                ) {
                    ListSyncLoadingOverlay(
                        message = listSyncUi.message,
                        processed = listSyncUi.processed,
                        total = listSyncUi.total,
                        strings = strings,
                        onDismiss = {
                            performHaptic(view, "light")
                            listSyncCoordinator.cancelListSync()
                        }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Якорь сверху: при «вдавливании» не открывается чёрный letterbox
                        // под status bar — контент остаётся edge-to-edge до выреза камеры.
                        val push = homePushProgress.value
                        val s = androidx.compose.ui.util.lerp(1f, 0.94f, push)
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        clip = push > 0.001f
                        shape = SquircleCornerShape(
                            topStart = 0.dp,
                            topEnd = 0.dp,
                            bottomEnd = 16.dp * push,
                            bottomStart = 16.dp * push,
                        )
                    }
                    .homeScrollBlur { blurAmount.value }
            ) {
                Box(modifier = Modifier.fillMaxSize().weight(1f).background(bgColor)) {
                    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
                    val apiSearchModels by viewModel.apiSearchWithStatus.collectAsStateWithLifecycle()

                    val refreshPullState = rememberPullToRefreshState()
                    val refreshController = rememberPullRefreshController(refreshPullState, isRefreshing, view)
                    val refreshRevealMax = 96.dp

                    // Индикатор pull-to-refresh лежит ЗА контентом (рисуется раньше него) и
                    // открывается в зазоре сверху, когда контент уезжает вниз. Он — СИБЛИНГ контента,
                    // а НЕ его предок, поэтому не оборачивает layerBackdrop-список и не ломает стекло дока.
                    PullRefreshIndicator(controller = refreshController, revealMax = refreshRevealMax)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(dockAutoHide.connection)
                            // Край списка — общая iOS-«резинка» (LocalOverscrollFactory в корне);
                            // верхнюю протяжку первым забирает pull-to-refresh как родитель в
                            // nested scroll, поэтому резинка сверху при обновлении не спорит с ним.
                            // Сдвиг контента = overscroll + раскрытие pull-to-refresh. Тот же
                            // СУЩЕСТВУЮЩИЙ offset-узел (не новая нода над layerBackdrop) — стекло
                            // дока остаётся целым.
                            .offset {
                                val reveal = (refreshController.revealFraction() * refreshRevealMax.toPx()).roundToInt()
                                IntOffset(0, reveal)
                            }
                    ) {
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = {
                                dismissCloudSyncPill()
                                // Жест сработал → индикатор фиксируется в оттянутом положении
                                // минимум на 2с (см. PullRefreshController).
                                refreshController.notifyRefreshInvoked()
                                viewModel.refreshList()
                            },
                            state = refreshPullState,
                            // Свой визуал рисуем сами, стоковый индикатор выключаем.
                            indicator = {},
                            modifier = Modifier.fillMaxSize()
                        ) {
                            key(layerBackdropResetKey) {
                            LazyColumn(
                                flingBehavior = IosScroll.flingBehavior(),
                                state = listState,
                                contentPadding = PaddingValues(top = 0.dp, bottom = 0.dp, start = 0.dp, end = 0.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .layerBackdrop(backdrop)
                            ) {
                                item {
                                    VetroWorkspaceTopBar(
                                        strings = getStrings(currentLanguage),
                                    )
                                }

                                val recsReady = recsState as? RecommendationsUiState.Ready
                                if (recsReady != null && recsReady.items.isNotEmpty() && uiState.searchQuery.isEmpty()) {
                                    item(key = "discovery_card", contentType = "discovery_card") {
                                        DiscoveryCard(
                                            state = recsReady,
                                            strings = recsStrings,
                                            onClick = {
                                                performHaptic(view, "light")
                                                dismissCloudSyncPill()
                                                showRecsSheet = true
                                            },
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                        )
                                    }
                                }

                                val showApiFirst = list.isEmpty() && uiState.searchQuery.isNotEmpty()

                                if (showApiFirst) {
                                    apiSearchResultsSection(
                                        strings = strings,
                                        apiSearchModels = apiSearchModels,
                                        uiState = uiState,
                                        currentLanguage = currentLanguage,
                                        genreRepository = genreRepository,
                                        view = view,
                                        viewModel = viewModel,
                                        topPadding = 8.dp
                                    )
                                    when {
                                        uiState.apiSearchLoading -> Unit
                                        uiState.apiSearchError != null -> Unit
                                        apiSearchModels.isNotEmpty() -> item {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 24.dp, horizontal = 16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = strings.noResultsInLibrary,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                        else -> item {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 32.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                EmptyStateView(
                                                    title = strings.noResults,
                                                    subtitle = ""
                                                )
                                            }
                                        }
                                    }
                                } else if (list.isNotEmpty()) {
                                    items(
                                        items = list,
                                        key = { it.id },
                                        contentType = { "anime_card" }
                                    ) { anime ->
                                        val webLinksEntry = webLinksMap[anime.id]
                                            val airingEntry = airingMap[anime.id]
                                            val cardProgress = rememberCardProgress(
                                                totalEpisodes = franchiseEpisodeTotal(
                                                    layout = seasonLayoutMap[anime.id],
                                                    storedEpisodes = anime.episodes,
                                                ),
                                                watched = watchedMap[anime.id],
                                                airing = airingEntry,
                                                reading = mangaReadingMap[anime.id],
                                            )
                                            val cardState = remember(anime, currentLanguage, webLinksEntry, cardProgress) {
                                                // Название по выбранному языку: EN → английское, RU → русское.
                                                // Замена, а не вторая строка; при отсутствии перевода — исходный title.
                                                val displayTitle = when (currentLanguage) {
                                                    AppLanguage.EN -> anime.titleEn?.takeIf { it.isNotBlank() } ?: anime.title
                                                    AppLanguage.RU -> anime.titleRu?.takeIf { it.isNotBlank() } ?: anime.title
                                                }
                                                val links = when (currentLanguage) {
                                                    AppLanguage.EN -> webLinksEntry?.enLinks
                                                    AppLanguage.RU -> webLinksEntry?.ruLinks
                                                }.orEmpty()
                                                AnimeCardState(
                                                    id = anime.id,
                                                    title = displayTitle,
                                                    titleEn = null,
                                                    rating = anime.rating,
                                                    genres = persistentListOf(
                                                        *anime.tags.take(3)
                                                            .mapNotNull { genreRepository.getLabel(it, currentLanguage).takeIf { n -> n.isNotBlank() } }
                                                            .toTypedArray()
                                                    ),
                                                    episodesCount = anime.episodes,
                                                    // У манги счёт идёт по главам — иначе на
                                                    // одной карточке соседствовали бы
                                                    // «12 / 60 ch.» и «60 eps.».
                                                    episodesUnit = when (anime.mediaType) {
                                                        com.example.myapplication.data.models.MediaType.MANGA -> "ch."
                                                        else -> "eps."
                                                    },
                                                    webLinks = links,
                                                    language = currentLanguage,
                                                    imagePath = viewModel.getImgPath(anime.imageFileName),
                                                    mediaTypeLabel = when (anime.mediaType) {
                                                        com.example.myapplication.data.models.MediaType.ANIME -> strings.typeAnime
                                                        com.example.myapplication.data.models.MediaType.MANGA -> strings.typeManga
                                                        com.example.myapplication.data.models.MediaType.MOVIE -> strings.typeMovie
                                                        com.example.myapplication.data.models.MediaType.SERIES -> strings.typeSeries
                                                    },
                                                    airing = cardProgress,
                                                    isFavorite = anime.isFavorite,
                                                )
                                            }

                                        // Тап по карточке — полноэкранные детали; кнопка справа-внизу — редактирование.
                                        val openDetails: () -> Unit = {
                                            performHaptic(view, "light")
                                            collapseUpdatesToBell()
                                            navController.navigateToDetails(anime.id)
                                        }
                                        val openEdit: () -> Unit = {
                                            performHaptic(view, "light")
                                            navController.navigateToAddEdit(anime.id)
                                        }
                                        val rowModifier = Modifier.padding(horizontal = 16.dp)
                                        // .animateItem() не добавляем: конфликт с SharedTransition при возврате.

                                        if (hostedInWorkspace) {
                                            // Горизонтальная ось отдана навигации: действия — по удержанию.
                                            var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                                            Box(
                                                modifier = rowModifier.onGloballyPositioned { coords = it },
                                            ) {
                                                with(sharedTransitionScope) {
                                                    OneUiAnimeCard(
                                                        state = cardState,
                                                        animatedVisibilityScope = animatedVisibilityScope,
                                                        onClick = openDetails,
                                                        onEditClick = openEdit,
                                                        onLongClick = {
                                                            val bounds = coords?.boundsInRoot() ?: return@OneUiAnimeCard
                                                            performHaptic(view, "light")
                                                            cardMenuTarget = CardMenuTarget(
                                                                state = cardState,
                                                                isFavorite = anime.isFavorite,
                                                                boundsInRoot = bounds,
                                                            )
                                                            cardMenuVisible = true
                                                        },
                                                    )
                                                }
                                            }
                                        } else {
                                            val dismissState = rememberSwipeToDismissBoxState(
                                                positionalThreshold = { totalDistance -> totalDistance * 0.4f }
                                            )
                                            LaunchedEffect(dismissState.currentValue) {
                                                when (dismissState.currentValue) {
                                                    SwipeToDismissBoxValue.StartToEnd -> {
                                                        performHaptic(view, "success")
                                                        animeToFavorite = anime
                                                        pendingSwipeReset = { dismissState.reset() }
                                                    }
                                                    SwipeToDismissBoxValue.EndToStart -> {
                                                        performHaptic(view, "warning")
                                                        animeToDelete = anime
                                                        pendingSwipeReset = { dismissState.reset() }
                                                    }
                                                    SwipeToDismissBoxValue.Settled -> Unit
                                                }
                                            }
                                            SwipeToDismissBox(
                                                state = dismissState,
                                                backgroundContent = { SwipeBackground(dismissState) },
                                                modifier = rowModifier
                                            ) {
                                                with(sharedTransitionScope) {
                                                    OneUiAnimeCard(
                                                        state = cardState,
                                                        animatedVisibilityScope = animatedVisibilityScope,
                                                        onClick = openDetails,
                                                        onEditClick = openEdit,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (uiState.searchQuery.isNotEmpty()) {
                                        apiSearchResultsSection(
                                            strings = strings,
                                            apiSearchModels = apiSearchModels,
                                            uiState = uiState,
                                            currentLanguage = currentLanguage,
                                            genreRepository = genreRepository,
                                            view = view,
                                            viewModel = viewModel,
                                            topPadding = 24.dp
                                        )
                                    }
                                } else {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillParentMaxSize()
                                                .padding(bottom = 120.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            EmptyStateView(
                                                title = strings.emptyTitle,
                                                subtitle = strings.emptySubtitle
                                            )
                                        }
                                    }
                                }
                                item(key = "home_bottom_dock_spacer") {
                                    Spacer(Modifier.height(220.dp))
                                }
                            }
                            } // key(layerBackdropResetKey)
                        }
                    }
                    CloudSyncPill(
                        visible = showCloudSyncPill,
                        label = cloudSyncPillStrings.label,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 8.dp)
                            .zIndex(4f),
                    )
                }
            }

            if (!hostedInWorkspace && !isSearchVisible && animeToDelete == null && animeToFavorite == null && !showCSheet) {
                Box(modifier = Modifier.align(Alignment.BottomCenter).zIndex(3f).navigationBarsPadding()) {
                    AnimatedVisibility(
                        // !showRecsSheet — внутри visible, НЕ в структурном if выше: иначе
                        // размонтирование ветки инвалидирует layerBackdrop без перезаписи,
                        // и всё стекло заливается сплошным цветом (см. homeScrollBlur).
                        visible = finalDockVisible && !showRecsSheet,
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = MotionTokens.sheetPresent()
                        ) + fadeIn(animationSpec = tween(250)),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = MotionTokens.sheetDismissForced()
                        ) + fadeOut(animationSpec = tween(200))
                    ) {
                        GlassBottomNavigation(
                            backdrop = backdrop,
                            nav = navController,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            currentLanguage = currentLanguage,
                            onShowStats = {
                                dismissCloudSyncPill()
                                showCSheet = true
                            },
                            onShowNotifs = {},
                            onInspectClick = {
                                performHaptic(view, "light")
                                dismissCloudSyncPill()
                                navController.navigateToInspect()
                            },
                            onSearchClick = {
                                performHaptic(view, "light")
                                dismissCloudSyncPill()
                                isSearchVisible = !isSearchVisible
                                if (!isSearchVisible) {
                                    viewModel.updateSearchQuery("")
                                    focusManager.clearFocus()
                                    kbd?.hide()
                                }
                            },
                            onSettingsClick = {
                                dismissCloudSyncPill()
                                navController.navigateToSettings()
                            },
                            isSearchActive = isSearchVisible,
                            modifier = Modifier,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isSearchVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp).windowInsetsPadding(WindowInsets.ime).padding(bottom = 16.dp).zIndex(10f)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val filters = listOf(
                            com.example.myapplication.data.models.MediaType.ANIME to strings.typeAnime,
                            com.example.myapplication.data.models.MediaType.MANGA to strings.typeManga,
                            // Одна опция на MOVIE+SERIES (был единый TV_SERIES) — при выборе ищем
                            // раздел SERIES (см. HomeViewModel), MOVIE-раздел отсюда пока не достижим.
                            com.example.myapplication.data.models.MediaType.SERIES to strings.typeSeries
                        )
                        filters.forEach { (type, label) ->
                            val isSelected = uiState.searchMediaTypeFilter == type
                            val scale by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = if (isSelected) 1.1f else 1.0f,
                                animationSpec = MotionTokens.menuPop(),
                                label = "scale"
                            )
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .scale(scale)
                                    .clip(CircleShape)
                                    .clickable { viewModel.setSearchMediaTypeFilter(type) }
                            ) {
                                com.example.myapplication.SimpGlassCard(
                                    backdrop = backdrop,
                                    shape = CircleShape,
                                    modifier = Modifier.matchParentSize()
                                ) {}
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    // Цвет от темы, как у поля поиска под чипами: матовое стекло в
                                    // светлой теме почти белое, и белые подписи на нём пропадали.
                                    Text(
                                        text = label,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        },
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontFamily = SnProFamily
                                    )
                                }
                            }
                        }
                    }
                    SimpGlassCard(backdrop = backdrop, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        BasicTextField(
                            value = uiState.searchQuery,
                            onValueChange = {
                                viewModel.updateSearchQuery(it)
                                if (it.isNotEmpty()) performHaptic(view, "light")
                            },
                            modifier = Modifier.fillMaxSize().focusRequester(searchFocusRequester).padding(horizontal = 20.dp),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, fontFamily = SnProFamily),
                            cursorBrush = SolidColor(BrandBlue),
                            decorationBox = { innerTextField ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = BrandBlue)
                                    Spacer(Modifier.width(12.dp))
                                    Box {
                                        if (uiState.searchQuery.isEmpty()) {
                                            Text("Search in collection...", color = MaterialTheme.colorScheme.secondary, fontSize = 16.sp, fontFamily = SnProFamily)
                                        }
                                        innerTextField()
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                kbd?.hide()
                                focusManager.clearFocus()
                            })
                        )
                    }
                }
                LaunchedEffect(Unit) {
                    searchFocusRequester.requestFocus()
                    kbd?.show()
                }
            }

            if (shouldBlur && animeToDelete == null && animeToFavorite == null && !showNotificationsOverlay && !showSortOverlay && !uiState.isGenreFilterVisible) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    focusManager.clearFocus()
                    if (isSearchVisible) {
                        viewModel.updateSearchQuery("")
                    }
                    isSearchVisible = false
                    kbd?.hide()
                }.zIndex(2f))
            }

            if (animeToDelete != null) {
                AnimeListMenuSheet(
                    anime = animeToDelete!!,
                    confirmMode = AnimeMenuConfirmMode.DELETE,
                    strings = strings,
                    language = currentLanguage,
                    getImgPath = { viewModel.getImgPath(it) },
                    onEvent = { event ->
                        when (event) {
                            is AnimeMenuEvent.OnConfirm -> viewModel.deleteAnime(animeToDelete!!.id)
                            is AnimeMenuEvent.OnCancel -> { }
                        }
                    },
                    onDismiss = {
                        val reset = pendingSwipeReset
                        pendingSwipeReset = null
                        animeToDelete = null
                        if (reset != null) scope.launch { reset() }
                    }
                )
            }
            if (animeToFavorite != null) {
                AnimeListMenuSheet(
                    anime = animeToFavorite!!,
                    // Свайп вправо — переключатель: у уже избранного он предлагает снять звезду,
                    // а не добавить её второй раз (toggleFavorite снизу и так был двусторонним,
                    // но шит утверждал обратное).
                    confirmMode = if (animeToFavorite!!.isFavorite) {
                        AnimeMenuConfirmMode.REMOVE_FROM_FAVORITE
                    } else {
                        AnimeMenuConfirmMode.ADD_TO_FAVORITE
                    },
                    strings = strings,
                    language = currentLanguage,
                    getImgPath = { viewModel.getImgPath(it) },
                    onEvent = { event ->
                        when (event) {
                            is AnimeMenuEvent.OnConfirm -> viewModel.toggleFavorite(animeToFavorite!!.id)
                            is AnimeMenuEvent.OnCancel -> { }
                        }
                    },
                    onDismiss = {
                        val reset = pendingSwipeReset
                        pendingSwipeReset = null
                        animeToFavorite = null
                        if (reset != null) scope.launch { reset() }
                    }
                )
            }

            // Появление и уход по спеке движения: оболочка — пружиной из 0.92 (точка исхода — сама
            // кнопка), свет — короткими кривыми; уход быстрее входа и без отскока.
            AnimatedVisibility(
                visible = showScrollToTop && !isSearchVisible && animeToDelete == null && animeToFavorite == null,
                enter = fadeIn(tween(MotionTokens.EaseEnterMillis, easing = MotionTokens.EaseEnter)) +
                    scaleIn(MotionTokens.springSurface(), initialScale = 0.92f),
                exit = fadeOut(tween(MotionTokens.EaseExitMillis, easing = MotionTokens.EaseExit)) +
                    scaleOut(MotionTokens.springExit(), targetScale = 0.92f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 24.dp)
                    // Подъём вслед за доком — сдвигом в раскладке, а не отступом: отступ читался
                    // в композиции и пересобирал главную на каждом кадре анимации дока.
                    .offset { IntOffset(0, -scrollToTopLift.value.roundToPx()) }
                    .zIndex(1f)
            ) {
                val onScrollToTop = {
                    performHaptic(view, "light")
                    scope.launch { listState.animateScrollToItem(0) }
                    Unit
                }
                if (LocalModernUi.current) {
                    // Тот же матовый материал, что у дока и круглой кнопки поиска рядом с ним:
                    // жидкое стекло с бликом здесь выглядело чужим среди матовых поверхностей.
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .frostedGlass(backdrop = backdrop, shape = CircleShape, material = FrostedMaterials.dock())
                            .clickable(onClick = onScrollToTop),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Up",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    SimpGlassCard(
                        backdrop = backdrop,
                        shape = CircleShape,
                        modifier = Modifier.size(44.dp).clickable(onClick = onScrollToTop)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Up",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Поверх scrim/blur оверлеев: TopBar-кнопки и Glass dock остаются чёткими
            Box(modifier = Modifier.fillMaxSize().zIndex(30f)) {
                if (!isHeaderFloating) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(top = 20.dp, bottom = 8.dp)
                            .padding(horizontal = 24.dp)
                    ) {
                        if (hostedInWorkspace) {
                            // Колокольчик в развёрнутой шапке — обычная кнопка, как его соседи.
                            NotificationBellButton(
                                count = uiState.updates.size,
                                glass = false,
                                backdrop = backdrop,
                                anchor = bellAnchor,
                                contentDescription = notificationStrings.bell,
                                onClick = openNotificationCenter,
                                size = 48.dp,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 96.dp),
                            )
                        }
                        WorkspaceSortNotificationActions(
                            strings = strings,
                            filterSelectedTags = uiState.filterTags,
                            updatesCount = uiState.updates.size,
                            onOpenSort = openWorkspaceSort,
                            onOpenNotifications = openWorkspaceNotifications,
                            onOpenMediaTypeFilter = openMediaTypeFilter,
                            dockButtonBackground = Color.Transparent,
                            useDockSizing = false,
                            modifier = Modifier.align(Alignment.CenterEnd),
                            // Та же средняя кнопка, что и у плавающего дока ниже: это два вида
                            // ОДНОЙ шапки (список вверху / список прокручен), и разъезжаться
                            // они не должны — иначе статистика видна только в одном из них.
                            middleAction = if (hostedInWorkspace) {
                                TopDockMiddleAction.STATS
                            } else {
                                TopDockMiddleAction.SYNC_PANEL
                            },
                            onOpenStats = {
                                performHaptic(view, "light")
                                dismissCloudSyncPill()
                                showCSheet = true
                            },
                            // В объединённом режиме статистика и синхронизация уехали в меню
                            // нижнего дока — верхнему остаются сортировка и тип контента.
                            showMiddleAction = !mergedDockMode,
                        )
                    }
                }
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(end = 16.dp)) {
                    GlassActionDock(
                        backdrop = backdrop,
                        isFloating = isHeaderFloating,
                        strings = strings,
                        filterSelectedTags = uiState.filterTags,
                        updates = uiState.updates,
                        onOpenSort = openWorkspaceSort,
                        onOpenNotifications = openWorkspaceNotifications,
                        onOpenMediaTypeFilter = openMediaTypeFilter,
                        modifier = Modifier.padding(top = 12.dp),
                        // В рабочей области середина верхнего дока — статистика: панель
                        // подключения оттуда уехала отдельным пунктом в настройки.
                        middleAction = if (hostedInWorkspace) {
                            TopDockMiddleAction.STATS
                        } else {
                            TopDockMiddleAction.SYNC_PANEL
                        },
                        onOpenStats = {
                            performHaptic(view, "light")
                            dismissCloudSyncPill()
                            showCSheet = true
                        },
                        showMiddleAction = !mergedDockMode,
                        // Колокольчик — отдельной каплей слева от капсулы, не внутри неё.
                        leading = if (hostedInWorkspace) {
                            {
                                NotificationBellButton(
                                    count = uiState.updates.size,
                                    glass = true,
                                    backdrop = backdrop,
                                    anchor = bellAnchor,
                                    contentDescription = notificationStrings.bell,
                                    onClick = openNotificationCenter,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            // iOS-стиль: пуш-стопка обновлений серий у верхней кромки главного экрана,
            // поверх всего. Пропадает, когда пользователь разобрал все карточки. В новом
            // интерфейсе — ещё и когда схлопнулась в колокольчик.
            if (uiState.updates.isNotEmpty() && !(hostedInWorkspace && updateStackGone)) {
                EpisodeUpdateStack(
                    updates = uiState.updates,
                    coverPathFor = { animeId ->
                        viewModel.getImgPath(viewModel.getAnimeById(animeId)?.imageFileName)
                    },
                    onOpen = { update ->
                        performHaptic(view, "light")
                        collapseUpdatesToBell()
                        navController.navigateToDetails(update.animeId)
                    },
                    onDismiss = { update ->
                        performHaptic(view, "light")
                        viewModel.dismissUpdate(update, ctx)
                    },
                    backdrop = backdrop,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(40f)
                        .statusBarsPadding()
                        .padding(top = 8.dp, start = 12.dp, end = 12.dp),
                    collapsing = hostedInWorkspace && notificationTray.collapsed,
                    collapseTarget = { bellAnchor.center },
                    onCollapsed = { updateStackGone = true },
                )
            }

            if (hostedInWorkspace) {
                NotificationCenter(
                    open = notificationCenterOpen,
                    updates = uiState.updates,
                    coverPathFor = { animeId ->
                        viewModel.getImgPath(viewModel.getAnimeById(animeId)?.imageFileName)
                    },
                    bellCenter = { bellAnchor.center },
                    backdrop = backdrop,
                    strings = notificationStrings,
                    onOpenUpdate = { update ->
                        performHaptic(view, "light")
                        notificationCenterOpen = false
                        navController.navigateToDetails(update.animeId)
                    },
                    onDismissUpdate = { update ->
                        performHaptic(view, "light")
                        viewModel.dismissUpdate(update, ctx)
                    },
                    onClearAll = {
                        performHaptic(view, "success")
                        viewModel.markAllUpdatesRead(uiState.updates, ctx)
                        notificationCenterOpen = false
                    },
                    onClose = { notificationCenterOpen = false },
                    modifier = Modifier.zIndex(45f),
                )
            }

            // Контекстное меню карточки — поверх всего содержимого главной. Блюр фона делает
            // homeScrollBlur (cardMenuTarget входит в shouldBlur), здесь только затемнение,
            // поднятая копия карточки и ряд действий.
            cardMenuTarget?.let { target ->
                val systemBars = WindowInsets.systemBars.asPaddingValues()
                CardActionMenuOverlay(
                    target = target,
                    visible = cardMenuVisible,
                    backdrop = backdrop,
                    // Ряд кнопок не должен уезжать под док рабочей области и нав-бар.
                    bottomInset = systemBars.calculateBottomPadding() + 100.dp,
                    topInset = systemBars.calculateTopPadding() + 8.dp,
                    onDismiss = { cardMenuVisible = false },
                    onClosed = { cardMenuTarget = null },
                    onToggleFavorite = {
                        performHaptic(view, "light")
                        animeToFavorite = list.firstOrNull { it.id == target.state.id }
                        cardMenuVisible = false
                    },
                    onDelete = {
                        performHaptic(view, "warning")
                        animeToDelete = list.firstOrNull { it.id == target.state.id }
                        cardMenuVisible = false
                    },
                    onEdit = {
                        performHaptic(view, "light")
                        cardMenuVisible = false
                        navController.navigateToAddEdit(target.state.id)
                    },
                    onDetails = {
                        performHaptic(view, "light")
                        cardMenuVisible = false
                        navController.navigateToDetails(target.state.id)
                    },
                    modifier = Modifier.zIndex(50f),
                )
            }
        }

        if (showRecsSheet) {
            RecommendationsSheet(
                state = recsState,
                strings = recsStrings,
                language = currentLanguage,
                genreRepository = genreRepository,
                onAdd = { item -> recommendationsViewModel.addToCollection(item) },
                onDismiss = { showRecsSheet = false },
            )
        }

        // Единое восстановление стекла после закрытия ЛЮБОГО оверлея над доком (не только
        // рекомендаций): новое меню покрывается автоматически, если добавлено в этот признак.
        // См. GlassBackdropRecovery.
        GlassBackdropRecovery(
            overlayActive = shouldBlur || anyHomeSheetOpen || showRecsSheet ||
                animeToDelete != null || animeToFavorite != null || isSearchVisible,
            // Обе анимации живут на ПРЕДКАХ layerBackdrop (homeScrollBlur и graphicsLayer со
            // «вдавливанием»), поэтому перезаписывать стекло раньше их конца бессмысленно.
            effectsSettled = effectsSettled,
            listState = listState,
            onRemount = { layerBackdropResetKey++ },
        )

        if (showCSheet) {
            LaunchedEffect(showCSheet) {
                viewModel.loadStatsAnimeList()
            }
            StatsOverlay(
                animeList = uiState.statsAnimeList,
                strings = getStrings(currentLanguage),
                appLanguage = currentLanguage,
                footerPhrase = uiState.statsFooterPhrase,
                onDismiss = { showCSheet = false }
            )
        }
        }
    }
}

/**
 * Блюр фона при открытых оверлеях. Важно: graphicsLayer-узел НЕ добавляется/не удаляется по
 * blur==0 — иначе при закрытии меню структурная смена цепочки инвалидирует дочерний
 * `layerBackdrop` (тот, что сэмплит док), и док заливается сплошным фоном вместо стекла до
 * первого скролла. Держим узел стабильным и просто гасим renderEffect в null.
 */
private fun Modifier.homeScrollBlur(blur: () -> Dp): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.graphicsLayer {
            val px = blur().toPx()
            renderEffect = if (px > 0f) {
                RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP).asComposeRenderEffect()
            } else {
                null
            }
            clip = px > 0f
        }
    } else if (blur() > 0.dp) {
        this.then(Modifier.blur(blur()))
    } else {
        this
    }

private fun LazyListScope.apiSearchResultsSection(
    strings: UiStrings,
    apiSearchModels: List<ApiSearchUiModel>,
    uiState: HomeUiState,
    currentLanguage: AppLanguage,
    genreRepository: GenreRepository,
    view: android.view.View,
    viewModel: HomeViewModel,
    topPadding: Dp
) {
    item {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topPadding, bottom = 12.dp, start = 16.dp, end = 16.dp)
        ) {
            Text(
                text = strings.externalResults,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = SnProFamily
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.apiSearch,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = SnProFamily
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${apiSearchModels.size} ${strings.viaApi}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = SnProFamily
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (uiState.apiSearchLoading) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
    uiState.apiSearchError?.let { err ->
        item {
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
    items(
        items = apiSearchModels,
        key = { "${it.result.source}_${it.result.externalId ?: it.result.title}" },
        contentType = { "api_card" }
    ) { uiModel ->
        val result = uiModel.result
        val key = "${result.source}_${result.externalId ?: result.title}"
        val isLoading = uiState.addingFromApiId == key
        val apiGenres = remember(result.genres, currentLanguage) {
            persistentListOf(
                *result.genres.take(3)
                    .map { genreRepository.getLabel(it, currentLanguage) }
                    .toTypedArray()
            )
        }
        ApiSearchResultCard(
            result = result,
            isAdded = uiModel.isAdded,
            isLoading = isLoading,
            onAddClick = {
                performHaptic(view, "light")
                viewModel.addFromApi(result)
            },
            modifier = Modifier.padding(horizontal = 16.dp),
            displayGenres = apiGenres,
            addLabel = strings.addButton,
            addedLabel = strings.addedButton,
            mediaTypeLabel = when (uiState.searchMediaTypeFilter) {
                com.example.myapplication.data.models.MediaType.ANIME -> strings.typeAnime
                com.example.myapplication.data.models.MediaType.MANGA -> strings.typeManga
                com.example.myapplication.data.models.MediaType.MOVIE -> strings.typeMovie
                com.example.myapplication.data.models.MediaType.SERIES -> strings.typeSeries
            }
        )
    }
}


/**
 * Какой бар показать на карточке:
 *  1. пользователь читает мангу — бар чтения по главам, брендовый оранжевый, «12 / 60 ch.»;
 *  2. пользователь смотрит (есть сохранённая позиция хотя бы в одной серии) — бар просмотра,
 *     брендовый оранжевый, «62 / 80 ep.»;
 *  3. иначе идёт сезон — бар выхода серий, фиолетовый, «S5 4 / 14 ep.»;
 *  4. иначе бара нет.
 *
 * Чтение и просмотр в приоритете над выходом серий: пока человек читает или смотрит, ему важнее
 * своя позиция. И то и другое — факт активности в приложении, а не догадка по числам, поэтому
 * ложных срабатываний на случайных тайтлах быть не может. Один тайтл не бывает одновременно
 * читаемым и смотримым: прогресс чтения есть только у манги с подтверждённой привязкой.
 */
@Composable
private fun rememberCardProgress(
    totalEpisodes: Int,
    watched: Int?,
    airing: com.example.myapplication.data.models.AiringProgress?,
    reading: com.example.myapplication.manga.domain.MangaReadingSummary?,
): AiringCardInfo? = remember(totalEpisodes, watched, airing, reading) {
    val progress = watched?.takeIf { it > 0 }
    when {
        reading != null -> AiringCardInfo(
            seasonNumber = null,
            airedEpisodes = reading.readChapters,
            totalEpisodes = reading.totalChapters,
            kind = CardProgressKind.READING,
            newItems = reading.newChapters,
        )

        // Числитель не подрезается: знаменатель приходит уже в франшизной шкале
        // (`franchiseEpisodeTotal`), а зажимать реальный прогресс под неразрешённый расклад —
        // значит показывать пользователю не то, что он посмотрел.
        progress != null -> AiringCardInfo(
            seasonNumber = null,
            airedEpisodes = progress,
            totalEpisodes = totalEpisodes.takeIf { it > 0 },
            kind = CardProgressKind.WATCHING,
        )

        airing != null -> AiringCardInfo(
            seasonNumber = airing.seasonNumber,
            airedEpisodes = airing.airedEpisodes,
            totalEpisodes = airing.totalEpisodes,
            kind = CardProgressKind.AIRING,
        )

        else -> null
    }
}
