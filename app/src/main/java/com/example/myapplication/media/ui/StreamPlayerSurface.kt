package com.example.myapplication.media.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.example.myapplication.media.subtitles.OpenSubtitlesUi
import com.example.myapplication.media.subtitles.SubtitleMenuAction
import com.example.myapplication.media.subtitles.SubtitlePage
import com.example.myapplication.media.subtitles.WhisperUi
import com.example.myapplication.media.subtitles.subtitleMenu
import com.example.myapplication.media.subtitles.whisper.ModelState
import com.example.myapplication.media.subtitles.whisper.SubtitleCue
import com.example.myapplication.media.subtitles.whisper.WhisperLanguage
import com.example.myapplication.media.subtitles.whisper.WhisperModel
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.myapplication.localplayer.ui.AudioTrackOption
import com.example.myapplication.localplayer.ui.SubtitleOption
import com.example.myapplication.localplayer.ui.playerIsRu
import com.example.myapplication.media.source.VetroSubtitleTrack
import com.example.myapplication.media.subtitles.SubtitleOffer
import com.example.myapplication.localplayer.ui.ImmersivePlayerWindow
import com.example.myapplication.localplayer.ui.rememberMediaSkipPlayback
import com.example.myapplication.localplayer.ui.rememberPlaybackClock
import com.example.myapplication.localplayer.ui.PlayerControlsOverlay
import com.example.myapplication.ui.shared.loading.BubbleClusterLoader
import com.example.myapplication.localplayer.ui.PlayerPinchState
import com.example.myapplication.localplayer.ui.VideoFit
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.ui.shared.theme.MotionTokens
import kotlin.math.max

