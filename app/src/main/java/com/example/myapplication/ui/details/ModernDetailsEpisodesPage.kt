package com.example.myapplication.ui.details

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.myapplication.data.models.Anime
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.domain.seasons.displayLabel
import com.example.myapplication.localplayer.ui.DownloadedPlayerActivity
import com.example.myapplication.media.progress.EpisodePlaybackProgress
import com.example.myapplication.media.ui.StreamPlayerActivity
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.loading.BubbleClusterLoader
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.utils.performHaptic
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Season-first episode menu. Season cards are collapsed on entry; tapping the card expands it,
 * while the trailing action is reserved for download/delete.
 */
@Composable
fun ModernDetailsEpisodesPage(
    animeId: String,
    animeTitle: String,
    animeTitleRu: String? = null,
    animeTitleEn: String? = null,
    malId: Int?,
    anilistId: Int?,
    language: AppLanguage,
    isDark: Boolean,
    seasons: List<SeasonInfo> = emptyList(),
    fallbackEpisodes: Int = 0,
    posterPath: String? = null,
    viewModel: EpisodeMenuViewModel = koinViewModel(key = "episode_menu_$animeId") {
        parametersOf(
            Anime(
                id = animeId,
                title = animeTitle,
                titleRu = animeTitleRu,
                titleEn = animeTitleEn,
                episodes = fallbackEpisodes.coerceAtLeast(1),
                rating = 0f,
                imageFileName = null,
                orderIndex = 0,
                dateAdded = 0L,
                malId = malId,
                anilistId = anilistId,
            )
        )
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ru = language == AppLanguage.RU
    var qualityAnchor by remember { mutableStateOf<Rect?>(null) }
    val normalizedSeasons = remember(seasons, fallbackEpisodes, anilistId, malId, animeTitle) {
        seasons.takeIf { it.isNotEmpty() }
            ?: listOf(
                SeasonInfo(
                    seasonNumber = 1,
                    episodes = fallbackEpisodes.coerceAtLeast(1),
                    anilistId = anilistId,
                    malId = malId,
                    source = "local",
                    title = animeTitle,
                )
            )
    }

    LaunchedEffect(normalizedSeasons, fallbackEpisodes) {
        viewModel.setSeasons(normalizedSeasons, fallbackEpisodes)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is EpisodeMenuEvent.Play -> {
                    context.startActivity(
                        StreamPlayerActivity.intent(
                            context = context,
                            video = event.video,
                            videos = event.candidates,
                            animeId = animeId,
                            animeTitle = event.animeTitle,
                            season = event.seasonNumber,
                            episode = event.episodeNumber,
                            animeTitleEn = event.animeTitleEn,
                            animeTitleRu = event.animeTitleRu,
                            malId = event.malId,
                            anilistId = event.anilistId,
                            seasonInfo = event.seasonInfo,
                            playbackIdentity = event.playbackIdentity,
                        )
                    )
                }

                is EpisodeMenuEvent.PlayLocal -> {
                    context.startActivity(
                        DownloadedPlayerActivity.intent(
                            context = context,
                            animeId = animeId,
                            animeTitle = event.animeTitle,
                            episodes = event.episodes,
                            startIndex = event.startIndex,
                            malId = event.malId,
                            anilistId = event.anilistId,
                        )
                    )
                }

                is EpisodeMenuEvent.Message -> {
                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Общий с настройками и меню глав градиент — значение живёт в теме, а не копией на экран.
    val episodeBackground = remember(isDark) { IosDesign.screenGradient(isDark) }

    Box(Modifier.fillMaxSize().background(episodeBackground)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(
                top = 76.dp,
                bottom = 148.dp,
                start = 18.dp,
                end = 18.dp,
            ),
        ) {
            item(key = "season_menu_header") {
                SeasonMenuHeader(
                    animeTitle = animeTitle,
                    seasonCount = normalizedSeasons.size,
                    episodeCount = normalizedSeasons.sumOf { it.episodes },
                    selectedQuality = state.selectedQuality,
                    qualityChipVersion = state.qualityChipVersion,
                    resolvingQuality = state.resolvingQuality,
                    ru = ru,
                    onQualityClick = viewModel::openQualityPicker,
                    onQualityAnchorChanged = { qualityAnchor = it },
                )
                Spacer(Modifier.height(12.dp))
            }
            // Раскрытый сезон разложен на ОТДЕЛЬНЫЕ элементы списка, а не лежит одним блоком
            // внутри item. Иначе LazyColumn на каждом кадре раскрытия перемеряет весь сезон
            // целиком (у длинных сезонов это десятки строк с обложками) — отсюда рывки.
            // Так же уходит вложенная AnimatedVisibility на каждую серию: N одновременных
            // анимаций внутри контейнера, который сам меняет высоту.
            normalizedSeasons.forEach { season ->
                val expanded = season.seasonNumber in state.expandedSeasons
                val episodeCount = season.episodes.coerceAtLeast(0)

                item(key = "season_head_${season.seasonNumber}") {
                    SeasonHeaderCard(
                        season = season,
                        expanded = expanded,
                        posterPath = posterPath,
                        seasonCover = state.seasonCovers[season.seasonNumber],
                        state = state,
                        ru = ru,
                        isDark = isDark,
                        onToggle = { viewModel.toggleSeason(season.seasonNumber) },
                        onSeasonAction = { viewModel.seasonAction(season) },
                        modifier = Modifier.animateItem(),
                    )
                }

                if (expanded) {
                    items(
                        count = episodeCount,
                        key = { index -> "ep_${season.seasonNumber}_${index + 1}" },
                    ) { index ->
                        val episode = index + 1
                        val key = EpisodeKey(season.seasonNumber, episode)
                        SeasonEpisodeSlot(
                            isLast = index == episodeCount - 1,
                            isDark = isDark,
                            modifier = Modifier.animateItem(),
                        ) {
                            EpisodeRow(
                                episodeNumber = episode,
                                visual = state.visuals[key],
                                fallbackImage = state.seasonCovers[season.seasonNumber] ?: posterPath,
                                action = state.actions[key] ?: EpisodeActionState.Available,
                                progress = state.playback[key],
                                streaming = state.streaming == key,
                                ru = ru,
                                isDark = isDark,
                                onClick = { viewModel.play(season, episode) },
                                onAction = { viewModel.episodeAction(season, episode) },
                            )
                        }
                    }
                }

                item(key = "season_gap_${season.seasonNumber}") {
                    Spacer(Modifier.height(12.dp))
                }
            }

            item(key = "find_more_seasons") {
                FindMoreSeasonsButton(
                    loading = state.discoveringSeasons,
                    ru = ru,
                    onClick = { viewModel.discoverMoreSeasons(ru) },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        state.qualityPicker?.let { picker ->
            if (picker.sources.isNotEmpty()) {
                // Movies and series pick a source first; anime keeps the anchored quality menu.
                MovieSeriesSourceSheet(
                    sources = picker.sources,
                    ru = ru,
                    onSelect = viewModel::selectSourceStream,
                    onDismiss = viewModel::dismissQualityPicker,
                )
            } else {
                qualityAnchor?.let { anchor ->
                    EpisodeQualityPopover(
                        state = picker,
                        selectedQuality = state.selectedQuality,
                        anchor = anchor,
                        ru = ru,
                        isDark = isDark,
                        onSelect = viewModel::selectQuality,
                        onDismiss = viewModel::dismissQualityPicker,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonMenuHeader(
    animeTitle: String,
    seasonCount: Int,
    episodeCount: Int,
    selectedQuality: Int?,
    qualityChipVersion: Int,
    resolvingQuality: Boolean,
    ru: Boolean,
    onQualityClick: () -> Unit,
    onQualityAnchorChanged: (Rect) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            text = animeTitle,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(11.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeaderMetaChip(
                text = if (ru) {
                    "$seasonCount ${pluralRuSeason(seasonCount)}"
                } else {
                    "$seasonCount ${if (seasonCount == 1) "season" else "seasons"}"
                },
                icon = Icons.Rounded.VideoLibrary,
                accent = false,
            )
            HeaderMetaChip(
                text = if (ru) "$episodeCount эп." else "$episodeCount ep.",
                icon = Icons.Rounded.PlayCircle,
                accent = true,
            )
            AnimatedContent(
                targetState = qualityChipVersion to selectedQuality,
                transitionSpec = {
                    (slideInVertically(initialOffsetY = { it * 4 }) +
                        scaleIn(initialScale = 0.68f) +
                        fadeIn()) togetherWith fadeOut() using SizeTransform(clip = false)
                },
                label = "qualityChipMorph",
            ) { (_, quality) ->
                HeaderQualityChip(
                    label = quality?.let { "${it}p" }
                        ?: if (ru) "Качество" else "Quality",
                    loading = resolvingQuality,
                    onClick = onQualityClick,
                    onAnchorChanged = onQualityAnchorChanged,
                )
            }
        }
    }
}

@Composable
private fun HeaderMetaChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Boolean,
) {
    val background = if (accent) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    }
    val foreground = if (accent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
            ),
            color = foreground,
        )
    }
}

@Composable
private fun HeaderQualityChip(
    label: String,
    loading: Boolean,
    onClick: () -> Unit,
    onAnchorChanged: (Rect) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
            .onGloballyPositioned { onAnchorChanged(it.boundsInWindow()) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !loading,
                onClick = onClick,
            )
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Hd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(5.dp))
        Icon(
            imageVector = Icons.Rounded.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.size(13.dp),
        )
    }
}

/** Шапка сезона. Раскрытые серии живут отдельными элементами списка — см. [SeasonEpisodeSlot]. */
@Composable
private fun SeasonHeaderCard(
    season: SeasonInfo,
    expanded: Boolean,
    posterPath: String?,
    seasonCover: String?,
    state: EpisodeMenuUiState,
    ru: Boolean,
    isDark: Boolean,
    onToggle: () -> Unit,
    onSeasonAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Раскрытый сезон: низ карточки срезан — её продолжают карточки серий следующими элементами.
    val cardShape = if (expanded) {
        RoundedCornerShape(topStart = SEASON_CARD_RADIUS, topEnd = SEASON_CARD_RADIUS)
    } else {
        RoundedCornerShape(SEASON_CARD_RADIUS)
    }
    val action = aggregateSeasonAction(season, state.actions)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MotionTokens.standard(),
        label = "seasonChevron",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(seasonCardColor(isDark)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                )
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                model = seasonCover ?: posterPath,
                modifier = Modifier.size(74.dp),
                cornerRadius = 16.dp,
                isDark = isDark,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = season.displayLabel(ru),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (season.ongoing) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = seasonEpisodesLabel(season, ru),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                season.title?.takeIf { it.isNotBlank() }?.let { title ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = SnProFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = chevronRotation },
            )
            Spacer(Modifier.width(6.dp))
            DownloadAction(
                state = action,
                contentDescription = if (ru) "Скачать сезон" else "Download season",
                onClick = onSeasonAction,
            )
        }

    }
}

private val SEASON_CARD_RADIUS = 22.dp

private fun seasonCardColor(isDark: Boolean): Color =
    if (isDark) Color.White.copy(alpha = 0.075f) else Color.Black.copy(alpha = 0.045f)

/**
 * Одна серия как элемент списка: продолжает карточку сезона (те же поля и заливка), последняя
 * закругляет низ. Появление/исчезновение отдаём [androidx.compose.foundation.lazy.LazyItemScope.animateItem]
 * — работают только видимые строки, без пересчёта всего сезона.
 */
@Composable
private fun SeasonEpisodeSlot(
    isLast: Boolean,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = if (isLast) {
        RoundedCornerShape(bottomStart = SEASON_CARD_RADIUS, bottomEnd = SEASON_CARD_RADIUS)
    } else {
        RectangleShape
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(seasonCardColor(isDark))
            .padding(start = 10.dp, end = 10.dp, bottom = if (isLast) 10.dp else 8.dp),
    ) {
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeRow(
    episodeNumber: Int,
    visual: EpisodeVisual?,
    fallbackImage: String?,
    action: EpisodeActionState,
    progress: EpisodePlaybackProgress?,
    streaming: Boolean,
    ru: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    onAction: () -> Unit,
) {
    val watched = progress?.watched == true
    val hasProgress = (progress?.positionMs ?: 0L) > 0L
    val statusColor = when {
        watched -> Color(0xFF20C997)
        hasProgress -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when {
        watched -> if (ru) "Просмотрено" else "Watched"
        hasProgress -> if (ru) "В процессе" else "Watching"
        else -> if (ru) "Не начато" else "Unwatched"
    }
    val rowShape = RoundedCornerShape(16.dp)
    val watchedColor = Color(0xFF20C997)
    val rowColor = when {
        watched && isDark -> watchedColor.copy(alpha = 0.10f)
        watched -> watchedColor.copy(alpha = 0.07f)
        isDark -> Color.Black.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.72f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (watched) 0.88f else 1f)
            .clip(rowShape)
            .background(rowColor)
            .border(
                width = if (watched) 1.dp else 0.dp,
                color = if (watched) watchedColor.copy(alpha = 0.48f) else Color.Transparent,
                shape = rowShape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !streaming,
                onClick = onClick,
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Artwork(
                model = visual?.imageUrl ?: fallbackImage,
                modifier = Modifier.width(92.dp).aspectRatio(16f / 10f),
                cornerRadius = 12.dp,
                isDark = isDark,
            )
            if (streaming) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.48f)),
                    contentAlignment = Alignment.Center,
                ) {
                    BubbleClusterLoader(
                        modifier = Modifier.size(30.dp),
                        color = Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (ru) "Серия $episodeNumber" else "Episode $episodeNumber",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                if (watched) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = if (ru) "Просмотрено" else "Watched",
                        tint = watchedColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text = visual?.title?.takeIf { it.isNotBlank() }
                    ?: if (ru) "Потоковое воспроизведение" else "Stream online",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = SnProFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EpisodeMetaChip(
                    text = "E${episodeNumber.toString().padStart(3, '0')}",
                    accent = MaterialTheme.colorScheme.primary,
                )
                if ((progress?.durationMs ?: 0L) > 0L) {
                    EpisodeMetaChip(
                        text = "${formatEpisodeTime(progress?.positionMs ?: 0L)} / " +
                            formatEpisodeTime(progress?.durationMs ?: 0L),
                        accent = statusColor,
                    )
                }
                visual?.site?.takeIf { it.isNotBlank() }?.let { site ->
                    EpisodeMetaChip(text = site, accent = MaterialTheme.colorScheme.secondary)
                }
                EpisodeMetaChip(text = statusText, accent = statusColor)
            }
            Spacer(Modifier.height(5.dp))
            LinearProgressIndicator(
                progress = { if (watched) 1f else progress?.fraction ?: 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape),
                color = if (watched) watchedColor else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                drawStopIndicator = {},
            )
        }
        Spacer(Modifier.width(8.dp))
        DownloadAction(
            state = action,
            contentDescription = when (action) {
                is EpisodeActionState.Downloaded, is EpisodeActionState.Completed ->
                    if (ru) "Удалить локальную копию" else "Delete local copy"
                else -> if (ru) "Скачать серию" else "Download episode"
            },
            onClick = onAction,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeMetaChip(
    text: String,
    accent: Color,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.13f))
            .border(0.5.dp, accent.copy(alpha = 0.24f), CircleShape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 9.sp,
            ),
            color = accent,
            maxLines = 1,
        )
    }
}

