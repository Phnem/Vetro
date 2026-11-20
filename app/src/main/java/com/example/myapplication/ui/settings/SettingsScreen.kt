package com.example.myapplication.ui.settings

import com.example.myapplication.ui.shared.theme.IosScroll
import com.example.myapplication.ui.shared.glassControl
import com.example.myapplication.ui.shared.rememberPinnableBackdrop
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import com.example.myapplication.ui.shared.fadedBlurEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.phnem.vetro.R
import com.example.myapplication.data.models.AppTheme
import com.example.myapplication.data.models.UiStrings
import com.example.myapplication.microml.MicroMlEngine
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.navigation.navigateToWelcome
import com.example.myapplication.ui.shared.rememberDockAutoHide
import com.example.myapplication.ui.shared.components.GlassMenuHeader
import com.example.myapplication.ui.shared.components.GrabberReservedTop
import com.example.myapplication.ui.shared.components.IosDialogAnimatedContent
import com.example.myapplication.ui.shared.components.IosIconWell
import com.example.myapplication.ui.shared.components.IosSelectionOption
import com.example.myapplication.ui.shared.components.IosSelectionSheet
import com.example.myapplication.ui.shared.components.IosSheetScaffold
import com.example.myapplication.ui.shared.components.IosListGroup
import com.example.myapplication.ui.shared.components.IosRow
import com.example.myapplication.ui.shared.components.IosSegmentedControl
import com.example.myapplication.ui.shared.DONATION_URL
import com.example.myapplication.ui.shared.components.IosSwitch
import com.example.myapplication.ui.shared.components.LocalSettingHelp
import com.example.myapplication.ui.shared.components.SettingHelpBinding
import com.example.myapplication.ui.shared.components.SettingHelpController
import com.example.myapplication.ui.shared.components.SettingHelpRequest
import com.example.myapplication.ui.settings.help.SettingHelpIds
import com.example.myapplication.ui.settings.help.SettingHelpSheet
import com.example.myapplication.ui.settings.help.SettingsSpotlight
import com.example.myapplication.ui.settings.help.SettingsSpotlightOverlay
import com.example.myapplication.ui.settings.help.SpotlightUi
import com.example.myapplication.ui.settings.help.settingHelpText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import com.example.myapplication.ui.shared.theme.rememberReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.compose.material.icons.filled.NotificationsActive
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.ui.shared.theme.BrandDeepRed
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleShape
import com.example.myapplication.utils.DevRepairDbStrings
import com.example.myapplication.utils.formatApkSizeLabel
import com.example.myapplication.utils.getAiConnectStrings
import com.example.myapplication.utils.getDevRepairDbStrings
import com.example.myapplication.utils.getTitleDubbingStrings
import com.example.myapplication.utils.TitleDubbingStrings
import com.example.myapplication.utils.getPlayerSettingsStrings
import com.example.myapplication.utils.getStrings
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

// Ссылка живёт в ui/shared/Links.kt — её открывает ещё и меню дока рабочей области.

// Единая палитра icon-well по категориям (IosDesign.Category — один цвет = одна категория).
private val IconLanguage = IosDesign.Category.Language
private val IconAppearance = IosDesign.Category.Appearance
private val IconContent = IosDesign.Category.Media
private val IconCloud = IosDesign.Category.Account
private val IconContact = IosDesign.Category.Support
private val IconUpdate = IosDesign.Category.Update
private val IconUpdateReady = IosDesign.Category.Network
private val IconDonate = IosDesign.Category.Donate
private val IconDeveloper = IosDesign.Category.Storage

/**
 * Порядок элементов LazyColumn настроек по ключам: экран прокручивает к пункту по индексу, а у
 * LazyListState поиска по ключу нет. Меняете порядок `item(key = …)` ниже — меняйте и здесь.
 */
private val SETTINGS_ITEM_ORDER = listOf(
    "group_general",
    "group_security",
    "group_account",
    "group_availability",
    "group_smart_models",
    "group_about",
    "group_developer",
    "version_footer",
)

/** Ключ элемента списка, в котором живёт пункт с пояснением [helpId]. */
private fun spotlightItemKey(helpId: String): String? = when (helpId) {
    SettingHelpIds.AVAILABILITY_THRESHOLD -> "group_availability"
    else -> null
}

private sealed interface SettingsOverlaySheet {
    data object Help : SettingsOverlaySheet
    data object Cloud : SettingsOverlaySheet
    data object AiConnect : SettingsOverlaySheet
    data object PlaybackSources : SettingsOverlaySheet
    data object Contact : SettingsOverlaySheet
    data object Update : SettingsOverlaySheet
    data object MangaModels : SettingsOverlaySheet
    data class Picker(val key: String) : SettingsOverlaySheet
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    /**
     * Что делает «назад». null — кнопки в шапке нет: экран показан страницей рабочей области,
     * где уходить некуда, а системный Back перехватывает хозяин (TICKET-07).
     */
    onBack: (() -> Unit)? = null,
    /** Резерв снизу под чужой док (рабочая область). 0 — экран сам себе хозяин. */
    bottomInset: Dp = 0.dp,
    /**
     * Открыть панель подключения и синхронизации. Не-null только в рабочей области: там она
     * уехала из верхнего дока сюда отдельным пунктом. В старой навигации панель по-прежнему
     * висит в доке главной, и пункта здесь нет.
     */
    onOpenSyncPanel: (() -> Unit)? = null,
    /** Сообщает наружу, что открыта шторка/диалог: хозяин прячет док. */
    onOverlayVisibleChange: (Boolean) -> Unit = {},
    /** Список скроллится: хозяин переводит стекло своего дока в экономный режим. */
    onContentScrollChange: (Boolean) -> Unit = {},
    /** Автоскрытие (D9): скролл вниз прячет док хозяина, скролл вверх возвращает. */
    onDockVisibleChange: (Boolean) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isDark = isAppInDarkTheme()
    val bg = IosDesign.groupedBackground(isDark)
    // Общий с меню серий и меню глав градиент — значение живёт в теме, а не тремя копиями по
    // экранам (две из них уже успели разъехаться в светлой теме).
    val settingsBackground = remember(isDark) { IosDesign.screenGradient(isDark) }
    val textC = MaterialTheme.colorScheme.onBackground
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current