/** The custom Exo controls applied to a remote, header-aware player. */
@Composable
fun StreamPlayerSurface(
    player: ExoPlayer,
    video: VetroVideo,
    renditions: List<VetroVideo> = emptyList(),
    onSelectRendition: (VetroVideo) -> Unit = {},
    title: String,
    episodeNumber: Int,
    malId: Int?,
    anilistId: Int?,
    /** Для IntroDB у кино и сериалов; у аниме разметку дают AniSkip/Anime-Skip. */
    imdbId: String? = null,
    seasonNumber: Int? = null,
    isMovie: Boolean = false,
    autoSkipEnabled: Boolean,
    isInPip: Boolean,
    onEnterPip: () -> Unit,
    onRotate: () -> Unit,
    onBack: () -> Unit,
    /**
     * Есть ли соседняя серия ПО НОМЕРУ (`EpisodeRange`). Доступность её ссылки — другое состояние:
     * поверхность о ней не знает, резолв живёт в активности.
     */
    hasPrevEpisode: Boolean = false,
    hasNextEpisode: Boolean = false,
    onPrevEpisode: () -> Unit = {},
    onNextEpisode: () -> Unit = {},
    /** Идёт резолв ссылки или переключение серии; показывается локальный индикатор в контролах. */
    loading: Boolean = false,
    /** Раздел OpenSubtitles меню «Субтитры» (BYOK). */
    openSubtitles: OpenSubtitlesUi = OpenSubtitlesUi(configured = false, offers = emptyList(), searching = false),
    onLoadSubtitle: (SubtitleOffer) -> Unit = {},
    /** Пользователь выбрал звуковую дорожку этого языка — запомнить для тайтла и его типа. */
    onAudioLanguageChosen: (String) -> Unit = {},
    /** Пользователь выбрал субтитры (язык) или выключил их — запомнить для тайтла и его типа. */
    onSubtitlesChosen: (language: String?, off: Boolean) -> Unit = { _, _ -> },
    /** Раздел «Создать на устройстве» (Whisper) и готовые реплики для показа поверх кадра. */
    whisper: WhisperUi? = null,
    whisperCues: List<SubtitleCue> = emptyList(),
    onSubtitleMenuAction: (SubtitleMenuAction) -> Unit = {},
    onHideWhisper: () -> Unit = {},
    /** Id подгруженной дорожки: включить, как только она появится в плеере. */
    pendingSubtitleId: String? = null,
    onPendingSubtitleApplied: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var isBuffering by remember(player) { mutableStateOf(player.playbackState != Player.STATE_READY) }
    val showLoading = shouldShowStreamLoading(
        requestPending = loading,
        playerBuffering = isBuffering,
    )
    // Позицию поверхность не читает: см. PlaybackClock.
    val clock = rememberPlaybackClock(player, isPlaying)
    var embeddedAudioTracks by remember(player) {
        mutableStateOf<List<AudioTrackOption>>(emptyList())
    }
    var tracksSnapshot by remember(player) { mutableStateOf(player.currentTracks) }
    var speed by remember(player) { mutableStateOf(1f) }
    var fit by remember(player) { mutableStateOf(VideoFit.ORIGINAL) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var videoAspect by remember(player) { mutableFloatStateOf(16f / 9f) }
    val viewportAspect = viewportSize.width.toFloat() / viewportSize.height.coerceAtLeast(1)
    val cropScale = max(
        viewportAspect / videoAspect.coerceAtLeast(0.01f),
        videoAspect / viewportAspect.coerceAtLeast(0.01f),
    ).coerceIn(1f, 2.5f)
    val animatedVideoScale by animateFloatAsState(
        targetValue = if (fit == VideoFit.CROP) cropScale else 1f,
        animationSpec = MotionTokens.standard(),
        label = "streamVideoFitScale",
    )
    val studioTracks = remember(renditions, video.url) {
        renditions
            .filter { !it.sourceName.isNullOrBlank() }
            .distinctBy { it.sourceName }
            .takeIf { it.size > 1 }
            .orEmpty()
            // Колесо «озвучки»: сначала голоса, переводы-субтитры («… (субтитры)») — после. У свежей
            // серии их бывает большинство, и вперемешку колесо выглядело как меню субтитров.
            .sortedBy { it.sourceName.orEmpty().contains("субтитр", ignoreCase = true) || it.sourceName.orEmpty().contains("subtitles", ignoreCase = true) }
            .map { rendition ->
                AudioTrackOption(
                    id = "rendition:${rendition.url}",
                    label = rendition.sourceName.orEmpty(),
                    isSelected = rendition.url == video.url,
                    groupIndex = -1,
                    trackIndex = -1,
                    renditionUrl = rendition.url,
                )
            }
    }
    // A single embedded track adds no choice. Multiple embedded tracks remain available together
    // with whole-studio renditions supplied by separate providers.
    val audioTracks =
        studioTracks + embeddedAudioTracks.takeIf { it.size > 1 }.orEmpty()

    val ru = playerIsRu()
    var subtitlePage by remember { mutableStateOf(SubtitlePage.ROOT) }
    val textTracks = remember(tracksSnapshot, ru) { tracksSnapshot.textTracks(ru) }
    val whisperUi = whisper ?: WhisperUi(
        engineAvailable = false, model = WhisperModel.DEFAULT, modelState = ModelState.Absent,
        recommended = WhisperModel.DEFAULT, language = WhisperLanguage.AUTO, progress = null,
        showing = false, durationKnown = false,
    )
    val subtitleOptions = remember(subtitlePage, textTracks, openSubtitles, whisperUi, ru) {
        subtitleMenu(subtitlePage, textTracks.embedded, openSubtitles, whisperUi, ru).map { option ->
            // Уже подгруженное предложение OpenSubtitles выбирается как дорожка, без повторной загрузки.
            option.externalKey?.toLongOrNull()?.let(textTracks.userAdded::get) ?: option
        }
    }
    // Подгруженная дорожка появляется в плеере после пересборки источника — тогда и включаем её.
    LaunchedEffect(player, tracksSnapshot, pendingSubtitleId) {
        val pending = pendingSubtitleId ?: return@LaunchedEffect
        if (player.selectTextTrackById(pending)) onPendingSubtitleApplied()
    }

    val skipPlayback = rememberMediaSkipPlayback(
        player = player,
        mediaId = video.url,
        diagnosticEpisodeKey =
            "stream:${malId ?: anilistId ?: title.hashCode()}:$episodeNumber",
        episodeNumber = episodeNumber,
        anilistId = anilistId,
        malId = malId,
        durationMs = clock.durationMs,
        positionMs = clock.position,
        autoSkipEnabled = autoSkipEnabled,
        exactTimestamps = video.timestamps,
        exactOrigin = video.sourceName,
        reference = video.skipReference,
        imdbId = imdbId,
        seasonNumber = seasonNumber,
        isMovie = isMovie,
    )
    val activeSegment = skipPlayback.activeSegment

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onTracksChanged(tracks: Tracks) {
                embeddedAudioTracks = tracks.streamAudioOptions()
                tracksSnapshot = tracks
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    videoAspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Положение кадра переживает смену серии: поверхность не пересоздаётся (меняются только url и
    // номер), а разворачивать кадр заново на каждой серии — работа, которую пользователь уже
    // сделал. Под новое соотношение сторон масштаб пересчитывает cropScale.
    val pinchState = remember { PlayerPinchState() }

    ImmersivePlayerWindow(enabled = !isInPip)

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .graphicsLayer {
                    // Единственный источник масштаба — положение кадра. Щипок и кнопка в доке
                    // меняют его же, поэтому перемножать здесь больше нечего.
                    scaleX = animatedVideoScale
                    scaleY = animatedVideoScale
                },
            factory = { context ->
                // SurfaceView (значение surface_type по умолчанию) — не менять на TextureView:
                // тот стоил бы каждого кадра воспроизведения.
                PlayerView(context).apply {
                    this.player = player
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

                view.keepScreenOn = isPlaying
            },
        )

        if (whisperUi.showing) {
            WhisperCueOverlay(cues = whisperCues, position = clock.position, inPip = isInPip)
        }

        if (!isInPip) {
            PlayerControlsOverlay(
                player = player,
                title = title,
                onRotate = onRotate,
                isPlaying = isPlaying,
                isBuffering = showLoading,
                position = clock.position,
                buffered = clock.buffered,
                duration = clock.durationMs,
                hasPrev = hasPrevEpisode,
                hasNext = hasNextEpisode,
                onPrev = onPrevEpisode,
                onNext = onNextEpisode,
                pinchState = pinchState,
                audioTracks = audioTracks,
                speed = speed,
                fit = fit,
                skipVisible = !autoSkipEnabled && activeSegment != null,
                onSkip = skipPlayback.manualSkip,
                undoOffer = skipPlayback.undoOffer,
                onUndoSkip = skipPlayback.undoSkip,
                onBack = onBack,
                onEnterPip = onEnterPip,
                onSelectSpeed = {
                    speed = it
                    player.playbackParameters = PlaybackParameters(it)
                },
                onSelectAudio = { option ->
                    val rendition = option.renditionUrl
                        ?.let { url -> renditions.firstOrNull { it.url == url } }
                    if (rendition != null) {
                        onSelectRendition(rendition)
                    } else {
                        player.applyStreamAudioOverride(option)
                        player.trackLanguage(option.groupIndex, option.trackIndex)?.let(onAudioLanguageChosen)
                    }
                },
                subtitleOptions = subtitleOptions,
                onSelectSubtitle = { option ->
                    val action = SubtitleMenuAction.decode(option.action)
                    when {
                        action is SubtitleMenuAction.Open -> subtitlePage = action.page
                        action == SubtitleMenuAction.None -> Unit
                        action != null -> {
                            onSubtitleMenuAction(action)
                            // Показ реплик Whisper — вместо дорожек плеера, не поверх них.
                            if (action == SubtitleMenuAction.Show) player.disableTextTracks()
                        }
                        option.isOff -> {
                            player.disableTextTracks()
                            onHideWhisper()
                            onSubtitlesChosen(null, true)
                        }
                        option.externalKey != null -> {
                            openSubtitles.offers.firstOrNull { it.fileId.toString() == option.externalKey }?.let(onLoadSubtitle)
                            onHideWhisper()
                        }
                        else -> {
                            player.selectTextTrack(option.groupIndex, option.trackIndex)
                            onHideWhisper()
                            onSubtitlesChosen(player.trackLanguage(option.groupIndex, option.trackIndex), false)
                        }
                    }
                    if (!option.keepsMenuOpen) subtitlePage = SubtitlePage.ROOT
                },
                onSetFit = { fit = it },
                onSubtitlesMenuOpened = { subtitlePage = SubtitlePage.ROOT },
            )
        }

        // В PiP весь Compose-слой управления не композится, а вместе с ним пропадал бы и
        // индикатор: окно с замершим кадром неотличимо от зависшего плеера. Доки, кнопки и жесты
        // в PiP по-прежнему не появляются — только индикатор, и компактнее обычного.
        if (isInPip && showLoading) {
            BubbleClusterLoader(
                modifier = Modifier.align(Alignment.Center).size(28.dp),
                color = Color.White,
            )
        }
    }
}

private fun Tracks.streamAudioOptions(): List<AudioTrackOption> {
    val result = ArrayList<AudioTrackOption>()
    groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
        for (trackIndex in 0 until group.length) {
            if (!group.isTrackSupported(trackIndex)) continue
            val format = group.getTrackFormat(trackIndex)
            val label = format.label
                ?: format.language?.let {
                    java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it }
                }
                ?: "Track ${result.size + 1}"
            result += AudioTrackOption(
                id = "$groupIndex:$trackIndex",
                label = label.replaceFirstChar { it.uppercase() },
                isSelected = group.isTrackSelected(trackIndex),
                groupIndex = groupIndex,
                trackIndex = trackIndex,
            )
        }
    }
    return result
}

