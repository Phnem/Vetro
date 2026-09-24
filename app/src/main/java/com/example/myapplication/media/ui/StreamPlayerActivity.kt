package com.example.myapplication.media.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.domain.enrichment.CollectionEnrichmentCoordinator
import com.example.myapplication.domain.enrichment.InteractiveMediaPauseViewModel
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.localplayer.ui.LocalPlayerViewModel
import com.example.myapplication.media.MediaGateway
import com.example.myapplication.media.episode.EpisodeRange
import com.example.myapplication.media.episode.EpisodeStreamResolver
import com.example.myapplication.media.episode.shouldAutoAdvance
import com.example.myapplication.media.player.StreamingPlaybackSessionFactory
import com.example.myapplication.media.player.ContinuousBufferingWatchdog
import com.example.myapplication.media.player.StreamRecoveryAction
import com.example.myapplication.media.player.StreamRecoveryInput
import com.example.myapplication.media.player.StreamRecoveryPolicy
import com.example.myapplication.media.player.StreamRecoveryTrigger
import com.example.myapplication.media.player.playbackHttpStatus
import com.example.myapplication.media.player.rankRecoveryCandidates
import com.example.myapplication.media.player.selectPreferredVideo
import com.example.myapplication.media.player.selectResumePosition
import com.example.myapplication.media.source.flattenVideosWithSource
import com.example.myapplication.media.player.VetroVideoCache
import com.example.myapplication.media.progress.EpisodePlaybackStore
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.source.PlaybackIdentity
import com.example.myapplication.media.source.rankVideosForResolution
import com.example.myapplication.ui.shared.theme.OneUiTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import kotlin.math.abs

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
 * Header-aware remote playback using the same custom Exo controls as the local player.
 */
class StreamPlayerActivity : ComponentActivity(), PipHostActivity {

