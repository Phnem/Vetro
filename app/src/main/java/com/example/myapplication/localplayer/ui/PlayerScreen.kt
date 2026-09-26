package com.example.myapplication.localplayer.ui

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.myapplication.localplayer.model.LocalEpisode
import com.example.myapplication.media.download.DownloadedEpisodeSkip
import com.example.myapplication.media.download.DownloadedSkipStore
import com.example.myapplication.media.ui.PipHostActivity
import com.example.myapplication.media.ui.PipPlaybackCommands
import com.example.myapplication.ui.shared.theme.MotionTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Один вариант аудиодорожки для выбора (например «Русский», «English», «Japanese»). */
data class AudioTrackOption(
    val id: String,
    val label: String,
    val isSelected: Boolean,
    val groupIndex: Int,
    val trackIndex: Int,
    val renditionUrl: String? = null,
)

/**
 * Пункт меню субтитров: выключить, встроенная дорожка ([groupIndex]/[trackIndex]) или внешняя, которую
 * ещё надо скачать ([externalKey]).
 */
data class SubtitleOption(
    val id: String,
    val label: String,
    val isSelected: Boolean,
    val groupIndex: Int = -1,
    val trackIndex: Int = -1,
    val externalKey: String? = null,
    val isOff: Boolean = false,
)

/** Режим кадра: как есть (letterbox, обычно 16:9) или с обрезкой краёв (заполнить экран). */
enum class VideoFit { ORIGINAL, CROP }

/**
 * Полноэкранный проигрыватель на androidx.media3 (ExoPlayer) с полностью кастомным скином:
 * плоские доки + белая полоса с оранжевым бегунком (см. [PlayerControlsOverlay]).
 * Стандартный контроллер [PlayerView] отключён (`useController = false`) — все кнопки наши,
 * никаких дефолтных +5/−5.
 *
 * Играет строго локальные `content://` из выбранной пользователем папки. Сетевого стриминга нет.
 */
