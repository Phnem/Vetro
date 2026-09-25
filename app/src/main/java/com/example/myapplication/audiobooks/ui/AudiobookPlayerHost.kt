package com.example.myapplication.audiobooks.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import com.example.myapplication.audiobooks.AudiobookFeatureGate
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline
import com.example.myapplication.audiobooks.playback.AudiobookPlaybackService
import com.example.myapplication.audiobooks.playback.AudiobookSessionCommands
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.phnem.vetro.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** One UI owner for the Media3 session. The service remains the playback owner in background. */
@Composable
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
fun AudiobookPlayerHost(language: AppLanguage = AppLanguage.RU, modifier: Modifier = Modifier) {
    val gate: AudiobookFeatureGate = koinInject()
    if (!gate.enabled && !BuildConfig.DEBUG) return

    val context = LocalContext.current.applicationContext
    val resolver: ManifestResolver = koinInject()
    val strings = getAudiobookStrings(language)
    val scope = rememberCoroutineScope()
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playing by remember { mutableStateOf<PlayingBook?>(null) }
    val position = remember { mutableLongStateOf(0L) }
    val sleepRemaining = remember { mutableLongStateOf(-1L) }
    var skipSilence by remember { mutableStateOf(false) }
    var timeline by remember { mutableStateOf<BookTimeline?>(null) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    val expansion = remember { Animatable(0f) }

    LaunchedEffect(context) {
        val token = SessionToken(context, ComponentName(context, AudiobookPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        var connected: MediaController? = null
        var listener: Player.Listener? = null
        try {
            val sessionController = withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
            connected = sessionController
            controller = sessionController
            sessionController.snapshot().let { current ->
                position.longValue = current?.positionMs ?: 0L
                playing = current?.copy(positionMs = 0L)
            }
            sleepRemaining.longValue = sessionController.sessionExtras.getLong(AudiobookSessionCommands.REMAINING_MS, -1L)
            skipSilence = sessionController.sessionExtras.getBoolean(AudiobookSessionCommands.SKIP_SILENCE, false)
            listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    sessionController.snapshot().let { current ->
                        position.longValue = current?.positionMs ?: 0L
                        playing = current?.copy(positionMs = 0L)
                    }
                }
            }
            sessionController.addListener(listener)
            while (isActive) {
                sessionController.snapshot().let { current ->
                    position.longValue = current?.positionMs ?: 0L
                    val structural = current?.copy(positionMs = 0L)
                    if (playing != structural) playing = structural
                }
                sleepRemaining.longValue = sessionController.sessionExtras.getLong(AudiobookSessionCommands.REMAINING_MS, -1L)
                skipSilence = sessionController.sessionExtras.getBoolean(AudiobookSessionCommands.SKIP_SILENCE, false)
                delay(when {
                    playing == null -> 400L
                    expansion.value < 0.01f -> 1_000L
                    else -> 250L
                })
            }
        } finally {
            if (connected != null && listener != null) connected.removeListener(listener)
            controller = null
            MediaController.releaseFuture(future)
        }
    }

    val book = playing
    LaunchedEffect(book?.mediaId) {
        timeline = book?.uri?.let(TrackUriCodec::decode)?.let { ref ->
            runCatching { BookTimeline(resolver.manifest(ref.variant).tracks, resolver.manifest(ref.variant).chapters) }
                .getOrNull()
        }
    }
    if (book == null || controller == null) return

    val isFull by remember { derivedStateOf { expansion.value > 0.5f } }
    BackHandler(enabled = isFull || sheet != null) {
        if (sheet != null) sheet = null else scope.launch { expansion.animateTo(0f, MotionTokens.largeSurfaceExit()) }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val cardPx = with(density) { 164.dp.toPx() }
        val marginPx = with(density) { 16.dp.toPx() }
        val dockPx = with(density) { 102.dp.toPx() }
        val top = WindowInsets.statusBars.getTop(density).toFloat() + marginPx
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val right = (widthPx - cardPx - marginPx).coerceAtLeast(marginPx)
        val bottom = (heightPx - cardPx - dockPx).coerceAtLeast(marginPx)
        val miniOffset = remember(widthPx, heightPx) { Animatable(Offset(right, bottom), Offset.VectorConverter) }
        FullAudiobookPlayer(
            book = book,
            timeline = timeline,
            position = position,
            strings = strings,
            controller = controller!!,
            interactive = isFull,
            onCollapse = { scope.launch { expansion.animateTo(0f, MotionTokens.largeSurfaceExit()) } },
            onShowChapters = { sheet = PlayerSheet.CHAPTERS },
            onShowSpeed = { sheet = PlayerSheet.SPEED },
            onShowTimer = { sheet = PlayerSheet.TIMER },
            sleepRemainingMs = sleepRemaining.longValue,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val progress = expansion.value.coerceIn(0f, 1f)
                val miniLeft = miniOffset.value.x
                val miniTop = miniOffset.value.y
                val miniWidth = cardPx
                val miniHeight = cardPx
                val shellWidth = miniWidth + (size.width - miniWidth) * progress
                val shellHeight = miniHeight + (size.height - miniHeight) * progress
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = shellWidth / size.width
                scaleY = shellHeight / size.height
                translationX = miniLeft * (1f - progress)
                translationY = miniTop * (1f - progress)
                clip = true
                shape = RoundedCornerShape((28f * (1f - progress)).dp)
                alpha = (progress / 0.32f).coerceIn(0f, 1f)
            },
        )
        if (expansion.value < 0.99f) {
            MiniAudiobookPlayer(
                book = book,
                timeline = timeline,
                position = position,
                strings = strings,
                onExpand = { scope.launch { expansion.animateTo(1f, MotionTokens.largeSurfaceEnter()) } },
                onPlayPause = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                onBack = { controller?.seekBack() },
                onForward = { controller?.seekForward() },
                onDrag = { delta ->
                    scope.launch {
                        miniOffset.snapTo(Offset(
                            (miniOffset.value.x + delta.x).coerceIn(marginPx, right),
                            (miniOffset.value.y + delta.y).coerceIn(top, bottom),
                        ))
                    }
                },
                onDragEnd = {
                    val target = Offset(
                        if (miniOffset.value.x + cardPx / 2 < widthPx / 2) marginPx else right,
                        if (miniOffset.value.y + cardPx / 2 < heightPx / 2) top else bottom,
                    )
                    scope.launch { miniOffset.animateTo(target, MotionTokens.largeSurfaceEnter()) }
                },
                modifier = Modifier
                    .offset { IntOffset(miniOffset.value.x.toInt(), miniOffset.value.y.toInt()) }
                    .graphicsLayer { alpha = (1f - expansion.value / 0.7f).coerceIn(0f, 1f) },
            )
        }
    }

    if (sheet != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            when (sheet) {
                PlayerSheet.CHAPTERS -> ChaptersSheet(book, timeline, controller!!, strings) { sheet = null }
                PlayerSheet.SPEED -> SpeedSheet(book, controller!!, strings, skipSilence)
                PlayerSheet.TIMER -> SleepTimerSheet(controller!!, sleepRemaining.longValue, strings) { sheet = null }
                null -> Unit
            }
        }
    }
}