    val strings = getStrings(uiState.language)
    val devRepairStrings = getDevRepairDbStrings(uiState.language)
    val titleDubbingStrings = getTitleDubbingStrings(uiState.language)
    val updateStrings = remember(uiState.language) { com.example.myapplication.update.updateStrings(uiState.language) }
    val updateManager: com.example.myapplication.update.AppUpdateManager = org.koin.compose.koinInject()
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val aiConnectStrings = getAiConnectStrings(uiState.language)
    val playerSettingsStrings = getPlayerSettingsStrings(uiState.language)

    var activeSheet by remember { mutableStateOf<SettingsOverlaySheet?>(null) }
    // Пояснения к пунктам (удержание на строке) и сценарий «покажи пункт» из плеера.
    val help = remember { SettingHelpController() }
    val spotlightUi = remember { SpotlightUi() }
    val settingsStore: DataStore<Preferences> = koinInject(qualifier = named("settings"))
    val reducedMotion = rememberReducedMotion()
    val ruUi = uiState.language == AppLanguage.RU
    LaunchedEffect(help.sheetOpen) { if (help.sheetOpen) activeSheet = SettingsOverlaySheet.Help }
    LaunchedEffect(activeSheet) { if (activeSheet != SettingsOverlaySheet.Help && help.sheetOpen) help.close() }
    var showDeveloperSection by rememberSaveable { mutableStateOf(false) }
    // Активный picker-лист: "lang" | "theme" | "content" | null.