    private val mediaGateway: MediaGateway by inject()
    private val okHttpClient: OkHttpClient by inject()
    private val playbackStore: EpisodePlaybackStore by inject()
    private val enrichmentCoordinator: CollectionEnrichmentCoordinator by inject()
    private val settings: DataStore<Preferences> by inject(named("settings"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var activePlayer: Player? = null
    private var activeAnimeId: String = ""
    private var activeSeason: Int = 1
    private var activeEpisode: Int = 1
    private val pipState = mutableStateOf(false)
    private val pipActions = PipActionsController(this)
    /** Экран хоть раз уходил в PiP — см. [finishIfStoppedOutsidePip]. */
    private var wasInPip = false

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

        val legacyVideo = intent.getStringExtra(EXTRA_VIDEO_JSON)
            ?.let { runCatching { json.decodeFromString(VetroVideo.serializer(), it) }.getOrNull() }
        val intentVideos = intent.getStringExtra(EXTRA_VIDEOS_JSON)
            ?.let {
                runCatching {
                    json.decodeFromString(ListSerializer(VetroVideo.serializer()), it)
                }.getOrNull()
            }
            .orEmpty()
        val initialVideos = (intentVideos + listOfNotNull(legacyVideo)).distinctBy { it.url }
        val video = initialVideos.firstOrNull()
        if (video == null) {
            finish()
            return
        }
        val animeId = intent.getStringExtra(EXTRA_ANIME_ID).orEmpty()
        val animeTitle = intent.getStringExtra(EXTRA_ANIME_TITLE).orEmpty()
        val animeTitleEn = intent.getStringExtra(EXTRA_ANIME_TITLE_EN)
        val animeTitleRu = intent.getStringExtra(EXTRA_ANIME_TITLE_RU)
        val malId = intent.getIntExtra(EXTRA_MAL_ID, -1).takeIf { it > 0 }
        val anilistId = intent.getIntExtra(EXTRA_ANILIST_ID, -1).takeIf { it > 0 }
        val playbackIdentity = intent.getStringExtra(EXTRA_PLAYBACK_IDENTITY_JSON)?.let {
            runCatching { json.decodeFromString(PlaybackIdentity.serializer(), it) }.getOrNull()
        } ?: PlaybackIdentity(
            libraryId = animeId,
            title = animeTitle,
            titleEn = animeTitleEn,
            titleRu = animeTitleRu,
            mediaType = MediaType.ANIME,
            malId = malId,
            anilistId = anilistId,
        )
        val season = intent.getIntExtra(EXTRA_SEASON, 1).coerceAtLeast(1)
        val initialEpisode = intent.getIntExtra(EXTRA_EPISODE, 1).coerceAtLeast(1)
        val seasonInfo = intent.getStringExtra(EXTRA_SEASON_JSON)?.let {
            runCatching { json.decodeFromString(SeasonInfo.serializer(), it) }.getOrNull()
        }
        // Сколько серий в сезоне доступно; null = не разрешено. Не то же самое, что «серий нет».
        val availableEpisodes = seasonInfo?.episodes
        val episodeResolver = episodeResolver(
            playbackIdentity = playbackIdentity,
            seasonInfo = seasonInfo,
        )

        activeAnimeId = animeId
        activeSeason = season
        activeEpisode = initialEpisode

        setContent {
            OneUiTheme {
                val scope = rememberCoroutineScope()
                // Серия — наблюдаемое состояние, а не прочитанное из интента один раз: её меняют
                // кнопки «предыдущая»/«следующая», и вместе с ней обязаны переехать прогресс,
                // заголовок, сегменты автоскипа и резолв запасных ссылок.
                var episode by remember { mutableIntStateOf(initialEpisode) }
                val autoSkip by settings.data
                    .map { it[LocalPlayerViewModel.AUTO_SKIP_KEY] ?: false }
                    .collectAsState(initial = false)
                val autoNext by settings.data
                    .map { it[LocalPlayerViewModel.AUTO_NEXT_KEY] ?: true }
                    .collectAsState(initial = true)
                val stored by key(animeId, season, episode) {
                    playbackStore.episodeFlow(animeId, season, episode)
                        .collectAsState(initial = null)
                }
                var candidates by remember { mutableStateOf(initialVideos) }
                var currentIndex by remember { mutableIntStateOf(0) }
                var current by remember { mutableStateOf(video) }
                var resumePosition by remember { mutableStateOf<Long?>(null) }
                var restoreAppliedTo by remember { mutableStateOf<String?>(null) }
                var retrying by remember { mutableStateOf(false) }
                var recoveryJob by remember { mutableStateOf<Job?>(null) }
                var recoveryGeneration by remember { mutableLongStateOf(0L) }
                var recoveryOwner by remember { mutableStateOf<Player?>(null) }
                var sameUrlRetryUsed by remember { mutableStateOf(false) }
                var playbackError by remember { mutableStateOf<String?>(null) }
                var manualSwitchFallback by remember { mutableStateOf<VetroVideo?>(null) }
                // Студия, выбранная пользователем через колесо озвучки — переносится на соседние
                // серии в switchToEpisode(), иначе выбор сбрасывался бы на дефолт резолвера каждый раз.
                var preferredSourceName by remember { mutableStateOf<String?>(null) }
                var failedRenditionUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
                var landscape by rememberSaveable { mutableStateOf(true) }
                // Номер серии, которая сейчас резолвится. Он же — защёлка от гонки: пока не null,
                // повторные нажатия ничего не запускают.
                var switchingTo by remember { mutableStateOf<Int?>(null) }
                var failedSwitchTarget by remember { mutableStateOf<Int?>(null) }
                var switchError by remember { mutableStateOf<String?>(null) }
                // Играет ли сейчас — только для иконки в PiP-окне; сама поверхность следит за этим
                // отдельно и своим состоянием ни с кем не делится.
                var pipPlaying by remember { mutableStateOf(false) }

                // Прогресс пишется под текущую серию и после переключения — тоже (onStop читает
                // именно эти поля).
                LaunchedEffect(episode) { activeEpisode = episode }

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

                val playbackSession = remember(current.url, current.resolvedAt) {
                    StreamingPlaybackSessionFactory.createSession(
                        this@StreamPlayerActivity,
                        okHttpClient,
                        current,
                    ).also { session ->
                        session.player.apply {
                            playWhenReady = true
                            prepare()
                        }
                    }
                }
                val player = playbackSession.player
                val recoveryPolicy = remember { StreamRecoveryPolicy() }
                val bufferingWatchdog = remember(player, current.url) {
                    ContinuousBufferingWatchdog(WATCHDOG_BUFFERING_MS)
                }

                fun cancelRecoveryResolve() {
                    recoveryGeneration += 1L
                    recoveryJob?.cancel()
                    recoveryJob = null
                    retrying = false
                }

                fun availableFallbacks(): List<VetroVideo> = rankRecoveryCandidates(
                    current = current,
                    candidates = candidates,
                    failedUrls = failedRenditionUrls,
                )

                fun switchToFallback(markCurrentFailed: Boolean): Boolean {
                    val next = availableFallbacks().firstOrNull() ?: return false
                    cancelRecoveryResolve()
                    resumePosition = player.currentPosition.coerceAtLeast(0L)
                    if (markCurrentFailed) failedRenditionUrls = failedRenditionUrls + current.url
                    currentIndex = candidates.indexOfFirst { it.url == next.url }.coerceAtLeast(0)
                    sameUrlRetryUsed = false
                    manualSwitchFallback = null
                    playbackError = null
                    Log.i(TAG, "Switching to ranked fallback ${currentIndex + 1}/${candidates.size}")
                    current = next.copy(resolvedAt = System.currentTimeMillis())
                    return true
                }

                fun resolveFreshStream(markCurrentFailed: Boolean) {
                    if (retrying) return
                    val previous = current
                    if (markCurrentFailed) failedRenditionUrls = failedRenditionUrls + previous.url
                    retrying = true
                    playbackError = null
                    resumePosition = player.currentPosition.coerceAtLeast(0L)
                    val refreshingEpisode = episode
                    val generation = recoveryGeneration + 1L
                    recoveryGeneration = generation
                    recoveryJob = scope.launch {
                        val refreshed = try {
                            resolveReplacement(
                                resolver = episodeResolver,
                                episode = refreshingEpisode,
                                previous = previous,
                                excludedUrls = candidates.mapTo(
                                    failedRenditionUrls.toMutableSet(),
                                ) { it.url },
                            )
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            null
                        }
                        // Пока обновляли ссылку, пользователь мог уйти на соседнюю серию: результат
                        // относится к прошлой и подменять им уже играющую новую нельзя.
                        if (
                            recoveryGeneration != generation ||
                            episode != refreshingEpisode ||
                            current.url != previous.url
                        ) return@launch
                        retrying = false
                        recoveryJob = null
                        if (refreshed != null) {
                            VetroVideoCache.put(
                                VetroVideoCache.key(
                                    animeId,
                                    refreshingEpisode,
                                    "best",
                                    refreshed.label,
                                ),
                                refreshed,
                            )
                            val updated = candidates + refreshed
                            candidates = updated
                            currentIndex = updated.lastIndex
                            sameUrlRetryUsed = false
                            manualSwitchFallback = null
                            current = refreshed.copy(resolvedAt = System.currentTimeMillis())
                        } else if (player.playbackState != Player.STATE_READY) {
                            // Замены нет — но исходный поток мог ожить сам, пока мы её искали:
                            // короткий обрыв связи ExoPlayer переживает своим буфером. Плашка
                            // «источник умер» поверх живого кадра врёт и запирает экран.
                            //
                            // Смотрим на состояние потока, а не на isPlaying: поставленная
                            // пользователем пауза — это не мёртвый источник, но и наоборот,
                            // молчать про мёртвый источник из-за паузы тоже нельзя.
                            playbackError = "Источник больше не отвечает. Попробуйте ещё раз."
                        }
                    }
                }

                fun executeRecovery(action: StreamRecoveryAction, reason: String) {
                    if (recoveryOwner === player) return
                    recoveryOwner = player
                    Log.i(TAG, "Streaming recovery action=$action reason=$reason")
                    when (action) {
                        StreamRecoveryAction.RETRY_CURRENT -> {
                            resumePosition = player.currentPosition.coerceAtLeast(0L)
                            sameUrlRetryUsed = true
                            playbackError = null
                            current = current.copy(resolvedAt = System.currentTimeMillis())
                        }
                        StreamRecoveryAction.SWITCH_CANDIDATE -> {
                            if (!switchToFallback(markCurrentFailed = true)) {
                                resolveFreshStream(markCurrentFailed = true)
                            }
                        }
                        StreamRecoveryAction.RERESOLVE -> {
                            resolveFreshStream(markCurrentFailed = true)
                        }
                    }
                }

                fun refreshStream() {
                    recoveryOwner = null
                    val action = if (availableFallbacks().isNotEmpty()) {
                        StreamRecoveryAction.SWITCH_CANDIDATE
                    } else {
                        StreamRecoveryAction.RERESOLVE
                    }
                    executeRecovery(action, reason = "manual_retry")
                }

                /**
                 * Переключение на соседнюю серию (FR-2). Текущее воспроизведение не трогаем, пока
                 * новая ссылка не готова: плеер пересобирается только по факту успешного резолва,
                 * а неудача оставляет серию играть и показывает сообщение.
                 */
                fun switchToEpisode(target: Int?) {
                    if (target == null || target == episode || switchingTo != null) return
                    cancelRecoveryResolve()
                    recoveryOwner = player
                    switchingTo = target
                    switchError = null
                    failedSwitchTarget = null
                    val leavingEpisode = episode
                    val leavingPosition = player.currentPosition.coerceAtLeast(0L)
                    val leavingDuration = player.duration
                    val preferredResolution = current.resolution ?: DEFAULT_RESOLUTION
                    scope.launch {
                        // Прогресс уходящей серии дописываем ДО подмены: плеер будет пересоздан под
                        // новую ссылку, и его позиция пропадёт вместе с ним.
                        if (leavingDuration > 0L) {
                            playbackStore.saveProgress(
                                animeId = animeId,
                                season = season,
                                episode = leavingEpisode,
                                positionMs = leavingPosition,
                                durationMs = leavingDuration,
                            )
                        }
                        val resolved = runCatching {
                            episodeResolver.resolve(target, preferredResolution)
                        }.getOrDefault(emptyList())
                        val best = selectPreferredVideo(resolved, preferredSourceName)
                        if (best == null) {
                            // «Ссылку достать не удалось» — не то же самое, что «серии нет»:
                            // кнопка останется активной, попытку можно повторить.
                            switchError = "Не удалось получить ссылку на серию $target"
                            failedSwitchTarget = target
                            switchingTo = null
                            recoveryOwner = null
                            return@launch
                        }
                        VetroVideoCache.put(
                            VetroVideoCache.key(animeId, target, "best", best.label),
                            best,
                        )
                        candidates = resolved
                        currentIndex = 0
                        failedRenditionUrls = emptySet()
                        manualSwitchFallback = null
                        sameUrlRetryUsed = false
                        playbackError = null
                        resumePosition = 0L
                        episode = target
                        current = best.copy(resolvedAt = System.currentTimeMillis())
                        switchingTo = null
                    }
                }

                LaunchedEffect(player, episode, stored, resumePosition) {
                    val restoreKey =
                        "${System.identityHashCode(player)}:$episode:${current.url}:${current.resolvedAt}"
                    if (restoreAppliedTo == restoreKey) return@LaunchedEffect
                    val savedPosition = stored
                        ?.takeIf { !it.watched && it.positionMs > 0L }
                        ?.positionMs
                    val target = selectResumePosition(resumePosition, savedPosition)
                    if (target == null && stored == null) return@LaunchedEffect
                    restoreAppliedTo = restoreKey
                    resumePosition = null
                    if (target != null) player.seekTo(target)
                }

                LaunchedEffect(player, animeId, season, episode) {
                    while (isActive) {
                        val duration = player.duration
                        if (duration > 0L) {
                            playbackStore.saveProgress(
                                animeId = animeId,
                                season = season,
                                episode = episode,
                                positionMs = player.currentPosition,
                                durationMs = duration,
                            )
                        }
                        delay(1_000L)
                    }
                }

                LaunchedEffect(player, current.url) {
                    while (isActive) {
                        val buffering = player.playWhenReady &&
                            player.playbackState == Player.STATE_BUFFERING
                        if (
                            bufferingWatchdog.sample(SystemClock.elapsedRealtime(), buffering) &&
                            !retrying &&
                            switchingTo == null
                        ) {
                            val action = recoveryPolicy.decide(
                                StreamRecoveryInput(
                                    trigger = StreamRecoveryTrigger.WATCHDOG,
                                    bufferedDurationMs = (
                                        player.bufferedPosition - player.currentPosition
                                    ).coerceAtLeast(0L),
                                    retryAlreadyUsed = sameUrlRetryUsed,
                                    httpStatus = null,
                                    hasFallback = availableFallbacks().isNotEmpty(),
                                ),
                            )
                            executeRecovery(action, reason = "buffering_watchdog")
                        }
                        delay(WATCHDOG_POLL_MS)
                    }
                }

                DisposableEffect(player, current.url, current.resolvedAt) {
                    // Old listener is disposed before this replacement effect starts, so clearing
                    // an owner from another player cannot reopen the old callback race.
                    if (recoveryOwner !== player) recoveryOwner = null
                    val listenerSessionUrl = current.url
                    val listenerSessionResolvedAt = current.resolvedAt
                    activePlayer = player
                    val mediaSession = MediaSession.Builder(this@StreamPlayerActivity, player)
                        .setId("vetro-stream-${System.currentTimeMillis()}")
                        .build()
                    val listener = object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            pipPlaying = playing
                            // Поток пошёл дальше — значит он жив, что бы ни решил резолвер
                            // замены секунду назад. Плашка снимается по факту, а не по кнопке:
                            // раньше её приходилось смахивать перезаходом в плеер.
                            if (playing) playbackError = null
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (recoveryOwner === player) return
                            Log.w(TAG, "Playback failed: ${error.errorCodeName}")
                            val previousRendition = manualSwitchFallback
                            if (previousRendition != null) {
                                Log.w(TAG, "Manual rendition failed; rolling back")
                                recoveryOwner = player
                                failedRenditionUrls = failedRenditionUrls + current.url
                                manualSwitchFallback = null
                                sameUrlRetryUsed = false
                                playbackError = null
                                current = previousRendition.copy(resolvedAt = System.currentTimeMillis())
                                return
                            }
                            val bufferedDurationMs =
                                (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                            val action = recoveryPolicy.decide(
                                StreamRecoveryInput(
                                    trigger = StreamRecoveryTrigger.PLAYER_ERROR,
                                    bufferedDurationMs = bufferedDurationMs,
                                    retryAlreadyUsed = sameUrlRetryUsed,
                                    httpStatus = playbackHttpStatus(error),
                                    hasFallback = availableFallbacks().isNotEmpty(),
                                ),
                            )
                            executeRecovery(action, reason = "player_error")
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (
                                playbackState == Player.STATE_READY &&
                                current.url == listenerSessionUrl &&
                                current.resolvedAt == listenerSessionResolvedAt
                            ) {
                                manualSwitchFallback = null
                                sameUrlRetryUsed = false
                            }
                            if (playbackState == Player.STATE_ENDED && player.duration > 0L) {
                                scope.launch {
                                    playbackStore.saveProgress(
                                        animeId = animeId,
                                        season = season,
                                        episode = episode,
                                        positionMs = player.duration,
                                        durationMs = player.duration,
                                    )
                                }
                            }
                            // Автопереход — тот же путь, что кнопка «дальше»: своей ветки резолва у
                            // него нет, иначе автоматика и кнопка разъехались бы в поведении
                            // (сообщение об ошибке, запись прогресса уходящей серии, гонки).
                            if (
                                playbackState == Player.STATE_ENDED &&
                                shouldAutoAdvance(
                                    enabled = autoNext,
                                    durationMs = player.duration,
                                    hasNext = EpisodeRange.hasNext(episode, availableEpisodes),
                                    switching = switchingTo != null,
                                )
                            ) {
                                switchToEpisode(EpisodeRange.nextOf(episode, availableEpisodes))
                            }
                        }
                    }
                    player.addListener(listener)
                    onDispose {
                        player.removeListener(listener)
                        mediaSession.release()
                        if (activePlayer === player) activePlayer = null
                        player.release()
                    }
                }

                // В PiP-окне нашего оверлея нет, а серия здесь не элемент плейлиста ExoPlayer —
                // «дальше» обязано идти тем же путём резолва, что и кнопка на экране.
                LaunchedEffect(player, episode, switchingTo, availableEpisodes, pipPlaying) {
                    updatePipCommands(
                        PipPlaybackCommands(
                            isPlaying = pipPlaying,
                            hasPrevious = EpisodeRange.hasPrevious(episode) && switchingTo == null,
                            hasNext = EpisodeRange.hasNext(episode, availableEpisodes) &&
                                switchingTo == null,
                            onPrevious = { switchToEpisode(EpisodeRange.previousOf(episode)) },
                            onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                            onNext = {
                                switchToEpisode(EpisodeRange.nextOf(episode, availableEpisodes))
                            },
                        )
                    )
                }

                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    StreamPlayerSurface(
                        player = player,
                        video = current,
                        renditions = selectStudioRenditions(
                            candidates.filterNot { it.url in failedRenditionUrls },
                            current,
                        ),
                        onSelectRendition = { rendition ->
                            preferredSourceName = rendition.sourceName ?: preferredSourceName
                            if (rendition.url != current.url) {
                                cancelRecoveryResolve()
                                recoveryOwner = player
                                resumePosition = player.currentPosition.coerceAtLeast(0L)
                                manualSwitchFallback = current
                                sameUrlRetryUsed = false
                                val selectedIndex = candidates.indexOfFirst {
                                    it.url == rendition.url
                                }
                                if (selectedIndex >= 0) currentIndex = selectedIndex
                                playbackError = null
                                current = rendition.copy(resolvedAt = System.currentTimeMillis())
                            }
                        },
                        title = "$animeTitle · ${if (season > 1) "S$season " else ""}E$episode",
                        episodeNumber = episode,
                        malId = seasonInfo?.malId ?: malId,
                        anilistId = seasonInfo?.anilistId ?: anilistId,
                        autoSkipEnabled = autoSkip,
                        isInPip = pipState.value,
                        onEnterPip = ::enterPip,
                        onRotate = { landscape = !landscape },
                        onBack = { finish() },
                        hasPrevEpisode = EpisodeRange.hasPrevious(episode) && switchingTo == null,
                        hasNextEpisode = EpisodeRange.hasNext(episode, availableEpisodes) &&
                            switchingTo == null,
                        onPrevEpisode = { switchToEpisode(EpisodeRange.previousOf(episode)) },
                        onNextEpisode = {
                            switchToEpisode(EpisodeRange.nextOf(episode, availableEpisodes))
                        },
                        // Ожидание рисует сама поверхность локально, вместо текстовой плашки.
                        loading = switchingTo != null || retrying,
                    )

                    val errorText = playbackError ?: switchError
                    if (errorText != null) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.72f))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = errorText,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = {
                                        val retryTarget = failedSwitchTarget
                                        if (retryTarget != null) {
                                            switchError = null
                                            failedSwitchTarget = null
                                            switchToEpisode(retryTarget)
                                        } else {
                                            sameUrlRetryUsed = false
                                            refreshStream()
                                        }
                                    }
                                ) {
                                    Text("Повторить")
                                }
                                // Ошибка переключения не должна запирать экран: текущая серия
                                // играет, сообщение можно просто закрыть.
                                if (failedSwitchTarget != null) {
                                    TextButton(
                                        onClick = {
                                            switchError = null
                                            failedSwitchTarget = null
                                        }
                                    ) {
                                        Text("Закрыть", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        persistActivePlayer()
        super.onStop()
        // Страховка к проверке в onPictureInPictureModeChanged: порядок этих двух колбэков
        // контрактом не закреплён, и на прошивке с обратным порядком крестик снова оставлял бы
        // серию играть в фоне.
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

    private fun persistActivePlayer() {
        val player = activePlayer ?: return
        val duration = player.duration
        if (duration <= 0L || activeAnimeId.isBlank()) return
        val position = player.currentPosition.coerceIn(0L, duration)
        // НЕ lifecycleScope: закрытие экрана (крестик PiP, «назад») ведёт из onStop прямо в
        // onDestroy, и запись, не успевшая взять мьютекс стора, отменялась бы вместе с ним —
        // пользователь терял бы последние секунды просмотра ровно при выходе.
        progressScope.launch {
            playbackStore.saveProgress(
                animeId = activeAnimeId,
                season = activeSeason,
                episode = activeEpisode,
                positionMs = position,
                durationMs = duration,
            )
        }
    }

    /**
     * Резолв ссылок для произвольной серии этого сезона. Один и тот же путь обслуживает и
     * пересборку протухшей ссылки текущей серии, и переход на соседнюю (TICKET-03).
     */
    private fun episodeResolver(
        playbackIdentity: PlaybackIdentity,
        seasonInfo: SeasonInfo?,
    ): EpisodeStreamResolver = EpisodeStreamResolver { episode, preferredResolution ->
        val anime = playbackIdentity.toAnime(seasonInfo, episode)
        val videos = flattenVideosWithSource(mediaGateway.resolveHosters(anime, episode, seasonInfo))
        rankVideosForResolution(videos, preferredResolution)
    }

    private suspend fun resolveReplacement(
        resolver: EpisodeStreamResolver,
        episode: Int,
        previous: VetroVideo,
        excludedUrls: Set<String>,
    ): VetroVideo? = resolver
        .resolve(episode, previous.resolution ?: DEFAULT_RESOLUTION)
        .firstOrNull { it.url !in excludedUrls }

    companion object {
        private const val TAG = "StreamPlayer"
        private const val WATCHDOG_BUFFERING_MS = 8_000L
        private const val WATCHDOG_POLL_MS = 250L
        /** К чему тянемся, если у текущей ссылки разрешение неизвестно. */
        private const val DEFAULT_RESOLUTION = 1080
        private const val EXTRA_VIDEO_JSON = "video_json"
        private const val EXTRA_VIDEOS_JSON = "videos_json"
        private const val EXTRA_ANIME_ID = "anime_id"
        private const val EXTRA_ANIME_TITLE = "anime_title"
        private const val EXTRA_ANIME_TITLE_EN = "anime_title_en"
        private const val EXTRA_ANIME_TITLE_RU = "anime_title_ru"
        private const val EXTRA_MAL_ID = "mal_id"
        private const val EXTRA_ANILIST_ID = "anilist_id"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_SEASON_JSON = "season_json"
        private const val EXTRA_PLAYBACK_IDENTITY_JSON = "playback_identity_json"
        private val intentJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun intent(
            context: Context,
            video: VetroVideo,
            animeId: String,
            animeTitle: String,
            episode: Int,
            videos: List<VetroVideo> = listOf(video),
            season: Int = 1,
            animeTitleEn: String? = null,
            animeTitleRu: String? = null,
            malId: Int? = null,
            anilistId: Int? = null,
            seasonInfo: SeasonInfo? = null,
            playbackIdentity: PlaybackIdentity? = null,
        ): Intent = Intent(context, StreamPlayerActivity::class.java).apply {
            putExtra(
                EXTRA_VIDEO_JSON,
                intentJson.encodeToString(VetroVideo.serializer(), video),
            )
            putExtra(
                EXTRA_VIDEOS_JSON,
                intentJson.encodeToString(
                    ListSerializer(VetroVideo.serializer()),
                    videos.distinctBy { it.url },
                ),
            )
            putExtra(EXTRA_ANIME_ID, animeId)
            putExtra(EXTRA_ANIME_TITLE, animeTitle)
            putExtra(EXTRA_ANIME_TITLE_EN, animeTitleEn)
            putExtra(EXTRA_ANIME_TITLE_RU, animeTitleRu)
            putExtra(EXTRA_MAL_ID, malId ?: -1)
            putExtra(EXTRA_ANILIST_ID, anilistId ?: -1)
            putExtra(EXTRA_SEASON, season)
            putExtra(EXTRA_EPISODE, episode)
            playbackIdentity?.let {
                putExtra(
                    EXTRA_PLAYBACK_IDENTITY_JSON,
                    intentJson.encodeToString(PlaybackIdentity.serializer(), it),
                )
            }
            seasonInfo?.let {
                putExtra(
                    EXTRA_SEASON_JSON,
                    intentJson.encodeToString(SeasonInfo.serializer(), it),
                )
            }
        }
    }
}
private fun selectStudioRenditions(
    videos: List<VetroVideo>,
    current: VetroVideo,
): List<VetroVideo> {
    val grouped = videos
        .filter { !it.sourceName.isNullOrBlank() }
        .groupBy { it.sourceName.orEmpty() }
    if (grouped.size <= 1) return emptyList()
    val targetResolution = current.resolution ?: 1080
    return grouped.values.mapNotNull { variants ->
        variants.minByOrNull { candidate ->
            abs((candidate.resolution ?: targetResolution) - targetResolution)
        }
    }.distinctBy { it.url }
}