private enum class PlayerSheet { CHAPTERS, SPEED, TIMER }

private data class PlayingBook(
    val mediaId: String,
    val uri: String,
    val title: String,
    val chapterTitle: String,
    val author: String,
    val narrator: String,
    val artworkUri: String?,
    val positionMs: Long,
    val durationMs: Long?,
    val trackIndex: Int,
    val isPlaying: Boolean,
    val speed: Float,
)

private fun MediaController.snapshot(): PlayingBook? {
    val item = currentMediaItem ?: return null
    return PlayingBook(
        mediaId = item.mediaId,
        uri = item.localConfiguration?.uri?.toString().orEmpty(),
        title = item.mediaMetadata.albumTitle?.toString().orEmpty().ifBlank { item.mediaMetadata.title?.toString().orEmpty() },
        chapterTitle = item.mediaMetadata.title?.toString().orEmpty(),
        author = item.mediaMetadata.artist?.toString().orEmpty(),
        narrator = item.mediaMetadata.albumArtist?.toString().orEmpty(),
        artworkUri = item.mediaMetadata.artworkUri?.toString(),
        positionMs = currentPosition.coerceAtLeast(0L),
        durationMs = duration.takeIf { it != C.TIME_UNSET && it > 0L },
        trackIndex = currentMediaItemIndex,
        isPlaying = isPlaying,
        speed = playbackParameters.speed,
    )
}