    val importDbPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.importDbFromFile(context, it) } }

    val anyOverlayVisible = activeSheet != null ||
        uiState.showRepairDbLogDialog || uiState.showTitleDubbingNoAiDialog
    LaunchedEffect(anyOverlayVisible) { onOverlayVisibleChange(anyOverlayVisible) }

    // Автоскрытие и экономный режим стекла для дока рабочей области: он соседний узел и про
    // скролл настроек сам не знает. Правило направления общее с главной (TICKET-04, D9).
    val dockAutoHide = rememberDockAutoHide()
    LaunchedEffect(dockAutoHide.visible) { onDockVisibleChange(dockAutoHide.visible) }
    DisposableEffect(Unit) {
        onDispose {
            onOverlayVisibleChange(false)
            onContentScrollChange(false)
            onDockVisibleChange(true)
        }
    }

    BackHandler(enabled = anyOverlayVisible) {
        when {
            uiState.showTitleDubbingNoAiDialog -> viewModel.dismissTitleDubbingNoAiDialog()
            uiState.showRepairDbLogDialog -> viewModel.discardRepairDbLog()
            else -> activeSheet = null
        }
    }

    // Мягкий блюр фона под модальными листами (§10, но без ударной физики — тут sheet).
    // Радиус постоянный, проявляется размытая копия (см. fadedBlurEffect); значение читается в
    // слое — анимация не пересобирает экран настроек.
    val backgroundBlur = animateFloatAsState(
        targetValue = if (activeSheet != null) 1f else 0f,
        animationSpec = tween(300),
        label = "backgroundBlur"
    )

    LaunchedEffect(uiState.importDbMessage) {
        val msg = uiState.importDbMessage ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        viewModel.clearImportDbMessage()
    }
    LaunchedEffect(uiState.repairDbMessage) {
        val msg = uiState.repairDbMessage ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        viewModel.clearRepairDbMessage()
    }
    LaunchedEffect(uiState.titleDubbingMessage) {
        val msg = uiState.titleDubbingMessage ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        viewModel.clearTitleDubbingMessage()
    }

    if (uiState.showRepairDbLogDialog) {
        RepairDbLogDialog(
            devRepairStrings = devRepairStrings,
            onCreateLog = { performHaptic(view, Haptic.Light); viewModel.exportRepairDbLog(context) },
            onDismiss = { performHaptic(view, Haptic.Light); viewModel.discardRepairDbLog() },
        )
    }
    if (uiState.showTitleDubbingNoAiDialog) {
        TitleDubbingNoAiDialog(
            strings = titleDubbingStrings,
            onConnect = {
                performHaptic(view, Haptic.Light)
                viewModel.dismissTitleDubbingNoAiDialog()
                activeSheet = SettingsOverlaySheet.AiConnect
            },
            onDismiss = { performHaptic(view, Haptic.Light); viewModel.dismissTitleDubbingNoAiDialog() },
        )
    }
    uiState.fullEnrichmentPromptGapCount?.let { gapCount ->
        FullEnrichmentPromptDialog(gapCount = gapCount, viewModel = viewModel)
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalSettingHelp provides help) {
    IosSheetScaffold(
        sheetVisible = activeSheet != null,
        onDismiss = { activeSheet = null },
        sheetHeightFraction = null,
        // Все шторки настроек — та же панель, что и в остальном приложении (iosSheetContainer).
        // Раньше здесь стоял Transparent, и каждая шторка рисовала свою плавающую карточку.
        sheetContainerColor = null,
        showGrabber = true,
        // Режим extera: поля вокруг вдавленного экрана настроек — тот же градиент, что у списка.
        backdropBrush = settingsBackground,
        content = {
      Box(modifier = Modifier.fillMaxSize()) {
        // Единый backdrop: список настроек пишет в него свой контент через layerBackdrop,
        // а плавающие стеклянные элементы (шапка, Share-FAB) сэмплируют его → настоящее
        // жидкое стекло с преломлением контента под ними (как бегунок рейтинга сэмплирует трек).
        val backdrop = rememberLayerBackdrop {
            drawRect(brush = settingsBackground)
            drawContent()
        }
        with(sharedTransitionScope) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Постоянный слой вместо Modifier.blur: тот добавлял слой в цепочку только при
                    // радиусе > 0, то есть над layerBackdrop при каждом открытии листа.
                    .graphicsLayer {
                        val amount = backgroundBlur.value
                        renderEffect = fadedBlurEffect(SETTINGS_SHEET_BLUR.toPx(), amount)
                        clip = amount > 0.01f
                    }
                    .sharedBounds(
                        rememberSharedContentState(key = "settings_container"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        // ScaleToBounds вместо RemeasureToBounds: список настроек не переразмечается
                        // каждый кадр открытия/закрытия (осн. причина падения FPS 120→105), а
                        // масштабируется на этапе отрисовки. Анимация сохранена (§ perf).
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                        clipInOverlayDuringTransition = OverlayClip(SquircleShape(IosDesign.RadiusLg))
                    )
                    .clip(SquircleShape(IosDesign.RadiusLg))
                    .background(settingsBackground)
            ) {
                val listState = rememberLazyListState()
                val settingsScrollInProgress by remember {
                    derivedStateOf { listState.isScrollInProgress }
                }
                LaunchedEffect(settingsScrollInProgress) {
                    onContentScrollChange(settingsScrollInProgress)
                }

                // «Покажи пункт»: прокрутить к нему, плавно мигнуть, а в самый первый раз ещё и показать
                // указателем, как удержанием открывается описание. Заявку ставит плеер (MainActivity).
                val spotlightTarget by SettingsSpotlight.target.collectAsStateWithLifecycle()
                val spotlightReady by SettingsSpotlight.pageReady.collectAsStateWithLifecycle()
                LaunchedEffect(spotlightTarget, spotlightReady) {
                    val id = spotlightTarget ?: return@LaunchedEffect
                    // Страница рабочей области сообщает о готовности сама; отдельный экран (есть «назад») готов сразу.
                    if (!spotlightReady && onBack == null) return@LaunchedEffect
                    try {
                        val index = spotlightItemKey(id)?.let { SETTINGS_ITEM_ORDER.indexOf(it) } ?: -1
                        if (index >= 0) {
                            val viewport = listState.layoutInfo.viewportSize.height
                            listState.animateScrollToItem(index, scrollOffset = -(viewport / 3))
                        }
                        delay(300)
                        help.pulse(id, times = 3, reducedMotion = reducedMotion)
                        val seen = settingsStore.data.first()[SettingsSpotlight.TUTORIAL_SEEN] == true
                        val bounds = help.bounds[id]
                        if (!seen && bounds != null) {
                            val to = Offset(bounds.left + bounds.width * 0.42f, bounds.center.y)
                            val from = to + Offset(with(density) { 90.dp.toPx() }, with(density) { 170.dp.toPx() })
                            spotlightUi.pressAt(to, from, reducedMotion)
                            help.open(
                                SettingHelpRequest(
                                    binding = SettingHelpBinding(
                                        id = id,
                                        checked = uiState.availabilityThreshold,
                                        onToggle = { viewModel.setAvailabilityThreshold(it) },
                                    ),
                                    onGo = null,
                                    fromTutorial = true,
                                ),
                            )
                            spotlightUi.release()
                            delay(700)
                            settingsStore.edit { it[SettingsSpotlight.TUTORIAL_SEEN] = true }
                            spotlightUi.hintVisible = true
                            delay(6_000)
                            spotlightUi.hintVisible = false
                        }
                    } finally {
                        SettingsSpotlight.finish()
                    }
                }

                LazyColumn(
                    flingBehavior = IosScroll.flingBehavior(),
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        // Строго ПОСЛЕ layerBackdrop: над узлом-провайдером бэкдропа лишних
                        // модификаторов не заводим (правило кодовой базы).
                        .layerBackdrop(backdrop)
                        .nestedScroll(dockAutoHide.connection),
                    contentPadding = PaddingValues(
                        // Резерв под плавающую стеклянную шапку (§11): statusBar + пузырёк 48 + отступы.
                        top = statusBarTop + 76.dp,
                        // Снизу резервируем ровно занятое место: нав-панель, док (его высоту
                        // присылает вызывающий) и воздух. Прежние 120dp ПОВЕРХ инсета дока были
                        // ничем не заняты — список прокручивался на пол-экрана мимо последнего
                        // пункта, и это читалось как сломанный overscroll.
                        bottom = 24.dp + bottomInset + navigationBarBottom,
                    ),
                    verticalArrangement = Arrangement.spacedBy(30.dp),
                ) {
                    // ГРУППА: Общие (picker-строки → bottom sheet со списком).
                    item(key = "group_general") {
                        IosListGroup(
                            isDark = isDark,
                            rows = listOf(
                                {
                                    IosRow(
                                        title = if (uiState.language == AppLanguage.RU) "Дополнительные источники" else "Additional sources",
                                        subtitle = if (uiState.language == AppLanguage.RU) {
                                            "WebDAV, Jellyfin и Emby"
                                        } else {
                                            "WebDAV, Jellyfin and Emby"
                                        },
                                        isDark = isDark,
                                        icon = Icons.Filled.PlayCircle,
                                        help = SettingHelpBinding(SettingHelpIds.PLAYBACK_SOURCES),
                                        iconWell = false,
                                        showChevron = true,
                                        onClick = { performHaptic(view, Haptic.Light); activeSheet = SettingsOverlaySheet.PlaybackSources },
                                    )
                                },
                                {
                                    IosRow(
                                        title = strings.languageCardTitle,
                                        isDark = isDark,
                                        iconRes = R.drawable.hugeicon_language,
                                        help = SettingHelpBinding(SettingHelpIds.LANGUAGE),
                                        iconWell = false,
                                        value = if (uiState.language == AppLanguage.RU) "Русский" else "English",
                                        showChevron = true,
                                        onClick = { performHaptic(view, Haptic.Light); activeSheet = SettingsOverlaySheet.Picker("lang") },
                                    )
                                },
                                {
                                    IosRow(
                                        title = strings.themeTitle,
                                        isDark = isDark,
                                        iconRes = R.drawable.hugeicon_appearance,
                                        help = SettingHelpBinding(SettingHelpIds.THEME),
                                        iconWell = false,
                                        value = when (uiState.theme) {
                                            AppTheme.LIGHT -> strings.themeLight
                                            AppTheme.DARK -> strings.themeDark
                                            AppTheme.SYSTEM -> strings.themeSystem
                                        },
                                        showChevron = true,
                                        onClick = { performHaptic(view, Haptic.Light); activeSheet = SettingsOverlaySheet.Picker("theme") },
                                    )
                                },
                            ),
                        )
                    }

                    item(key = "group_security") {
                        IosListGroup(
                            isDark = isDark,
                            rows = listOf({
                                com.example.myapplication.security.BiometricLockSettingRow(
                                    ru = uiState.language == AppLanguage.RU,
                                    isDark = isDark,
                                )
                            }),
                        )
                    }

                    // ГРУППА: Аккаунт / связь.
                    item(key = "group_account") {
                        IosListGroup(
                            isDark = isDark,
                            rows = listOf(
                                {
                                    IosRow(
                                        title = strings.cloudSettingsTitle,
                                        subtitle = strings.cloudSettingsSubtitle,
                                        isDark = isDark,
                                        iconRes = R.drawable.hugeicon_account,
                                        help = SettingHelpBinding(SettingHelpIds.CLOUD),
                                        iconWell = false,
                                        showChevron = true,
                                        onClick = { performHaptic(view, Haptic.Light); activeSheet = SettingsOverlaySheet.Cloud },
                                    )
                                },
                                *(if (onOpenSyncPanel != null) {
                                    // Панель подключения: в рабочей области она уехала из
                                    // верхнего дока сюда. Подписи зашиты рядом — в UiStrings
                                    // свободных полей почти не осталось (252 из 254).
                                    val ru = uiState.language == AppLanguage.RU
                                    arrayOf<@Composable () -> Unit>({
                                        IosRow(
                                            title = if (ru) "Синхронизация" else "Sync",
                                            subtitle = if (ru) {
                                                "Аккаунты, списки и проверка новых серий"
                                            } else {
                                                "Accounts, lists and episode checks"
                                            },
                                            isDark = isDark,
                                            icon = Icons.Filled.Sync,
                                            help = SettingHelpBinding(SettingHelpIds.SYNC),
                                            iconWell = false,
                                            showChevron = true,
                                            onClick = { performHaptic(view, Haptic.Light); onOpenSyncPanel() },
                                        )
                                    })
                                } else emptyArray()),
                                {
                                    IosRow(
                                        title = aiConnectStrings.tileTitle,
                                        subtitle = aiConnectStrings.tileSubtitle,
                                        isDark = isDark,
                                        iconRes = R.drawable.ic_ai_sparkle,
                                        help = SettingHelpBinding(SettingHelpIds.AI_CONNECT),
                                        iconWell = false,
                                        showChevron = true,
                                        onClick = { performHaptic(view, Haptic.Light); activeSheet = SettingsOverlaySheet.AiConnect },
                                    )
                                },
                                {
                                    MangaAutoTranslateRow(
                                        language = uiState.language,
                                        isDark = isDark,
                                        onHaptic = { performHaptic(view, Haptic.Light) },
                                        onOpenModels = { activeSheet = SettingsOverlaySheet.MangaModels },
                                    )
                                },
                                {
                                    IosRow(
                                        title = playerSettingsStrings.autoSkipTitle,
                                        subtitle = playerSettingsStrings.autoSkipSubtitle,
                                        isDark = isDark,
                                        icon = Icons.Filled.FastForward,
                                        help = SettingHelpBinding(
                                            id = SettingHelpIds.AUTO_SKIP,
                                            checked = uiState.autoSkipSegments,
                                            onToggle = { viewModel.setAutoSkipSegments(it) },
                                        ),
                                        iconWell = false,
                                        trailing = {
                                            IosSwitch(
                                                checked = uiState.autoSkipSegments,
                                                onCheckedChange = {
                                                    performHaptic(view, Haptic.Light)
                                                    viewModel.setAutoSkipSegments(it)
                                                },
                                            )
                                        },
                                    )
                                },
                                {
                                    IosRow(
                                        title = playerSettingsStrings.autoNextTitle,
                                        subtitle = playerSettingsStrings.autoNextSubtitle,
                                        isDark = isDark,
                                        icon = Icons.Filled.SkipNext,
                                        help = SettingHelpBinding(
                                            id = SettingHelpIds.AUTO_NEXT,
                                            checked = uiState.autoNextEpisode,
                                            onToggle = { viewModel.setAutoNextEpisode(it) },
                                        ),
                                        iconWell = false,
                                        trailing = {
                                            IosSwitch(
                                                checked = uiState.autoNextEpisode,
                                                onCheckedChange = {
                                                    performHaptic(view, Haptic.Light)
                                                    viewModel.setAutoNextEpisode(it)
                                                },
                                            )
                                        },
                                    )
                                },
                                {
                                    IosRow(
                                        title = strings.contactTitle,
                                        subtitle = strings.contactSubtitle,
                                        isDark = isDark,
                                        iconRes = R.drawable.hugeicon_contact,
                                        help = SettingHelpBinding(SettingHelpIds.CONTACT),
                                        iconWell = false,
                                        showChevron = true,
                                        onClick = { performHaptic(view, Haptic.Light); activeSheet = SettingsOverlaySheet.Contact },
                                    )
                                },
                            ),
                        )
                    }

                    // ГРУППА: Порог доступности (уведомление о серии ждёт озвучек). Позиция — в SETTINGS_ITEM_ORDER.
                    item(key = "group_availability") {
                        IosListGroup(
                            isDark = isDark,
                            rows = listOf({
                                IosRow(
                                    title = if (ruUi) "Порог доступности" else "Availability threshold",
                                    subtitle = if (ruUi) "Уведомление, когда серию уже озвучили" else "Notify once the episode is dubbed",
                                    isDark = isDark,
                                    icon = Icons.Filled.NotificationsActive,
                                    iconWell = false,
                                    help = SettingHelpBinding(
                                        id = SettingHelpIds.AVAILABILITY_THRESHOLD,
                                        checked = uiState.availabilityThreshold,
                                        onToggle = { viewModel.setAvailabilityThreshold(it) },
                                    ),
                                    trailing = {
                                        IosSwitch(
                                            checked = uiState.availabilityThreshold,
                                            onCheckedChange = {
                                                performHaptic(view, Haptic.Light)
                                                viewModel.setAvailabilityThreshold(it)
                                            },
                                        )
                                    },
                                )
                            }),
                        )
                    }

                    // ГРУППА: Умные модели (три тумблера моделей и добровольный сбор данных).
                    item(key = "group_smart_models") {
                        SmartModelsGroup(ru = strings.languageName == "RU", isDark = isDark)
                    }

                    // ГРУППА: О приложении.
                    item(key = "group_about") {
                        val updateAvailable = updateState is com.example.myapplication.update.UpdateState.Available ||
                            updateState is com.example.myapplication.update.UpdateState.Ready ||
                            updateState is com.example.myapplication.update.UpdateState.NeedsPermission
                        val updateValue: String? = when (val u = updateState) {
                            is com.example.myapplication.update.UpdateState.Checking -> updateStrings.rowChecking
                            is com.example.myapplication.update.UpdateState.Available -> updateStrings.rowAvailable(u.release.tag)
                            is com.example.myapplication.update.UpdateState.Downloading -> updateStrings.rowDownloading((u.fraction * 100).toInt())
                            is com.example.myapplication.update.UpdateState.Ready,
                            is com.example.myapplication.update.UpdateState.NeedsPermission -> updateStrings.rowReady
                            is com.example.myapplication.update.UpdateState.Failed -> updateStrings.rowFailed
                            else -> null
                        }
                        IosListGroup(
                            isDark = isDark,
                            rows = listOf(
                                {
                                    IosRow(
                                        title = updateStrings.rowTitle,
                                        isDark = isDark,
                                        iconRes = R.drawable.hugeicon_update,
                                        help = SettingHelpBinding(SettingHelpIds.UPDATE),
                                        iconWell = false,
                                        value = updateValue,
                                        valueColor = if (updateAvailable) IconUpdateReady else null,
                                        showChevron = true,
                                        onClick = {
                                            performHaptic(view, Haptic.Light)
                                            // Окно уже открыто здесь: предложение при запуске для этого релиза не нужно.
                                            updateManager.dismissStartupOffer()
                                            activeSheet = SettingsOverlaySheet.Update
                                        },
                                    )
                                },
                                {
                                    IosRow(
                                        title = strings.donationCardTitle,
                                        subtitle = strings.donationCardSubtitle,
                                        isDark = isDark,
                                        iconRes = R.drawable.hugeicon_donate,
                                        help = SettingHelpBinding(SettingHelpIds.DONATION),
                                        iconWell = false,
                                        showChevron = true,
                                        onClick = {
                                            performHaptic(view, Haptic.Light)
                                            context.startActivity(Intent(Intent.ACTION_VIEW, DONATION_URL.toUri()))
                                        },
                                    )
                                },
                                {
                                    IosRow(
                                        title = strings.devSectionTitle,
                                        isDark = isDark,
                                        iconRes = R.drawable.hugeicon_developer,
                                        iconWell = false,
                                        chevronExpanded = showDeveloperSection,
                                        onClick = {
                                            performHaptic(view, Haptic.Light)
                                            showDeveloperSection = !showDeveloperSection
                                        },
                                    )
                                },
                            ),
                        )
                    }

                    // ГРУППА: Разработчик (раскрывается).
                    item(key = "group_developer") {
                        AnimatedVisibility(
                            visible = showDeveloperSection,
                            enter = expandVertically(animationSpec = tween(300)) + fadeIn(tween(300)),
                            exit = shrinkVertically(animationSpec = tween(220)) + fadeOut(tween(180)),
                        ) {
                            DeveloperGroups(
                                strings = strings,
                                uiState = uiState,
                                isDark = isDark,
                                onMirrorDbToggle = { performHaptic(view, Haptic.Light); viewModel.setDevMirrorDb(it) },
                                onHideShareToggle = { performHaptic(view, Haptic.Light); viewModel.setDevHideShare(it) },
                                onFpsOverlayToggle = { performHaptic(view, Haptic.Light); viewModel.setDevFpsOverlay(it) },
                                onAdaptiveGlassToggle = { performHaptic(view, Haptic.Light); viewModel.setDevAdaptiveGlassScroll(it) },
                                onFullBleedCardsToggle = { performHaptic(view, Haptic.Light); viewModel.setDevFullBleedCards(it) },
                                onMiniNotifToggle = { performHaptic(view, Haptic.Light); viewModel.setDevMiniNotif(it) },
                                onExperimentalGridToggle = { performHaptic(view, Haptic.Light); viewModel.setDevExperimentalGrid(it) },
                                onLegacySplashToggle = { performHaptic(view, Haptic.Light); viewModel.setDevLegacySplash(it) },
                                onExportLogs = { performHaptic(view, Haptic.Light); viewModel.exportLogs(context) },
                                onExportPdf = { performHaptic(view, Haptic.Light); viewModel.exportCollectionPdf(context) },
                                onImportDb = { performHaptic(view, Haptic.Light); importDbPicker.launch("*/*") },
                            )
                        }
                    }

                    item(key = "version_footer") {
                        if (uiState.currentVersion.isNotBlank()) {
                            Text(
                                text = "Vetro · ${uiState.currentVersion}",
                                color = textC.copy(alpha = 0.4f),
                                fontFamily = SnProFamily,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }

                // Плавающая стеклянная шапка (§11): пузырёк-назад + капсула-заголовок скользят
                // поверх прокручиваемого списка (единый компонент с Details / AddEdit).
                GlassMenuHeader(
                    backdrop = backdrop,
                    title = strings.settingsScreenTitle,
                    onBack = onBack,
                    isDark = isDark,
                    contentColor = textC,
                    iconModifier = Modifier.sharedElement(
                        rememberSharedContentState(key = "settings_icon"),
                        animatedVisibilityScope = animatedVisibilityScope,
                    ),
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            // Share FAB (стеклянный) — сохранён.
            if (!uiState.devHideShareButton) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 24.dp + bottomInset, end = 24.dp)
                ) {
                    val shareBackdrop = rememberPinnableBackdrop(backdrop)
                    // Тот же рецепт жидкого стекла, что у бегунка рейтинга: сэмплируем
                    // общий backdrop списка, слабый blur + крупная линза преломляют контент
                    // под кнопкой, сверху — стеклянный блик и светлая окантовка.
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .glassControl(shareBackdrop, CircleShape) {
                                drawBackdrop(
                                    backdrop = shareBackdrop,
                                    shape = { CircleShape },
                                    effects = {
                                        vibrancy()
                                        blur(2f.dp.toPx())
                                        lens(16f.dp.toPx(), 44f.dp.toPx())
                                    },
                                )
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.White.copy(alpha = 0.22f),
                                            0.5f to Color.Transparent,
                                            1f to Color.Black.copy(alpha = 0.10f),
                                        ),
                                        CircleShape,
                                    )
                                    .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { performHaptic(view, Haptic.Light); viewModel.shareWithDb(context) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = textC,
                            modifier = Modifier.size(26.dp).offset(x = (-2).dp)
                        )
                    }
                }
            }
        }

      }
        },
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = GrabberReservedTop, bottom = 8.dp),
            ) {
                when {
                    activeSheet == SettingsOverlaySheet.Help -> {
                        val request = help.request
                        val text = request?.let { settingHelpText(it.binding.id, ruUi) }
                        if (request != null && text != null) {
                            SettingHelpSheet(
                                request = request,
                                text = text,
                                ru = ruUi,
                                isDark = isDark,
                                onBack = { help.close(); activeSheet = null },
                                onGo = { help.close(); request.onGo?.invoke() },
                            )
                        }
                    }
                    activeSheet == SettingsOverlaySheet.Picker("lang") -> IosSelectionSheet(
                        title = strings.languageCardTitle,
                        options = listOf(
                            IosSelectionOption("English", Icons.Outlined.Language, IconLanguage),
                            IosSelectionOption("Русский", Icons.Outlined.Language, IconLanguage),
                        ),
                        selectedIndex = if (uiState.language == AppLanguage.RU) 1 else 0,
                        isDark = isDark,
                        cardCornerRadius = 22.dp,
                        onSelect = { i ->
                            performHaptic(view, Haptic.Light)
                            viewModel.setLanguage(if (i == 1) AppLanguage.RU else AppLanguage.EN)
                            activeSheet = null
                        },
                    )
                    activeSheet == SettingsOverlaySheet.Picker("theme") -> IosSelectionSheet(
                        title = strings.themeTitle,
                        options = listOf(
                            IosSelectionOption(strings.themeLight, Icons.Outlined.LightMode, IconAppearance),
                            IosSelectionOption(strings.themeDark, Icons.Outlined.DarkMode, IconAppearance),
                            IosSelectionOption(strings.themeSystem, Icons.Outlined.BrightnessAuto, IconAppearance),
                        ),
                        selectedIndex = when (uiState.theme) {
                            AppTheme.LIGHT -> 0
                            AppTheme.DARK -> 1
                            AppTheme.SYSTEM -> 2
                        },
                        isDark = isDark,
                        cardCornerRadius = 22.dp,
                        onSelect = { i ->
                            performHaptic(view, Haptic.Light)
                            viewModel.setTheme(
                                when (i) {
                                    0 -> AppTheme.LIGHT
                                    1 -> AppTheme.DARK
                                    else -> AppTheme.SYSTEM
                                }
                            )
                            activeSheet = null
                        },
                    )
                    activeSheet == SettingsOverlaySheet.Cloud -> CloudSettingsSheet(
                        onDismiss = { activeSheet = null },
                        onLogout = { activeSheet = null; navController.navigateToWelcome() },
                    )
                    activeSheet == SettingsOverlaySheet.AiConnect -> AiConnectSheet(
                        onDismiss = { activeSheet = null },
                    )
                    activeSheet == SettingsOverlaySheet.PlaybackSources -> PlaybackSourcesSettingsSheet(
                        language = uiState.language,
                        onDismiss = { activeSheet = null },
                    )
                    activeSheet == SettingsOverlaySheet.MangaModels -> MangaModelsSheet(
                        language = uiState.language,
                        onDismiss = { activeSheet = null },
                    )
                    activeSheet == SettingsOverlaySheet.Contact -> ContactSheet(
                        onDismiss = { activeSheet = null },
                    )
                    activeSheet == SettingsOverlaySheet.Update -> com.example.myapplication.ui.update.UpdateSheet(
                        language = uiState.language,
                        manual = true,
                        onDismiss = { activeSheet = null },
                    )
                }
            }
        },
    )
    }
    SettingsSpotlightOverlay(ui = spotlightUi, ru = ruUi)
    }
}