/** Текстовые дорожки плеера: пришедшие с видео и подгруженные пользователем (по file_id OpenSubtitles). */
private class TextTracks(val embedded: List<SubtitleOption>, val userAdded: Map<Long, SubtitleOption>)

private fun Tracks.textTracks(ru: Boolean): TextTracks {
    val embedded = ArrayList<SubtitleOption>()
    val userAdded = HashMap<Long, SubtitleOption>()
    groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
        for (trackIndex in 0 until group.length) {
            if (!group.isTrackSupported(trackIndex)) continue
            val format = group.getTrackFormat(trackIndex)
            val label = format.label
                ?: format.language?.let {
                    java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it }
                }
                ?: if (ru) "Дорожка ${embedded.size + 1}" else "Track ${embedded.size + 1}"
            val option = SubtitleOption(
                id = "$groupIndex:$trackIndex",
                label = label.replaceFirstChar { it.uppercase() },
                isSelected = group.isTrackSelected(trackIndex),
                groupIndex = groupIndex,
                trackIndex = trackIndex,
            )
            val fileId = format.id?.substringAfter(VetroSubtitleTrack.USER_ADDED_PREFIX, "")?.takeWhile(Char::isDigit)?.toLongOrNull()
            if (fileId != null) userAdded[fileId] = option else embedded += option
        }
    }
    return TextTracks(embedded, userAdded)
}