@Composable
private fun MiniAudiobookPlayer(
    book: PlayingBook,
    timeline: BookTimeline?,
    position: State<Long>,
    strings: AudiobookStrings,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ref = TrackUriCodec.decode(book.uri)
    val positionMs = position.value
    val global = if (ref != null) timeline?.toGlobal(ref.trackIndex, positionMs) else null
    val chapter = if (global != null) timeline?.chapterAt(global) else null
    val next = chapter?.let { current -> timeline?.chapters?.firstOrNull { it.startMs > current.startMs }?.startMs }
    val duration = chapter?.durationMs ?: if (chapter != null && next != null) next - chapter.startMs else book.durationMs
    val chapterPosition = if (chapter != null && global != null) global - chapter.startMs else positionMs
    val progress = if (duration != null && duration > 0) (chapterPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        modifier = modifier
            .size(164.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF211E22))
            .pointerInput(book.mediaId) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDrag = { change, amount -> change.consume(); onDrag(amount) },
                )
            },
    ) {
        BookArtwork(book.artworkUri, Modifier.fillMaxSize().clickable(onClick = onExpand))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.58f), Color.Transparent, Color.Black.copy(alpha = 0.88f)))))
        Text(book.title, Modifier.align(Alignment.TopStart).padding(12.dp).clickable(onClick = onExpand),
            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(chapterPosition), color = Color.White, fontSize = 10.sp)
                Text(duration?.let { "−${formatTime((it - chapterPosition).coerceAtLeast(0))}" } ?: "—",
                    color = Color.White, fontSize = 10.sp)
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.45f))) {
                Box(Modifier.fillMaxWidth(progress).height(2.dp).background(Color(0xFFFF641F)))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                PlayerButton("↶", "−15", onBack, 32)
                PlayerButton(if (book.isPlaying) "Ⅱ" else "▶", if (book.isPlaying) strings.pause else strings.play, onPlayPause, 36)
                PlayerButton("↷", "+30", onForward, 32)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FullAudiobookPlayer(
    book: PlayingBook,
    timeline: BookTimeline?,
    position: State<Long>,
    strings: AudiobookStrings,
    controller: MediaController,
    interactive: Boolean,
    onCollapse: () -> Unit,
    onShowChapters: () -> Unit,
    onShowSpeed: () -> Unit,
    onShowTimer: () -> Unit,
    sleepRemainingMs: Long,
    modifier: Modifier = Modifier,
) {
    val ref = remember(book.uri) { TrackUriCodec.decode(book.uri) }
    val positionMs = position.value
    val global = if (ref != null) timeline?.toGlobal(ref.trackIndex, positionMs) else null
    val chapter = if (global != null) timeline?.chapterAt(global) else null
    val nextStart = chapter?.let { current -> timeline?.chapters?.firstOrNull { it.startMs > current.startMs }?.startMs }
    val chapterDuration = chapter?.durationMs ?: if (chapter != null && nextStart != null) nextStart - chapter.startMs else book.durationMs
    val chapterPosition = if (chapter != null && global != null) (global - chapter.startMs).coerceAtLeast(0L) else positionMs
    var pendingSeek by remember(book.mediaId, chapter?.index) { mutableStateOf<Float?>(null) }
    val progress = pendingSeek ?: if (chapterDuration != null && chapterDuration > 0) (chapterPosition.toFloat() / chapterDuration).coerceIn(0f, 1f) else 0f
    val sliderColors = SliderDefaults.colors(
        thumbColor = Color(0xFFFF641F),
        activeTrackColor = Color(0xFFFF641F),
        inactiveTrackColor = Color(0xFF4B4E50),
    )

    Box(
        modifier = modifier
            .background(Color(0xFF11141A))
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})
            .then(if (interactive) Modifier.pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount -> dragged += amount; change.consume() },
                    onDragEnd = { if (dragged > 110.dp.toPx()) onCollapse(); dragged = 0f },
                )
            } else Modifier),
    ) {
        BookArtwork(book.artworkUri, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.3f),
            0.42f to Color.Transparent,
            0.63f to Color(0xB9080C11),
            1f to Color(0xFF090C10),
        )))
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            PlayerButton("←", strings.collapse, onCollapse, 54, enabled = interactive)
            PlayerButton("⋮", strings.chapters, onShowChapters, 54, enabled = interactive)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 26.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(book.title, color = Color.White, fontSize = 32.sp, lineHeight = 35.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(book.author.ifBlank { book.narrator }, color = Color.White.copy(alpha = 0.72f),
                fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Slider(
                value = progress,
                enabled = interactive,
                colors = sliderColors,
                thumb = { Box(Modifier.size(16.dp).background(Color(0xFFFF641F), CircleShape)) },
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        colors = sliderColors,
                        drawStopIndicator = null,
                        thumbTrackGapSize = 0.dp,
                    )
                },
                onValueChange = { pendingSeek = it },
                onValueChangeFinished = {
                    val target = (chapterDuration ?: book.durationMs)?.let { duration ->
                        (duration * (pendingSeek ?: progress)).toLong()
                    }
                    if (target != null) {
                        val track = if (chapter != null && timeline != null) timeline.toTrack(chapter.startMs + target) else null
                        if (track != null) controller.seekTo(track.first, track.second) else controller.seekTo(target)
                    }
                    pendingSeek = null
                },
                modifier = Modifier.height(26.dp).semantics { contentDescription = strings.chapterProgress },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(chapterPosition), color = Color.White.copy(alpha = 0.76f), fontSize = 13.sp)
                Text(chapterDuration?.let(::formatTime) ?: "—",
                    color = Color.White.copy(alpha = 0.76f), fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(chapter?.title ?: book.chapterTitle,
                    modifier = Modifier.weight(1f),
                    color = Color.White.copy(alpha = 0.74f), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val bookProgress = global?.let { timeline?.progress(it) }
                if (bookProgress != null) {
                    Text("${(bookProgress * 100).toInt()}% ${strings.bookProgress}",
                        color = Color.White.copy(alpha = 0.52f), fontSize = 11.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                PlayerButton("↶\n15", "−15", { controller.seekBack() }, 72, enabled = interactive)
                PlayerButton(if (book.isPlaying) "Ⅱ" else "▶", if (book.isPlaying) strings.pause else strings.play,
                    { if (book.isPlaying) controller.pause() else controller.play() }, 90, enabled = interactive)
                PlayerButton("↷\n30", "+30", { controller.seekForward() }, 72, enabled = interactive)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PlayerTile("${book.speed}×", strings.playbackSpeed, Modifier.weight(1f), onShowSpeed, enabled = interactive)
                PlayerTile("≡", strings.chapters, Modifier.weight(1f), onShowChapters, enabled = interactive)
                PlayerTile("◴", if (sleepRemainingMs >= 0) formatTime(sleepRemainingMs) else strings.sleepTimerShort,
                    Modifier.weight(1f), onShowTimer, enabled = interactive)
                PlayerTile("♫", "Озвучка", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BookArtwork(uri: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF1C2632), Color(0xFF18202A), Color(0xFF080B11)))),
        contentAlignment = Alignment.Center) {
        if (uri != null) AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop)
    }
}