/** Строка-контейнер с сегментированным переключателем: icon + title, ниже — сам контрол. */
@Composable
private fun IosSegmentedRow(
    icon: ImageVector,
    iconBg: Color,
    title: String,
    isDark: Boolean,
    optionCount: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    segment: @Composable (index: Int, selected: Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = IosDesign.ListHorizontalInset, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IosIconWell(icon = icon, background = iconBg)
            Spacer(Modifier.width(IosDesign.ListIconTextGap))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = SnProFamily,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        IosSegmentedControl(
            optionCount = optionCount,
            selectedIndex = selectedIndex,
            isDark = isDark,
            modifier = Modifier.fillMaxWidth(),
            onSelect = onSelect,
            segment = segment,
        )
    }
}

@Composable
private fun SegLabel(text: String, selected: Boolean, isDark: Boolean) {
    Text(
        text = text,
        fontFamily = SnProFamily,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        fontSize = 13.sp,
        color = if (selected) {
            if (isDark) Color.White else Color.Black
        } else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SegIcon(icon: ImageVector, selected: Boolean, isDark: Boolean) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = if (selected) {
            if (isDark) Color.White else Color.Black
        } else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
}

/** MicroML Engine: состояние трёх пакетов моделей и теневые счётчики. Названия из коллекции не хранятся и не уходят. */
@Composable
private fun MicroMlStatusRow(ru: Boolean, isDark: Boolean, devIcon: Color) {
    val microMl = org.koin.compose.koinInject<com.example.myapplication.microml.MicroMlEngine>()
    val controls by microMl.controlRevision.collectAsStateWithLifecycle()
    val status = androidx.compose.runtime.produceState(initialValue = "…", microMl, controls, ru) {
        value = microMl.status().joinToString(" · ") { s ->
            val name = s.pack.key.replaceFirstChar { it.uppercase() }
            when (s.state) {
                com.example.myapplication.microml.MicroMlEngine.PackState.READY -> "$name ✓" + if (s.rolledBack) " (prev)" else ""
                com.example.myapplication.microml.MicroMlEngine.PackState.DISABLED -> "$name ${if (ru) "выкл" else "off"}"
                com.example.myapplication.microml.MicroMlEngine.PackState.SUSPENDED -> "$name ${if (ru) "сбой" else "failed"}"
                com.example.myapplication.microml.MicroMlEngine.PackState.MISSING -> "$name ${if (ru) "нет пакета" else "no pack"}"
            }
        }
    }
    val observed = microMl.shadowCounts().entries.sortedByDescending { it.value }.take(6)
    IosRow(
        title = if (ru) "MicroML: пакеты и наблюдения" else "MicroML: packs and observations",
        subtitle = status.value + if (observed.isEmpty()) "" else " — " + observed.joinToString(" · ") { "${it.key.substringAfter(':')} ${it.value}" },
        isDark = isDark,
        icon = Icons.Filled.Code,
        iconBackground = devIcon,
    )
}

@Composable
private fun MicroMlRollbackRow(pack: MicroMlEngine.Pack, ru: Boolean, isDark: Boolean, devIcon: Color) {
    val microMl = org.koin.compose.koinInject<MicroMlEngine>()
    var previous by remember(pack) { mutableStateOf(microMl.isRolledBack(pack)) }
    val name = pack.key.replaceFirstChar { it.uppercase() } + " AI"
    IosRow(
        title = if (ru) "$name — предыдущая версия" else "$name — previous version",
        subtitle = if (ru) "Если версии нет, используются обычные правила Vetro" else "Uses Vetro's standard rules if unavailable",
        isDark = isDark,
        icon = Icons.Filled.Code,
        iconBackground = devIcon,
        trailing = {
            IosSwitch(checked = previous, onCheckedChange = { on -> microMl.setRolledBack(pack, on); previous = on })
        },
    )
}

/** Группы раздела «Разработчик» в iOS-стиле. */
@Composable
private fun DeveloperGroups(
    strings: UiStrings,
    uiState: SettingsUiState,
    isDark: Boolean,
    onMirrorDbToggle: (Boolean) -> Unit,
    onHideShareToggle: (Boolean) -> Unit,
    onFpsOverlayToggle: (Boolean) -> Unit,
    onAdaptiveGlassToggle: (Boolean) -> Unit,
    onFullBleedCardsToggle: (Boolean) -> Unit,
    onMiniNotifToggle: (Boolean) -> Unit,
    onExperimentalGridToggle: (Boolean) -> Unit,
    onLegacySplashToggle: (Boolean) -> Unit,
    onExportLogs: () -> Unit,
    onExportPdf: () -> Unit,
    onImportDb: () -> Unit,
) {
    val devIcon = Color(0xFF8E8E93)
    Column(verticalArrangement = Arrangement.spacedBy(30.dp)) {
        IosListGroup(
            isDark = isDark,
            header = strings.devSectionTitle,
            rows = listOf(
                {
                    IosRow(
                        title = strings.devMirrorDbTitle,
                        subtitle = strings.devMirrorDbSubtitle,
                        isDark = isDark,
                        icon = Icons.Filled.FileOpen,
                        iconBackground = devIcon,
                        trailing = { IosSwitch(checked = uiState.devMirrorDbToDocuments, onCheckedChange = onMirrorDbToggle) },
                    )
                },
                {
                    IosRow(
                        title = strings.devHideShareTitle,
                        subtitle = strings.devHideShareSubtitle,
                        isDark = isDark,
                        icon = Icons.Filled.Share,
                        iconBackground = devIcon,
                        trailing = { IosSwitch(checked = uiState.devHideShareButton, onCheckedChange = onHideShareToggle) },
                    )
                },
                {
                    IosRow(
                        title = strings.devAdaptiveGlassTitle,
                        subtitle = strings.devAdaptiveGlassSubtitle,
                        isDark = isDark,
                        icon = Icons.Filled.Code,
                        iconBackground = devIcon,
                        trailing = { IosSwitch(checked = uiState.devAdaptiveGlassScroll, onCheckedChange = onAdaptiveGlassToggle) },
                    )
                },
                {
                    // Подписи зашиты здесь, а не в UiStrings: там 252 поля из 254 допустимых
                    // (за пределом RELEASE падает с VerifyError в clinit).
                    val ru = strings.languageName == "RU"
                    IosRow(
                        title = if (ru) "Карточки с обложкой" else "Full-cover cards",
                        subtitle = if (ru) {
                            "Обложка на всю карточку, текст поверх"
                        } else {
                            "Cover fills the card, text on top"
                        },
                        isDark = isDark,
                        icon = Icons.Filled.Image,
                        iconBackground = devIcon,
                        trailing = {
                            IosSwitch(
                                checked = uiState.devFullBleedCards,
                                onCheckedChange = onFullBleedCardsToggle,
                            )
                        },
                    )
                },
                {
                    val ru = strings.languageName == "RU"
                    IosRow(
                        title = if (ru) "Мини-уведомления" else "Mini notifications",
                        subtitle = if (ru) {
                            "Короткие сообщения плашкой снизу, как Dynamic Island, вместо тостов"
                        } else {
                            "Short messages as a bottom pill that morphs like Dynamic Island, instead of toasts"
                        },
                        isDark = isDark,
                        icon = Icons.Filled.NotificationsActive,
                        iconBackground = devIcon,
                        trailing = {
                            IosSwitch(
                                checked = uiState.devMiniNotif,
                                onCheckedChange = onMiniNotifToggle,
                            )
                        },
                    )
                },
                {
                    val ru = strings.languageName == "RU"
                    IosRow(
                        title = if (ru) "Экспериментальная сетка" else "Experimental grid",
                        subtitle = if (ru) {
                            "Избранное в горизонтальной ленте над коллекцией"
                        } else {
                            "Favorites in a horizontal strip above the collection"
                        },
                        isDark = isDark,
                        icon = Icons.Filled.ViewCarousel,
                        iconBackground = devIcon,
                        trailing = {
                            IosSwitch(
                                checked = uiState.devExperimentalGrid,
                                onCheckedChange = onExperimentalGridToggle,
                            )
                        },
                    )
                },
                {
                    // Теневые наблюдения TitleMatch: что модель сказала бы о рекомендациях рядом с вашей
                    // коллекцией. Только счётчики в памяти, названия не хранятся и не уходят.
                    val ru = strings.languageName == "RU"
                    val titleMatch = org.koin.compose.koinInject<com.example.myapplication.domain.titleidentityintegration.TitleIdentityService>()
                    val observed = titleMatch.shadowCounts().entries
                        .groupBy({ it.key.split(':').getOrNull(1).orEmpty() }, { it.value })
                        .mapValues { (_, counts) -> counts.sum() }
                        .entries.sortedByDescending { it.value }.take(5)
                    IosRow(
                        title = if (ru) "TitleMatch: наблюдения" else "TitleMatch: observations",
                        subtitle = if (observed.isEmpty()) {
                            if (ru) "Пока пусто: рекомендации ещё не считались или пакета модели нет" else "Empty so far: no recommendations yet or no model pack"
                        } else {
                            observed.joinToString(" · ") { "${it.key} ${it.value}" }
                        },
                        isDark = isDark,
                        icon = Icons.Filled.Code,
                        iconBackground = devIcon,
                    )
                },
                { MicroMlStatusRow(strings.languageName == "RU", isDark, devIcon) },
                { MicroMlRollbackRow(MicroMlEngine.Pack.IDENTITY, strings.languageName == "RU", isDark, devIcon) },
                { MicroMlRollbackRow(MicroMlEngine.Pack.CATALOG, strings.languageName == "RU", isDark, devIcon) },
                { MicroMlRollbackRow(MicroMlEngine.Pack.EXPERIENCE, strings.languageName == "RU", isDark, devIcon) },
                {
                    val ru = strings.languageName == "RU"
                    IosRow(
                        title = if (ru) "Старая заставка старта" else "Legacy startup splash",
                        subtitle = if (ru) {
                            "Переливание вместо анимации логотипа; применяется при следующем запуске"
                        } else {
                            "The wave instead of the logo animation; applies on the next launch"
                        },
                        isDark = isDark,
                        icon = Icons.Filled.Code,
                        iconBackground = devIcon,
                        trailing = {
                            IosSwitch(
                                checked = uiState.devLegacySplash,
                                onCheckedChange = onLegacySplashToggle,
                            )
                        },
                    )
                },
                {
                    IosRow(
                        title = strings.devFpsOverlayTitle,
                        subtitle = strings.devFpsOverlaySubtitle,
                        isDark = isDark,
                        icon = Icons.Filled.BugReport,
                        iconBackground = devIcon,
                        trailing = { IosSwitch(checked = uiState.devFpsOverlay, onCheckedChange = onFpsOverlayToggle) },
                    )
                },
            ),
        )
        IosListGroup(
            isDark = isDark,
            rows = listOf(
                {
                    DevActionRow(
                        title = strings.devExportLogsTitle, subtitle = strings.devExportLogsSubtitle,
                        icon = Icons.Filled.BugReport, iconBg = devIcon, isLoading = uiState.isExportingLogs,
                        isDark = isDark, onClick = onExportLogs,
                    )
                },
                {
                    DevActionRow(
                        title = strings.devExportPdfTitle, subtitle = strings.devExportPdfSubtitle,
                        icon = Icons.Filled.PictureAsPdf, iconBg = devIcon, isLoading = uiState.isExportingPdf,
                        isDark = isDark, onClick = onExportPdf,
                    )
                },
                {
                    DevActionRow(
                        title = strings.devImportDbTitle, subtitle = strings.devImportDbSubtitle,
                        icon = Icons.Filled.FileOpen, iconBg = devIcon, isLoading = uiState.isImportingDb,
                        isDark = isDark, onClick = onImportDb,
                    )
                },
            ),
        )
    }
}

@Composable
private fun DevActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconBg: Color,
    isLoading: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    IosRow(
        title = title,
        subtitle = subtitle,
        isDark = isDark,
        icon = icon,
        iconBackground = iconBg,
        showChevron = !isLoading,
        trailing = if (isLoading) {
            { CircularProgressIndicator(modifier = Modifier.size(18.dp), color = BrandOrange, strokeWidth = 2.dp) }
        } else null,
        onClick = if (isLoading) null else onClick,
    )
}