@Composable
private fun Artwork(
    model: String?,
    modifier: Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp,
    isDark: Boolean,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.28f else 0.18f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = if (isDark) 0.18f else 0.10f),
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(24.dp),
        )
        if (!model.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(model)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun DownloadAction(
    state: EpisodeActionState,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val active = state !is EpisodeActionState.Resolving && state !is EpisodeActionState.Downloading
    val tint = when (state) {
        is EpisodeActionState.Completed -> Color(0xFF20C997)
        is EpisodeActionState.Downloaded -> MaterialTheme.colorScheme.error
        is EpisodeActionState.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.11f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = active,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            EpisodeActionState.Resolving, is EpisodeActionState.Downloading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.2.dp,
                )
            }

            is EpisodeActionState.Completed -> Icon(
                Icons.Rounded.Check,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )

            is EpisodeActionState.Downloaded -> Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )

            EpisodeActionState.Available, is EpisodeActionState.Failed -> Icon(
                Icons.Rounded.Download,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

private fun aggregateSeasonAction(
    season: SeasonInfo,
    actions: Map<EpisodeKey, EpisodeActionState>,
): EpisodeActionState {
    val values = (1..season.episodes.coerceAtLeast(0)).map {
        actions[EpisodeKey(season.seasonNumber, it)] ?: EpisodeActionState.Available
    }
    if (values.any { it is EpisodeActionState.Resolving }) return EpisodeActionState.Resolving
    values.filterIsInstance<EpisodeActionState.Downloading>().firstOrNull()?.let { return it }
    if (values.isNotEmpty() && values.all {
            it is EpisodeActionState.Completed || it is EpisodeActionState.Downloaded
        }
    ) {
        values.filterIsInstance<EpisodeActionState.Completed>().firstOrNull()?.let { return it }
        return EpisodeActionState.Downloaded("")
    }
    values.filterIsInstance<EpisodeActionState.Failed>().firstOrNull()?.let { return it }
    return EpisodeActionState.Available
}

private fun formatEpisodeTime(valueMs: Long): String {
    val totalSeconds = (valueMs.coerceAtLeast(0L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * Подпись сезона. Выходящий сезон показывает «сколько доступно из анонсированных» — список ниже
 * содержит только вышедшие серии, и без итога было бы непонятно, ждать ли ещё.
 */
private fun seasonEpisodesLabel(season: SeasonInfo, ru: Boolean): String {
    val announced = season.totalEpisodes?.takeIf { season.ongoing && it > season.episodes }
    val count = if (ru) {
        "${season.episodes} ${pluralRuEpisode(season.episodes)}"
    } else {
        "${season.episodes} ${if (season.episodes == 1) "episode" else "episodes"}"
    }
    return if (announced == null) count else {
        if (ru) "${season.episodes} из $announced ${pluralRuEpisode(announced)}"
        else "${season.episodes} of $announced episodes"
    }
}

private fun pluralRuSeason(value: Int): String {
    val mod100 = value % 100
    val mod10 = value % 10
    return when {
        mod100 in 11..14 -> "сезонов"
        mod10 == 1 -> "сезон"
        mod10 in 2..4 -> "сезона"
        else -> "сезонов"
    }
}

private fun pluralRuEpisode(value: Int): String {
    val mod100 = value % 100
    val mod10 = value % 10
    return when {
        mod100 in 11..14 -> "серий"
        mod10 == 1 -> "серия"
        mod10 in 2..4 -> "серии"
        else -> "серий"
    }
}

/**
 * «Найти ещё» — добрать сезоны у источников просмотра там, где каталожные API их не знают
 * (см. [com.example.myapplication.domain.seasons.StreamingSeasonDiscovery]).
 *
 * Оранжевая капсула — тот же язык, что у чипов сезонов на вкладке «Детали»
 * ([com.example.myapplication.ui.details.SeasonChip]): это действие, ведущее к новым сезонам, а не
 * тег. Кнопка живёт в конце списка, потому что «показанных сезонов не хватило» видно именно там.
 */
@Composable
private fun FindMoreSeasonsButton(
    loading: Boolean,
    ru: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(
                    enabled = !loading,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        performHaptic(view, "light")
                        onClick()
                    },
                )
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = when {
                    loading && ru -> "Ищем…"
                    loading -> "Searching…"
                    ru -> "Найти ещё"
                    else -> "Find more"
                },
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                ),
                color = Color.White,
            )
        }
    }
}
