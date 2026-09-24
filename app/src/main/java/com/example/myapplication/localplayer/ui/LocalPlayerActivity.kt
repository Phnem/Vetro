package com.example.myapplication.localplayer.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.lifecycle.ViewModelProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.localplayer.model.LocalEpisode
import com.example.myapplication.domain.enrichment.CollectionEnrichmentCoordinator
import com.example.myapplication.domain.enrichment.InteractiveMediaPauseViewModel
import com.example.myapplication.media.ui.finishIfPipWindowClosed
import com.example.myapplication.media.ui.finishIfStoppedOutsidePip
import com.example.myapplication.media.ui.PipActionsController
import com.example.myapplication.media.ui.PipHostActivity
import com.example.myapplication.media.ui.PipPlaybackCommands
import com.example.myapplication.ui.shared.theme.OneUiTheme
import com.example.myapplication.ui.shared.theme.SnProFamily
import org.koin.androidx.compose.koinViewModel
import org.koin.android.ext.android.inject
import org.koin.core.parameter.parametersOf
import java.util.Locale

/**
 * Экран локального плеера одного тайтла (изолированная фича). Запускается из Details интентом
 * [intent]. Внутри — выбор папки (SAF), скан+разбор эпизодов, список и переход в [PlayerScreen].
 *
 * Удаление фичи: убрать этот Activity из манифеста, удалить пакет localplayer, снять DI-строки и
 * кнопку-иконку в DetailsScreen. Больше нигде состояния нет.
 */
class LocalPlayerActivity : ComponentActivity(), PipHostActivity {

    private val enrichmentCoordinator: CollectionEnrichmentCoordinator by inject()
    private val pipState = androidx.compose.runtime.mutableStateOf(false)
    /** Экран хоть раз уходил в PiP — см. [finishIfStoppedOutsidePip]. */
    private var wasInPip = false

    // Кнопки PiP-окна; состояние в них присылает PlayerScreen, пока плеер жив.
    private val pipActions = PipActionsController(this)

    override fun updatePipCommands(commands: PipPlaybackCommands?) {
        pipActions.setCommands(commands)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViewModelProvider(
            this,
            InteractiveMediaPauseViewModel.factory(enrichmentCoordinator),
        )[InteractiveMediaPauseViewModel::class.java]
        enableEdgeToEdge()
        pipActions.register()
        val animeId = intent.getStringExtra(EXTRA_ANIME_ID).orEmpty()
        val animeTitle = intent.getStringExtra(EXTRA_ANIME_TITLE).orEmpty()
        val animeMalId = intent.getIntExtra(EXTRA_ANIME_MAL_ID, -1).takeIf { it > 0 }
        val animeAnilistId = intent.getIntExtra(EXTRA_ANIME_ANILIST_ID, -1).takeIf { it > 0 }
        // >= 0 — активити запущена со страницы «Серии» деталей: сразу играем этот эпизод,
        // а «назад» из плеера закрывает активити (список живёт в DetailsScreen).
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, -1).takeIf { it >= 0 }
        if (animeId.isBlank()) {
            finish()
            return
        }
        setContent {
            OneUiTheme(darkTheme = isSystemInDarkTheme()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LocalPlayerHost(
                        viewModel = koinViewModel { parametersOf(animeId, animeTitle, animeMalId, animeAnilistId) },
                        onClose = { finish() },
                        isInPip = pipState,
                        onEnterPip = { enterPip() },
                        onSetOrientation = { setPlayerOrientation(it) },
                        autoPlayIndex = startIndex,
                    )
                }
            }
        }
    }

    /** Ориентация плеера: горизонт (по умолчанию при входе), портрет (по кнопке), null = отпустить. */
    private fun setPlayerOrientation(landscape: Boolean?) {
        requestedOrientation = when (landscape) {
            true -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            false -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /** Вход в режим «картинка в картинке» (только API 26+, что совпадает с minSdk проекта). */
    private fun enterPip() {
        pipActions.enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipState.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) wasInPip = true
        // Крестик на окне закрывает экран целиком — иначе остановленная активити остаётся жива
        // и продолжает играть серию в фоне.
        finishIfPipWindowClosed(isInPictureInPictureMode)
    }

    override fun onStop() {
        super.onStop()
        // Страховка к проверке выше: порядок onStop и onPictureInPictureModeChanged контрактом
        // не закреплён — см. finishIfStoppedOutsidePip.
        finishIfStoppedOutsidePip(wasInPip)
    }

    override fun onDestroy() {
        pipActions.unregister()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ANIME_ID = "anime_id"
        private const val EXTRA_ANIME_TITLE = "anime_title"
        private const val EXTRA_ANIME_MAL_ID = "anime_mal_id"
        private const val EXTRA_ANIME_ANILIST_ID = "anime_anilist_id"
        private const val EXTRA_START_INDEX = "start_index"

        fun intent(
            context: Context,
            animeId: String,
            animeTitle: String,
            malId: Int? = null,
            anilistId: Int? = null,
            startIndex: Int? = null,
        ): Intent =
            Intent(context, LocalPlayerActivity::class.java).apply {
                putExtra(EXTRA_ANIME_ID, animeId)
                putExtra(EXTRA_ANIME_TITLE, animeTitle)
                putExtra(EXTRA_ANIME_MAL_ID, malId ?: -1)
                putExtra(EXTRA_ANIME_ANILIST_ID, anilistId ?: -1)
                putExtra(EXTRA_START_INDEX, startIndex ?: -1)
            }
    }
}

