package com.example.myapplication.ui.details

import com.example.myapplication.domain.seasons.displayLabel
import com.example.myapplication.media.download.DownloadQuality
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.download.DownloadStatus
import com.example.myapplication.download.EpisodeProgress
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.SnProFamily
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Мастер скачивания серий (ModalBottomSheet, 4 шага с анимированным переключением контента):
 *  1. выбор сезона, 2. выбор качества, 3. выбор серий, 4. экран прогресса активной загрузки.
 *
 * Стейт и связка с File-based IPC мостом — в [DownloadWizardViewModel] (MVVM). VM живёт на
 * уровне страницы «Серии» (ключ по animeId), поэтому опрос прогресса продолжается, даже если
 * шторку скрыли; сама загрузка идёт во внешнем воркере независимо.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsDownloadSheet(
    animeId: String,
    animeTitle: String,
    seasons: List<SeasonInfo>,
    fallbackEpisodes: Int,
    language: AppLanguage,
    onDismiss: () -> Unit,
    viewModel: DownloadWizardViewModel = koinViewModel(key = "download_$animeId") {
        parametersOf(language)
    },
) {
    val ru = language == AppLanguage.RU
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Реактивно подаём сезоны из фонового резолвера (VM обновит их, только пока на шаге выбора).
    LaunchedEffect(seasons, fallbackEpisodes) {
        viewModel.setSeasons(seasons, fallbackEpisodes)
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (state.finished) viewModel.reset()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            DownloadSheetHeader(step = state.step, finished = state.finished, ru = ru)
            Spacer(Modifier.height(16.dp))

            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(tween(280)) { dir * it } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(280)) { -dir * it } + fadeOut(tween(200)))
                },
                label = "downloadStep",
            ) { current ->
                when (current) {
                    DownloadStep.SEASON -> SeasonStep(
                        seasons = state.seasons,
                        selectedIndex = state.selectedSeasonIndex,
                        ru = ru,
                        onSelect = viewModel::selectSeason,
                    )

                    DownloadStep.QUALITY -> QualityStep(
                        selected = state.selectedQuality,
                        onSelect = viewModel::selectQuality,
                    )

                    DownloadStep.EPISODES -> EpisodesStep(
                        episodeCount = state.episodeCount,
                        selected = state.selectedEpisodes,
                        ru = ru,
                        onToggle = viewModel::toggleEpisode,
                        onSelectAll = viewModel::setAllEpisodes,
                    )

                    DownloadStep.PROGRESS -> ProgressStep(state = state, ru = ru)
                }
            }

            Spacer(Modifier.height(20.dp))
            DownloadSheetButtons(
                state = state,
                ru = ru,
                onBack = viewModel::back,
                onNext = {
                    when (state.step) {
                        DownloadStep.EPISODES -> viewModel.onStartDownload(animeTitle, animeId)
                        DownloadStep.PROGRESS -> {
                            if (state.finished) viewModel.reset()
                            onDismiss()
                        }
                        else -> viewModel.next()
                    }
                },
            )
        }
    }
}

