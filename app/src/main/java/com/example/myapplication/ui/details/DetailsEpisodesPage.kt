package com.example.myapplication.ui.details

import com.example.myapplication.ui.shared.theme.IosScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.localplayer.model.LocalEpisode
import com.example.myapplication.localplayer.ui.LocalPlayerActivity
import com.example.myapplication.localplayer.ui.LocalPlayerUiState
import com.example.myapplication.localplayer.ui.LocalPlayerViewModel
import com.example.myapplication.localplayer.ui.RejectReason
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.SnProFamily
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Страница «Серии» внутри DetailsScreen (вторая вкладка пейджера): локальная библиотека
 * скачанных эпизодов этого тайтла. Логика — тот же [LocalPlayerViewModel], что и у
 * изолированной фичи localplayer (выбор SAF-папки, скан, разбор имён). Тап по серии
 * запускает [LocalPlayerActivity] сразу в режиме воспроизведения (startIndex).
 */
@Composable
fun DetailsEpisodesPage(
    animeId: String,
    animeTitle: String,
    animeTitleRu: String? = null,
    animeTitleEn: String? = null,
    malId: Int?,
    anilistId: Int?,
    language: AppLanguage,
    isDark: Boolean,
    seasons: List<com.example.myapplication.domain.seasons.SeasonInfo> = emptyList(),
    fallbackEpisodes: Int = 0,
    viewModel: LocalPlayerViewModel = koinViewModel(key = "localplayer_$animeId") {
        parametersOf(animeId, animeTitle, malId, anilistId)
    },
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ru = language == AppLanguage.RU

    var showDownloadSheet by remember { mutableStateOf(false) }
    var watchEpisode by remember { mutableStateOf<Int?>(null) }

    val animeModel = remember(animeId, animeTitle, animeTitleRu, animeTitleEn, fallbackEpisodes) {
        com.example.myapplication.data.models.Anime(
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
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.onFolderPicked(uri) }

    fun play(index: Int) {
        context.startActivity(
            LocalPlayerActivity.intent(
                context = context,
                animeId = animeId,
                animeTitle = animeTitle,
                malId = malId,
                anilistId = anilistId,
                startIndex = index,
            ),
        )
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            LocalPlayerUiState.Loading, LocalPlayerUiState.Scanning -> EpisodesProgress(
                label = if (s == LocalPlayerUiState.Scanning) {
                    if (ru) "Разбираем эпизоды…" else "Parsing episodes…"
                } else null,
            )

            LocalPlayerUiState.NeedSource -> EpisodesInfoState(
                title = if (ru) "Добавьте источник" else "Add a source",
                subtitle = if (ru) {
                    "Выберите папку со скачанными сериями этого тайтла (например Download/Jujutsu Kaisen). " +
                        "Общие папки вроде Download или Documents выбрать нельзя."
                } else {
                    "Pick the folder that holds this title's downloaded episodes (e.g. Download/Jujutsu Kaisen). " +
                        "Generic folders like Download or Documents can't be selected."
                },
                actionLabel = if (ru) "Добавить папку" else "Add folder",
                onAction = { folderPicker.launch(null) },
                secondaryLabel = if (ru) "Скачать" else "Download",
                secondaryIcon = Icons.Rounded.Download,
                onSecondary = { showDownloadSheet = true },
                tertiaryLabel = if (ru) "Смотреть онлайн" else "Watch online",
                tertiaryIcon = Icons.Rounded.PlayArrow,
                onTertiary = { watchEpisode = 1 },
            )

            is LocalPlayerUiState.Rejected -> {
                val (title, subtitle) = when (s.reason) {
                    RejectReason.TOO_GENERAL ->
                        (if (ru) "Слишком общая папка" else "Folder is too generic") to
                            if (ru) {
                                "Выберите конкретную папку тайтла внутри, а не Download/Documents/DCIM целиком."
                            } else {
                                "Pick a specific title folder inside, not the whole Download/Documents/DCIM."
                            }

                    RejectReason.NO_VIDEOS ->
                        (if (ru) "Видео не найдено" else "No videos found") to
                            if (ru) {
                                "В этой папке нет видеофайлов (mp4, mkv, avi…). Выберите другую."
                            } else {
                                "This folder has no video files (mp4, mkv, avi…). Try another one."
                            }
                }
                EpisodesInfoState(
                    title = title,
                    subtitle = subtitle,
                    actionLabel = if (ru) "Выбрать другую" else "Choose another",
                    onAction = { folderPicker.launch(null) },
                )
            }

            is LocalPlayerUiState.Library -> {
                val episodes = s.source.episodes
                LazyColumn(
                    flingBehavior = IosScroll.flingBehavior(),
                    modifier = Modifier.fillMaxSize().statusBarsPadding(),
                    contentPadding = PaddingValues(
                        top = 72.dp,
                        bottom = 150.dp,
                        start = 20.dp,
                        end = 20.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "episodes_header") {
                        EpisodesPageHeader(
                            animeTitle = animeTitle,
                            folderLabel = s.source.folderLabel,
                            episodeCount = episodes.size,
                            usedAi = s.usedAi,
                            episodes = episodes,
                            ru = ru,
                            isDark = isDark,
                            onPlayFirst = { play(0) },
                            onDownload = { showDownloadSheet = true },
                            onWatchOnline = { watchEpisode = 1 },
                            onChangeFolder = { folderPicker.launch(null) },
                        )
                    }
                    itemsIndexed(episodes, key = { _, ep -> ep.documentUri }) { index, ep ->
                        EpisodeCard(
                            episode = ep,
                            isDark = isDark,
                            onClick = { play(index) },
                        )
                    }
                }
            }
        }

        if (showDownloadSheet) {
            DetailsDownloadSheet(
                animeId = animeId,
                animeTitle = animeTitle,
                seasons = seasons,
                fallbackEpisodes = fallbackEpisodes,
                language = language,
                onDismiss = { showDownloadSheet = false },
            )
        }
        watchEpisode?.let { ep ->
            com.example.myapplication.media.ui.StreamWatchSheet(
                anime = animeModel,
                episodeNumber = ep,
                ru = ru,
                onDismiss = { watchEpisode = null },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodesPageHeader(
    animeTitle: String,
    folderLabel: String,
    episodeCount: Int,
    usedAi: Boolean,
    episodes: List<LocalEpisode>,
    ru: Boolean,
    isDark: Boolean,
    onPlayFirst: () -> Unit,
    onDownload: () -> Unit,
    onWatchOnline: () -> Unit,
    onChangeFolder: () -> Unit,
) {
    val releaseTags = remember(episodes) { inferReleaseTags(episodes) }
    val headerSubtitle = remember(animeTitle, folderLabel) {
        when {
            folderLabel.isBlank() -> animeTitle
            folderLabel.equals(animeTitle, ignoreCase = true) -> animeTitle
            else -> "$animeTitle — $folderLabel"
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(
            text = if (ru) "Серии" else "Episodes",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = headerSubtitle,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (episodeCount > 0) {
                EpisodesMetaChip(
                    text = if (ru) "$episodeCount эп." else "$episodeCount ep.",
                    icon = Icons.Rounded.PlayArrow,
                    accent = true,
                    isDark = isDark,
                )
            }
            releaseTags.qualityLabel?.let { quality ->
                EpisodesMetaChip(
                    text = quality,
                    icon = Icons.Rounded.Hd,
                    accent = false,
                    isDark = isDark,
                )
            }
            releaseTags.codec?.let { codec ->
                EpisodesMetaChip(
                    text = codec,
                    accent = false,
                    isDark = isDark,
                )
            }
            if (usedAi) {
                EpisodesMetaChip(
                    text = if (ru) "ИИ-разметка" else "AI-sorted",
                    accent = false,
                    isDark = isDark,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (episodeCount > 0) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onPlayFirst() }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = if (ru) "Начать просмотр" else "Start watching",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (isDark) Color.White.copy(alpha = 0.10f)
                        else Color.Black.copy(alpha = 0.06f),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDownload() }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (ru) "Скачать" else "Download",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (isDark) Color.White.copy(alpha = 0.10f)
                        else Color.Black.copy(alpha = 0.06f),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onWatchOnline() }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Hd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (ru) "Онлайн" else "Online",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (ru) "Сменить папку" else "Change folder",
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChangeFolder() },
        )
    }
}

@Composable
private fun EpisodesMetaChip(
    text: String,
    icon: ImageVector? = null,
    accent: Boolean,
    isDark: Boolean,
) {
    val bg = if (accent) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.20f else 0.12f)
    } else {
        if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.05f)
    }
    val border = if (accent) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.38f else 0.28f)
    } else {
        if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    }
    val fg = if (accent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, border, CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = fg,
        )
    }
}