private fun isRu(): Boolean = Locale.getDefault().language == "ru"

@Composable
private fun LocalPlayerHost(
    viewModel: LocalPlayerViewModel,
    onClose: () -> Unit,
    isInPip: State<Boolean>,
    onEnterPip: () -> Unit,
    onSetOrientation: (Boolean?) -> Unit,
    autoPlayIndex: Int? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Индекс эпизода, с которого запущен плеер; null = плеер закрыт.
    var playingFrom by rememberSaveable { mutableStateOf<Int?>(null) }

    // Запуск со страницы «Серии» деталей: как только библиотека готова — сразу в плеер.
    var autoPlayPending by rememberSaveable { mutableStateOf(autoPlayIndex != null) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.onFolderPicked(uri) }

    val library = state as? LocalPlayerUiState.Library
    val playing = playingFrom

    LaunchedEffect(library, autoPlayPending) {
        if (autoPlayPending && library != null && library.source.episodes.isNotEmpty()) {
            playingFrom = autoPlayIndex?.coerceIn(0, library.source.episodes.lastIndex)
            autoPlayPending = false
        }
    }

    // При автозапуске «назад» из плеера закрывает активити — список серий живёт в деталях.
    BackHandler(enabled = playing != null) {
        if (autoPlayIndex != null) onClose() else playingFrom = null
    }

    if (playing != null && library != null) {
        val autoSkip by viewModel.autoSkipEnabled.collectAsStateWithLifecycle()
        val autoNext by viewModel.autoNextEnabled.collectAsStateWithLifecycle()
        // Плеер стартует в горизонтали; кнопка поворота переключает портрет/горизонт.
        var landscape by rememberSaveable { mutableStateOf(true) }
        DisposableEffect(Unit) {
            onSetOrientation(landscape)
            onDispose { onSetOrientation(null) }
        }
        PlayerScreen(
            episodes = library.source.episodes,
            startIndex = playing,
            malId = viewModel.malId,
            anilistId = viewModel.anilistId,
            autoSkipEnabled = autoSkip,
            autoNextEnabled = autoNext,
            isInPip = isInPip.value,
            onEnterPip = onEnterPip,
            onRotate = { landscape = !landscape; onSetOrientation(landscape) },
            onBack = { if (autoPlayIndex != null) onClose() else playingFrom = null },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            LocalPlayerUiState.Loading, LocalPlayerUiState.Scanning ->
                CenteredProgress(
                    label = if (s == LocalPlayerUiState.Scanning) {
                        if (isRu()) "Разбираем эпизоды…" else "Parsing episodes…"
                    } else null,
                )

            LocalPlayerUiState.NeedSource ->
                EmptyState(onPick = { folderPicker.launch(null) })

            is LocalPlayerUiState.Rejected ->
                RejectedState(reason = s.reason, onPick = { folderPicker.launch(null) })

            is LocalPlayerUiState.Library ->
                LibraryContent(
                    title = viewModel.title,
                    state = s,
                    onPlayFrom = { index -> playingFrom = index },
                    onChangeFolder = { folderPicker.launch(null) },
                )
        }

        GlassBackButton(
            onClick = onClose,
            modifier = Modifier.statusBarsPadding().padding(12.dp).align(Alignment.TopStart),
        )
    }
}