@Composable
fun PlayerScreen(
    episodes: List<LocalEpisode>,
    startIndex: Int,
    malId: Int?,
    anilistId: Int?,
    autoSkipEnabled: Boolean,
    /**
     * Включать следующую серию по концу текущей. У локального плеера серии лежат плейлистом в
     * ExoPlayer, и переход по концу — его штатное поведение; настройка его не воспроизводит, а
     * гасит, иначе получилось бы два независимых механизма перехода на одну кнопку.
     */
    autoNextEnabled: Boolean = true,
    isInPip: Boolean,
    onEnterPip: () -> Unit,
    onRotate: () -> Unit,
    onBack: () -> Unit,
    onPlayerAvailable: (ExoPlayer?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
            val items = episodes.map { MediaItem.fromUri(Uri.parse(it.documentUri)) }
            setMediaItems(items, startIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0)), 0L)
            playWhenReady = true
            prepare()
        }
    }

    // ——— Наблюдаемое состояние плеера ———
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    // Позицию хост не читает: см. PlaybackClock.
    val clock = rememberPlaybackClock(exoPlayer, isPlaying)
    var currentIndex by remember { mutableIntStateOf(startIndex.coerceAtLeast(0)) }

    val pinchState = remember { PlayerPinchState() }
    var audioTracks by remember { mutableStateOf<List<AudioTrackOption>>(emptyList()) }
    // Метка выбранной пользователем дорожки — переживает переход между сериями (весь сезон живёт
    // одним плейлистом в одном ExoPlayer, см. класс-комментарий), поэтому запоминаем её здесь, а
    // не в EpisodePlaybackStore: до кросс-сессионного хранения это не требование тикета.
    var preferredAudioLabel by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableStateOf(1f) }
    // Положение кадра переживает переключение серии: сезон смотрят одним подряд, и разворачивать
    // кадр заново на каждой серии — работа, которую пользователь уже сделал. Пересчёт под новое
    // соотношение сторон делает cropScale ниже, так что перенос выбора безопасен и для серий с
    // другой геометрией.
    var fit by remember { mutableStateOf(VideoFit.ORIGINAL) }
    androidx.compose.runtime.LaunchedEffect(autoNextEnabled) {
        // Выключенная настройка = остановиться на последнем кадре серии, а не поехать дальше по
        // плейлисту. Кнопка «дальше» при этом продолжает работать.
        exoPlayer.pauseAtEndOfMediaItems = !autoNextEnabled
    }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var videoAspect by remember { mutableFloatStateOf(16f / 9f) }
    val viewportAspect = viewportSize.width.toFloat() / viewportSize.height.coerceAtLeast(1)
    val cropScale = max(
        viewportAspect / videoAspect.coerceAtLeast(0.01f),
        videoAspect / viewportAspect.coerceAtLeast(0.01f),
    ).coerceIn(1f, 2.5f)
    val animatedVideoScale by animateFloatAsState(
        targetValue = if (fit == VideoFit.CROP) cropScale else 1f,
        animationSpec = MotionTokens.standard(),
        label = "localVideoFitScale",
    )
    var playbackError by remember { mutableStateOf<String?>(null) }

    val currentEpisode = episodes.getOrNull(currentIndex)
    val currentMediaId = currentEpisode?.documentUri.orEmpty()
    // Тайминги, сохранённые рядом с серией при скачивании (jut.su/AniLiberty). Без них локальному
    // плееру остаётся только сетевой AniSkip, то есть офлайн — ничего.
    val storedSkip by produceState<DownloadedEpisodeSkip?>(null, currentMediaId) {
        value = withContext(Dispatchers.IO) { DownloadedSkipStore.loadFor(currentMediaId) }
    }
    val skipPlayback = rememberMediaSkipPlayback(
        player = exoPlayer,
        mediaId = currentMediaId,
        diagnosticEpisodeKey = "local:$currentMediaId",
        episodeNumber = currentEpisode?.episodeNumber,
        anilistId = anilistId,
        malId = malId,
        durationMs = clock.durationMs,
        positionMs = clock.position,
        autoSkipEnabled = autoSkipEnabled,
        exactTimestamps = storedSkip?.timestamps.orEmpty(),
        exactOrigin = storedSkip?.origin,
        reference = storedSkip?.reference,
    )
    val activeSegment = skipPlayback.activeSegment

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) playbackError = null
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("LocalPlayer", "Playback failed: ${error.errorCodeName}", error)
                isBuffering = false
                playbackError = "Файл повреждён или имеет неподдерживаемый формат"
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = exoPlayer.currentMediaItemIndex
            }

            override fun onTracksChanged(tracks: Tracks) {
                audioTracks = tracks.extractAudioOptions()
                // Новая серия ExoPlayer подбирает дорожку сама — если пользователь уже выбирал
                // дорожку раньше в этом сеансе, переносим её выбор на серию, если такая есть.
                matchPreferredAudioTrack(preferredAudioLabel, audioTracks)?.let { match ->
                    exoPlayer.applyAudioOverride(match)
                }
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    videoAspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            }
        }
        onPlayerAvailable(exoPlayer)
        exoPlayer.addListener(listener)
        onDispose {
            onPlayerAvailable(null)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Media session: аппаратные/BT-кнопки (пауза, next/prev) маршрутизируются в плеер.
    DisposableEffect(exoPlayer) {
        val session = androidx.media3.session.MediaSession.Builder(context, exoPlayer)
            .setId("vetro-local-player-${System.currentTimeMillis()}")
            .build()
        onDispose { session.release() }
    }

    // PiP: в окне «картинка в картинке» наш оверлей скрыт, поэтому перемотку по сериям и паузу
    // рисует система — по этому описанию состояния.
    val pipHost = remember(context) {
        var candidate: android.content.Context? = context
        var found: PipHostActivity? = null
        while (candidate is android.content.ContextWrapper) {
            if (candidate is PipHostActivity) { found = candidate; break }
            candidate = candidate.baseContext
        }
        found
    }
    androidx.compose.runtime.LaunchedEffect(
        pipHost,
        exoPlayer,
        isPlaying,
        currentIndex,
        episodes.size,
    ) {
        pipHost?.updatePipCommands(
            PipPlaybackCommands(
                isPlaying = isPlaying,
                hasPrevious = currentIndex > 0,
                hasNext = currentIndex < episodes.lastIndex,
                // Кнопка «следующая» в PiP значит «включи следующую»: при выключенном автопереходе
                // плеер остановлен на последнем кадре, и одна перемотка оставила бы его на паузе.
                onPrevious = { exoPlayer.seekToPreviousMediaItem(); exoPlayer.play() },
                onPlayPause = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                onNext = { exoPlayer.seekToNextMediaItem(); exoPlayer.play() },
            )
        )
    }
    // Уход с экрана плеера снимает кнопки: Activity живёт дольше, и осиротевшие команды дёргали бы
    // отпущенный ExoPlayer.
    DisposableEffect(pipHost) {
        onDispose { pipHost?.updatePipCommands(null) }
    }

    // Полный экран: статус-бар и навигация уезжают, пока виден кадр (в PiP окно и так без баров).
    ImmersivePlayerWindow(enabled = !isInPip)

    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
    ) {
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
            factory = { ctx ->
                // surface_type по умолчанию = SurfaceView: дешевле по батарее и композитингу, чем
                // TextureView.
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

                // Пока идёт воспроизведение — не даём экрану гаснуть/затемняться.
                view.keepScreenOn = isPlaying
            },
        )

        playbackError?.let { message ->
            androidx.compose.material3.Text(
                text = message,
                color = Color.White,
                modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            )
        }

        // В режиме «картинка в картинке» все контролы прячем — остаётся только видео.
        if (!isInPip) {
            PlayerControlsOverlay(
                player = exoPlayer,
                title = episodes.getOrNull(currentIndex)?.originalName.orEmpty(),
                onRotate = onRotate,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                position = clock.position,
                buffered = clock.buffered,
                duration = clock.durationMs,
                hasPrev = currentIndex > 0,
                hasNext = currentIndex < episodes.lastIndex,
                pinchState = pinchState,
                audioTracks = audioTracks,
                speed = speed,
                fit = fit,
                // Кнопку показываем только когда автоскип ВЫКЛ и мы внутри отрезка опенинга/эндинга.
                skipVisible = !autoSkipEnabled && activeSegment != null,
                onSkip = skipPlayback.manualSkip,
                undoOffer = skipPlayback.undoOffer,
                onUndoSkip = skipPlayback.undoSkip,
                onBack = onBack,
                onEnterPip = onEnterPip,
                onSelectSpeed = { s -> speed = s; exoPlayer.playbackParameters = PlaybackParameters(s) },
                onSelectAudio = { opt ->
                    preferredAudioLabel = opt.label
                    exoPlayer.applyAudioOverride(opt)
                },
                onSetFit = { fit = it },
            )
        }
    }
}

