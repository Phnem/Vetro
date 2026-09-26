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
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.example.myapplication.domain.recommendations.RecommendationItem
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.components.SwipeableCardDeck
import com.example.myapplication.ui.shared.components.rememberIosSheetSwipe
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleCornerShape
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
    onDismiss: () -> Unit,
) {
    val isDark = isAppInDarkTheme()
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
                    .background(Color.Black.copy(alpha = OverlayThemeTokens.scrimAlpha(isDark)))
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
                        .then(swipe.panelModifier)
                        .fillMaxWidth()
                        .heightIn(max = maxSheetHeight)
                        .iosSheetContainer(
                            SquircleCornerShape(IosDesign.SheetCorner, IosDesign.SheetCorner, 0.dp, 0.dp),
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

        CardDeck(
            items = state.items,
            addedKeys = state.addedKeys,
            strings = strings,
            language = language,
            genreRepository = genreRepository,
            onAdd = onAdd,
            modifier = Modifier.fillMaxWidth()
        )

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
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    SwipeableCardDeck(
        items = items,
        key = { it.key },
        modifier = modifier,
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
