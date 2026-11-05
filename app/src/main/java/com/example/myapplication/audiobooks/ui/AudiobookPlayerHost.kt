package com.example.myapplication.audiobooks.ui

import com.example.myapplication.di.AUDIOBOOK_SOURCES
import org.koin.core.qualifier.named
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.myapplication.audiobooks.data.BookCoverManager
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
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.example.myapplication.audiobooks.playback.AudioLevelMeter
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import androidx.compose.ui.unit.Dp
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
    // Развёрнутый плеер — не время для ненавязчивых предложений поверх него.
    com.example.myapplication.ui.shared.BusyUiEffect(expanded)

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
    // Найденные главы: принятие/отказ меняет разметку — шкала перечитывается.
    val recoveredStore: com.example.myapplication.audiobooks.chapters.RecoveredChapterStore = koinInject()
    val chapterRecovery: com.example.myapplication.audiobooks.chapters.ChapterRecoveryManager = koinInject()
    val recovered by recoveredStore.flow.collectAsState()
    val recoveredEntry = variant?.let { recovered[it.value] }
    // Субтитры: выбранный источник работает, пока книга играет; другой источник — остановлен.
    val subtitleStrings = remember(language) { subtitleStrings(language) }
    val subtitleModesStore: AudiobookSubtitleModes = koinInject()
    val subtitleModes by subtitleModesStore.modes.collectAsState()
    val bookAlignment: com.example.myapplication.audiobooks.text.BookAlignmentManager = koinInject()
    val deviceSubtitles: com.example.myapplication.audiobooks.text.DeviceSubtitleManager = koinInject()
    val subtitleMode = variant?.let { subtitleModes[it] } ?: AudiobookSubtitleMode.OFF
    val narrationForText = book?.narrationId
    LaunchedEffect(variant, subtitleMode, narrationForText) {
        val v = variant ?: return@LaunchedEffect
        when (subtitleMode) {
            AudiobookSubtitleMode.BOOK_TEXT -> {
                deviceSubtitles.disable(v)
                narrationForText?.let { bookAlignment.enable(v, it) { state.globalMs() } }
            }
            AudiobookSubtitleMode.DEVICE -> {
                bookAlignment.disable(v)
                deviceSubtitles.enable(v, if (strings.russian) "ru" else null) { state.globalMs() }
            }
            AudiobookSubtitleMode.OFF -> {
                bookAlignment.disable(v)
                deviceSubtitles.disable(v)
            }
        }
    }
    LaunchedEffect(variant, recoveredEntry?.applied) {
        state.timeline = variant?.let { v ->
            runCatching { resolver.manifest(v).let { BookTimeline(it.tracks, it.chapters) } }.getOrNull()
        }
        // Своя книга без разметки — главы ищутся сами, в фоне; результат ждёт согласия в «Главах».
        // С сайта — только по кнопке: книгу пришлось бы скачать целиком.
        if (variant != null && variant.value.startsWith("local:") && recoveredStore.get(variant) == null) {
            val raw = runCatching { resolver.rawManifest(variant) }.getOrNull()
            if (raw != null && com.example.myapplication.audiobooks.chapters.ChapterRecoveryManager.needsRecovery(raw)) {
                chapterRecovery.start(variant, strings.russian)
            }
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
    // Обложка живёт в БД: её смена («Ещё → обновить / импортировать») видна и в плеере, и на полках
    // сразу, а не только после перезапуска книги — метаданные текущего трека остаются прежними.
    val covers: BookCoverManager = koinInject()
    val storedCover by remember(book?.workId) {
        book?.workId?.let(repository::coverUrl) ?: flowOf(null)
    }.collectAsState(initial = null)
    var coverBusy by remember { mutableStateOf(false) }
    fun announceCover(result: BookCoverManager.Result) {
        val message = when (result) {
            is BookCoverManager.Result.Changed ->
                if (result.coverUrl.startsWith("collection-attachment://")) strings.coverImported else strings.coverChanged
            is BookCoverManager.Result.ChangedOnDevice -> strings.coverImportedLocal
            BookCoverManager.Result.NothingNew -> strings.coverNoOthers
            BookCoverManager.Result.Failed -> strings.coverFailed
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    val importCoverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        val workId = book?.workId
        if (picked != null && workId != null) {
            coverBusy = true
            scope.launch {
                val result = covers.import(workId, picked)
                coverBusy = false
                announceCover(result)
            }
        }
    }
    val controller = state.controller
    if (book == null || controller == null) return
    val shownBook = storedCover?.let { book.copy(artworkUri = it) } ?: book

    val isFull = expanded
    val sheetVisible = remember { MutableTransitionState(false) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var narration by remember { mutableStateOf(NarrationChoice()) }
    // Повторное открытие «Озвучки» отменяет прежний поиск, а не гонится с ним за результат.
    var narrationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
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
        val miniWidthDp = minOf(maxWidth - 32.dp, MiniWidth)
        val miniW = with(density) { miniWidthDp.toPx() }
        val miniH = with(density) { MiniHeight.toPx() }
        val marginPx = with(density) { 16.dp.toPx() }
        // Над доком капсула стоит, только пока он на экране; на страницах без дока (и когда док спрятан)
        // она садится к нижнему краю над навигацией, а не висит на высоте, где дока уже нет.
        val dockClearance = MiniPlayerClearance.bottom
        val dockPx = WindowInsets.navigationBars.getBottom(density).toFloat() +
            with(density) { (if (dockClearance > 0.dp) dockClearance + 10.dp else 16.dp).toPx() }
        val topPx = WindowInsets.statusBars.getTop(density).toFloat() + marginPx
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val right = (widthPx - miniW - marginPx).coerceAtLeast(marginPx)
        val bottom = (heightPx - miniH - dockPx).coerceAtLeast(marginPx)
        val miniOffset = remember(widthPx, heightPx) { Animatable(Offset(right, bottom), Offset.VectorConverter) }
        val miniRadiusPx = miniH / 2f
        var atBottom by remember { mutableStateOf(true) }
        // Закрыть: пауза и пустая очередь — мини-плеер уходит, место в книге сохранено, книга остаётся в «Продолжить».
        fun closeMini() {
            performHaptic(view, Haptic.Light)
            state.controller?.run { pause(); stop(); clearMediaItems() }
        }
        // Смахнули капсулу вниз за край, как мини-плеер в YouTube: она уезжает, плеер закрывается, а в
        // исходную точку капсула возвращается уже невидимой — к следующему запуску книги.
        fun dismissMini() {
            scope.launch {
                miniOffset.animateTo(Offset(miniOffset.value.x, heightPx), MotionTokens.springSurface())
                closeMini()
                kotlinx.coroutines.delay(300)
                atBottom = true
                miniOffset.snapTo(Offset(right, bottom))
            }
        }
        fun snapToCorner(leftSide: Boolean, topSide: Boolean) {
            atBottom = !topSide
            scope.launch {
                miniOffset.animateTo(
                    Offset(if (leftSide) marginPx else right, if (topSide) topPx else bottom),
                    MotionTokens.springSurface(),
                )
            }
        }
        // Док появился или спрятался: капсула, стоящая внизу, переезжает на новую высоту.
        LaunchedEffect(bottom) {
            if (atBottom && kotlin.math.abs(miniOffset.value.y - bottom) > 1f) {
                miniOffset.animateTo(
                    Offset(miniOffset.value.x.coerceIn(marginPx, right), bottom),
                    MotionTokens.springSurface(),
                )
            }
        }
        fun windowRect(p: Float): Rect {
            val m = miniOffset.value
            return Rect(lerp(m.x, 0f, p), lerp(m.y, 0f, p), lerp(m.x + miniW, widthPx, p), lerp(m.y + miniH, heightPx, p))
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
                    uri = shownBook.artworkUri,
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
                    book = shownBook,
                    state = state,
                    strings = strings,
                    onClose = ::closeMini,
                    onExpand = { expand() },
                    onDrag = { delta ->
                        scope.launch {
                            miniOffset.snapTo(Offset(
                                (miniOffset.value.x + delta.x).coerceIn(marginPx, right),
                                // Вниз можно тянуть дальше стоянки: так капсулу смахивают, чтобы закрыть.
                                (miniOffset.value.y + delta.y).coerceIn(topPx, bottom + miniH * 1.2f),
                            ))
                        }
                    },
                    onDragEnd = { velocityY ->
                        val pushed = miniOffset.value.y - bottom
                        if (pushed > miniH * 0.45f || (velocityY > 1800f && pushed > -miniH)) {
                            dismissMini()
                        } else {
                            snapToCorner(
                                leftSide = miniOffset.value.x + miniW / 2 < widthPx / 2,
                                topSide = miniOffset.value.y + miniH / 2 < heightPx / 2,
                            )
                        }
                    },
                    onMoveToCorner = ::snapToCorner,
                    modifier = Modifier
                        .offset { windowRect(expansion.value.coerceIn(0f, 1f)).topLeft.let { IntOffset(it.x.toInt(), it.y.toInt()) } }
                        .size(miniWidthDp, MiniHeight)
                        .graphicsLayer {
                            // Уходящая вниз капсула бледнеет: видно, что отпускание её закроет.
                            val sinking = ((miniOffset.value.y - bottom) / (miniH * 1.2f)).coerceIn(0f, 1f)
                            alpha = (1f - expansion.value / 0.3f).coerceIn(0f, 1f) * (1f - 0.5f * sinking)
                        },
                )
            }

            val fullVisible by remember { derivedStateOf { expansion.value > 0.05f } }
            if (fullVisible) {
                FullPlayerContent(
                    book = shownBook,
                    state = state,
                    strings = strings,
                    backdrop = backdrop,
                    favorite = favorite,
                    interactive = isFull,
                    onCollapse = { collapse() },
                    onFavorite = ::toggleFavorite,
                    markers = markers,
                    foundChapters = recoveredEntry?.takeIf { !it.applied && !it.declined }?.chapters?.size ?: 0,
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
                            narrationJob?.cancel()
                            narrationJob = scope.launch { narration = findNarrations(book, sources, search) }
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
                            subtitlesLabel = variant?.let { subtitleStrings.title + " · " + subtitleStrings.menuValue(subtitleModes[it] ?: AudiobookSubtitleMode.OFF) },
                            onSubtitles = { openSheet(PlayerSheet.SUBTITLES) },
                            coverBusy = coverBusy,
                            onReloadCover = {
                                val workId = book.workId
                                if (workId != null) {
                                    coverBusy = true
                                    Toast.makeText(context, strings.coverSearching, Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        val result = covers.reload(workId)
                                        coverBusy = false
                                        announceCover(result)
                                    }
                                }
                            },
                            onImportCover = { importCoverPicker.launch("image/*") },
                        )
                        PlayerSheet.SUBTITLES -> if (variant != null) SubtitlesSheetContent(
                            variant = variant,
                            narration = book.narrationId,
                            strings = subtitleStrings,
                            position = { state.globalMs() },
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

/**
 * Мини-плеер — капсула: круглая обложка, полоса-«волна» (она же прогресс главы) и кнопка play/pause.
 * Матовая капсула, а не стекло: настоящее стекло преломляло бы экран приложения под ним, а для этого
 * весь экран пришлось бы гнать через общий бэкдроп. Поэтому цвет — тёмный/светлый, чуть отличный от фона
 * своей темы. Размер — 2×1 по сетке виджетов. Тап раскрывает плеер, перетаскивание ведёт капсулу к углам,
 * смахивание вниз за край (или долгое нажатие) закрывает: креста нет, в 56dp он не помещается.
 */
@Composable
private fun MiniPlayerContent(
    book: PlayingBook,
    state: AudiobookPlayerState,
    strings: PlayerStrings,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (velocityY: Float) -> Unit,
    onMoveToCorner: (Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val surface = if (dark) Color(0xFF1C1C1E) else Color.White
    val ink = if (dark) Color.White else Color.Black
    val onInk = if (dark) Color.Black else Color.White
    // Живая «волна»: каждый столбик показывает громкость книги в своей точке вокруг «сейчас» на гладкой
    // кривой, которую AudioLevelMeter достраивает по отсчётам с самого аудиовыхода плеера (слева недавнее,
    // справа то, что прозвучит через миг). Каждый кадр пересчитываются только семь высот; подъём быстрый,
    // спад медленнее и считается по времени кадра, а не по их числу — на 60 и 120 Гц выглядит одинаково.
    // На паузе столбики оседают и цикл кадров останавливается.
    val playing = book.isPlaying
    val levels = remember { FloatArray(WaveBars) }
    var waveFrame by remember { mutableIntStateOf(0) }
    LaunchedEffect(playing) {
        var lastFrameNs = 0L
        while (isActive) {
            val frameNs = withFrameNanos { it }
            val dt = if (lastFrameNs == 0L) 0f else ((frameNs - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
            lastFrameNs = frameNs
            val rise = 1f - kotlin.math.exp(-dt / WaveAttackSec)
            val fall = 1f - kotlin.math.exp(-dt / WaveReleaseSec)
            val now = if (playing) AudioLevelMeter.nowUs() else Long.MIN_VALUE
            var settled = true
            for (i in levels.indices) {
                val target = if (now == Long.MIN_VALUE || now < 0L) 0f
                else AudioLevelMeter.levelAt(now + (i - WaveBars / 2) * WaveStepUs)
                // Рост быстрый, спад мягкий: так голос читается как пульс, а не как дрожь.
                levels[i] += (target - levels[i]) * (if (target > levels[i]) rise else fall)
                if (levels[i] > 0.02f) settled = false
            }
            waveFrame++
            if (!playing && settled) break
        }
    }
    val label = listOf(book.title, book.author).filter { it.isNotBlank() }.joinToString(" — ")
    Row(
        modifier
            .background(surface)
            .pointerInput(book.mediaId) {
                // Скорость отличает «смахнуть вниз» от доводки к углу. Координаты внутри капсулы едут вместе
                // с пальцем, поэтому пройденный путь копим сами.
                val tracker = VelocityTracker()
                var travelled = Offset.Zero
                detectDragGestures(
                    onDragStart = { tracker.resetTracking(); travelled = Offset.Zero },
                    onDragEnd = { onDragEnd(tracker.calculateVelocity().y) },
                    onDragCancel = { onDragEnd(0f) },
                    onDrag = { change, amount ->
                        change.consume()
                        travelled += amount
                        tracker.addPosition(change.uptimeMillis, travelled)
                        onDrag(amount)
                    },
                )
            }
            .pointerInput(book.mediaId) { detectTapGestures(onTap = { onExpand() }, onLongPress = { onClose() }) }
            .semantics {
                contentDescription = label
                customActions = listOf(
                    CustomAccessibilityAction(strings.moveMiniTopLeft) { onMoveToCorner(true, true); true },
                    CustomAccessibilityAction(strings.moveMiniTopRight) { onMoveToCorner(false, true); true },
                    CustomAccessibilityAction(strings.moveMiniBottomLeft) { onMoveToCorner(true, false); true },
                    CustomAccessibilityAction(strings.moveMiniBottomRight) { onMoveToCorner(false, false); true },
                    CustomAccessibilityAction(strings.closePlayer) { onClose(); true },
                )
            }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookArt(book.artworkUri, Modifier.size(44.dp).clip(CircleShape))
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(30.dp)
                .drawBehind {
                    if (waveFrame < 0) return@drawBehind   // чтение кадра: перерисовка на каждый кадр цикла
                    val barW = 3.dp.toPx()
                    val gap = (size.width - barW) / (WaveBars - 1)
                    for (i in 0 until WaveBars) {
                        val h = size.height * (0.14f + 0.86f * levels[i])
                        val x = i * gap
                        drawRoundRect(
                            color = ink,
                            topLeft = Offset(x, (size.height - h) / 2f),
                            size = Size(barW, h),
                            cornerRadius = CornerRadius(barW / 2f),
                        )
                    }
                },
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(ink)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.playPause() }
                .semantics { contentDescription = if (book.isPlaying) strings.pause else strings.play },
            contentAlignment = Alignment.Center,
        ) {
            PhIcon(if (book.isPlaying) R.drawable.ph_pause_fill else R.drawable.ph_play_fill, 20.dp, onInk)
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
    /** Найдено глав, ждущих согласия: плашка сверху ведёт в «Главы». */
    foundChapters: Int = 0,
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
            if (foundChapters >= 2) {
                Text(
                    strings.recoveryPill(foundChapters),
                    color = Color.White,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .clip(CircleShape)
                        .background(BrandOrange)
                        .clickable(enabled = interactive) { onSheet(PlayerSheet.CHAPTERS) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.weight(1f))
            }
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
            TrackUriCodec.decode(book.uri)?.variant?.let { v ->
                AudiobookSubtitleLine(v, state, remember(strings.russian) { subtitleStrings(if (strings.russian) AppLanguage.RU else AppLanguage.EN) },
                    Modifier.padding(top = 14.dp))
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

/** Мини-плеер — капсула; в полный экран она растёт той же пружиной, что и раньше квадрат. */
// 2×1 по сетке виджетов: две ячейки в ширину, одна в высоту.
/** Столбиков в «волне» мини-плеера и шаг между точками времени, которые они показывают. */
private const val WaveBars = 7
private const val WaveStepUs = 35_000L
/** Постоянные времени подъёма и спада столбика, секунды. */
private const val WaveAttackSec = 0.012f
private const val WaveReleaseSec = 0.09f
private val MiniHeight = 56.dp
private val MiniWidth = 160.dp

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

/**
 * Сколько снизу занято доком, над которым стоит капсула мини-плеера. Дока нет (страница без него, он
 * спрятан) — запись снимают, и капсула садится к краю. Владельцев несколько (рабочая область, Details),
 * берём наибольшее: пока один экран уезжает, а другой въезжает, оба могут быть в композиции.
 */
object MiniPlayerClearance {
    private val owners = mutableStateMapOf<Any, Dp>()

    val bottom: Dp get() = owners.values.maxOrNull() ?: 0.dp

    fun set(owner: Any, value: Dp) {
        if (value <= 0.dp) clear(owner) else if (owners[owner] != value) owners[owner] = value
    }

    fun clear(owner: Any) {
        if (owner in owners) owners.remove(owner)
    }
}
