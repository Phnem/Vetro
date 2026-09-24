package com.example.myapplication.localplayer.ui

import com.example.myapplication.media.progress.runPlaybackProgressSaver
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.exoplayer.ExoPlayer
import com.example.myapplication.localplayer.model.LocalEpisode
import com.example.myapplication.media.progress.EpisodePlaybackStore
import com.example.myapplication.media.ui.finishIfPipWindowClosed
import com.example.myapplication.media.ui.finishIfStoppedOutsidePip
import com.example.myapplication.media.ui.PipActionsController
import com.example.myapplication.media.ui.PipHostActivity
import com.example.myapplication.media.ui.PipPlaybackCommands
import com.example.myapplication.ui.shared.theme.OneUiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named

/**
 * Область для записи прогресса просмотра, переживающая закрытие экрана.
 *
 * `lifecycleScope` отменяется на `onDestroy`, а выход из плеера — это `onStop` и сразу `onDestroy`:
 * запись, не успевшая взять мьютекс стора, до диска не доезжала, и пользователь терял последние
 * секунды просмотра именно при выходе. Область живёт столько же, сколько процесс, и хранит ровно
 * одну короткую задачу за раз.
 */
private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Plays app-owned downloaded files in the same custom Exo UI as the SAF local library.
 */
class DownloadedPlayerActivity : ComponentActivity(), PipHostActivity {

    private val playbackStore: EpisodePlaybackStore by inject()
    private val settings: DataStore<Preferences> by inject(named("settings"))
    private var player: ExoPlayer? = null
    private var animeId: String = ""
    private var episodes: List<LocalEpisode> = emptyList()
    private val pipState = mutableStateOf(false)
    private val pipActions = PipActionsController(this)
    /** Экран хоть раз уходил в PiP — см. [finishIfStoppedOutsidePip]. */
    private var wasInPip = false

    override fun updatePipCommands(commands: PipPlaybackCommands?) {
        pipActions.setCommands(commands)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pipActions.register()

        animeId = intent.getStringExtra(EXTRA_ANIME_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_ANIME_TITLE).orEmpty()
        val malId = intent.getIntExtra(EXTRA_MAL_ID, -1).takeIf { it > 0 }
        val anilistId = intent.getIntExtra(EXTRA_ANILIST_ID, -1).takeIf { it > 0 }
        episodes = decodeEpisodes(intent.getStringExtra(EXTRA_EPISODES_JSON))
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
            .coerceIn(0, (episodes.lastIndex).coerceAtLeast(0))

        if (animeId.isBlank() || episodes.isEmpty()) {
            finish()
            return
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runPlaybackProgressSaver(isPlaying = { player?.isPlaying == true }) {
                    persistCurrentProgress()
                }
            }
        }

        setContent {
            OneUiTheme {
                val playerSettingsState = rememberPlayerSettings(settings)
                CompositionLocalProvider(LocalPlayerLanguage provides playerSettingsState.value.language) {
                    val playerSettings by playerSettingsState
                    val autoSkip = playerSettings.autoSkip
                    val autoNext = playerSettings.autoNext
                    var landscape by rememberSaveable { mutableStateOf(true) }
                    BackHandler { finish() }
                    DisposableEffect(landscape) {
                        requestedOrientation = if (landscape) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                        onDispose {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        PlayerScreen(
                            episodes = episodes,
                            startIndex = startIndex,
                            malId = malId,
                            anilistId = anilistId,
                            autoSkipEnabled = autoSkip,
                            autoNextEnabled = autoNext,
                            isInPip = pipState.value,
                            onEnterPip = ::enterPip,
                            onRotate = { landscape = !landscape },
                            onBack = { finish() },
                            onPlayerAvailable = { player = it },
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        val snapshot = progressSnapshot()
        if (snapshot != null) {
            // НЕ lifecycleScope: закрытие экрана ведёт из onStop прямо в onDestroy, и запись
            // прогресса отменялась бы вместе с ним.
            progressScope.launch { save(snapshot) }
        }
        super.onStop()
        // Страховка к проверке в onPictureInPictureModeChanged — см. finishIfStoppedOutsidePip.
        finishIfStoppedOutsidePip(wasInPip)
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

    private fun enterPip() {
        pipActions.enterPip()
    }

    override fun onDestroy() {
        pipActions.unregister()
        super.onDestroy()
    }

    private suspend fun persistCurrentProgress() {
        progressSnapshot()?.let { save(it) }
    }

    private fun progressSnapshot(): ProgressSnapshot? {
        val active = player ?: return null
        val item = episodes.getOrNull(active.currentMediaItemIndex) ?: return null
        val season = item.season?.coerceAtLeast(1) ?: 1
        val episode = item.episodeNumber?.coerceAtLeast(1) ?: return null
        val duration = active.duration
        if (duration <= 0L) return null
        return ProgressSnapshot(
            season = season,
            episode = episode,
            positionMs = active.currentPosition.coerceIn(0L, duration),
            durationMs = duration,
        )
    }

    private suspend fun save(snapshot: ProgressSnapshot) {
        playbackStore.saveProgress(
            animeId = animeId,
            season = snapshot.season,
            episode = snapshot.episode,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
        )
    }

    private data class ProgressSnapshot(
        val season: Int,
        val episode: Int,
        val positionMs: Long,
        val durationMs: Long,
    )

    companion object {
        private const val EXTRA_ANIME_ID = "anime_id"
        private const val EXTRA_ANIME_TITLE = "anime_title"
        private const val EXTRA_MAL_ID = "mal_id"
        private const val EXTRA_ANILIST_ID = "anilist_id"
        private const val EXTRA_EPISODES_JSON = "episodes_json"
        private const val EXTRA_START_INDEX = "start_index"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun intent(
            context: Context,
            animeId: String,
            animeTitle: String,
            episodes: List<LocalEpisode>,
            startIndex: Int,
            malId: Int? = null,
            anilistId: Int? = null,
        ): Intent = Intent(context, DownloadedPlayerActivity::class.java).apply {
            putExtra(EXTRA_ANIME_ID, animeId)
            putExtra(EXTRA_ANIME_TITLE, animeTitle)
            putExtra(EXTRA_MAL_ID, malId ?: -1)
            putExtra(EXTRA_ANILIST_ID, anilistId ?: -1)
            putExtra(
                EXTRA_EPISODES_JSON,
                json.encodeToString(ListSerializer(LocalEpisode.serializer()), episodes),
            )
            putExtra(EXTRA_START_INDEX, startIndex)
        }

        private fun decodeEpisodes(raw: String?): List<LocalEpisode> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(LocalEpisode.serializer()), raw)
            }.getOrElse { emptyList() }
        }
    }
}