@Composable
private fun TitleDubbingNoAiDialog(
    strings: TitleDubbingStrings,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = isAppInDarkTheme()
    val accent = if (isDark) BrandOrangeBright else BrandDeepRed
    val surface = IosDesign.rowBackground(isDark)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val shape = SquircleShape(IosDesign.RadiusMd)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        IosDialogAnimatedContent {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                shape = shape,
                color = surface,
                tonalElevation = 0.dp,
                shadowElevation = if (isDark) 0.dp else 8.dp,
            ) {
                DialogBody(
                    title = strings.noAiTitle,
                    body = strings.noAiMessage,
                    cancel = strings.noAiCancel,
                    confirm = strings.noAiConnect,
                    accent = accent,
                    onSurface = onSurface,
                    muted = muted,
                    onDismiss = onDismiss,
                    onConfirm = onConnect,
                )
            }
        }
    }
}

@Composable
private fun RepairDbLogDialog(
    devRepairStrings: DevRepairDbStrings,
    onCreateLog: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = isAppInDarkTheme()
    val accent = if (isDark) BrandOrangeBright else BrandDeepRed
    val surface = IosDesign.rowBackground(isDark)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val shape = SquircleShape(IosDesign.RadiusMd)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        IosDialogAnimatedContent {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                shape = shape,
                color = surface,
                tonalElevation = 0.dp,
                shadowElevation = if (isDark) 0.dp else 8.dp,
            ) {
                DialogBody(
                    title = devRepairStrings.logDialogTitle,
                    body = devRepairStrings.logDialogMessage,
                    cancel = devRepairStrings.logDialogCancel,
                    confirm = devRepairStrings.logDialogCreate,
                    accent = accent,
                    onSurface = onSurface,
                    muted = muted,
                    onDismiss = onDismiss,
                    onConfirm = onCreateLog,
                )
            }
        }
    }
}

