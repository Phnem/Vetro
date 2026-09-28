package com.example.myapplication.audiobooks.ui

import com.example.myapplication.di.AUDIOBOOK_SOURCES
import org.koin.core.qualifier.named
import android.widget.Toast
import com.example.myapplication.audiobooks.data.remote.web.SiteText
import com.example.myapplication.audiobooks.domain.source.WorkMatch
import com.example.myapplication.audiobooks.domain.source.AudiobookSearch
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import android.content.ComponentName
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.myapplication.audiobooks.AudiobookFeatureGate
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline
import com.example.myapplication.audiobooks.playback.AudiobookLauncher
import com.example.myapplication.audiobooks.playback.AudiobookPlaybackService
import com.example.myapplication.audiobooks.playback.AudiobookSessionCommands
import com.example.myapplication.audiobooks.data.AudiobookRepository.Companion.normalize
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.phnem.vetro.BuildConfig
import com.phnem.vetro.R
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin
import org.koin.compose.koinInject

// ==========================================
// Плеер аудиокниг — ОДНО окно, которое живёт между двумя формами: квадратной карточкой мини-плеера
// и полным экраном (spec/10, D-04, UNIVERSAL_MOTION_SPEC закон 1). Прямоугольник окна и его радиус
// интерполируются одной пружиной `expansion`; обложка внутри масштабируется «на заполнение», без
// искажений. Контент у двух форм свой и живёт отдельно от оболочки (§5): мини-контролы гаснут в
// начале раскрытия, полные проявляются, когда окно уже почти развернулось. Системный жест «назад»
// и свайп вниз ведут то же значение пальцем, отпускание решает `willDismiss` по пути и скорости.
// ==========================================

