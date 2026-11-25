package com.example.myapplication.ui.inspect

import com.example.myapplication.network.listKey
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.example.myapplication.domain.inspect.InspectContentMode
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.navigation.navigateToSettings
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.mascot.MascotEmptyState
import com.example.myapplication.ui.shared.mascot.MascotScene
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.IosScroll
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleShape
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.getAiConnectStrings
import com.example.myapplication.utils.getStrings
import com.example.myapplication.utils.performHaptic
import com.phnem.vetro.R
import kotlinx.coroutines.launch

/**
 * Подписи, которых нет в UiStrings: туда больше нельзя добавлять поля (лимит 254, см. память
 * проекта), а новому виду экрана нужны кнопка выбора, подзаголовок и заголовок результатов.
 */
private class InspectExtraStrings(ru: Boolean) {
    val subtitle = if (ru) {
        "Скриншот из серии или фильма — найдём, откуда кадр"
    } else {
        "A screenshot from an episode or a film — we'll find where it's from"
    }
    val choose = if (ru) "Выбрать кадр" else "Choose a frame"
    val another = if (ru) "Другой кадр" else "Another frame"
    val found = if (ru) "Похоже, это" else "Looks like"
    val dropTitle = if (ru) "Покажите кадр" else "Show a frame"
}

/**
 * Поиск по кадру. Вид — общий с остальным приложением: тёплый градиент настроек вместо фото под
 * виньеткой, крупный заголовок, карточки на ступенях [IosDesign], Phosphor-иконки и маскот в
 * состояниях загрузки и ошибки. Режимы (аниме / фильмы и сериалы) — страницы одного пейджера,
 * которым управляет нижний мини-док.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun InspectScreen(
    navController: NavController,
    viewModel: InspectViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    /**
     * Что делает «назад». null — кнопки в шапке нет: экран показан страницей рабочей области,
     * где уходить некуда, а системный Back перехватывает хозяин (TICKET-07).
     */
    onBack: (() -> Unit)? = null,
    /**
     * Промо «подключить ИИ». null — открыть маршрут настроек (старая навигация); страница
     * рабочей области передаёт «перелистни на Настройки».
     */
    onOpenSettings: (() -> Unit)? = null,
    /** Резерв снизу под чужой док (рабочая область). 0 — экран сам себе хозяин. */
    bottomInset: Dp = 0.dp,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val isDark = isAppInDarkTheme()
    val lang by viewModel.uiLanguage.collectAsStateWithLifecycle()
    val strings = getStrings(lang)
    val extra = remember(lang) { InspectExtraStrings(lang == AppLanguage.RU) }
    val contentMode by viewModel.contentMode.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedUri by viewModel.selectedImageUri.collectAsStateWithLifecycle()
    val addingId by viewModel.addingFromApiId.collectAsStateWithLifecycle()
    val hasVisionProvider by viewModel.hasVisionProvider.collectAsStateWithLifecycle()
    val aiConnectStrings = getAiConnectStrings(lang)
    val ink = MaterialTheme.colorScheme.onBackground
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    val background = remember(isDark) { IosDesign.screenGradient(isDark) }
    val onSelectMode: (Int) -> Unit = { index ->
        performHaptic(view, Haptic.Light)
        when (index) {
            0 -> viewModel.setContentMode(InspectContentMode.Anime)
            1 -> viewModel.setContentMode(InspectContentMode.MoviesSeries)
        }
    }

    // Пейджер — единственный источник правды о режиме: док листает его, а осевшая страница
    // возвращается во вьюмодель. Иначе клик по доку и свайп спорили бы за состояние.
    val pagerState = rememberPagerState(
        initialPage = if (contentMode == InspectContentMode.Anime) 0 else 1,
        pageCount = { 2 },
    )
    val scope = rememberCoroutineScope()
    // Сохранённый режим читается из DataStore позже первого кадра: пейджер догоняет его.
    LaunchedEffect(contentMode) {
        val target = if (contentMode == InspectContentMode.Anime) 0 else 1
        if (pagerState.settledPage != target && !pagerState.isScrollInProgress) pagerState.scrollToPage(target)
    }
    LaunchedEffect(pagerState.settledPage) {
        val settled = if (pagerState.settledPage == 0) InspectContentMode.Anime else InspectContentMode.MoviesSeries
        if (settled != viewModel.contentMode.value) onSelectMode(pagerState.settledPage)
    }

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.analyzeImage(context, it) }
    }
    val pick: () -> Unit = {
        performHaptic(view, Haptic.Light)
        pickLauncher.launch("image/*")
    }

    // Содержимое окна идёт своей дорожкой (§5 спеки движения): оболочка вырастает из кнопки
    // сразу, а экран проявляется, когда она почти раскрылась, и гаснет первым при закрытии.
    val windowContentAlpha by animatedVisibilityScope.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(
                    durationMillis = MotionTokens.EaseEnterMillis,
                    delayMillis = MotionTokens.WindowContentRevealDelayMillis,
                    easing = MotionTokens.EaseEnter,
                )
            } else {
                tween(MotionTokens.EaseExitMillis, easing = MotionTokens.EaseExit)
            }
        },
        label = "inspectContentAlpha",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

    with(sharedTransitionScope) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "inspect_container"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    // Оболочка видна с первого кадра — прозрачность содержимого ведёт
                    // windowContentAlpha. Уходя, оболочка гаснет, пока схлопывается в кнопку.
                    enter = EnterTransition.None,
                    exit = fadeOut(
                        tween(
                            durationMillis = WINDOW_SHELL_FADE_MS,
                            delayMillis = MotionTokens.EaseExitMillis,
                            easing = MotionTokens.EaseExit,
                        ),
                    ),
                    // Открытие: окно — цель, и пружина берётся с его стороны.
                    boundsTransform = { _, _ -> MotionTokens.largeSurfaceEnterBounds },
                    // Экран меряется один раз и масштабируется: перемерять пейджер и списки на
                    // каждом кадре роста из 36dp в весь экран — провал FPS (закон 7).
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                    clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(32.dp))
                )
                .clip(RoundedCornerShape(32.dp))
                .background(IosDesign.groupedBackground(isDark))
                // Без layerBackdrop: корень экрана — узел sharedBounds, и при переходе Compose
                // рисует это поддерево в overlay шаред-элемента. Записанный GraphicsLayer бэкдропа
                // при этом отрисовывается из двух мест сразу → SIGSEGV в RenderThread на открытии.
                .background(background)
        ) {
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = windowContentAlpha }) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    InspectHeader(
                        title = strings.inspectTitle,
                        subtitle = extra.subtitle,
                        onBack = onBack?.let { back ->
                            {
                                performHaptic(view, Haptic.Light)
                                back()
                            }
                        },
                        backContentDescription = strings.inspectBack,
                        isDark = isDark,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    // Shared Axis X, как в Details: режимы — страницы одного пейджера, палец
                    // тянет контент за собой.
                    HorizontalPager(
                        flingBehavior = IosScroll.pagerFlingBehavior(pagerState),
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        // Флаг вычисляем ПО СТРАНИЦЕ, а не по активному режиму: соседняя
                        // страница рендерится заранее (beyondViewportPageCount), и общий
                        // requiresAiSetup показал бы на ней контент чужого режима.
                        val pageNeedsAi = page == 1 && !hasVisionProvider
                        Column(Modifier.fillMaxSize()) {
                            if (pageNeedsAi) {
                                AiConnectPromptCard(
                                    title = aiConnectStrings.tileTitle,
                                    body = strings.inspectGeminiRequiredMovies,
                                    buttonLabel = aiConnectStrings.connectButton,
                                    isDark = isDark,
                                    onOpenAiConnect = {
                                        performHaptic(view, Haptic.Light)
                                        // В рабочей области настройки — соседняя страница, а не
                                        // маршрут: push поверх пейджера увёл бы из области целиком.
                                        onOpenSettings?.invoke() ?: navController.navigateToSettings()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                )
                            } else {
                                FramePicker(
                                    selectedUri = selectedUri,
                                    isDark = isDark,
                                    title = extra.dropTitle,
                                    hint = strings.inspectPickScreenshot,
                                    formats = strings.inspectImageFormatsHint,
                                    chooseLabel = extra.choose,
                                    anotherLabel = extra.another,
                                    clearDescription = strings.inspectClearPhoto,
                                    onPick = pick,
                                    onClear = {
                                        performHaptic(view, Haptic.Light)
                                        viewModel.clearPreviewAndResults()
                                    },
                                    // Соседняя страница тоже скомпонована: общий ключ — только у текущей.
                                    iconModifier = if (page == pagerState.currentPage) {
                                        Modifier.sharedElement(
                                            sharedContentState = rememberSharedContentState(key = "inspect_icon"),
                                            animatedVisibilityScope = animatedVisibilityScope,
                                        )
                                    } else {
                                        Modifier
                                    },
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                if (!pageNeedsAi) {
                                    Text(
                                        text = strings.inspectPoweredByFooter,
                                        fontFamily = SnProFamily,
                                        fontSize = 10.sp,
                                        letterSpacing = 1.2.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = muted.copy(alpha = 0.6f),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .zIndex(0f)
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                            .padding(bottom = 88.dp + bottomInset, top = 10.dp)
                                    )
                                    InspectStateContent(
                                        state = uiState,
                                        ink = ink,
                                        muted = muted,
                                        isDark = isDark,
                                        foundLabel = extra.found,
                                        addingId = addingId,
                                        addLabel = strings.addButton,
                                        addedLabel = strings.addedButton,
                                        onAdd = { result ->
                                            performHaptic(view, Haptic.Light)
                                            viewModel.addFromApi(result)
                                        },
                                        bottomInset = bottomInset,
                                        modifier = Modifier.fillMaxSize().zIndex(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                InspectModeDock(
                    animeLabel = strings.inspectModeAnime,
                    moviesLabel = strings.inspectSegmentMoviesTv,
                    selectedIndex = pagerState.targetPage,
                    onSelect = { page ->
                        performHaptic(view, Haptic.Light)
                        scope.launch {
                            pagerState.animateScrollToPage(
                                page,
                                animationSpec = MotionTokens.sheetPresent(),
                            )
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        // Внутренний док режимов встаёт НАД доком рабочей области: оба
                        // прижаты к низу, и без резерва они наезжали бы друг на друга.
                        .padding(bottom = 16.dp + bottomInset)
                        .zIndex(3f),
                )
            }
        }
    }
}

/** Оболочка окна гаснет, пока схлопывается в кнопку: к гнезду она подлетает уже прозрачной. */
private const val WINDOW_SHELL_FADE_MS = 200

private val PickerShape = SquircleShape(28.dp, smoothing = 0.6f)

/**
 * Зона кадра. Пусто — приглашение с иконкой сканера и оранжевой кнопкой; кадр выбран — он сам во
 * всю зону, поверх него «сбросить» и «другой кадр» тёмными пилюлями.
 */
@Composable
private fun FramePicker(
    selectedUri: Any?,
    isDark: Boolean,
    title: String,
    hint: String,
    formats: String,
    chooseLabel: String,
    anotherLabel: String,
    clearDescription: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
    iconModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(272.dp)
            .clip(PickerShape)
            .background(IosDesign.groupBackground(isDark))
            .border(1.dp, IosDesign.separator(isDark).copy(alpha = 0.08f), PickerShape)
            .fluidClickable(scaleDown = 0.985f) { onPick() },
    ) {
        AnimatedContent(
            targetState = selectedUri,
            transitionSpec = {
                (fadeIn(MotionTokens.tweenStandard()) + scaleIn(MotionTokens.springSurface(), initialScale = 0.96f))
                    .togetherWith(fadeOut(MotionTokens.tweenFast()))
            },
            label = "inspectFrame",
        ) { uri ->
            if (uri != null) {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    DarkPill(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(36.dp),
                        onClick = onClear,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ph_x),
                            contentDescription = clearDescription,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DarkPill(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .height(36.dp),
                        onClick = onPick,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ph_image),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = anotherLabel,
                                color = Color.White,
                                fontFamily = SnProFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .then(iconModifier)
                            .clip(SquircleShape(20.dp, smoothing = 0.6f))
                            .background(BrandOrange.copy(alpha = if (isDark) 0.18f else 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ph_scan),
                            contentDescription = null,
                            tint = BrandOrange,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = title,
                        color = ink,
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = hint,
                        color = muted,
                        fontFamily = SnProFamily,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(CircleShape)
                            .background(BrandOrange)
                            .fluidClickable { onPick() }
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ph_image),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = chooseLabel,
                            color = Color.White,
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = formats,
                        color = muted.copy(alpha = 0.7f),
                        fontFamily = SnProFamily,
                        fontSize = 11.sp,
                        letterSpacing = 0.6.sp,
                    )
                }
            }
        }
    }
}

/** Тёмная полупрозрачная пилюля поверх кадра: читается на любой картинке. */
@Composable
private fun DarkPill(
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.48f))
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .fluidClickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun InspectStateContent(
    state: InspectUiState,
    ink: Color,
    muted: Color,
    isDark: Boolean,
    foundLabel: String,
    addingId: String?,
    addLabel: String,
    addedLabel: String,
    onAdd: (com.example.myapplication.network.ApiSearchResult) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = state,
        contentKey = { it::class },
        transitionSpec = { fadeIn(MotionTokens.tweenStandard()).togetherWith(fadeOut(MotionTokens.tweenFast())) },
        label = "inspectState",
        modifier = modifier,
    ) { s ->
        when (s) {
            is InspectUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                MascotEmptyState(
                    scene = MascotScene.Search,
                    title = s.message,
                    illustrationSize = 140.dp,
                    titleColor = ink,
                    subtitleColor = muted,
                    isDark = isDark,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 24.dp, end = 24.dp),
                )
            }

            is InspectUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                MascotEmptyState(
                    scene = MascotScene.Question,
                    title = s.message,
                    illustrationSize = 140.dp,
                    titleColor = ink,
                    subtitleColor = muted,
                    isDark = isDark,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 24.dp, end = 24.dp),
                )
            }

            is InspectUiState.Idle -> Box(Modifier.fillMaxSize())

            is InspectUiState.Success -> LazyColumn(
                flingBehavior = IosScroll.flingBehavior(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 18.dp, 16.dp, 116.dp + bottomInset),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") {
                    Text(
                        text = foundLabel,
                        style = OverlayThemeTokens.SectionLabel,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = OverlayThemeTokens.LabelMutedAlpha),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                items(
                    items = s.results,
                    key = { m -> m.result.listKey },
                ) { uiModel ->
                    val r = uiModel.result
                    val addKey = r.listKey
                    InspectResultCard(
                        result = r,
                        isAdded = uiModel.isAdded,
                        isLoading = addingId == addKey,
                        addLabel = addLabel,
                        addedLabel = addedLabel,
                        onAddClick = { onAdd(r) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectHeader(
    title: String,
    subtitle: String,
    /** null — кнопки «назад» нет: экран показан страницей рабочей области, уходить некуда. */
    onBack: (() -> Unit)?,
    backContentDescription: String,
    isDark: Boolean,
) {
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        if (onBack != null) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(IosDesign.groupRowBackground(isDark))
                    .fluidClickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ph_arrow_left),
                    contentDescription = backContentDescription,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            letterSpacing = (-0.6).sp,
            lineHeight = 36.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            color = muted,
            fontFamily = SnProFamily,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun AiConnectPromptCard(
    title: String,
    body: String,
    buttonLabel: String,
    isDark: Boolean,
    onOpenAiConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = SquircleShape(IosDesign.GroupRadius, smoothing = 0.6f)
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clip(shape)
            .background(IosDesign.groupBackground(isDark))
            .padding(18.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(SquircleShape(14.dp, smoothing = 0.6f))
                .background(BrandOrange.copy(alpha = if (isDark) 0.18f else 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ph_sparkle_fill),
                contentDescription = null,
                tint = BrandOrange,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            color = muted,
            fontFamily = SnProFamily,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(CircleShape)
                .background(BrandOrange)
                .fluidClickable { onOpenAiConnect() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = buttonLabel,
                color = Color.White,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}