/**
 * Дорожка новой серии, соответствующая ранее выбранной пользователем (по [label][AudioTrackOption.label]
 * — единственный устойчивый признак дорожки между сериями, `groupIndex`/`trackIndex` per-episode).
 * `null`, если выбора не было, подходящей дорожки нет, либо она и так уже выбрана.
 */
internal fun matchPreferredAudioTrack(
    preferredLabel: String?,
    options: List<AudioTrackOption>,
): AudioTrackOption? {
    if (preferredLabel == null) return null
    return options.firstOrNull { it.label == preferredLabel && !it.isSelected }
}

/** Достаёт список аудиодорожек из [Tracks] для меню выбора. */
private fun Tracks.extractAudioOptions(): List<AudioTrackOption> {
    val out = ArrayList<AudioTrackOption>()
    groups.forEachIndexed { gIndex, group ->
        if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
        for (t in 0 until group.length) {
            if (!group.isTrackSupported(t)) continue
            val format = group.getTrackFormat(t)
            val label = format.label
                ?: format.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
                ?: "Track ${out.size + 1}"
            out += AudioTrackOption(
                id = "$gIndex:$t",
                label = label.replaceFirstChar { it.uppercase() },
                isSelected = group.isTrackSelected(t),
                groupIndex = gIndex,
                trackIndex = t,
            )
        }
    }
    return out
}

/** Применяет выбор аудиодорожки через override параметров трек-селектора. */
private fun ExoPlayer.applyAudioOverride(option: AudioTrackOption) {
    val group = currentTracks.groups.getOrNull(option.groupIndex) ?: return
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setOverrideForType(
            TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex)),
        )
        .build()
}