@Composable
@UnstableApi
fun AudiobookPlayerHost(language: AppLanguage = AppLanguage.RU, modifier: Modifier = Modifier) {
    val gate: AudiobookFeatureGate = koinInject()
    if (!gate.enabled && !BuildConfig.DEBUG) return

    val context = LocalContext.current.applicationContext
    val resolver: ManifestResolver = koinInject()
    val state: AudiobookPlayerState = koinInject()
    val repository: AudiobookRepository = koinInject()
    val launcher: AudiobookLauncher = koinInject()
    val koin = getKoin()
    val sources = remember { koin.get<List<AudiobookSource>>(named(AUDIOBOOK_SOURCES)) }
    val search: AudiobookSearch = koinInject()
    val strings = remember(language) { playerStrings(language) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val expansion = remember { Animatable(0f) }
    // Куда окно едет, а не где оно сейчас: жест назад и перетаскивание проводят value через
    // середину, и флаг «полный» не должен от этого выключать сам жест (иначе короткий бросок
    // отменялся и окно возвращалось в полный размер).
    var expanded by remember { mutableStateOf(false) }

    // Живое подключение к сессии: пишет общее состояние, частота опроса — по тому, что видно.
    LaunchedEffect(context) {
        val token = SessionToken(context, ComponentName(context, AudiobookPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        var connected: MediaController? = null
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                connected?.let { c ->
                    state.book = c.snapshot()
                    state.trackPositionMs.longValue = c.currentPosition.coerceAtLeast(0L)
                }
            }
        }
        try {
            val controller = withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
            connected = controller
            state.controller = controller
            controller.addListener(listener)
            while (isActive) {
                val snapshot = controller.snapshot()
                if (state.book != snapshot) state.book = snapshot
                state.trackPositionMs.longValue = controller.currentPosition.coerceAtLeast(0L)
                state.sleepRemainingMs = controller.sessionExtras.getLong(AudiobookSessionCommands.REMAINING_MS, -1L)
                state.skipSilenceLevel = controller.sessionExtras.getInt(AudiobookSessionCommands.SKIP_SILENCE_LEVEL, 0)
                val noticeSeq = controller.sessionExtras.getInt(AudiobookSessionCommands.SOURCE_NOTICE_SEQ, 0)
                if (noticeSeq != state.sourceNotice.first) {
                    state.sourceNotice = noticeSeq to controller.sessionExtras.getString(AudiobookSessionCommands.SOURCE_NOTICE).orEmpty()
                }
                delay(if (snapshot?.isPlaying == true) 250L else 1_000L)
            }
        } finally {
            connected?.removeListener(listener)
            state.controller = null
            MediaController.releaseFuture(future)
        }
    }

    val book = state.book
    val variant = book?.uri?.let(TrackUriCodec::decode)?.variant
    LaunchedEffect(variant) {
        state.timeline = variant?.let { v ->
            runCatching { resolver.manifest(v).let { BookTimeline(it.tracks, it.chapters) } }.getOrNull()
        }
    }
    // Переход по цепочке сайтов виден пользователю: плеер не молча сменил источник или замолчал.
    var shownNotice by remember { mutableIntStateOf(state.sourceNotice.first) }
    LaunchedEffect(state.sourceNotice) {
        val (seq, name) = state.sourceNotice
        if (seq != shownNotice) {
            shownNotice = seq
            if (seq > 0) {
                Toast.makeText(context, if (name.isEmpty()) strings.noSourcesLeft else strings.switchedSource(name), Toast.LENGTH_LONG).show()
            }
        }
    }
    // Просьба раскрыть плеер («Слушать», глава со страницы книги) может прийти раньше, чем сессия
    // отдаст книгу: ждём готовности и раскрываем. Эффект не зависит от «съеденности» просьбы —
    // раньше отметка «съедено» перезапускала эффект и обрывала раскрытие на первом кадре.
    var consumedExpand by remember { mutableIntStateOf(state.expandRequests) }
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(state.expandRequests, state.book != null, state.controller != null) }
            .collect { (request, hasBook, hasController) ->
                if (request != consumedExpand && hasBook && hasController) {
                    consumedExpand = request
                    expanded = true
                    launch { expansion.animateTo(1f, MotionTokens.largeSurfaceEnter()) }
                }
            }
    }
    val favorite by remember(book?.workId) {
        book?.workId?.let(repository::isFavorite) ?: flowOf(false)
    }.collectAsState(initial = false)
    val markerStore: com.example.myapplication.audiobooks.data.AudiobookMarkerStore = koinInject()
    val markers by remember(book?.narrationId) {
        book?.narrationId?.value?.let(markerStore::markers) ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val controller = state.controller
    if (book == null || controller == null) return

    val isFull = expanded
    val sheetVisible = remember { MutableTransitionState(false) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var narration by remember { mutableStateOf(NarrationChoice()) }
    fun openSheet(which: PlayerSheet) {
        sheet = which
        sheetVisible.targetState = true
    }
    fun closeSheet() {
        sheetVisible.targetState = false
    }
    LaunchedEffect(sheetVisible.isIdle, sheetVisible.currentState) {
        if (sheetVisible.isIdle && !sheetVisible.currentState) sheet = null
    }
    fun collapse() {
        expanded = false
        scope.launch { expansion.animateTo(0f, MotionTokens.largeSurfaceExit()) }
    }
    fun expand() {
        expanded = true
        scope.launch { expansion.animateTo(1f, MotionTokens.largeSurfaceEnter()) }
    }
    fun toggleFavorite() {
        val work = book.workId ?: return
        performHaptic(view, if (favorite) Haptic.Light else Haptic.Success)
        scope.launch { repository.setFavorite(work, !favorite) }
    }

    // Системный жест «назад» ведёт окно пальцем. Как у системного «назад»: отпустили где угодно —
    // окно доезжает в мини с того места, где его оставил палец; вернули палец к краю (система
    // отменила) — обратно в полный. Длина жеста не решает, решает отпускание.
    PredictiveBackHandler(enabled = isFull && sheet == null) { events ->
        try {
            events.collect { e -> expansion.snapTo(1f - BackFollow * e.progress) }
            collapse()
        } catch (e: CancellationException) {
            expand()
            throw e
        }
    }
    BackHandler(enabled = sheet != null) { closeSheet() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val miniPx = with(density) { MiniSize.toPx() }
        val marginPx = with(density) { 16.dp.toPx() }
        val dockPx = with(density) { 104.dp.toPx() }
        val topPx = WindowInsets.statusBars.getTop(density).toFloat() + marginPx
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val right = (widthPx - miniPx - marginPx).coerceAtLeast(marginPx)
        val bottom = (heightPx - miniPx - dockPx).coerceAtLeast(marginPx)
        val miniOffset = remember(widthPx, heightPx) { Animatable(Offset(right, bottom), Offset.VectorConverter) }
        val miniRadiusPx = with(density) { MiniRadius.toPx() }
        fun snapToCorner(leftSide: Boolean, topSide: Boolean) {
            scope.launch {
                miniOffset.animateTo(
                    Offset(if (leftSide) marginPx else right, if (topSide) topPx else bottom),
                    MotionTokens.springSurface(),
                )
            }
        }
        fun windowRect(p: Float): Rect {
            val m = miniOffset.value
            return Rect(lerp(m.x, 0f, p), lerp(m.y, 0f, p), lerp(m.x + miniPx, widthPx, p), lerp(m.y + miniPx, heightPx, p))
        }

        val backdrop = rememberLayerBackdrop()
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = expansion.value.coerceIn(0f, 1f)
                    clip = true
                    shape = WindowShape(windowRect(p), lerp(miniRadiusPx, 0f, p))
                    shadowElevation = lerp(18.dp.toPx(), 0f, p)
                },
        ) {
            // Обложка и затемнение записываются в бэкдроп: стеклянные кнопки обеих форм преломляют их.
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                BookArt(
                    uri = book.artworkUri,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val r = windowRect(expansion.value.coerceIn(0f, 1f))
                            val s = maxOf(r.width / size.width, r.height / size.height)
                            transformOrigin = TransformOrigin.Center
                            scaleX = s
                            scaleY = s
                            translationX = r.center.x - size.width / 2f
                            translationY = r.center.y - size.height / 2f
                        },
                )
                // Затемнение в координатах окна: сверху под кнопки, низ под текст и контролы.
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val r = windowRect(expansion.value.coerceIn(0f, 1f))
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.36f),
                                    0.28f to Color.Transparent,
                                    0.52f to Color.Transparent,
                                    0.74f to Color.Black.copy(alpha = 0.72f),
                                    1f to Color.Black.copy(alpha = 0.92f),
                                    startY = r.top,
                                    endY = r.bottom,
                                ),
                                topLeft = r.topLeft,
                                size = r.size,
                            )
                        },
                )
            }

            val miniVisible by remember { derivedStateOf { expansion.value < 0.4f } }
            if (miniVisible) {
                MiniPlayerContent(
                    book = book,
                    state = state,
                    strings = strings,
                    backdrop = backdrop,
                    favorite = favorite,
                    onFavorite = ::toggleFavorite,
                    onExpand = { expand() },
                    onDrag = { delta ->
                        scope.launch {
                            miniOffset.snapTo(Offset(
                                (miniOffset.value.x + delta.x).coerceIn(marginPx, right),
                                (miniOffset.value.y + delta.y).coerceIn(topPx, bottom),
                            ))
                        }
                    },
                    onDragEnd = {
                        snapToCorner(
                            leftSide = miniOffset.value.x + miniPx / 2 < widthPx / 2,
                            topSide = miniOffset.value.y + miniPx / 2 < heightPx / 2,
                        )
                    },
                    onMoveToCorner = ::snapToCorner,
                    modifier = Modifier
                        .offset { windowRect(expansion.value.coerceIn(0f, 1f)).topLeft.let { IntOffset(it.x.toInt(), it.y.toInt()) } }
                        .size(MiniSize)
                        .graphicsLayer { alpha = (1f - expansion.value / 0.3f).coerceIn(0f, 1f) },
                )
            }

            val fullVisible by remember { derivedStateOf { expansion.value > 0.05f } }
            if (fullVisible) {
                FullPlayerContent(
                    book = book,
                    state = state,
                    strings = strings,
                    backdrop = backdrop,
                    favorite = favorite,
                    interactive = isFull,
                    onCollapse = { collapse() },
                    onFavorite = ::toggleFavorite,
                    markers = markers,
                    onAddMarker = {
                        val id = book.narrationId?.value
                        val at = state.globalMs()
                        if (id != null && at != null) {
                            performHaptic(view, Haptic.Success)
                            val chapter = state.timeline?.chapterAt(at)
                            scope.launch {
                                markerStore.add(id, at, chapter?.index ?: 0, at - (chapter?.startMs ?: 0L))
                            }
                        }
                    },
                    onSheet = { which ->
                        if (which == PlayerSheet.NARRATION) {
                            narration = NarrationChoice()
                            scope.launch { narration = findNarrations(book, sources, search) }
                        }
                        openSheet(which)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = ((expansion.value - 0.6f) / 0.4f).coerceIn(0f, 1f) }
                        .draggable(
                            orientation = Orientation.Vertical,
                            enabled = isFull && sheet == null,
                            state = rememberDraggableState { dy ->
                                scope.launch { expansion.snapTo((expansion.value - dy / heightPx).coerceIn(0f, 1f)) }
                            },
                            onDragStopped = { velocity ->
                                // Бросок вниз сворачивает с любого места, бросок вверх — раскрывает;
                                // медленное отпускание решает пройденная доля экрана.
                                val v = velocity / density.density
                                val close = when {
                                    v > MotionTokens.DismissVelocityThresholdDpPerSec -> true
                                    v < -MotionTokens.DismissVelocityThresholdDpPerSec -> false
                                    else -> expansion.value < 0.75f
                                }
                                if (close) collapse() else expand()
                            },
                        ),
                )
            }
        }

        if (sheet != null) {
            Box(Modifier.fillMaxSize().zIndex(2f)) {
                PlayerSheetFrame(visible = sheetVisible, onDismiss = ::closeSheet) {
                    when (sheet) {
                        PlayerSheet.CHAPTERS -> ChaptersSheetContent(state, strings, ::closeSheet, markers)
                        PlayerSheet.SPEED -> SpeedSheetContent(state, strings)
                        PlayerSheet.TIMER -> SleepTimerSheetContent(state, strings, ::closeSheet)
                        PlayerSheet.NARRATION -> NarrationSheetContent(narration, strings) { option ->
                            val fraction = state.globalMs()?.let { g -> state.timeline?.progress(g) }
                            narration = narration.copy(loading = true, message = null)
                            scope.launch {
                                val result = launcher.play(option.book, startFraction = fraction)
                                if (result == AudiobookLauncher.Result.Started) {
                                    closeSheet()
                                } else {
                                    narration = narration.copy(loading = false, message = strings.narrationUnavailable)
                                }
                            }
                        }
                        PlayerSheet.MORE -> MoreSheetContent(
                            state, strings, favorite, ::toggleFavorite,
                            markerCount = markers.size,
                            onMarkers = { openSheet(PlayerSheet.MARKERS) },
                        )
                        PlayerSheet.MARKERS -> MarkersSheetContent(
                            state = state,
                            strings = strings,
                            markers = markers,
                            onDelete = { marker -> scope.launch { markerStore.remove(marker) } },
                            onDismiss = ::closeSheet,
                        )
                        null -> Unit
                    }
                }
            }
        }
    }
}

