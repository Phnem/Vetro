package com.example.myapplication.media.ui

import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
                    }
                },
                onSetFit = { fit = it },
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

private fun ExoPlayer.applyStreamAudioOverride(option: AudioTrackOption) {
    val group = currentTracks.groups.getOrNull(option.groupIndex) ?: return
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setOverrideForType(
            TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex))
        )
        .build()
}