@Composable
private fun PlayerTile(symbol: String, label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    enabled: Boolean = true) {
    Column(modifier.height(76.dp).clip(RoundedCornerShape(22.dp))
        .background(Color(0xFF25282C).copy(alpha = if (onClick == null) 0.56f else 0.82f))
        .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
        .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween) {
        Text(symbol, color = if (label == "Скорость") Color(0xFFFF641F) else Color.White,
            fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        Text(label, color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun PlayerButton(label: String, description: String, onClick: () -> Unit, size: Int = 48,
    enabled: Boolean = true) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(0xD623272B))
            .clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChaptersSheet(book: PlayingBook, timeline: BookTimeline?, controller: MediaController,
                          strings: AudiobookStrings, onDismiss: () -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(strings.chapters, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        if (timeline == null) item { Text(book.chapterTitle) }
        else items(timeline.chapters, key = { it.index }) { chapter ->
            Button(onClick = {
                timeline.toTrack(chapter.startMs)?.let { controller.seekTo(it.first, it.second) }
                onDismiss()
            }, modifier = Modifier.fillMaxWidth()) { Text(chapter.title) }
        }
    }
}

@Composable
private fun SpeedSheet(book: PlayingBook, controller: MediaController, strings: AudiobookStrings,
                       skipSilence: Boolean) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${strings.playbackSpeed} ${book.speed}×", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Slider(value = book.speed.coerceIn(0.5f, 3f), onValueChange = {
            controller.setPlaybackSpeed((it * 20).toInt() / 20f)
        }, valueRange = 0.5f..3f, modifier = Modifier.semantics { contentDescription = strings.playbackSpeed })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (speed in listOf(0.8f, 1f, 1.2f, 1.5f, 2f)) {
                Button(onClick = { controller.setPlaybackSpeed(speed) }) { Text("${speed}×") }
            }
        }
        Button(onClick = {
            controller.sendCustomCommand(AudiobookSessionCommands.setSkipSilence,
                Bundle().apply { putBoolean(AudiobookSessionCommands.SKIP_SILENCE, !skipSilence) })
        }) { Text("${strings.skipSilence}: ${if (skipSilence) "✓" else "○"}") }
    }
}