/** Окно плеера: скруглённый прямоугольник в координатах полноэкранного слоя. */
private class WindowShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/**
 * Другие озвучки той же книги на всех сайтах: то же название и совпадающая фамилия автора. Один чтец
 * на нескольких сайтах — один пункт (с лучшего источника); остальные копии — запасные при запуске.
 */
private suspend fun findNarrations(
    book: PlayingBook,
    sources: List<AudiobookSource>,
    search: AudiobookSearch,
): NarrationChoice {
    val currentKey = TrackUriCodec.decode(book.uri)?.variant?.value
    val currentVoice = WorkMatch.words(listOf(book.narrator)).sorted().joinToString(" ")
    val options = search.sameWork(book.title, SiteText.names(book.author))
        .sortedBy { search.rank(it.ref.source) }
        .mapNotNull { hit ->
            val source = sources.firstOrNull { it.id == hit.ref.source } ?: return@mapNotNull null
            val key = source.variantOf(hit.ref).value
            NarrationOption(
                key = key,
                narrators = hit.narrators.joinToString(", "),
                duration = hit.durationSec?.let { formatClock(it * 1000) },
                source = source.displayName,
                current = key == currentKey,
                book = hit,
            )
        }
        .filter { it.current || it.narrators.isNotBlank() }
        .distinctBy { o -> if (o.current) currentVoice else WorkMatch.words(listOf(o.narrators)).sorted().joinToString(" ") }
        .sortedByDescending { it.current }
    return NarrationChoice(loading = false, options = options)
}