private fun ExoPlayer.disableTextTracks() {
    trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
}

/**
 * Реплики Whisper поверх кадра: распознанное появляется по мере готовности, без пересборки источника.
 * Вид — как у субтитров плеера: белый текст на полупрозрачной подложке у нижнего края.
 */
@Composable
private fun BoxScope.WhisperCueOverlay(cues: List<SubtitleCue>, position: () -> Long, inPip: Boolean) {
    val text by remember(cues) {
        derivedStateOf {
            val p = position()
            cues.lastOrNull { it.startMs <= p && p < it.endMs }?.text?.trim()
        }
    }
    val current = text ?: return
    Text(
        text = current,
        color = Color.White,
        fontSize = if (inPip) 10.sp else 17.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 48.dp, vertical = if (inPip) 6.dp else 36.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Язык дорожки (как его отдаёт контейнер: «ru», «eng», «ja»); null — не указан. */
private fun ExoPlayer.trackLanguage(groupIndex: Int, trackIndex: Int): String? =
    currentTracks.groups.getOrNull(groupIndex)
        ?.takeIf { trackIndex in 0 until it.length }
        ?.getTrackFormat(trackIndex)
        ?.language
        ?.takeIf { it.isNotBlank() && it != "und" }

private fun ExoPlayer.selectTextTrack(groupIndex: Int, trackIndex: Int) {
    val group = currentTracks.groups.getOrNull(groupIndex) ?: return
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)))
        .build()
}

/** Включить текстовую дорожку по id формата; false — её в плеере ещё нет. */
private fun ExoPlayer.selectTextTrackById(id: String): Boolean {
    currentTracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
        for (trackIndex in 0 until group.length) {
            if (group.getTrackFormat(trackIndex).id?.contains(id) == true) {
                selectTextTrack(groupIndex, trackIndex)
                return true
            }
        }
    }
    return false
}

private fun ExoPlayer.applyStreamAudioOverride(option: AudioTrackOption) {
    val group = currentTracks.groups.getOrNull(option.groupIndex) ?: return
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setOverrideForType(
            TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex))
        )
        .build()
}
