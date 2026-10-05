package com.example.myapplication.media.ui

import androidx.compose.animation.core.MutableTransitionState
import com.example.myapplication.media.remote.RemoteConnection
import com.example.myapplication.media.remote.RemoteDevice
import com.example.myapplication.media.remote.RemoteError
import com.example.myapplication.media.remote.RemoteMedia
import com.example.myapplication.media.remote.RemotePlaybackException
import com.example.myapplication.media.remote.RemoteSessionPlayer
import com.example.myapplication.media.remote.RemoteStatus
import com.example.myapplication.media.remote.RemoteSubtitle
import com.example.myapplication.media.remote.proxy.ProxyUpstream
import com.example.myapplication.media.remote.ui.RemoteButtonState
import com.example.myapplication.media.remote.ui.RemoteDevicesPanel
import com.example.myapplication.media.source.SanitizeHeaders
import com.example.myapplication.media.source.credentialHeadersFor
import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import androidx.compose.runtime.collectAsState
import androidx.media3.common.C
import com.example.myapplication.media.subtitles.OpenSubtitlesUi
import com.example.myapplication.media.subtitles.SubtitleMenuAction
import com.example.myapplication.media.subtitles.WhisperUi
import com.example.myapplication.media.subtitles.whisper.ModelState
import com.example.myapplication.media.subtitles.whisper.OpenRouterWhisper
import com.example.myapplication.media.subtitles.whisper.SpeechRouter
import com.example.myapplication.media.subtitles.whisper.WhisperDeviceProfile
import com.example.myapplication.media.subtitles.whisper.WhisperLanguage
import com.example.myapplication.media.subtitles.whisper.WhisperModel
import com.example.myapplication.media.subtitles.whisper.WhisperModelStore
import com.example.myapplication.media.subtitles.whisper.WhisperRequest
import com.example.myapplication.media.subtitles.whisper.WhisperSubtitleManager
import com.example.myapplication.network.AppStoreJson
import com.example.myapplication.media.progress.runPlaybackProgressSaver
import androidx.compose.runtime.CompositionLocalProvider
import com.example.myapplication.localplayer.ui.LocalPlayerLanguage
import com.example.myapplication.localplayer.ui.rememberPlayerSettings
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.example.myapplication.media.player.dubSimilarity
import com.example.myapplication.media.player.selectResumePosition
import com.example.myapplication.media.source.flattenVideosWithSource
import com.example.myapplication.media.player.VetroVideoCache
import com.example.myapplication.media.progress.EpisodePlaybackStore
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.source.PlaybackIdentity
import com.example.myapplication.media.source.rankVideosForResolution
import com.example.myapplication.ui.shared.theme.AppThemed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import com.example.myapplication.media.progress.EpisodePlaybackProgress
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.produceState
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.widget.Toast
import com.example.myapplication.media.subtitles.ExternalSubtitleService
import com.example.myapplication.media.subtitles.SubtitleLoad
import com.example.myapplication.media.subtitles.SubtitleOffer
import com.example.myapplication.network.AppLanguage
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
    private val externalSubtitles: ExternalSubtitleService by inject()
    private val whisperModels: WhisperModelStore by inject()
    private val whisperSubtitles: WhisperSubtitleManager by inject()
    private val speechRouter: SpeechRouter by inject()
    private val enrichmentCoordinator: CollectionEnrichmentCoordinator by inject()
    private val settings: DataStore<Preferences> by inject(named("settings"))
    private val playbackPrefs: com.example.myapplication.media.prefs.ContentPlaybackPreferences by inject()
    private val remote: com.example.myapplication.media.remote.RemotePlaybackManager by inject()
    private val sourceIntelligence: com.example.myapplication.media.intelligence.SourceIntelligence by inject()
    private val appScope: com.example.myapplication.AppScope by inject()
    /** Прошлая ссылка этой серии: по ней видно, что новая сессия - переход на другой источник. */
    private var lastSessionKey: String? = null
    private var lastSessionUrl: String? = null
    private var lastSessionStudio: String? = null
    /** Телевизор как плеер, пока идёт показ на нём: прогресс при выходе пишется с его позиции. */
    private var remoteControl: Player? = null
    private val json = AppStoreJson

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
            AppThemed(settings) {
                val playerSettingsState = rememberPlayerSettings(settings)
                CompositionLocalProvider(LocalPlayerLanguage provides playerSettingsState.value.language) {
                    val scope = rememberCoroutineScope()
                    // Серия — наблюдаемое состояние, а не прочитанное из интента один раз: её меняют
                    // кнопки «предыдущая»/«следующая», и вместе с ней обязаны переехать прогресс,
                    // заголовок, сегменты автоскипа и резолв запасных ссылок.
                    var episode by remember { mutableIntStateOf(initialEpisode) }
                    val playerSettings by playerSettingsState
                    val autoSkip = playerSettings.autoSkip
                    val autoNext = playerSettings.autoNext
                    // Сохранённая позиция нужна один раз — чтобы вернуться в неё на входе в серию.
                    // Раньше это была живая подписка, да ещё и создаваемая заново на каждой
                    // рекомпозиции: каждая запись прогресса (раз в 10 с) пересобирала корень плеера.
                    val stored by produceState<EpisodePlaybackProgress?>(null, animeId, season, episode) {
                        value = playbackStore.episodeFlow(animeId, season, episode).first()
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
                    // Плеер сам ушёл на другой источник посреди серии — коротко говорим об этом.
                    var sourceSwitchNotice by remember { mutableStateOf<String?>(null) }
                    var manualSwitchFallback by remember { mutableStateOf<VetroVideo?>(null) }
                    // Студия, выбранная пользователем через колесо озвучки — переносится на соседние
                    // серии в switchToEpisode(), иначе выбор сбрасывался бы на дефолт резолвера каждый раз.
                    var preferredSourceName by remember { mutableStateOf<String?>(null) }
                    var failedRenditionUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
                    var landscape by rememberSaveable { mutableStateOf(true) }
                    // Номер серии, которая сейчас резолвится. Он же — защёлка от гонки: пока не null,
                    // повторные нажатия ничего не запускают.
                    var switchingTo by remember { mutableStateOf<Int?>(null) }
                    // Системная карточка медиа (экран блокировки, шторка) — см. VideoMediaSession.
                    var videoSession by remember { mutableStateOf<VideoMediaSession?>(null) }
                    val posterUri by produceState<android.net.Uri?>(null, animeId) {
                        value = collectionPosterUri(animeId)
                    }
                    var failedSwitchTarget by remember { mutableStateOf<Int?>(null) }
                    var switchError by remember { mutableStateOf<String?>(null) }
                    // Играет ли сейчас — только для иконки в PiP-окне; сама поверхность следит за этим
                    // отдельно и своим состоянием ни с кем не делится.
                    var pipPlaying by remember { mutableStateOf(false) }
                    // Субтитры OpenSubtitles: поиск на серию (квоту пользователя не тратит), скачивание —
                    // по выбору в меню. После скачивания источник пересобирается с той же позиции.
                    var subtitleOffers by remember { mutableStateOf<List<SubtitleOffer>>(emptyList()) }
                    var pendingSubtitleId by remember { mutableStateOf<String?>(null) }
                    var subtitleLoading by remember { mutableStateOf(false) }
                    var openSubtitlesSearching by remember { mutableStateOf(false) }
                    val uiLanguage = playerSettingsState.value.language
                    LaunchedEffect(season, episode, uiLanguage) {
                        subtitleOffers = emptyList()
                        if (!externalSubtitles.isAvailable) return@LaunchedEffect
                        openSubtitlesSearching = true
                        val languages = listOf(if (uiLanguage == AppLanguage.RU) "ru" else "en", "en").distinct()
                        subtitleOffers = runCatching {
                            externalSubtitles.offers(playbackIdentity, season, episode, languages)
                        }.getOrDefault(emptyList())
                        openSubtitlesSearching = false
                    }
                    // Whisper: модель по силам устройства, язык — вручную или «определить».
                    val recommendedModel = remember { recommendedWhisperModel() }
                    val modelStates by whisperModels.states.collectAsState()
                    var chosenModel by remember { mutableStateOf<WhisperModel?>(null) }
                    val whisperModel = chosenModel
                        ?: whisperModels.anyReady(recommendedModel)?.first
                        ?: recommendedModel
                    // Модели на телефоне нет, но подключён ключ OpenRouter — распознаёт облако.
                    val cloudSpeech by speechRouter.cloudAvailability.collectAsState(initial = speechRouter.cloudAvailable)
                    val whisperRoute = when {
                        speechRouter.onDeviceAvailable && modelStates[whisperModel] is ModelState.Ready -> whisperModel.name
                        cloudSpeech -> OpenRouterWhisper.CACHE_ID
                        else -> whisperModel.name
                    }
                    var whisperLanguage by rememberSaveable { mutableStateOf(WhisperLanguage.AUTO) }
                    var whisperShowing by remember(episode) { mutableStateOf(false) }

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

                    // Предпочтения, которые следуют за контентом: озвучка, язык звука и субтитры,
                    // выбранные раньше для этого тайтла (или, если нет, для его типа).
                    val contentType = playbackIdentity.mediaType.name
                    var playbackChoice by remember {
                        mutableStateOf<com.example.myapplication.media.prefs.PlaybackChoice?>(null)
                    }
                    LaunchedEffect(Unit) {
                        val choice = runCatching { playbackPrefs.choiceFor(animeId, contentType) }.getOrNull()
                            ?: return@LaunchedEffect
                        playbackChoice = choice
                        val studio = choice.sourceName ?: return@LaunchedEffect
                        if (preferredSourceName == null) preferredSourceName = studio
                        if (current.sourceName != studio) {
                            val index = candidates.indexOfFirst { it.sourceName == studio }
                            if (index >= 0) {
                                currentIndex = index
                                current = candidates[index].copy(resolvedAt = System.currentTimeMillis())
                            }
                        }
                    }
                    LaunchedEffect(player, playbackChoice) {
                        val choice = playbackChoice ?: return@LaunchedEffect
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                            choice.audioLanguage?.let { setPreferredAudioLanguage(it) }
                            when {
                                choice.subtitlesOff == true -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                choice.subtitleLanguage != null -> {
                                    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    setPreferredTextLanguage(choice.subtitleLanguage)
                                }
                            }
                        }.build()
                    }
                    // ---------- «Воспроизвести на…» ----------
                    // Телевизор — тоже Media3 Player: пока он подключён, им управляют те же кнопки,
                    // шкала и жесты, а плеер телефона стоит на паузе.
                    val remoteConnection by remote.connection.collectAsState()
                    val remoteDevices by remote.devices.collectAsState()
                    val remotePlayback by remote.playback.collectAsState()
                    val remotePlayer = remember { RemoteSessionPlayer(remote) }
                    DisposableEffect(Unit) { onDispose { remotePlayer.release() } }
                    val remoteActive = remoteConnection is RemoteConnection.Connected
                    val controlPlayer: Player = if (remoteActive) remotePlayer else player
                    LaunchedEffect(controlPlayer) { remoteControl = if (remoteActive) remotePlayer else null }
                    val devicesPanel = remember { MutableTransitionState(false) }
                    val devicesPanelOpen = devicesPanel.targetState
                    val mediaKey = "$animeId|$season|$episode"
                    val ruRemote = uiLanguage == AppLanguage.RU
                    // С какой серией и озвучкой уже работает ТВ — чтобы не перезагружать её лишний раз.
                    var remoteLoadedFor by remember { mutableStateOf<String?>(null) }
                    var remoteWasConnected by remember { mutableStateOf(false) }

                    DisposableEffect(devicesPanelOpen) {
                        if (devicesPanelOpen) remote.startDiscovery()
                        onDispose { if (devicesPanelOpen) remote.stopDiscovery() }
                    }

                    fun selectedSubtitleLanguage(): String? = player.currentTracks.groups
                        .firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
                        ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }

                    /** Что отдать телевизору: провайдер решил «что», трансляция — «куда». */
                    fun remoteMediaFor(video: VetroVideo, startMs: Long): RemoteMedia {
                        val selectedLanguage = selectedSubtitleLanguage()
                        return RemoteMedia(
                            key = mediaKey,
                            title = animeTitle,
                            subtitle = if (playbackIdentity.mediaType == MediaType.MOVIE) null
                                else (if (season > 1) "S$season · " else "") + (if (ruRemote) "Серия $episode" else "Episode $episode"),
                            artworkUrl = null,
                            url = video.url,
                            mimeType = null,
                            headers = SanitizeHeaders.sanitize(video.headers),
                            // Учётные заголовки — только в их область, как у локального плеера.
                            headersFor = if (video.credentialRef != null) {
                                { target: String -> SanitizeHeaders.sanitize(video.credentialHeadersFor(target)) }
                            } else null,
                            subtitles = video.subtitles
                                .filterNot { it.mimeType.contains("ass", true) || it.mimeType.contains("ssa", true) }
                                .map { t ->
                                    RemoteSubtitle(
                                        url = t.url,
                                        language = t.lang,
                                        label = t.label,
                                        mimeType = t.mimeType,
                                        selected = selectedLanguage != null && t.lang == selectedLanguage,
                                        isLocalFile = !t.url.startsWith("http"),
                                    )
                                },
                            durationMs = player.duration.takeIf { it > 0 },
                            startPositionMs = startMs,
                        )
                    }

                    /** Источник отозвал ссылку посреди показа — та же озвучка заново (для прокси). */
                    fun refreshUpstreamFor(video: VetroVideo): () -> ProxyUpstream? = {
                        kotlinx.coroutines.runBlocking {
                            runCatching { resolveReplacement(episodeResolver, episode, video, emptySet()) }.getOrNull()
                        }?.let { fresh -> ProxyUpstream(fresh.url, SanitizeHeaders.sanitize(fresh.headers)) }
                    }

                    fun castTo(device: RemoteDevice) {
                        val connected = remoteConnection as? RemoteConnection.Connected
                        if (connected?.device?.id == device.id) { devicesPanel.targetState = false; return }
                        // Телефон → ТВ: с того же места, а не с начала.
                        val wasPlaying = player.isPlaying
                        val start = if (remoteActive) remote.currentPositionMs() else player.currentPosition.coerceAtLeast(0L)
                        player.pause()
                        val target = current
                        scope.launch {
                            try {
                                remote.cast(device, remoteMediaFor(target, start), refreshUpstreamFor(target))
                                remoteLoadedFor = "${target.url}|$episode"
                                devicesPanel.targetState = false
                            } catch (e: RemotePlaybackException) {
                                // Ошибку показывает лист; телефон продолжает, как играл.
                                if (wasPlaying && remote.connection.value !is RemoteConnection.Connected) player.play()
                            }
                        }
                    }

                    fun returnToPhone() {
                        if (!remoteActive) { devicesPanel.targetState = false; return }
                        scope.launch {
                            // ТВ → телефон: позиция телевизора, остановка показа, продолжение здесь.
                            val position = remote.disconnect(stopPlayback = true)
                            remoteLoadedFor = null
                            if (position > 0) player.seekTo(position)
                            player.play()
                            devicesPanel.targetState = false
                        }
                    }

                    // Пока показ на ТВ, телефон молчит — в том числе новый плеер после смены озвучки.
                    LaunchedEffect(player, remoteActive) {
                        if (remoteActive) player.playWhenReady = false
                    }
                    LaunchedEffect(remoteConnection) {
                        when (val c = remoteConnection) {
                            is RemoteConnection.Connected -> remoteWasConnected = true
                            is RemoteConnection.Failed -> if (remoteWasConnected && c.error == RemoteError.DeviceGone) {
                                // ТВ пропал посреди показа — продолжаем на телефоне с его последней позиции.
                                remoteWasConnected = false
                                val position = remote.disconnect(stopPlayback = false)
                                remoteLoadedFor = null
                                if (position > 0) player.seekTo(position)
                                player.play()
                                Toast.makeText(
                                    this@StreamPlayerActivity,
                                    if (ruRemote) "Связь с «${c.device?.name.orEmpty()}» потеряна — продолжаем на телефоне"
                                    else "Lost “${c.device?.name.orEmpty()}” — continuing on the phone",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            else -> Unit
                        }
                    }
                    // Другая озвучка или серия, пока идёт показ, — на тот же телевизор.
                    LaunchedEffect(remoteActive, current.url, current.resolvedAt, episode) {
                        if (!remoteActive) return@LaunchedEffect
                        val id = "${current.url}|$episode"
                        if (remoteLoadedFor == id) return@LaunchedEffect
                        val loaded = remote.media.value
                        if (remoteLoadedFor == null && loaded?.key == mediaKey && loaded.url == current.url) {
                            remoteLoadedFor = id
                            return@LaunchedEffect
                        }
                        val sameEpisode = loaded?.key == mediaKey
                        val start = if (sameEpisode) remote.currentPositionMs()
                            else stored?.takeIf { !it.watched }?.positionMs ?: 0L
                        runCatching { remote.replaceMedia(remoteMediaFor(current, start), refreshUpstreamFor(current)) }
                        remoteLoadedFor = id
                    }

                    // Длительность — для плана распознавания: пока источник не готов, «Сгенерировать» скрыто.
                    var playerDurationMs by remember(player) { mutableLongStateOf(0L) }
                    DisposableEffect(player) {
                        val listener = object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { playerDurationMs = it }
                            }
                        }
                        player.addListener(listener)
                        onDispose { player.removeListener(listener) }
                    }
                    // Ключ кэша Whisper: серия + озвучка (разные озвучки — разная речь) + язык + модель.
                    val whisperKey = "whisper|$animeId|s$season|e$episode|" +
                        "${current.sourceName ?: current.url.substringBefore('?')}|${whisperLanguage.name}|$whisperRoute"
                    val cachedWhisper = remember(whisperKey, playerDurationMs) {
                        playerDurationMs.takeIf { it > 0 }?.let { whisperSubtitles.cached(whisperKey, it) }
                    }
                    val liveWhisper by remember(whisperKey) { whisperSubtitles.progress(whisperKey) }.collectAsState(initial = null)
                    val whisperProgress = liveWhisper ?: cachedWhisper
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
                        sourceSwitchNotice = next.sourceName ?: next.label
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
                                if (refreshed.sourceName != previous.sourceName) {
                                    sourceSwitchNotice = refreshed.sourceName ?: refreshed.label
                                }
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

                    // Прогресс — с того, что сейчас играет: телефона или телевизора.
                    LaunchedEffect(controlPlayer, animeId, season, episode) {
                        runPlaybackProgressSaver(isPlaying = { controlPlayer.isPlaying }) {
                            val duration = controlPlayer.duration
                            if (duration > 0L) {
                                playbackStore.saveProgress(
                                    animeId = animeId,
                                    season = season,
                                    episode = episode,
                                    positionMs = controlPlayer.currentPosition,
                                    durationMs = duration,
                                )
                            }
                        }
                    }

                    // Серия на ТВ закончилась — автопереход тем же путём, что кнопка «дальше».
                    DisposableEffect(remotePlayer, autoNext, availableEpisodes, episode, switchingTo) {
                        val listener = object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED &&
                                    remote.connection.value is RemoteConnection.Connected &&
                                    shouldAutoAdvance(
                                        enabled = autoNext,
                                        durationMs = remotePlayer.duration,
                                        hasNext = EpisodeRange.hasNext(episode, availableEpisodes),
                                        switching = switchingTo != null,
                                    )
                                ) {
                                    switchToEpisode(EpisodeRange.nextOf(episode, availableEpisodes))
                                }
                            }
                        }
                        remotePlayer.addListener(listener)
                        onDispose { remotePlayer.removeListener(listener) }
                    }

                    // Сторож просыпается только на время буферизации: слушатель плеера говорит, когда
                    // она началась и кончилась, а 4 опроса в секунду остаются лишь внутри неё.
                    LaunchedEffect(player, current.url) {
                        player.stallFlow().collectLatest { stalled ->
                            if (!stalled) {
                                bufferingWatchdog.sample(SystemClock.elapsedRealtime(), buffering = false)
                                return@collectLatest
                            }
                            while (isActive) {
                                if (
                                    bufferingWatchdog.sample(SystemClock.elapsedRealtime(), buffering = true) &&
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
                    }

                    DisposableEffect(player, current.url, current.resolvedAt) {
                        // Old listener is disposed before this replacement effect starts, so clearing
                        // an owner from another player cannot reopen the old callback race.
                        if (recoveryOwner !== player) recoveryOwner = null
                        val listenerSessionUrl = current.url
                        val listenerSessionResolvedAt = current.resolvedAt
                        activePlayer = player
                        // Рейтинг источников: сессия на этой ссылке меряется зондом и приписывается провайдеру,
                        // а озвучка считается сохранённой, если после смены источника она осталась той же или близкой.
                        val sessionKey = "$animeId|$season|$episode"
                        val switched = lastSessionKey == sessionKey && lastSessionUrl != null && lastSessionUrl != current.url
                        val dubKept = if (switched && lastSessionStudio != null && current.sourceName != null) {
                            dubSimilarity(lastSessionStudio, current.sourceName) >= 2
                        } else null
                        lastSessionKey = sessionKey
                        lastSessionUrl = current.url
                        lastSessionStudio = current.sourceName
                        val qualityProbe = com.example.myapplication.media.intelligence.PlaybackQualityProbe(dubKept)
                        val probeProvider = current.providerKey
                        val probeLanguage = uiLanguage
                        player.addListener(qualityProbe)
                        val mediaSession = VideoMediaSession(
                            context = this@StreamPlayerActivity,
                            player = player,
                            id = "vetro-stream-${System.currentTimeMillis()}",
                            placeholderIcon = if (playbackIdentity.mediaType == MediaType.MOVIE) {
                                com.phnem.vetro.R.drawable.ph_film_slate_fill
                            } else {
                                com.phnem.vetro.R.drawable.ph_television_simple_fill
                            },
                            contentIntent = videoPlayerContentIntent(this@StreamPlayerActivity),
                        )
                        videoSession = mediaSession
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
                            player.removeListener(qualityProbe)
                            qualityProbe.finish()?.let { sample ->
                                if (probeProvider != null) {
                                    appScope.launch {
                                        runCatching { sourceIntelligence.recordSession(probeProvider, probeLanguage, sample) }
                                    }
                                }
                            }
                            if (videoSession === mediaSession) videoSession = null
                            mediaSession.release()
                            if (activePlayer === player) activePlayer = null
                            player.release()
                        }
                    }

                    LaunchedEffect(videoSession, episode, switchingTo, posterUri) {
                        val canSwitch = switchingTo == null
                        videoSession?.update(
                            title = animeTitle,
                            line = VideoMediaSession.line(
                                season = season,
                                episode = episode,
                                isMovie = playbackIdentity.mediaType == MediaType.MOVIE,
                            ),
                            artwork = posterUri,
                            hasPrevious = canSwitch && EpisodeRange.hasPrevious(episode),
                            hasNext = canSwitch && EpisodeRange.hasNext(episode, availableEpisodes),
                            onPrevious = { switchToEpisode(EpisodeRange.previousOf(episode)) },
                            onNext = { switchToEpisode(EpisodeRange.nextOf(episode, availableEpisodes)) },
                        )
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
                            // Один список на набор кандидатов: новый список на каждой рекомпозиции
                            // сбрасывал remember дорожек озвучки в поверхности плеера.
                            renditions = remember(candidates, failedRenditionUrls, current) {
                                selectStudioRenditions(
                                    candidates.filterNot { it.url in failedRenditionUrls },
                                    current,
                                )
                            },
                            onSelectRendition = { rendition ->
                                preferredSourceName = rendition.sourceName ?: preferredSourceName
                                rendition.sourceName?.let { studio ->
                                    scope.launch { runCatching { playbackPrefs.rememberSource(animeId, contentType, studio) } }
                                }
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
                                    // Подгруженные субтитры переезжают на другую озвучку той же серии.
                                    current = rendition.copy(
                                        subtitles = rendition.subtitles + current.subtitles.filter { it.isUserAdded },
                                        resolvedAt = System.currentTimeMillis(),
                                    )
                                }
                            },
                            title = "$animeTitle · ${if (season > 1) "S$season " else ""}E$episode",
                            episodeNumber = episode,
                            malId = seasonInfo?.malId ?: malId,
                            anilistId = seasonInfo?.anilistId ?: anilistId,
                            imdbId = playbackIdentity.imdbId,
                            seasonNumber = season,
                            isMovie = playbackIdentity.mediaType == MediaType.MOVIE,
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
                            loading = switchingTo != null || retrying || subtitleLoading,
                            openSubtitles = OpenSubtitlesUi(
                                configured = externalSubtitles.isAvailable,
                                offers = subtitleOffers,
                                searching = openSubtitlesSearching,
                            ),
                            whisper = WhisperUi(
                                engineAvailable = speechRouter.onDeviceAvailable,
                                cloudAvailable = cloudSpeech,
                                model = whisperModel,
                                modelState = modelStates[whisperModel] ?: ModelState.Absent,
                                recommended = recommendedModel,
                                language = whisperLanguage,
                                progress = whisperProgress,
                                showing = whisperShowing,
                                durationKnown = playerDurationMs > 0,
                            ),
                            whisperCues = whisperProgress?.cues.orEmpty(),
                            onHideWhisper = { whisperShowing = false },
                            onSubtitleMenuAction = { action ->
                                when (action) {
                                    is SubtitleMenuAction.DownloadModel -> {
                                        chosenModel = action.model
                                        whisperModels.download(action.model)
                                    }
                                    SubtitleMenuAction.CancelDownload -> whisperModels.cancel(whisperModel)
                                    SubtitleMenuAction.Generate -> (speechRouter.onDevice(whisperModel) ?: speechRouter.cloud())?.let { recognizer ->
                                        whisperSubtitles.start(
                                            WhisperRequest(
                                                key = whisperKey,
                                                mediaItem = StreamingPlaybackSessionFactory.buildMediaItem(current),
                                                sourceFactory = StreamingPlaybackSessionFactory.mediaSourceFactory(
                                                    this@StreamPlayerActivity, okHttpClient, current,
                                                ),
                                                durationMs = playerDurationMs,
                                                fromMs = player.currentPosition.coerceAtLeast(0L),
                                                language = whisperLanguage,
                                                recognizer = recognizer,
                                            ),
                                        )
                                        whisperShowing = true
                                    }
                                    SubtitleMenuAction.Stop -> whisperSubtitles.stop(whisperKey)
                                    SubtitleMenuAction.Show -> whisperShowing = true
                                    SubtitleMenuAction.CycleLanguage -> {
                                        whisperLanguage = WhisperLanguage.entries[(whisperLanguage.ordinal + 1) % WhisperLanguage.entries.size]
                                    }
                                    SubtitleMenuAction.DeleteModel -> {
                                        whisperSubtitles.stop(whisperKey)
                                        whisperModels.delete(whisperModel)
                                        chosenModel = null
                                        whisperShowing = false
                                    }
                                    is SubtitleMenuAction.Open, SubtitleMenuAction.None -> Unit
                                }
                            },
                            onAudioLanguageChosen = { language ->
                                scope.launch { runCatching { playbackPrefs.rememberAudio(animeId, contentType, language) } }
                            },
                            onSubtitlesChosen = { language, off ->
                                scope.launch { runCatching { playbackPrefs.rememberSubtitles(animeId, contentType, language, off) } }
                            },
                            onLoadSubtitle = { offer ->
                                if (!subtitleLoading) {
                                    subtitleLoading = true
                                    scope.launch {
                                        val result = externalSubtitles.load(offer)
                                        subtitleLoading = false
                                        when (result) {
                                            is SubtitleLoad.Loaded -> {
                                                resumePosition = player.currentPosition.coerceAtLeast(0L)
                                                pendingSubtitleId = result.track.id
                                                current = current.copy(
                                                    subtitles = current.subtitles + result.track,
                                                    resolvedAt = System.currentTimeMillis(),
                                                )
                                            }
                                            else -> Toast.makeText(
                                                this@StreamPlayerActivity,
                                                subtitleLoadMessage(result, uiLanguage == AppLanguage.RU),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                }
                            },
                            pendingSubtitleId = pendingSubtitleId,
                            onPendingSubtitleApplied = { pendingSubtitleId = null },
                            remote = (remoteConnection as? RemoteConnection.Connected)?.let { c ->
                                RemoteSurface(
                                    player = remotePlayer,
                                    deviceName = c.device.name,
                                    status = when (remotePlayback.status) {
                                        RemoteStatus.LOADING, RemoteStatus.BUFFERING -> if (ruRemote) "Загрузка…" else "Loading…"
                                        RemoteStatus.PAUSED -> if (ruRemote) "Пауза" else "Paused"
                                        RemoteStatus.ENDED -> if (ruRemote) "Серия закончилась" else "Finished"
                                        else -> c.service.protocol.label
                                    },
                                )
                            },
                            remoteButton = when (remoteConnection) {
                                is RemoteConnection.Connected -> RemoteButtonState.CONNECTED
                                is RemoteConnection.Connecting -> RemoteButtonState.CONNECTING
                                is RemoteConnection.Failed -> RemoteButtonState.ERROR
                                RemoteConnection.Idle -> if (devicesPanelOpen) RemoteButtonState.DISCOVERING else RemoteButtonState.IDLE
                            },
                            // Нажатие всегда открывает список; долгое — тоже, но с последним устройством
                            // первой строкой. Само не подключается никогда.
                            onRemote = { devicesPanel.targetState = true },
                            onRemoteLongPress = { devicesPanel.targetState = true },
                        )

                        if (devicesPanel.targetState || devicesPanel.currentState || !devicesPanel.isIdle) {
                            val suggested = remember(remoteDevices) { remote.onlineLastDevice() }
                            RemoteDevicesPanel(
                                state = devicesPanel,
                                devices = remoteDevices,
                                connection = remoteConnection,
                                hasLocalNetwork = remember(remoteDevices, devicesPanelOpen) { remote.hasLocalNetwork() },
                                suggested = suggested,
                                onPhone = ::returnToPhone,
                                onDevice = ::castTo,
                                onDismiss = {
                                    devicesPanel.targetState = false
                                    remote.clearError()
                                },
                            )
                        }

                        sourceSwitchNotice?.let { name ->
                            LaunchedEffect(name, current.url) {
                                kotlinx.coroutines.delay(SOURCE_NOTICE_MS)
                                sourceSwitchNotice = null
                            }
                            val ru = playerSettingsState.value.language == com.example.myapplication.network.AppLanguage.RU
                            Text(
                                text = (if (ru) "Источник переключён · " else "Source switched · ") + name,
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(top = 16.dp)
                                    .background(Color.Black.copy(alpha = 0.66f), androidx.compose.foundation.shape.CircleShape)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }

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
    }

    /** Кнопки громкости во время показа на ТВ меняют громкость телевизора. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val connected = remote.connection.value is RemoteConnection.Connected
        if (connected && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val step = if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 0.05f else -0.05f
            val volume = ((remote.playback.value.volume ?: 0.5f) + step).coerceIn(0f, 1f)
            lifecycleScope.launch { runCatching { remote.setVolume(volume) } }
            return true
        }
        return super.onKeyDown(keyCode, event)
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
        val player = remoteControl ?: activePlayer ?: return
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

    /** Свежая ссылка на ту же серию: сначала та же или самая похожая озвучка (см. dubSimilarity). */
    private suspend fun resolveReplacement(
        resolver: EpisodeStreamResolver,
        episode: Int,
        previous: VetroVideo,
        excludedUrls: Set<String>,
    ): VetroVideo? = resolver
        .resolve(episode, previous.resolution ?: DEFAULT_RESOLUTION)
        .filter { it.url !in excludedUrls }
        .withIndex()
        .maxWithOrNull(
            compareBy<IndexedValue<VetroVideo>> { dubSimilarity(previous.sourceName, it.value.sourceName) }
                .thenByDescending { it.index },
        )
        ?.value

    companion object {
        private const val TAG = "StreamPlayer"
        private const val WATCHDOG_BUFFERING_MS = 8_000L
        private const val WATCHDOG_POLL_MS = 250L
        /** Сколько висит плашка «Источник переключён». */
        private const val SOURCE_NOTICE_MS = 3_500L
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
/** Модель Whisper по силам устройства: память, ядра, 64-битный ARM. */
private fun Activity.recommendedWhisperModel(): WhisperModel {
    val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    return WhisperDeviceProfile.recommended(
        totalRamBytes = memory.totalMem,
        cores = Runtime.getRuntime().availableProcessors(),
        arm64 = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a"),
    )
}

private fun subtitleLoadMessage(result: SubtitleLoad, ru: Boolean): String = when (result) {
    SubtitleLoad.AccountRejected -> if (ru) {
        "OpenSubtitles не принял логин — проверьте аккаунт в настройках источников"
    } else {
        "OpenSubtitles rejected the login — check the account in source settings"
    }
    SubtitleLoad.QuotaExhausted -> if (ru) {
        "Суточный лимит скачиваний OpenSubtitles исчерпан"
    } else {
        "OpenSubtitles daily download limit reached"
    }
    else -> if (ru) "Не удалось загрузить субтитры" else "Couldn't load subtitles"
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

/** Стоит ли плеер в буферизации, желая играть. Отдаёт значение сразу и затем — только изменения. */
private fun Player.stallFlow(): Flow<Boolean> = callbackFlow {
    fun publish() {
        trySend(playWhenReady && playbackState == Player.STATE_BUFFERING)
    }
    val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }
    publish()
    addListener(listener)
    awaitClose { removeListener(listener) }
}.distinctUntilChanged()