// ---------- Мини-плеер ----------

@Composable
private fun MiniPlayerContent(
    book: PlayingBook,
    state: AudiobookPlayerState,
    strings: PlayerStrings,
    backdrop: Backdrop,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onExpand: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onMoveToCorner: (Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pos = chapterPosition(book, state.timeline, state.trackPositionMs.longValue)
    val progress = pos.durationMs?.takeIf { it > 0 }?.let { pos.positionMs.toFloat() / it } ?: 0f
    Box(
        modifier
            .pointerInput(book.mediaId) {
                detectDragGestures(onDragEnd = onDragEnd, onDrag = { change, amount -> change.consume(); onDrag(amount) })
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onExpand)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(strings.moveMiniTopLeft) { onMoveToCorner(true, true); true },
                    CustomAccessibilityAction(strings.moveMiniTopRight) { onMoveToCorner(false, true); true },
                    CustomAccessibilityAction(strings.moveMiniBottomLeft) { onMoveToCorner(true, false); true },
                    CustomAccessibilityAction(strings.moveMiniBottomRight) { onMoveToCorner(false, false); true },
                )
            }
            .padding(9.dp),
    ) {
        // Верх: капсула автор/чтец и сердце (референс мини-плеера).
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassSurface(backdrop, CircleShape, Modifier.weight(1f).height(38.dp)) {
                Row(Modifier.fillMaxSize().padding(start = 4.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookArt(book.artworkUri, Modifier.size(30.dp).clip(CircleShape))
                    Spacer(Modifier.width(7.dp))
                    // Две строки обязаны поместиться в капсулу 38dp при любом масштабе шрифта: высота строк
                    // задана явно, без шрифтовых полей, а масштаб в крошечной капсуле ограничен — иначе
                    // вторая строка вылезала под капсулу поверх обложки.
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.1f)),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Text(book.author.ifBlank { book.title }, style = CapsuleLine.copy(fontSize = 11.sp, lineHeight = 13.sp,
                                fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (book.narrator.isNotBlank()) {
                                Text(book.narrator, style = CapsuleLine.copy(fontSize = 9.sp, lineHeight = 11.sp),
                                    color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            GlassCircleButton(
                backdrop, if (favorite) R.drawable.ph_heart_fill else R.drawable.ph_heart, strings.favorite,
                size = 38.dp, iconSize = 18.dp, tint = if (favorite) BrandOrange else Color.White, onClick = onFavorite,
            )
        }
        // Низ: время, тонкая полоса и три стеклянные кнопки.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(formatClock(pos.positionMs), color = Color.White, fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(pos.durationMs?.let { "−" + formatClock((it - pos.positionMs).coerceAtLeast(0)) } ?: "",
                    color = Color.White, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Color.White))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                GlassCircleButton(backdrop, R.drawable.ph_rewind_fill, strings.back15, 38.dp, 16.dp) { state.seekBack() }
                GlassCircleButton(
                    backdrop, if (book.isPlaying) R.drawable.ph_pause_fill else R.drawable.ph_play_fill,
                    if (book.isPlaying) strings.pause else strings.play, 44.dp, 20.dp,
                ) { state.playPause() }
                GlassCircleButton(backdrop, R.drawable.ph_fast_forward_fill, strings.forward30, 38.dp, 16.dp) { state.seekForward() }
            }
        }
    }
}

