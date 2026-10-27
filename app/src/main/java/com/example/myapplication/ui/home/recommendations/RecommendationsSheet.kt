package com.example.myapplication.ui.home.recommendations

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.domain.recommendations.ContentType
import com.example.myapplication.domain.recommendations.FeedbackVerdict
import com.example.myapplication.domain.recommendations.RecommendationItem
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.components.SwipeDeckController
import com.example.myapplication.ui.shared.components.SwipeFeedback
import com.example.myapplication.ui.shared.components.SwipeVerdict
import com.example.myapplication.ui.shared.components.SwipeableCardDeck
import com.example.myapplication.ui.shared.components.rememberSwipeDeckController
import com.example.myapplication.ui.shared.components.rememberIosSheetSwipe
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.theme.BrandDeepRed
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.StatusSuccess
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleCornerShape
import com.example.myapplication.ui.shared.theme.sheetFloatingMargin
import com.example.myapplication.ui.shared.theme.sheetPanelShape
import com.example.myapplication.ui.shared.theme.iosSheetContainer
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import kotlinx.coroutines.launch

// ==========================================
// RecommendationsSheet — шторка с колодой свайп-карточек
// (физика портирована из swipe_cards_animation.html)
// ==========================================

@Composable
fun RecommendationsSheet(
    state: RecommendationsUiState,
    strings: RecommendationsStrings,
    language: AppLanguage,
    genreRepository: GenreRepository,
    onAdd: (RecommendationItem) -> Unit,
    /** Оценка свайпом: вверх — нравится, вниз — не интересно. */
    onRate: (RecommendationItem, FeedbackVerdict) -> Unit,
    onUndoRate: (RecommendationItem) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = isAppInDarkTheme()
    com.example.myapplication.ui.shared.SheetGateEffect(true)
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    fun triggerDismiss() {
        visible = false
    }

    LaunchedEffect(visible) {
        if (!visible) {
            kotlinx.coroutines.delay(250)
            onDismiss()
        }
    }

    BackHandler { triggerDismiss() }

    val swipe = rememberIosSheetSwipe { triggerDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = OverlayThemeTokens.scrimAlpha(isDark) * com.example.myapplication.ui.shared.theme.SheetStyle.scrimFactor))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { triggerDismiss() }
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = MotionTokens.sheetPresent()
            ) + fadeIn(),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = MotionTokens.sheetDismissForced()
            ) + fadeOut()
        ) {
            BoxWithConstraints {
                val maxSheetHeight = maxHeight * 0.92f
                // Шторка по высоте контента (не фиксированная): колода задаёт свою высоту
                // сама, поэтому пустот сверху/снизу нет. Cap — 92% экрана.
                Column(
                    modifier = Modifier
                        .sheetFloatingMargin()
                        .then(swipe.panelModifier)
                        .fillMaxWidth()
                        .heightIn(max = maxSheetHeight)
                        .iosSheetContainer(
                            sheetPanelShape(),
                            isDark,
                            IosDesign.sheetSurface(isDark),
                        )
                        .navigationBarsPadding(),
                ) {
                    GrabberHandle(isDark, modifier = swipe.handleModifier)

                    when (state) {
                        is RecommendationsUiState.Ready -> SheetContent(
                            state = state,
                            strings = strings,
                            language = language,
                            genreRepository = genreRepository,
                            isDark = isDark,
                            onAdd = onAdd,
                            onRate = onRate,
                            onUndoRate = onUndoRate,
                            onLoadMore = onLoadMore,
                        )
                        is RecommendationsUiState.Loading -> Box(
                            modifier = Modifier.fillMaxWidth().height(360.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = BrandOrange)
                        }
                        is RecommendationsUiState.Unavailable -> Box(
                            modifier = Modifier.fillMaxWidth().height(280.dp).padding(horizontal = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = strings.emptyMessage,
                                fontFamily = SnProFamily,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                lineHeight = 20.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetContent(
    state: RecommendationsUiState.Ready,
    strings: RecommendationsStrings,
    language: AppLanguage,
    genreRepository: GenreRepository,
    isDark: Boolean,
    onAdd: (RecommendationItem) -> Unit,
    onRate: (RecommendationItem, FeedbackVerdict) -> Unit,
    onUndoRate: (RecommendationItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Заголовок
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = overlineFor(state, strings),
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
                color = BrandOrange,
            )
            Text(
                text = strings.sheetTitle,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        val controller = rememberSwipeDeckController()
        // Состояние привязано к списку: новый список (пересчёт/«Показать ещё») — новая колода.
        var deckEmpty by remember(state.items) { mutableStateOf(state.items.isEmpty()) }
        val verdicts = remember(state.items) { mutableStateListOf<SwipeVerdict>() }

        Box(modifier = Modifier.fillMaxWidth()) {
            CardDeck(
                items = state.items,
                addedKeys = state.addedKeys,
                strings = strings,
                language = language,
                genreRepository = genreRepository,
                onAdd = onAdd,
                controller = controller,
                onVerdict = { item, verdict ->
                    verdicts += verdict
                    onRate(
                        item,
                        if (verdict == SwipeVerdict.Approve) FeedbackVerdict.APPROVED else FeedbackVerdict.DISAPPROVED,
                    )
                },
                onUndo = { item, _ ->
                    verdicts.removeLastOrNull()
                    onUndoRate(item)
                },
                onEmptyChanged = { deckEmpty = it },
                modifier = Modifier.fillMaxWidth()
            )
            if (deckEmpty) {
                CaughtUpPanel(
                    strings = strings,
                    isLoading = state.isRefreshing,
                    onLoadMore = onLoadMore,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }

        val lastVerdict = verdicts.lastOrNull()
        if (lastVerdict != null) {
            UndoRow(
                strings = strings,
                verdict = lastVerdict,
                onUndo = { controller.undo() },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp, bottom = 16.dp),
            )
        } else {
            Text(
                text = strings.skipHint,
                fontFamily = SnProFamily,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp, bottom = 16.dp),
            )
        }
    }
}

/** Последний вердикт и «Отменить»: свайп необратим сам по себе, поэтому откат всегда под рукой. */
@Composable
private fun UndoRow(
    strings: RecommendationsStrings,
    verdict: SwipeVerdict,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val approved = verdict == SwipeVerdict.Approve
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (approved) Icons.Rounded.Check else Icons.Rounded.Close,
            contentDescription = null,
            tint = if (approved) StatusSuccess else BrandDeepRed,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (approved) strings.approvedLabel else strings.rejectedLabel,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = strings.undoAction,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = BrandOrange,
            modifier = Modifier
                .clip(CircleShape)
                .fluidClickable { onUndo() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Колода пуста: оценённое не возвращается, пока пул не пополнится с учётом оценок. */
@Composable
private fun CaughtUpPanel(
    strings: RecommendationsStrings,
    isLoading: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = BrandOrange)
            return@Column
        }
        Text(
            text = strings.caughtUpTitle,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = strings.caughtUpBody,
            fontFamily = SnProFamily,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = strings.loadMoreAction,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = Color.White,
            modifier = Modifier
                .clip(CircleShape)
                .background(BrandOrange)
                .fluidClickable { onLoadMore() }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

// ==========================================
// CardDeck — тонкая обёртка над generic SwipeableCardDeck
// (физика колоды переехала в ui/shared/components/SwipeableCardDeck.kt).
// ==========================================

@Composable
private fun CardDeck(
    items: kotlinx.collections.immutable.ImmutableList<RecommendationItem>,
    addedKeys: kotlinx.collections.immutable.ImmutableSet<String>,
    strings: RecommendationsStrings,
    language: AppLanguage,
    genreRepository: GenreRepository,
    onAdd: (RecommendationItem) -> Unit,
    controller: SwipeDeckController,
    onVerdict: (RecommendationItem, SwipeVerdict) -> Unit,
    onUndo: (RecommendationItem, SwipeVerdict) -> Unit,
    onEmptyChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    // Вверх — зелёный (как «просмотрено»), вниз — красный из брендового градиента.
    val feedback = SwipeFeedback(
        controller = controller,
        approveColor = StatusSuccess,
        rejectColor = BrandDeepRed,
        approveIcon = Icons.Rounded.Check,
        rejectIcon = Icons.Rounded.Close,
        onVerdict = onVerdict,
        onUndo = onUndo,
        onEmptyChanged = onEmptyChanged,
    )
    SwipeableCardDeck(
        items = items,
        key = { it.key },
        modifier = modifier,
        feedback = feedback,
    ) { card, isTop ->
        SwipeCardContent(
            card = card,
            isAdded = card.key in addedKeys,
            strings = strings,
            language = language,
            genreRepository = genreRepository,
            isTop = isTop,
            onAdd = {
                performHaptic(view, Haptic.Success)
                onAdd(card)
            },
        )
    }
}

@Composable
private fun BoxScope.SwipeCardContent(
    card: RecommendationItem,
    isAdded: Boolean,
    strings: RecommendationsStrings,
    language: AppLanguage,
    genreRepository: GenreRepository,
    isTop: Boolean,
    onAdd: () -> Unit,
) {
    val context = LocalContext.current

    val genreLabel = remember(card.key, language) {
        card.genres.take(2).joinToString(" · ") { genreRepository.getLabel(it, language) }
    }
    val rating10 = card.externalRating?.let { it / 10f }

    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(card.coverUrl)
                .size(Size(560, 800))
                .crossfade(true)
                .build(),
            contentDescription = card.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Градиент для читаемости текста (аналог card::before в прототипе)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.08f),
                        0.55f to Color.Black.copy(alpha = 0.12f),
                        1f to Color.Black.copy(alpha = 0.78f),
                    )
                )
        )
        // Весь «обвес» появляется, только когда карточка вышла на фронт, и делает это
        // плавно (fadeIn), а не резким включением. Задние карточки — чистая обложка.
        val infoEnter = fadeIn(tween(300, delayMillis = 60))
        val infoExit = fadeOut(tween(120))

        // Рейтинг источника
        AnimatedVisibility(
            visible = isTop && rating10 != null && rating10 > 0f,
            enter = infoEnter,
            exit = infoExit,
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Row(
                modifier = Modifier
                    .padding(14.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = BrandOrange,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = String.format(java.util.Locale.US, "%.1f", rating10 ?: 0f),
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White,
                )
            }
        }

        // Тип контента: в смешанной колоде аниме, фильмы и сериалы идут вперемешку.
        AnimatedVisibility(
            visible = isTop,
            enter = infoEnter,
            exit = infoExit,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Text(
                text = when (card.contentType) {
                    ContentType.ANIME -> strings.typeAnime
                    ContentType.MOVIE -> strings.typeMovie
                    ContentType.TV -> strings.typeSeries
                },
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = Color.White,
                modifier = Modifier
                    .padding(14.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

        AnimatedVisibility(
            visible = isTop,
            enter = infoEnter,
            exit = infoExit,
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                if (!card.seedTitle.isNullOrBlank()) {
                    Text(
                        text = "${strings.similarToPrefix} ${card.seedTitle}",
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = BrandOrange.copy(alpha = 0.95f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = card.title,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    lineHeight = 26.sp,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (genreLabel.isNotBlank()) {
                    Text(
                        text = genreLabel,
                        fontFamily = SnProFamily,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isAdded) Color.White.copy(alpha = 0.22f) else BrandOrange)
                        .then(if (isAdded) Modifier else Modifier.fluidClickable { onAdd() })
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isAdded) Icons.Rounded.Check else Icons.Rounded.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isAdded) strings.addedLabel else strings.addAction,
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color.White,
                    )
                }
            }
        }
    }
}
