package com.example.myapplication.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.UiStrings
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.isAppInDarkTheme
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.typeManga
import com.example.myapplication.data.models.typeSeries
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.myapplication.ui.shared.components.FormatCategoryTile
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.components.rememberIosSheetSwipe
import com.example.myapplication.ui.shared.components.IosRow
import com.example.myapplication.ui.shared.components.IosRowDivider
import com.example.myapplication.ui.shared.components.IosSelectionOption
import com.example.myapplication.ui.shared.components.IosSelectionSheet
import androidx.compose.material.icons.filled.Check
import com.example.myapplication.ui.shared.gradientHighlightBorder
import com.example.myapplication.ui.shared.inertialCollision
import com.example.myapplication.ui.shared.rememberInertialCollisionState
import com.example.myapplication.ui.shared.theme.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector

// ==========================================
// LibraryMediaTypeFilterRow — плитки фильтра коллекции в нижней зоне
// ==========================================
@Composable
fun LibraryMediaTypeFilterRow(
    strings: UiStrings,
    selected: MediaType?,
    onSelect: (MediaType?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isAppInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val tileShape = RoundedCornerShape(14.dp)
    val tileBg =
        if (isDark) scheme.surfaceContainerHigh.copy(alpha = 0.42f)
        else scheme.surfaceVariant
    val allLabel = if (strings.languageName == "RU") "Все" else "All"

    data class FilterTile(
        val filter: MediaType?,
        val label: String,
        val icon: ImageVector,
        val accent: Color,
    )

    val tiles = listOf(
        FilterTile(null, allLabel, Icons.Outlined.GridView, Color(0xFFA7A7A7)),
        FilterTile(MediaType.ANIME, strings.typeAnime, Icons.Outlined.AutoAwesome, Color(0xFFE85002)),
        FilterTile(MediaType.MANGA, strings.typeManga, Icons.AutoMirrored.Outlined.MenuBook, Color(0xFFD9C3AB)),
        // Одна плитка на MOVIE+SERIES (был единый TV_SERIES) — комбинированное сопоставление
        // живёт в AnimeRepository.observeAnimeList, не здесь.
        FilterTile(MediaType.SERIES, strings.typeSeries, Icons.Outlined.Tv, Color(0xFFFFB067)),
    )

    @Composable
    fun FilterTileItem(tile: FilterTile, modifier: Modifier = Modifier) {
        val isSelected = selected == tile.filter
        FormatCategoryTile(
            icon = tile.icon,
            accentColor = tile.accent,
            label = tile.label,
            showAccentBorder = isSelected,
            useAccentIcon = isSelected,
            selectedCount = if (isSelected) 1 else 0,
            isDark = isDark,
            tileShape = tileShape,
            tileBg = tileBg,
            onClick = { onSelect(tile.filter) },
            modifier = modifier,
            alwaysAccentIcon = true,
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilterTileItem(tiles[0], Modifier.weight(1f))
            FilterTileItem(tiles[1], Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilterTileItem(tiles[2], Modifier.weight(1f))
            FilterTileItem(tiles[3], Modifier.weight(1f))
        }
    }
}

@Composable
fun MediaTypeFilterOverlay(
    visibleState: MutableTransitionState<Boolean>,
    strings: UiStrings,
    selected: MediaType?,
    onSelect: (MediaType?) -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = isAppInDarkTheme()
    BackHandler { onDismiss() }
    val swipe = rememberIosSheetSwipe(onDismiss)

    val ru = strings.languageName == "RU"
    data class TypeOption(val filter: MediaType?, val label: String, val icon: ImageVector, val accent: Color, val subtitle: String)
    val allLabel = if (ru) "Все" else "All"
    val options = listOf(
        TypeOption(null, allLabel, Icons.Outlined.GridView, Color(0xFFA7A7A7), if (ru) "Вся коллекция" else "Whole collection"),
        TypeOption(MediaType.ANIME, strings.typeAnime, Icons.Outlined.AutoAwesome, Color(0xFFE85002), if (ru) "Только аниме" else "Anime only"),
        TypeOption(MediaType.MANGA, strings.typeManga, Icons.AutoMirrored.Outlined.MenuBook, Color(0xFFD9C3AB), if (ru) "Только манга" else "Manga only"),
        // MOVIE+SERIES под одной опцией (см. HomeComponents.tiles выше) — комбинированное
        // сопоставление в AnimeRepository.observeAnimeList.
        TypeOption(MediaType.SERIES, strings.typeSeries, Icons.Outlined.Tv, Color(0xFFFFB067), if (ru) "Фильмы и сериалы" else "Movies & series"),
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Шторка выезжает снизу и уходит вниз; физика и хореография — в MotionBottomSheet.
        MotionBottomSheet(
            visibleState = visibleState,
            onDismiss = onDismiss,
            isDark = isDark,
            swipe = swipe,
            panelModifier = Modifier.fillMaxWidth(),
            scrimAlpha = 0.4f,
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            ) {
                GrabberHandle(isDark, modifier = swipe.handleModifier)
                IosSelectionSheet(
                    title = strings.contentTypeTitle,
                    options = options.map { IosSelectionOption(it.label, it.icon, it.accent, it.subtitle) },
                    selectedIndex = options.indexOfFirst { it.filter == selected }.coerceAtLeast(0),
                    isDark = isDark,
                    doneLabel = strings.genreFilterDone,
                    onSelect = { i -> onSelect(options[i].filter) },
                    onDone = onDismiss,
                )
            }
        }
    }
}

// ==========================================
// AnimeListActionMenu — Material 3 bottom sheet content (UDF)
// ==========================================

/** Режим подтверждения: удаление, добавление в избранное или снятие из избранного. */
enum class AnimeMenuConfirmMode { DELETE, ADD_TO_FAVORITE, REMOVE_FROM_FAVORITE }

/**
 * Тексты снятия из избранного — отдельным провайдером, а не полями [UiStrings].
 *
 * В UiStrings 252 поля при жёстком потолке 254: 255-й параметр конструктора роняет RELEASE-сборку
 * `VerifyError` в `<clinit>` (debug при этом собирается и работает). См. [[uistrings-255-param-limit]].
 */
private data class FavoriteMenuStrings(val snark: String, val confirm: String)

private fun favoriteMenuStrings(language: AppLanguage): FavoriteMenuStrings = when (language) {
    AppLanguage.RU -> FavoriteMenuStrings(
        snark = "Больше не любимое?",
        confirm = "Убрать",
    )
    AppLanguage.EN -> FavoriteMenuStrings(
        snark = "Not a favorite anymore?",
        confirm = "Remove",
    )
}

/** MVI State for the anime list action menu. */
data class AnimeMenuState(
    val title: String,
    val imageUrl: String,
    val statusText: String,
    val confirmMode: AnimeMenuConfirmMode
)

/** Isolated events (Sealed Interface — Kotlin 2.x). */
sealed interface AnimeMenuEvent {
    data object OnConfirm : AnimeMenuEvent
    data object OnCancel : AnimeMenuEvent
}

@Composable
fun AnimeListActionMenu(
    state: AnimeMenuState,
    strings: UiStrings,
    language: AppLanguage,
    onEvent: (AnimeMenuEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val favStrings = favoriteMenuStrings(language)
    val isDark = isAppInDarkTheme()
    val onCard = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (isDark) {
        OverlayThemeTokens.LabelMutedDark.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val confirmButtonContainer = when (state.confirmMode) {
        AnimeMenuConfirmMode.DELETE -> OverlayThemeTokens.AccentNeonRed
        AnimeMenuConfirmMode.ADD_TO_FAVORITE -> OverlayThemeTokens.FavoriteConfirmGold
        // Снятие — не разрушительное действие, поэтому не красный: та же кнопка, но приглушённая.
        AnimeMenuConfirmMode.REMOVE_FROM_FAVORITE -> OverlayThemeTokens.FavoriteCardBorder
    }
    val confirmButtonContent = when (state.confirmMode) {
        AnimeMenuConfirmMode.DELETE -> Color.White
        AnimeMenuConfirmMode.ADD_TO_FAVORITE,
        AnimeMenuConfirmMode.REMOVE_FROM_FAVORITE -> OverlayThemeTokens.OnFavoriteConfirmGold
    }
    val confirmLabel = when (state.confirmMode) {
        AnimeMenuConfirmMode.DELETE -> strings.deleteConfirm
        AnimeMenuConfirmMode.ADD_TO_FAVORITE -> strings.addButton
        AnimeMenuConfirmMode.REMOVE_FROM_FAVORITE -> favStrings.confirm
    }
    val confirmIcon = when (state.confirmMode) {
        AnimeMenuConfirmMode.DELETE -> Icons.Default.Delete
        AnimeMenuConfirmMode.ADD_TO_FAVORITE -> Icons.Rounded.Star
        AnimeMenuConfirmMode.REMOVE_FROM_FAVORITE -> Icons.Rounded.StarBorder
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // --- БЛОК 1: УВЕЛИЧЕННОЕ ПРЕВЬЮ И ИНФО ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            AsyncImage(
                model = state.imageUrl.takeIf { it.isNotBlank() }?.let { java.io.File(it) },
                contentDescription = "Anime Poster",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(114.dp)
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(16.dp))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = onCard,
                    lineHeight = 34.sp
                )
                Text(
                    text = state.statusText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontFamily = SnProFamily
                    ),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    color = muted
                )
            }
        }

        HorizontalDivider(
            color = if (isDark) OverlayThemeTokens.RimDark else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
        )

        // --- БЛОК 2: ДВЕ КНОПКИ — подтверждение + отмена (только обводка) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { onEvent(AnimeMenuEvent.OnConfirm) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(100),
                colors = ButtonDefaults.buttonColors(
                    containerColor = confirmButtonContainer,
                    contentColor = confirmButtonContent
                )
            ) {
                Icon(confirmIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { onEvent(AnimeMenuEvent.OnCancel) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(100),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = onCard
                ),
                border = BorderStroke(
                    1.dp,
                    if (isDark) Color.White.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                )
            ) {
                Text(strings.cancel)
            }
        }
    }
}