// ---------- Полный плеер ----------

@Composable
private fun FullPlayerContent(
    book: PlayingBook,
    state: AudiobookPlayerState,
    strings: PlayerStrings,
    backdrop: Backdrop,
    favorite: Boolean,
    interactive: Boolean,
    onCollapse: () -> Unit,
    onFavorite: () -> Unit,
    onSheet: (PlayerSheet) -> Unit,
    markers: List<com.example.myapplication.audiobooks.data.AudiobookMarker> = emptyList(),
    onAddMarker: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pos = chapterPosition(book, state.timeline, state.trackPositionMs.longValue)
    val nowGlobal = state.globalMs()
    // Маркер «здесь» — активное состояние кнопки (оранжевая), см. правило «активное — оранжевое».
    val markedHere = nowGlobal != null && markers.any {
        kotlin.math.abs(it.globalMs - nowGlobal) < com.example.myapplication.audiobooks.data.AudiobookMarkerStore.MERGE_WINDOW_MS
    }
    // Точки маркеров на шкале текущей главы.
    val markerFractions = run {
        val chapter = pos.chapter
        val duration = pos.durationMs?.takeIf { it > 0 }
        if (chapter == null || duration == null) emptyList()
        else markers.mapNotNull { m ->
            val f = (m.globalMs - chapter.startMs).toFloat() / duration
            f.takeIf { it in 0f..1f }
        }
    }
    // «Вернуться» живёт несколько секунд после дальнего прыжка.
    val undoFrom = state.undoSeekFromMs
    LaunchedEffect(undoFrom) {
        if (undoFrom != null) {
            kotlinx.coroutines.delay(UNDO_SEEK_VISIBLE_MS)
            state.dismissUndoSeek()
        }
    }
    var seeking by remember(book.mediaId, pos.index) { mutableFloatStateOf(-1f) }
    val fraction = if (seeking >= 0f) seeking else pos.durationMs?.takeIf { it > 0 }?.let { pos.positionMs.toFloat() / it } ?: 0f
    Box(modifier) {
        // Верх: кнопки под статус-баром, не на нём.
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            GlassCircleButton(backdrop, R.drawable.ph_caret_down, strings.collapse, 52.dp, 22.dp, enabled = interactive, onClick = onCollapse)
            Spacer(Modifier.weight(1f))
            GlassCircleButton(backdrop, R.drawable.ph_dots_three_vertical, strings.more, 52.dp, 22.dp, enabled = interactive) {
                onSheet(PlayerSheet.MORE)
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 18.dp),
        ) {
            // Название и автор; сердце — справа, на уровне названия (референс).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(book.title, color = Color.White, fontFamily = SnProFamily, fontWeight = FontWeight.Bold,
                        fontSize = 30.sp, lineHeight = 34.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(book.author.ifBlank { book.narrator }, color = Color.White.copy(alpha = 0.66f),
                        fontFamily = SnProFamily, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                // Маркер — прямо над сердцем. Оба белые, оранжевые только в активном состоянии.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlassCircleButton(
                        backdrop, if (markedHere) R.drawable.ph_bookmark_simple_fill else R.drawable.ph_bookmark_simple,
                        strings.addMarker, 52.dp, 22.dp, tint = if (markedHere) BrandOrange else Color.White,
                        enabled = interactive, onClick = onAddMarker,
                    )
                    Spacer(Modifier.height(10.dp))
                    GlassCircleButton(
                        backdrop, if (favorite) R.drawable.ph_heart_fill else R.drawable.ph_heart, strings.favorite,
                        52.dp, 24.dp, tint = if (favorite) BrandOrange else Color.White, enabled = interactive, onClick = onFavorite,
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            SeekBar(
                fraction = fraction,
                markers = markerFractions,
                enabled = interactive,
                onSeek = { seeking = it },
                onSeekFinished = {
                    val chapter = pos.chapter
                    val duration = pos.durationMs
                    if (chapter != null && duration != null && seeking >= 0f) {
                        state.seekToGlobal(chapter.startMs + (duration * seeking).toLong())
                    }
                    seeking = -1f
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                val shown = if (seeking >= 0f) pos.durationMs?.let { (it * seeking).toLong() } ?: pos.positionMs else pos.positionMs
                Text(formatClock(shown), color = Color.White.copy(alpha = 0.72f), fontFamily = SnProFamily, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                if (undoFrom != null && interactive) {
                    // Отмена случайной перемотки: туда, где был до прыжка.
                    Text(
                        "↺ " + strings.undoSeek,
                        color = Color.White,
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable { state.undoSeek() }
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                    )
                    Spacer(Modifier.weight(1f))
                }
                Text(pos.durationMs?.let(::formatClock) ?: "", color = Color.White.copy(alpha = 0.72f), fontFamily = SnProFamily, fontSize = 13.sp)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SkipButton(backdrop, R.drawable.ph_arrow_counter_clockwise, "15", strings.back15, interactive) { state.seekBack() }
                GlassCircleButton(
                    backdrop, if (book.isPlaying) R.drawable.ph_pause_fill else R.drawable.ph_play_fill,
                    if (book.isPlaying) strings.pause else strings.play, 100.dp, 42.dp, tint = BrandOrange, enabled = interactive,
                ) { state.playPause() }
                SkipButton(backdrop, R.drawable.ph_arrow_clockwise, "30", strings.forward30, interactive) { state.seekForward() }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayerTile(backdrop, strings.speed, interactive, Modifier.weight(1f), onClick = { onSheet(PlayerSheet.SPEED) }) {
                    // Скорость по умолчанию (1×) — белая, изменённая — оранжевая.
                    Text(formatSpeed(book.speed), color = if (kotlin.math.abs(book.speed - 1f) > 0.01f) BrandOrange else Color.White,
                        fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                }
                PlayerTile(backdrop, if (pos.count > 1) strings.chapterShort(pos.index + 1, pos.count) else strings.chapters,
                    interactive, Modifier.weight(1f), onClick = { onSheet(PlayerSheet.CHAPTERS) }) {
                    PhIcon(R.drawable.ph_list_dashes, 24.dp)
                }
                val timerOn = state.sleepRemainingMs >= 0
                PlayerTile(backdrop, if (timerOn) formatClock(state.sleepRemainingMs) else strings.timer, interactive,
                    Modifier.weight(1f), onClick = { onSheet(PlayerSheet.TIMER) }) {
                    PhIcon(R.drawable.ph_timer, 24.dp, if (timerOn) BrandOrange else Color.White)
                }
                PlayerTile(backdrop, strings.narration, interactive, Modifier.weight(1f), onClick = { onSheet(PlayerSheet.NARRATION) }) {
                    PhIcon(R.drawable.ph_waveform, 24.dp)
                }
            }
        }
    }
}

@Composable
private fun SkipButton(backdrop: Backdrop, icon: Int, seconds: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    GlassSurface(backdrop, CircleShape, Modifier.size(76.dp), description = description, enabled = enabled, onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            PhIcon(icon, 40.dp)
            Text(seconds, color = Color.White, fontFamily = SnProFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PlayerTile(
    backdrop: Backdrop,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    top: @Composable () -> Unit,
) {
    GlassSurface(backdrop, RoundedCornerShape(24.dp), modifier.height(80.dp), description = label, enabled = enabled, onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) { top() }
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color.White.copy(alpha = 0.78f), fontFamily = SnProFamily, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** Шкала главы: трек 6 dp, оранжевая заливка и бегунок; тап и перетаскивание — перемотка. */
@Composable
private fun SeekBar(
    fraction: Float,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    /** Маркеры на шкале главы (доли 0..1) — маленькие белые точки. */
    markers: List<Float> = emptyList(),
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { onSeek((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = onSeekFinished,
                    onDragCancel = onSeekFinished,
                    onDrag = { change, _ -> change.consume(); onSeek((change.position.x / size.width).coerceIn(0f, 1f)) },
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)); onSeekFinished() }
            }
            .drawBehind {
                val track = 6.dp.toPx()
                val y = size.height / 2f
                val r = CornerRadius(track / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.2f), Offset(0f, y - track / 2), Size(size.width, track), r)
                val x = size.width * fraction.coerceIn(0f, 1f)
                drawRoundRect(BrandOrange, Offset(0f, y - track / 2), Size(x.coerceAtLeast(track), track), r)
                val dot = 3.dp.toPx()
                markers.forEach { m ->
                    drawCircle(Color.White, radius = dot, center = Offset((size.width * m).coerceIn(dot, size.width - dot), y))
                }
                drawCircle(BrandOrange, radius = 9.dp.toPx(), center = Offset(x.coerceIn(9.dp.toPx(), size.width - 9.dp.toPx()), y))
            },
    )
}

private val MiniSize = 196.dp

/** Сколько живёт кнопка «Вернуться» после дальнего прыжка. */
private const val UNDO_SEEK_VISIBLE_MS = 7_000L

/** Строка капсулы мини-плеера: без шрифтовых полей, высота строки задаётся на месте. */
private val CapsuleLine = TextStyle(
    fontFamily = SnProFamily,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/** Доля «пути назад», которую окно проходит под пальцем; остаток доезжает само после отпускания. */
private const val BackFollow = 0.85f
private val MiniRadius = 30.dp
