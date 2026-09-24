package com.example.myapplication.audiobooks.ui

import android.content.ComponentName
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    val isFull = expansion.value > 0.5f
    BackHandler(enabled = isFull || sheet != null) {
        if (sheet != null) sheet = null else scope.launch { expansion.animateTo(0f, MotionTokens.largeSurfaceExit()) }
    }

    Box(modifier.fillMaxSize()) {
        if (expansion.value > 0.01f) {
            FullAudiobookPlayer(
                book = book,
                timeline = timeline,
                position = position,
                strings = strings,
                controller = controller!!,
                onCollapse = { scope.launch { expansion.animateTo(0f, MotionTokens.largeSurfaceExit()) } },
                onShowChapters = { sheet = PlayerSheet.CHAPTERS },
                onShowSpeed = { sheet = PlayerSheet.SPEED },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = expansion.value
                    translationY = size.height * (1f - expansion.value) * 0.16f
                },
            )
        }
        if (expansion.value < 0.99f) {
            MiniAudiobookPlayer(
                book = book,
                timeline = timeline,
                position = position,
                strings = strings,
                onExpand = { scope.launch { expansion.animateTo(1f, MotionTokens.largeSurfaceEnter()) } },
                onPlayPause = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                onForward = { controller?.seekForward() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 102.dp)
                    .graphicsLayer { alpha = 1f - expansion.value },
            )
        }
    }

    if (sheet != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            when (sheet) {
                PlayerSheet.CHAPTERS -> ChaptersSheet(book, timeline, controller!!, strings) { sheet = null }
                PlayerSheet.SPEED -> SpeedSheet(book, controller!!, strings)
                null -> Unit
            }
        }
    }
}

private enum class PlayerSheet { CHAPTERS, SPEED }

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
    onForward: () -> Unit,
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
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF241F2B)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().clickable(onClick = onExpand).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        BookArtwork(book.artworkUri, Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f)) {
            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(chapter?.title ?: book.chapterTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
        PlayerButton(if (book.isPlaying) "Ⅱ" else "▶", if (book.isPlaying) strings.pause else strings.play, onPlayPause)
        PlayerButton("↻30", "+30", onForward)
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(2.dp).background(Color(0xFFFFBB85)))
    }
}

@Composable
private fun FullAudiobookPlayer(
    book: PlayingBook,
    timeline: BookTimeline?,
    position: State<Long>,
    strings: AudiobookStrings,
    controller: MediaController,
    onCollapse: () -> Unit,
    onShowChapters: () -> Unit,
    onShowSpeed: () -> Unit,
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

    Column(
        modifier = modifier
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2834), Color(0xFF18131B), Color(0xFF09090D))))
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount -> dragged += amount; change.consume() },
                    onDragEnd = { if (dragged > 110.dp.toPx()) onCollapse(); dragged = 0f },
                )
            }
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PlayerButton("⌄", strings.collapse, onCollapse)
            PlayerButton("≡", strings.chapters, onShowChapters)
        }
        BookArtwork(
            book.artworkUri,
            Modifier.fillMaxWidth(0.78f).aspectRatio(0.76f).align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(18.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(book.title, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(listOf(book.author, book.narrator).filter(String::isNotBlank).joinToString(" · "),
                color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            Text(chapter?.title ?: book.chapterTitle, color = Color.White.copy(alpha = 0.86f), maxLines = 2)
            Spacer(Modifier.height(10.dp))
            Slider(
                value = progress,
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
                modifier = Modifier.semantics { contentDescription = strings.chapterProgress },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(chapterPosition), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                Text(chapterDuration?.let { "−${formatTime((it - chapterPosition).coerceAtLeast(0L))}" } ?: "—",
                    color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
            }
            Text(global?.let { timeline?.progress(it) }?.let { "${(it * 100).toInt()}% ${strings.bookProgress}" }.orEmpty(),
                color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            PlayerButton("↶15", "−15", { controller.seekBack() }, 66)
            PlayerButton(if (book.isPlaying) "Ⅱ" else "▶", if (book.isPlaying) strings.pause else strings.play,
                { if (book.isPlaying) controller.pause() else controller.play() }, 80)
            PlayerButton("↷30", "+30", { controller.seekForward() }, 66)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PlayerButton("${book.speed}×", strings.playbackSpeed, onShowSpeed)
            PlayerButton("≡", strings.chapters, onShowChapters)
        }
    }
}

@Composable
private fun BookArtwork(uri: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF254657), Color(0xFF775B71), Color(0xFFB78260)))),
        contentAlignment = Alignment.Center) {
        if (uri != null) AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop)
        else Text("VETRO\nBOOKS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    }
}

@Composable
private fun PlayerButton(label: String, description: String, onClick: () -> Unit, size: Int = 48) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick).semantics { contentDescription = description },
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
private fun SpeedSheet(book: PlayingBook, controller: MediaController, strings: AudiobookStrings) {
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
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000
    val minutes = seconds / 60
    return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds % 60)
    else "%d:%02d".format(minutes, seconds % 60)
}