@Composable
private fun GlassBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun CenteredProgress(label: String?) {
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
private fun EmptyState(onPick: () -> Unit) {
    InfoState(
        title = if (isRu()) "Добавьте источник" else "Add a source",
        subtitle = if (isRu()) {
            "Выберите папку с эпизодами этого тайтла (например Download/Jujutsu Kaisen). " +
                "Общие папки вроде Download или Documents выбрать нельзя."
        } else {
            "Pick the folder that holds this title's episodes (e.g. Download/Jujutsu Kaisen). " +
                "Generic folders like Download or Documents can't be selected."
        },
        actionLabel = if (isRu()) "Выбрать папку" else "Choose folder",
        onAction = onPick,
    )
}

@Composable
private fun RejectedState(reason: RejectReason, onPick: () -> Unit) {
    val (title, subtitle) = when (reason) {
        RejectReason.TOO_GENERAL -> Pair(
            if (isRu()) "Слишком общая папка" else "Folder is too generic",
            if (isRu()) {
                "Выберите конкретную папку тайтла внутри, а не Download/Documents/DCIM целиком."
            } else {
                "Pick a specific title folder inside, not the whole Download/Documents/DCIM."
            },
        )
        RejectReason.NO_VIDEOS -> Pair(
            if (isRu()) "Видео не найдено" else "No videos found",
            if (isRu()) {
                "В этой папке нет видеофайлов (mp4, mkv, avi…). Выберите другую."
            } else {
                "This folder has no video files (mp4, mkv, avi…). Try another one."
            },
        )
    }
    InfoState(
        title = title,
        subtitle = subtitle,
        actionLabel = if (isRu()) "Выбрать другую" else "Choose another",
        onAction = onPick,
    )
}

@Composable
private fun InfoState(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
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
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PillButton(label = actionLabel, onClick = onAction)
    }
}

@Composable
private fun LibraryContent(
    title: String,
    state: LocalPlayerUiState.Library,
    onPlayFrom: (Int) -> Unit,
    onChangeFolder: () -> Unit,
) {
    val episodes = state.source.episodes
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 76.dp,
                bottom = 120.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    val count = episodes.size
                    val sub = buildString {
                        append(state.source.folderLabel)
                        append(" · ")
                        append(if (isRu()) "$count эп." else "$count ep.")
                        if (state.usedAi) append(if (isRu()) " · ИИ-разметка" else " · AI-sorted")
                    }
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = SnProFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (isRu()) "Сменить папку" else "Change folder",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onChangeFolder),
                    )
                }
            }
            items(episodes, key = { it.documentUri }) { ep ->
                EpisodeRow(
                    episode = ep,
                    onClick = { onPlayFrom(episodes.indexOf(ep)) },
                )
            }
        }

        // «Начать просмотр» — снизу справа.
        if (episodes.isNotEmpty()) {
            StartWatchingButton(
                onClick = { onPlayFrom(0) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(20.dp),
            )
        }
    }
}

@Composable
private fun EpisodeRow(episode: LocalEpisode, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
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
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun StartWatchingButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (isRu()) "Начать просмотр" else "Start watching",
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