@Composable
private fun DownloadSheetHeader(step: DownloadStep, finished: Boolean, ru: Boolean) {
    val title = when (step) {
        DownloadStep.SEASON -> if (ru) "Выберите сезон" else "Choose a season"
        DownloadStep.QUALITY -> if (ru) "Выберите качество" else "Choose quality"
        DownloadStep.EPISODES -> if (ru) "Выберите серии" else "Choose episodes"
        DownloadStep.PROGRESS -> if (finished) {
            if (ru) "Готово" else "Done"
        } else {
            if (ru) "Скачивание" else "Downloading"
        }
    }
    val caption = if (step == DownloadStep.PROGRESS) {
        if (ru) "Загрузка идёт в фоне" else "Runs in the background"
    } else {
        val stepNo = step.ordinal + 1
        if (ru) "Шаг $stepNo из 3" else "Step $stepNo of 3"
    }
    Column {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = SnProFamily),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ==========================================
// Шаг 1: сезон
// ==========================================

@Composable
private fun SeasonStep(
    seasons: List<SeasonInfo>,
    selectedIndex: Int,
    ru: Boolean,
    onSelect: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.heightIn(max = 340.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(seasons) { index, season ->
            SingleChoiceRow(
                title = season.displayLabel(ru),
                subtitle = if (ru) "${season.episodes} эп." else "${season.episodes} ep.",
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

// ==========================================
// Шаг 2: качество
// ==========================================

@Composable
private fun QualityStep(
    selected: DownloadQuality?,
    onSelect: (DownloadQuality) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DownloadQuality.entries.forEach { quality ->
            SingleChoiceRow(
                title = quality.label,
                subtitle = null,
                selected = quality == selected,
                onClick = { onSelect(quality) },
            )
        }
    }
}

// ==========================================
// Шаг 3: серии
// ==========================================

@Composable
private fun EpisodesStep(
    episodeCount: Int,
    selected: Set<Int>,
    ru: Boolean,
    onToggle: (Int) -> Unit,
    onSelectAll: (Boolean) -> Unit,
) {
    val allEpisodes = remember(episodeCount) { (1..episodeCount).toList() }
    val allSelected = episodeCount > 0 && selected.size == episodeCount

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onSelectAll(!allSelected) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = allSelected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (ru) "Выбрать всё" else "Select all",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (selected.isNotEmpty()) {
                Text(
                    text = "${selected.size}/$episodeCount",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = SnProFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier.heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(allEpisodes) { ep ->
                val checked = ep in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onToggle(ep) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (ru) "Серия $ep" else "Episode $ep",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = SnProFamily),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// ==========================================
// Шаг 4: прогресс активной загрузки
// ==========================================

@Composable
private fun ProgressStep(state: DownloadWizardState, ru: Boolean) {
    val progress = state.progress
    val pct = progress?.progressPct ?: 0
    val isDark = MaterialTheme.colorScheme.surface.luminanceIsDark()

    Column {
        // Главный линейный индикатор.
        LinearProgressIndicator(
            progress = { (pct / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f),
        )
        Spacer(Modifier.height(10.dp))

        // Саммари.
        val summary = progress?.summary
        val summaryText = when {
            state.submitting || progress == null ->
                if (ru) "Отправляем задачу…" else "Submitting task…"
            summary != null ->
                if (ru) "Завершено: ${summary.done} из ${summary.total}"
                else "Completed: ${summary.done} of ${summary.total}"
            else -> "$pct%"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "$pct%",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Ошибка задачи, если есть.
        progress?.error?.takeIf { it.isNotBlank() }?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = SnProFamily),
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(16.dp))

        val episodes = progress?.episodes.orEmpty()
        if (episodes.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(episodes, key = { it.number }) { ep ->
                    EpisodeProgressCard(episode = ep, isDark = isDark, ru = ru)
                }
            }
        }
    }
}

/** Карточка одной серии на экране прогресса: номер, статус-иконка, локальный прогресс-бар. */
@Composable
private fun EpisodeProgressCard(episode: EpisodeProgress, isDark: Boolean, ru: Boolean) {
    val (icon, tint) = episode.statusVisual()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (ru) "Серия ${episode.number}" else "Episode ${episode.number}",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = episode.statusLabel(ru),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Локальный прогресс показываем только пока серия качается.
            if (episode.status.equals("downloading", ignoreCase = true)) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (episode.progressPct / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f),
                )
            }
        }
    }
}

private fun EpisodeProgress.statusVisual(): Pair<ImageVector, Color> = when (status.lowercase()) {
    "success" -> Icons.Rounded.CheckCircle to Color(0xFF4CAF50)
    "downloading" -> Icons.Rounded.Downloading to Color(0xFFE85002)
    "failed" -> Icons.Rounded.ErrorOutline to Color(0xFFE53935)
    "skipped" -> Icons.Rounded.RemoveCircleOutline to Color(0xFF9E9E9E)
    else -> Icons.Rounded.Schedule to Color(0xFF9E9E9E) // queued/pending
}

private fun EpisodeProgress.statusLabel(ru: Boolean): String = when (status.lowercase()) {
    "success" -> if (ru) "готово" else "done"
    "downloading" -> if (ru) "${progressPct}%" else "${progressPct}%"
    "failed" -> if (ru) "ошибка" else "failed"
    "skipped" -> if (ru) "пропущено" else "skipped"
    else -> if (ru) "в очереди" else "queued"
}

// ==========================================
// Общие элементы
// ==========================================

@Composable
private fun SingleChoiceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    val bg = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.20f else 0.12f)
    } else {
        if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
    }
    val titleColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = titleColor,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = SnProFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                    else Modifier.background(
                        if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f),
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun DownloadSheetButtons(
    state: DownloadWizardState,
    ru: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val step = state.step
    val isDark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    // «Назад» доступна на шагах 2–3 (не на прогрессе — там загрузка уже идёт).
    val showBack = step == DownloadStep.QUALITY || step == DownloadStep.EPISODES

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            Row(
                modifier = Modifier
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    )
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (ru) "Назад" else "Back",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        val nextLabel = when (step) {
            DownloadStep.EPISODES -> if (ru) "Начать скачивание" else "Start download"
            DownloadStep.PROGRESS -> if (state.finished) {
                if (ru) "Готово" else "Done"
            } else {
                if (ru) "Скрыть шторку" else "Hide sheet"
            }
            else -> if (ru) "Далее" else "Next"
        }
        val enabled = state.nextEnabled
        Box(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    onClick = onNext,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = nextLabel,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                ),
                color = Color.White,
            )
        }
    }
}

/** Тёмная ли поверхность (для выбора контрастных подложек внутри sheet). */
private fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