@Composable
private fun SleepTimerSheet(controller: MediaController, remainingMs: Long, strings: AudiobookStrings,
                            onDismiss: () -> Unit) {
    var customMinutes by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(strings.sleepTimer, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (remainingMs >= 0L) {
            Text(formatTime(remainingMs))
            Button(onClick = {
                controller.sendCustomCommand(AudiobookSessionCommands.cancelSleepTimer, Bundle.EMPTY)
                onDismiss()
            }) { Text(strings.cancelTimer) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (minutes in listOf(5, 10, 15, 30, 45, 60)) {
                Button(onClick = {
                    controller.sendCustomCommand(AudiobookSessionCommands.setSleepTimer,
                        Bundle().apply { putInt(AudiobookSessionCommands.MINUTES, minutes) })
                    onDismiss()
                }) { Text("$minutes ${strings.minutesUnit}") }
            }
        }
        OutlinedTextField(value = customMinutes, onValueChange = { value ->
            customMinutes = value.filter(Char::isDigit).take(3)
        }, label = { Text(strings.customMinutes) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        val minutes = customMinutes.toIntOrNull()
        Button(onClick = {
            controller.sendCustomCommand(AudiobookSessionCommands.setSleepTimer,
                Bundle().apply { putInt(AudiobookSessionCommands.MINUTES, minutes ?: 0) })
            onDismiss()
        }, enabled = minutes != null && minutes in 1..240) { Text(strings.startTimer) }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000
    val minutes = seconds / 60
    return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds % 60)
    else "%d:%02d".format(minutes, seconds % 60)
}
