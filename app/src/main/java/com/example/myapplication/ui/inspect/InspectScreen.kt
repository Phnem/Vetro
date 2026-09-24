package com.example.myapplication.ui.inspect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image as BgImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.phnem.vetro.R
import com.example.myapplication.domain.inspect.InspectContentMode
import com.example.myapplication.ui.navigation.navigateToSettings
import com.example.myapplication.ui.shared.theme.InspectVisualSearchTheme
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.utils.getAiConnectStrings
import com.example.myapplication.utils.getStrings
import com.example.myapplication.utils.performHaptic
import kotlinx.coroutines.launch

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
    val lang by viewModel.uiLanguage.collectAsStateWithLifecycle()
    val strings = getStrings(lang)
    val contentMode by viewModel.contentMode.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedUri by viewModel.selectedImageUri.collectAsStateWithLifecycle()
    val addingId by viewModel.addingFromApiId.collectAsStateWithLifecycle()
    val hasVisionProvider by viewModel.hasVisionProvider.collectAsStateWithLifecycle()
    val aiConnectStrings = getAiConnectStrings(lang)
    val onSelectMode: (Int) -> Unit = { index ->
        performHaptic(view, "light")
        when (index) {
            0 -> viewModel.setContentMode(InspectContentMode.Anime)
            1 -> viewModel.setContentMode(InspectContentMode.MoviesSeries)
        }
    }

    val vignetteBrush = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Black.copy(alpha = 0.82f),
                0.24f to Color.Black.copy(alpha = 0.10f),
                0.76f to Color.Black.copy(alpha = 0.10f),
                1f to Color.Black.copy(alpha = 0.88f)
            )
        )
    }

    // Пейджер — единственный источник правды о режиме: док листает его, а осевшая страница
    // возвращается во вьюмодель. Иначе клик по доку и свайп спорили бы за состояние.
    val pagerState = rememberPagerState(
        initialPage = if (contentMode == InspectContentMode.Anime) 0 else 1,
        pageCount = { 2 },
    )
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState.settledPage) { onSelectMode(pagerState.settledPage) }

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.analyzeImage(context, it) }
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
                .background(Color.Black)
        ) {
            Box(modifier = Modifier.graphicsLayer { alpha = windowContentAlpha }) {
            InspectVisualSearchTheme {
                val cardShape = RoundedCornerShape(22.dp)
                val outlineMuted = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

                // Без layerBackdrop: корень экрана — узел sharedBounds, и при переходе Compose
                // рисует это поддерево в overlay шаред-элемента. Записанный GraphicsLayer бэкдропа
                // при этом отрисовывается из двух мест сразу → SIGSEGV в RenderThread на открытии.
                // Док берёт статичную заливку; фон здесь и так фото под виньеткой.
                Box(modifier = Modifier.fillMaxSize()) {
                    BgImage(
                        painter = painterResource(R.drawable.vsbg),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(vignetteBrush)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        InspectHeader(
                            toolbarTitle = strings.inspectVisualSearchToolbarTitle,
                            brandLabel = strings.appName.uppercase(),
                            onBack = onBack?.let { back ->
                                {
                                    performHaptic(view, "light")
                                    back()
                                }
                            },
                            backContentDescription = strings.inspectBack,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Text(
                            text = strings.inspectTitle,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            textAlign = TextAlign.Start
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // Shared Axis X, как в Details: режимы — страницы одного пейджера, палец
                        // тянет контент за собой. Раньше свайп просто подменял режим на месте,
                        // без всякого движения, поэтому перехода не читалось вовсе.
                        HorizontalPager(
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
                                onOpenAiConnect = {
                                    performHaptic(view, "light")
                                    // В рабочей области настройки — соседняя страница, а не
                                    // маршрут: push поверх пейджера увёл бы из области целиком.
                                    onOpenSettings?.invoke() ?: navController.navigateToSettings()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(268.dp)
                                    .padding(horizontal = 16.dp)
                                    .clip(cardShape)
                                    .border(1.dp, outlineMuted, cardShape)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.38f))
                                    .clickable {
                                        performHaptic(view, "light")
                                        pickLauncher.launch("image/*")
                                    }
                            ) {
                                when {
                                    selectedUri != null -> {
                                        AsyncImage(
                                            model = selectedUri,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                        IconButton(
                                            onClick = {
                                                performHaptic(view, "light")
                                                viewModel.clearPreviewAndResults()
                                            },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(12.dp)
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                                                )
                                        ) {
                                            Icon(
                                                painter = painterResource(android.R.drawable.ic_menu_close_clear_cancel),
                                                contentDescription = strings.inspectClearPhoto,
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }

                                    else -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(64.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary)
                                                    .sharedElement(
                                                        sharedContentState = rememberSharedContentState(key = "inspect_icon"),
                                                        animatedVisibilityScope = animatedVisibilityScope
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Image,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(20.dp))
                                            Text(
                                                text = strings.inspectPickScreenshot,
                                                style = MaterialTheme.typography.headlineMedium,
                                                color = MaterialTheme.colorScheme.onBackground,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = strings.inspectImageFormatsHint,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            if (!pageNeedsAi) {
                                Text(
                                    text = strings.inspectPoweredByFooter,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .zIndex(0f)
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .padding(bottom = 88.dp + bottomInset, top = 10.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(1f)
                                ) {
                                    when (val s = uiState) {
                                        is InspectUiState.Loading -> {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                                ) {
                                                    CircularProgressIndicator(
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Text(
                                                        text = s.message,
                                                        color = MaterialTheme.colorScheme.onBackground,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        textAlign = TextAlign.Center,
                                                        modifier = Modifier.padding(horizontal = 24.dp)
                                                    )
                                                }
                                            }
                                        }

                                        is InspectUiState.Error -> {
                                            Text(
                                                text = s.message,
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodyLarge,
                                                modifier = Modifier.padding(24.dp)
                                            )
                                        }

                                        is InspectUiState.Idle -> { /* empty */ }
                                        is InspectUiState.Success -> {
                                            LazyColumn(
                                                modifier = Modifier.fillMaxSize(),
                                                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 116.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                items(
                                                    items = s.results,
                                                    key = { m ->
                                                        "${m.result.source}_${m.result.externalId ?: m.result.title}"
                                                    }
                                                ) { uiModel ->
                                                    val r = uiModel.result
                                                    val addKey = "${r.source}_${r.externalId ?: r.title}"
                                                    InspectResultCard(
                                                        result = r,
                                                        isAdded = uiModel.isAdded,
                                                        isLoading = addingId == addKey,
                                                        addLabel = strings.addButton,
                                                        addedLabel = strings.addedButton,
                                                        onAddClick = {
                                                            performHaptic(view, "light")
                                                            viewModel.addFromApi(r)
                                                        },
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
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
                            performHaptic(view, "light")
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
    }
}

/** Оболочка окна гаснет, пока схлопывается в кнопку: к гнезду она подлетает уже прозрачной. */
private const val WINDOW_SHELL_FADE_MS = 200

@Composable
private fun AiConnectPromptCard(
    title: String,
    body: String,
    buttonLabel: String,
    onOpenAiConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.38f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.9f)
        )
        Button(
            onClick = onOpenAiConnect,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(buttonLabel)
        }
    }
}