/** Bottom sheet wrapper for [AnimeListActionMenu] — overlay, card, spring animation, dismiss. */
@Composable
fun AnimeListMenuSheet(
    anime: Anime,
    confirmMode: AnimeMenuConfirmMode,
    strings: UiStrings,
    language: AppLanguage,
    getImgPath: (String?) -> String?,
    onEvent: (AnimeMenuEvent) -> Unit,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    fun dismiss() {
        visible = false
    }

    LaunchedEffect(visible) {
        if (!visible) {
            kotlinx.coroutines.delay(250)
            onDismiss()
        }
    }

    BackHandler { dismiss() }

    val isDark = isAppInDarkTheme()
    val panelBg = if (isDark) DarkSurface else MaterialTheme.colorScheme.surface
    val menuState = remember(anime.id, confirmMode, language, strings.menuDeleteSnark, strings.menuAddFavoriteSnark) {
        AnimeMenuState(
            title = anime.title,
            imageUrl = getImgPath(anime.imageFileName) ?: "",
            statusText = when (confirmMode) {
                AnimeMenuConfirmMode.DELETE -> strings.menuDeleteSnark
                AnimeMenuConfirmMode.ADD_TO_FAVORITE -> strings.menuAddFavoriteSnark
                AnimeMenuConfirmMode.REMOVE_FROM_FAVORITE -> favoriteMenuStrings(language).snark
            },
            confirmMode = confirmMode
        )
    }

    Box(
        modifier = Modifier.fillMaxSize().zIndex(10f),
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
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { dismiss() }
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
            Card(
                modifier = Modifier
                    .padding(16.dp)
                    .navigationBarsPadding()
                    .fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                OverlayGlassPanel(
                    isDark = isDark,
                    panelBg = panelBg,
                    cornerRadius = 28.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    when (confirmMode) {
                                        AnimeMenuConfirmMode.DELETE -> OverlayThemeTokens.AccentNeonRed.copy(alpha = if (isDark) 0.14f else 0.1f)
                                        AnimeMenuConfirmMode.ADD_TO_FAVORITE -> OverlayThemeTokens.FavoriteConfirmGold.copy(alpha = if (isDark) 0.14f else 0.1f)
                                        AnimeMenuConfirmMode.REMOVE_FROM_FAVORITE -> OverlayThemeTokens.FavoriteCardBorder.copy(alpha = if (isDark) 0.14f else 0.1f)
                                    },
                                    Color.Transparent
                                ),
                                center = Offset(0f, 0f),
                                radius = 540f
                            )
                        )
                ) {
                    AnimeListActionMenu(
                        state = menuState,
                        strings = strings,
                        language = language,
                        onEvent = { event ->
                            onEvent(event)
                            dismiss()
                        }
                    )
                }
            }
        }
    }
}