private data class ReleaseTags(
    val source: String? = null,
    val resolution: String? = null,
    val codec: String? = null,
) {
    val qualityLabel: String? = when {
        source != null && resolution != null -> "$source $resolution"
        source != null -> source
        resolution != null -> resolution
        else -> null
    }
}

private fun inferReleaseTags(episodes: List<LocalEpisode>): ReleaseTags {
    if (episodes.isEmpty()) return ReleaseTags()

    fun mostCommon(
        pattern: Regex,
        transform: (MatchResult) -> String = { it.value },
    ): String? = episodes
        .mapNotNull { ep -> pattern.find(ep.originalName)?.let(transform) }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key

    val resolution = mostCommon(Regex("""(?i)\b(2160p|4K|1080p|720p|480p)\b""")) {
        when (it.value.uppercase()) {
            "4K" -> "2160p"
            else -> it.value.lowercase()
        }
    }
    val source = mostCommon(Regex("""(?i)\b(WEBRip|WEB-DL|WEBDL|BDRip|BluRay|HDTV|BRRip|DVDRip)\b""")) {
        when (it.value.uppercase().replace("-", "")) {
            "WEBDL" -> "WEB-DL"
            "WEBRIP" -> "WEBRip"
            "BDRIP", "BRRIP" -> "BDRip"
            "BLURAY" -> "BluRay"
            "HDTV" -> "HDTV"
            "DVDRIP" -> "DVDRip"
            else -> it.value
        }
    }
    val codec = mostCommon(Regex("""(?i)\b(HEVC|x265|x264|H\.?264|AV1|VP9)\b""")) {
        when (it.value.lowercase().replace(".", "")) {
            "x265", "hevc" -> "HEVC"
            "x264", "h264" -> "H.264"
            "av1" -> "AV1"
            "vp9" -> "VP9"
            else -> it.value.uppercase()
        }
    }
    return ReleaseTags(source = source, resolution = resolution, codec = codec)
}

@Composable
private fun EpisodeCard(
    episode: LocalEpisode,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isDark) Color.White.copy(alpha = 0.06f)
                else Color.Black.copy(alpha = 0.04f),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = episode.episodeNumber?.toString() ?: "•",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = episode.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = episode.originalName,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = SnProFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun EpisodesProgress(label: String?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.5.dp)
        if (label != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EpisodesInfoState(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    secondaryIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onSecondary: (() -> Unit)? = null,
    tertiaryLabel: String? = null,
    tertiaryIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onTertiary: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAction,
                    )
                    .padding(horizontal = 26.dp, vertical = 14.dp),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                )
            }
            if (secondaryLabel != null && onSecondary != null) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onSecondary,
                        )
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (secondaryIcon != null) {
                        Icon(
                            secondaryIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = secondaryLabel,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (tertiaryLabel != null && onTertiary != null) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onTertiary,
                        )
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tertiaryIcon != null) {
                        Icon(
                            tertiaryIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = tertiaryLabel,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