@Composable
private fun DialogBody(
    title: String,
    body: String,
    cancel: String,
    confirm: String,
    accent: Color,
    onSurface: Color,
    muted: Color,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
            color = onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = SnProFamily,
            color = muted,
            lineHeight = 20.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) {
                Text(text = cancel, fontFamily = SnProFamily, color = muted)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Button(
                onClick = onConfirm,
                shape = SquircleShape(IosDesign.RadiusSm),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
            ) {
                Text(text = confirm, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Сегментированный переключатель (капсульный) — используется на экране Inspect.
 * Оставлен для обратной совместимости; в самих настройках теперь [IosSegmentedControl].
 */
@Composable
fun CapsuleChipRow(
    options: List<SegmentedOption>,
    selectedIndex: Int,
    accentColor: Color,
    modifier: Modifier = Modifier,
    contentDescription: (Int) -> String? = { null },
    onOptionClick: (Int) -> Unit
) {
    val containerHeight = 44.dp
    val containerCornerRadius = containerHeight / 2
    val containerShape = RoundedCornerShape(containerCornerRadius)
    val isDark = isAppInDarkTheme()
    val unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.92f else 0.86f)
    val selectedContentColor = Color.White
    val containerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val selectedPillColor = if (isDark) accentColor.copy(alpha = 0.9f) else Color(0xFF2C2C2E)
    val selectedPillBorder = if (isDark) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.08f)

    BoxWithConstraints(
        modifier = modifier
            .height(containerHeight)
            .clip(containerShape)
            .background(containerColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.36f else 0.24f),
                shape = containerShape
            )
    ) {
        val innerWidth = maxWidth
        val segmentWidth = innerWidth / options.size
        val pillInsetHorizontal = 2.dp
        val pillInsetVertical = 3.dp
        val pillCornerRadius = (containerHeight - pillInsetVertical * 2) / 2
        val innerShape = RoundedCornerShape(pillCornerRadius)
        val pillOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "pill_offset"
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = pillOffset)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .padding(pillInsetHorizontal, pillInsetVertical)
                    .clip(innerShape)
                    .background(selectedPillColor, innerShape)
                    .border(1.dp, selectedPillBorder, innerShape),
            )
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEachIndexed { index, opt ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onOptionClick(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (opt.icon != null) {
                            Icon(
                                opt.icon,
                                contentDescription = contentDescription(index),
                                modifier = Modifier.size(20.dp),
                                tint = if (selectedIndex == index) selectedContentColor else unselectedContentColor
                            )
                        } else {
                            Text(
                                opt.label ?: "",
                                fontFamily = SnProFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (selectedIndex == index) selectedContentColor else unselectedContentColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Радиус размытия фона под листами настроек. */
private val SETTINGS_SHEET_BLUR = 16.dp