// ==========================================
// VetroWorkspaceTopBar — заголовок (кнопки сортировки/уведомлений вынесены в HomeScreen поверх scrim)
// ==========================================
@Composable
fun VetroWorkspaceTopBar(
    strings: UiStrings,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Collection",
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Black,
                    fontFamily = SnProFamily,
                    letterSpacing = (-0.5).sp
                ),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
            Spacer(modifier = Modifier.width(88.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = strings.statsSubtitle,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = SnProFamily
            ),
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

// ==========================================
// EmptyStateView — gentle, spacious
// ==========================================
@Composable
fun EmptyStateView(
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(48.dp)
        ) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = SnProFamily
                ),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = SnProFamily
                    ),
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ==========================================
// CloudSyncPill — индикатор синхронизации из облака (верхняя пилюля)
// ==========================================
@Composable
fun CloudSyncPill(
    visible: Boolean,
    label: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        // Плавающая пилюля синка сверху (§11.4 barReveal-паттерн).
        enter = slideInVertically(
            initialOffsetY = { fullHeight -> -fullHeight },
            animationSpec = MotionTokens.barReveal(),
        ) + fadeIn(animationSpec = tween(280)),
        exit = slideOutVertically(
            targetOffsetY = { fullHeight -> -fullHeight },
            animationSpec = MotionTokens.standard(),
        ) + fadeOut(animationSpec = tween(220)),
        modifier = modifier,
    ) {
        val isDark = isAppInDarkTheme()
        val pillShape = RoundedCornerShape(999.dp)
        val surfaceColor = if (isDark) {
            Color(0xFF262626).copy(alpha = 0.92f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        }
        val borderColor = if (isDark) {
            BrandBlueSoft.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        }
        Surface(
            shape = pillShape,
            color = surfaceColor,
            tonalElevation = if (isDark) 0.dp else 4.dp,
            shadowElevation = if (isDark) 0.dp else 6.dp,
            border = BorderStroke(1.dp, borderColor),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = if (isDark) BrandBlueSoft else BrandBlue,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ==========================================
// CloudRestoreIndicator — legacy full-screen card (splash only via strings)
// ==========================================
@Composable
fun CloudRestoreIndicator(
    isRestoring: Boolean,
    strings: UiStrings,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isRestoring,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
        modifier = modifier
    ) {
        ElevatedCard(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(0.85f),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "SyncTransition")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "SyncRotation"
                )
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = "Restoring from cloud",
                    modifier = Modifier
                        .size(48.dp)
                        .rotate(rotation),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = strings.cloudRestoreTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = strings.cloudRestoreSubtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = SnProFamily
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

// ==========================================
// SwipeBackground
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeBackground(dismissState: SwipeToDismissBoxState) {
    // Используем dismissDirection вместо targetValue — показывает реальное направление свайпа.
    val direction = dismissState.dismissDirection

    val color by animateColorAsState(
        targetValue = when (direction) {
            // Тот же токен, что у рамки и чипса избранного: раньше здесь был литерал, и цвет
            // заливки разъехался с рамкой карточки.
            SwipeToDismissBoxValue.StartToEnd -> OverlayThemeTokens.FavoriteGold.copy(alpha = 0.85f)
            SwipeToDismissBoxValue.EndToStart -> Color(0xFFFF1744).copy(alpha = 0.85f)
            SwipeToDismissBoxValue.Settled -> Color.Transparent
        },
        label = "swipeBg"
    )

    val icon = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Icons.Rounded.Star
        SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
        SwipeToDismissBoxValue.Settled -> Icons.Rounded.Star
    }

    val alignment = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        SwipeToDismissBoxValue.Settled -> Alignment.Center
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment
    ) {
        if (direction != SwipeToDismissBoxValue.Settled) {
            val scale by animateFloatAsState(
                targetValue = if (dismissState.progress > 0.05f) 1f else 0.5f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "iconScale"
            )

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (direction == SwipeToDismissBoxValue.Settled) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                } else {
                    Color.White
                },
                modifier = Modifier
                    .size(26.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
            )
        }
    }
}

// StatsOverlay вынесён в StatsOverlay.kt (пакет com.example.myapplication.ui.home).
