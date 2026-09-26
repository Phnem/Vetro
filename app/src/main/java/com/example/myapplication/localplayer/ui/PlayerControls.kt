package com.example.myapplication.localplayer.ui

import androidx.compose.foundation.border
import com.example.myapplication.ui.shared.theme.IosScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.phnem.vetro.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.shared.loading.BubbleClusterLoader
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.example.myapplication.ui.shared.theme.BrandOrangeBright
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.roundToInt

private enum class PlayerMenu { AUDIO, SUBTITLES, SPEED }

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val Capsule = RoundedCornerShape(percent = 50)

/** Шаг перемотки дабл-тапом; каждый следующий тап подряд добавляет ещё один такой шаг. */
private const val SEEK_STEP_MS = 10_000L
/** Перемотка одним тапом по пузырьку «+89» слева от нижнего дока. */
private const val SEEK_FORWARD_MS = 89_000L
/** Сколько ждём следующий тап, прежде чем решить, что это был одиночный тап (показать контролы). */
private const val SINGLE_TAP_DELAY_MS = 220L
/** Сколько серия перемотки живёт после последнего тапа — окно накопления и время показа плашки. */
private const val SEEK_BURST_HOLD_MS = 750L

/**
 * Аудиодорожка и скорость в верхнем доке — крупнее остальных иконок дока (22.dp).
 *
 * Обе рисуются штриховыми глифами, и в общем размере читались хуже сплошных: тонкие линии на кадре
 * теряются. Растёт только глиф — слот кнопки остаётся 44.dp, чтобы ряд не разъезжал.
 */
private val TOP_DOCK_ICON = 29.dp


/** Активная серия дабл-тапов: сторона и сколько шагов по [SEEK_STEP_MS] уже накоплено. */
private data class SeekBurst(val forward: Boolean, val steps: Int)

/**
 * Кастомный скин контролов поверх видео: матовые доки ([ambientDockSurface]) + белая полоса
 * с оранжевым бегунком. Автоскрытие 3.5 c, тап по видео — показать/скрыть, дабл-тап по краю —
 * перемотка с накоплением (2 тапа = 10 c, 3 = 20 c, …), замок — заблокировать жесты.
 */
@Composable
fun PlayerControlsOverlay(
    player: ExoPlayer,
    title: String,
    /** Вторая строка шапки — серия и сезон. `null` = заголовок остаётся однострочным. */
    subtitle: String? = null,
    isPlaying: Boolean,
    isBuffering: Boolean,
    /** Позиция и буфер — лямбдами из [PlaybackClock]: их читают только полоса и метка времени. */
    position: () -> Long,
    buffered: () -> Long,
    duration: Long,
    hasPrev: Boolean,
    hasNext: Boolean,
    /**
     * Чем переключать серию. Локальный плеер их не передаёт: у него серии лежат в плейлисте
     * ExoPlayer, и переключение — это шаг по плейлисту. У стримингового плеера плейлиста нет,
     * соседняя серия резолвится снаружи, поэтому он подставляет свои обработчики.
     */
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    /**
     * Арбитраж щипка против однопальцевых жестов. Поднято в хост, потому что жест ловится здесь
     * (оверлей поверх видео), а положение кадра применяется там — к самой поверхности плеера.
     */
    pinchState: PlayerPinchState,
    audioTracks: List<AudioTrackOption>,
    speed: Float,
    fit: VideoFit,
    skipVisible: Boolean,
    onSkip: () -> Unit,
    /**
     * Предложение вернуть автоматический пропуск; `null` — предлагать нечего.
     *
     * С [skipVisible] не пересекается: ручная кнопка живёт при ВЫКЛЮЧЕННОМ автопропуске, эта —
     * только после его срабатывания. Поэтому обе занимают одно место в углу.
     */
    undoOffer: SkipUndoOffer? = null,
    onUndoSkip: () -> Unit = {},
    onRotate: () -> Unit,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectAudio: (AudioTrackOption) -> Unit,
    /** Пусто — кнопки субтитров нет (выбирать не из чего). */
    subtitleOptions: List<SubtitleOption> = emptyList(),
    onSelectSubtitle: (SubtitleOption) -> Unit = {},
    /**
     * Положение кадра. Один вход и для кнопки в доке, и для щипка: пользователь потребовал, чтобы
     * жест работал «прям 1в1» с кнопкой, а два входа в одно состояние гарантированно разъехались
     * бы при первой же правке.
     */
    onSetFit: (VideoFit) -> Unit,
    onControlsVisibleChange: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var menuKind by remember { mutableStateOf<PlayerMenu?>(null) }
    val menuState = remember { MutableTransitionState(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var unlockHintNonce by remember { mutableStateOf(0) }
    var showUnlockHint by remember { mutableStateOf(false) }

    // Жесты по видео: превью перемотки (свайп вбок) + накапливающаяся перемотка дабл-тапом.
    var seekPreview by remember { mutableStateOf<SeekPreview?>(null) }
    var seekBurst by remember { mutableStateOf<SeekBurst?>(null) }
    var lastBurst by remember { mutableStateOf<SeekBurst?>(null) }
    var tapCount by remember { mutableIntStateOf(0) }
    var tapForward by remember { mutableStateOf(true) }
    var burstBase by remember { mutableLongStateOf(0L) }
    var tapJob by remember { mutableStateOf<Job?>(null) }
    /** Палец держат в правой половине — идёт ускоренное воспроизведение. */
    var speedHeld by remember { mutableStateOf(false) }
    /** Громкость и яркость вертикальных свайпов — вместе с их плашкой поверх кадра. */
    val levels = rememberPlayerLevels()
    /** Последняя показанная зона — чтобы плашке было что рисовать, пока она уезжает. */
    var lastLevelZone by remember { mutableStateOf(VerticalZone.Brightness) }
    LaunchedEffect(levels.hud) { levels.hud?.let { lastLevelZone = it } }
    /** Скорость, к которой вернуться после удержания. Читается в момент отпускания, а не подписки. */
    val currentSpeed by rememberUpdatedState(speed)

    LaunchedEffect(controlsVisible) { onControlsVisibleChange(controlsVisible) }

    LaunchedEffect(controlsVisible, isPlaying, menuKind, isScrubbing, locked, seekPreview) {
        if (controlsVisible && isPlaying && menuKind == null && !isScrubbing && !locked && seekPreview == null) {
            delay(3500)
            controlsVisible = false
        }
    }
    LaunchedEffect(unlockHintNonce) {
        if (unlockHintNonce > 0) {
            showUnlockHint = true
            delay(2500)
            showUnlockHint = false
        }
    }
    LaunchedEffect(menuState.currentState, menuState.isIdle) {
        if (!menuState.currentState && menuState.isIdle) menuKind = null
    }

    /**
     * Один тап по видео. Второй и каждый следующий тап подряд с той же стороны не перезапускает
     * перемотку, а НАРАЩИВАЕТ её от позиции, зафиксированной на входе в серию: 2 тапа = 10 c,
     * 3 = 20 c, 4 = 30 c и так далее. Считаем сами (а не через onDoubleTap), потому что
     * detectTapGestures рапортует только пары и третий тап пришёл бы как новый одиночный.
     */
    fun onVideoTap(x: Float, widthPx: Int) {
        if (locked) {
            unlockHintNonce++
            return
        }
        tapJob?.cancel()
        if (duration <= 0L) {
            tapCount = 0
            controlsVisible = !controlsVisible
            return
        }

        val forward = x > widthPx / 2f
        tapCount = if (tapCount > 0 && forward == tapForward) tapCount + 1 else 1
        tapForward = forward

        if (tapCount == 1) {
            tapJob = scope.launch {
                delay(SINGLE_TAP_DELAY_MS)
                controlsVisible = !controlsVisible
                tapCount = 0
            }
            return
        }

        if (tapCount == 2) burstBase = player.currentPosition
        val steps = tapCount - 1
        val delta = steps * SEEK_STEP_MS * (if (forward) 1 else -1)
        player.seekTo((burstBase + delta).coerceIn(0L, duration))
        SeekBurst(forward, steps).let {
            seekBurst = it
            lastBurst = it
        }
        tapJob = scope.launch {
            delay(SEEK_BURST_HOLD_MS)
            seekBurst = null
            tapCount = 0
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Тапы и удержание — ОДИН обработчик. Разными их сделать нельзя: правая половина занята
            // накапливающейся перемоткой дабл-тапом, и два независимых распознавателя на одном
            // касании неминуемо отняли бы друг у друга либо тап, либо удержание.
            .pointerInput(locked, duration, pinchState) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val holdZone = !locked &&
                        isSpeedHoldZone(down.position.x, size.width.toFloat()) &&
                        !pinchState.swipesBlocked()
                    if (holdZone) {
                        // Ждём отпускания не дольше порога долгого нажатия. Уложился — это тап;
                        // не уложился (withTimeoutOrNull вернул null по таймауту) — удержание.
                        var released: PointerInputChange? = null
                        val finishedInTime = withTimeoutOrNull(
                            viewConfiguration.longPressTimeoutMillis,
                        ) {
                            released = waitForUpOrCancellation()
                        } != null
                        if (finishedInTime) {
                            val up = released ?: return@awaitEachGesture
                            up.consume()
                            onVideoTap(up.position.x, size.width)
                            return@awaitEachGesture
                        }
                        speedHeld = true
                        player.playbackParameters = PlaybackParameters(HOLD_SPEED)
                        try {
                            // Держим до отрыва последнего пальца. Движение не потребляем: пусть
                            // палец гуляет — жест от этого не должен рваться.
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                        } finally {
                            // Возврат — в finally: отмена жеста (второй палец, потеря окна, уход
                            // композиции) не должна оставить плеер ускоренным без причины.
                            speedHeld = false
                            // Скорость читаем СЕЙЧАС, а не ту, что была на момент подписки: иначе
                            // выбор из меню, сделанный вторым пальцем во время удержания, откатился
                            // бы обратно при отпускании.
                            // runCatching — на случай, когда композиция уходит вместе с активностью
                            // и плеер успевают освободить раньше, чем сюда доходит отмена.
                            runCatching {
                                player.playbackParameters = PlaybackParameters(currentSpeed)
                            }
                        }
                        return@awaitEachGesture
                    }
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    up.consume()
                    onVideoTap(up.position.x, size.width)
                }
            }
            // Щипок идёт ПЕРЕД перемоткой: он потребляет событие при двух пальцах, и до
            // detectHorizontalDragGestures мультитач тогда не доходит.
            .playerFitGestures(pinchState, enabled = !locked, onFit = onSetFit)
            .pointerInput(locked, duration, pinchState) {
                if (locked) return@pointerInput
                var startPos = 0L
                var target = 0L
                // Драг, начавшийся при мультитаче или в дебаунсе после щипка, перемоткой не
                // является. Решение принимается на onDragStart и держится весь драг: переобуться
                // в середине свайпа — это ровно тот скачок, ради которого ось и лочат.
                var blocked = false
                // Ось выбирается один раз за жест и больше не меняется: иначе палец, ведущий
                // перемотку, соскальзывал бы в яркость от малейшего наклона.
                var axis = DragAxis.Undecided
                var totalX = 0f
                var totalY = 0f
                val axisThresholdPx = DRAG_AXIS_THRESHOLD_DP * density
                // Половина экрана, где начался жест, — на весь жест. Палец, переехавший середину,
                // не должен посреди свайпа перескочить с яркости на громкость.
                var zone = VerticalZone.Brightness
                // Свайп, начатый у верхней кромки, уровни не трогает: это промах по системной
                // шторке, а не попытка покрутить громкость (см. LEVEL_DEAD_ZONE_TOP_DP).
                var levelsBlocked = false
                val deadZonePx = LEVEL_DEAD_ZONE_TOP_DP * density
                detectDragGestures(
                    onDragStart = { offset ->
                        // Во время удержания палец принадлежит ускорению, а не свайпам.
                        blocked = pinchState.swipesBlocked() || speedHeld
                        axis = DragAxis.Undecided
                        totalX = 0f
                        totalY = 0f
                        zone = verticalZoneAt(offset.x, size.width.toFloat())
                        levelsBlocked = isLevelDeadZone(offset.y, deadZonePx)
                    },
                    onDragEnd = {
                        if (!blocked && axis == DragAxis.Horizontal && duration > 0) {
                            player.seekTo(target)
                        }
                        seekPreview = null
                        levels.release()
                        blocked = false
                    },
                    onDragCancel = {
                        seekPreview = null
                        levels.release()
                        blocked = false
                    },
                    onDrag = { change, dragAmount ->
                        if (blocked) {
                            // Ничего: жест принадлежит щипку, а не свайпам.
                            return@detectDragGestures
                        }
                        change.consume()
                        totalX += dragAmount.x
                        totalY += dragAmount.y
                        if (axis == DragAxis.Undecided) {
                            axis = dominantDragAxis(totalX, totalY, axisThresholdPx)
                            when (axis) {
                                DragAxis.Horizontal -> {
                                    startPos = player.currentPosition
                                    target = startPos
                                    seekPreview = SeekPreview(startPos, target, duration)
                                }
                                DragAxis.Vertical -> if (!levelsBlocked) levels.begin(zone)
                                DragAxis.Undecided -> Unit
                            }
                        }
                        when (axis) {
                            DragAxis.Horizontal -> {
                                val msPerPx = 120_000f / size.width.coerceAtLeast(1)
                                target = (target + dragAmount.x * msPerPx)
                                    .toLong()
                                    .coerceIn(0L, duration)
                                seekPreview = SeekPreview(startPos, target, duration)
                            }
                            DragAxis.Vertical -> if (!levelsBlocked) {
                                levels.nudge(
                                    zone,
                                    levelDelta(dragAmount.y, size.height.toFloat()),
                                )
                            }
                            // Порог ещё не пройден — ось не выбрана, и трогать нечего.
                            DragAxis.Undecided -> Unit
                        }
                    },
                )
            },
    ) {
        // Индикатор загрузки — собственный слой, а не часть доков. Раньше он жил внутри
        // AnimatedVisibility(controlsVisible) вместе с рядом prev/play-pause/next и гас вместе с
        // ним: при автоскрытии через 3.5 c экран выглядел зависшим. Слой стоит до ветки locked,
        // потому что загрузка не перестаёт идти оттого, что пользователь заблокировал жесты.
        if (isBuffering) {
            BubbleClusterLoader(
                modifier = Modifier.align(Alignment.Center).size(46.dp),
                color = Color.White,
            )
        }

        if (locked) {
            AnimatedVisibility(
                visible = showUnlockHint,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(20.dp),
                enter = fadeIn(MotionTokens.standard()),
                exit = fadeOut(MotionTokens.dialogExit()),
            ) {
                // Без капсулы — как и остальные иконки плеера: подложка осталась бы единственным
                // доком на экране.
                DockIconButton(
                    icon = painterResource(R.drawable.ic_player_lock_open),
                    contentDescription = if (playerIsRu()) "Разблокировать" else "Unlock",
                    tint = Color.White,
                    onClick = { locked = false; showUnlockHint = false; controlsVisible = true },
                )
            }
            return@Box
        }

        // Превью перемотки свайпом + «линза» дабл-тапа — поверх, независимо от контролов.
        seekPreview?.let { SeekPreviewOverlay(it, Modifier.align(Alignment.Center)) }
        AnimatedVisibility(
            visible = seekBurst != null,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(280)),
        ) {
            lastBurst?.let { SeekRipple(it) }
        }

        // Плашка громкости/яркости — там же, где превью перемотки: одновременно они не появятся,
        // ось жеста залочена на первых же пикселях.
        AnimatedVisibility(
            visible = levels.hud != null,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(MotionTokens.standard()),
            exit = fadeOut(MotionTokens.dialogExit()),
        ) {
            // Зону запоминаем: на выезде плашки levels.hud уже null, и без этого она бы моргнула
            // чужой иконкой.
            val zone = levels.hud ?: lastLevelZone
            LevelHud(zone = zone, level = levels.levelOf(zone))
        }

        // Плашка «2×» — единственный признак, что ускорение включилось: без неё жест неотличим от
        // случайно залипшего пальца.
        AnimatedVisibility(
            visible = speedHeld,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 72.dp),
            enter = fadeIn(MotionTokens.standard()),
            exit = fadeOut(MotionTokens.dialogExit()),
        ) {
            Row(
                modifier = Modifier
                    .clip(Capsule)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${HOLD_SPEED.toInt()}×",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Кнопка «Пропустить» — весь опенинг, независимо от автоскрытия.
        if (skipVisible) {
            SkipButton(
                onClick = onSkip,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 104.dp),
            )
        }

        // Отмена только что сработавшего автопропуска — там же и в той же форме, что ручная
        // кнопка: это то же самое действие в обратную сторону, и искать его в другом углу
        // пользователю незачем.
        if (undoOffer != null) {
            UndoSkipButton(
                secondsLeft = undoOffer.secondsLeft,
                onClick = onUndoSkip,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 104.dp),
            )
        }

        // ——— Затемняющие градиенты ———
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(MotionTokens.standard()),
            exit = fadeOut(MotionTokens.dialogExit()),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxWidth().height(160.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent)),
                )
                Box(
                    Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))),
                )
            }
        }

        // ——— Верхний ряд ———
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            enter = fadeIn(MotionTokens.standard()) + slideInVertically(MotionTokens.sheetOffset) { -it },
            exit = fadeOut(MotionTokens.dialogExit()) + slideOutVertically(MotionTokens.dialogExit()) { -it },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Статус-бар спрятан ([ImmersivePlayerWindow]), поэтому его инсет нулевой —
                    // от выреза камеры отступаем отдельно.
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Капсул в шапке больше нет: иконки и заголовок лежат прямо на кадре. Читаемость
                // держит затемнение по краям кадра, а не подложка под каждым элементом.
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TransportIcon(
                            painterResource(R.drawable.ic_player_back), 40.dp, 24.dp,
                            tint = Color.White, onClick = onBack,
                        )
                        // Две строки: название и, если есть, серия/сезон под ним.
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = SnProFamily,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = SnProFamily,
                                    ),
                                    color = Color.White.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    DockIconButton(
                        icon = painterResource(R.drawable.ic_player_audio),
                        contentDescription = if (playerIsRu()) "Аудиодорожка" else "Audio track",
                        tint = Color.White,
                        iconSize = TOP_DOCK_ICON,
                        onClick = {
                            if (audioTracks.isNotEmpty()) { menuKind = PlayerMenu.AUDIO; menuState.targetState = true }
                        },
                    )
                    if (subtitleOptions.isNotEmpty()) {
                        DockIconButton(
                            icon = painterResource(R.drawable.ic_player_subtitles),
                            contentDescription = if (playerIsRu()) "Субтитры" else "Subtitles",
                            tint = Color.White,
                            iconSize = TOP_DOCK_ICON,
                            onClick = { menuKind = PlayerMenu.SUBTITLES; menuState.targetState = true },
                        )
                    }
                    DockIconButton(
                        icon = painterResource(R.drawable.ic_player_speed),
                        contentDescription = if (playerIsRu()) "Скорость" else "Speed",
                        tint = Color.White,
                        iconSize = TOP_DOCK_ICON,
                        onClick = { menuKind = PlayerMenu.SPEED; menuState.targetState = true },
                    )
                }
            }
        }

        // ——— Центр: prev / play-pause / next (поверх голого кадра — всегда белые) ———
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(MotionTokens.standard()),
            exit = fadeOut(MotionTokens.dialogExit()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                TransportIcon(
                    painterResource(R.drawable.ic_player_skip_back), 56.dp, 34.dp, enabled = hasPrev,
                ) {
                    if (onPrev != null) onPrev() else player.seekToPreviousMediaItem()
                }
                // Пока идёт загрузка, место кнопки пустует: индикатор рисует отдельный слой ниже
                // по дереву, в той же точке. Слот сохраняется, чтобы ряд не съезжал.
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    if (!isBuffering) {
                        TransportIcon(
                            painterResource(
                                if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
                            ),
                            72.dp, 46.dp,
                        ) { if (isPlaying) player.pause() else player.play() }
                    }
                }
                TransportIcon(
                    painterResource(R.drawable.ic_player_skip_forward), 56.dp, 34.dp, enabled = hasNext,
                ) {
                    if (onNext != null) onNext() else player.seekToNextMediaItem()
                }
            }
        }

        // ——— Низ: время + бегунок, справа док ———
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            enter = fadeIn(MotionTokens.standard()) + slideInVertically(MotionTokens.sheetOffset) { it },
            exit = fadeOut(MotionTokens.dialogExit()) + slideOutVertically(MotionTokens.dialogExit()) { it },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Лёгкое затемнение вместо капсулы под иконками: тянется книзу, где и лежит
                    // ряд иконок. background до отступов — затемнение доходит до края экрана.
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.35f to Color.Black.copy(alpha = 0.28f),
                            1f to Color.Black.copy(alpha = 0.62f),
                        )
                    )
                    .navigationBarsPadding()
                    .displayCutoutPadding()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Время и полоса — в контейнере 90% ширины (5%–95%), чтобы время было строго над
                // левым концом полосы.
                Column(modifier = Modifier.fillMaxWidth(0.9f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaybackTimeLabel(position = position, duration = duration)
                    }

                    SeekBar(
                        modifier = Modifier.fillMaxWidth(),
                        position = position,
                        buffered = buffered,
                        duration = duration,
                        thumbColor = BrandOrangeBright,
                        onScrubStart = { isScrubbing = true },
                        onScrubEnd = { fraction ->
                            isScrubbing = false
                            if (duration > 0) player.seekTo((fraction * duration).toLong())
                        },
                    )

                    // Ряд действий — под полосой, на затемнении и без капсулы, по центру.
                    // Подписи есть только в ландшафте: в портрете ряд из пяти пар «иконка+текст»
                    // не помещается по ширине, поэтому остаются одни иконки.
                    val showActionLabels =
                        LocalConfiguration.current.orientation != Configuration.ORIENTATION_PORTRAIT
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerActionButton(
                            icon = painterResource(R.drawable.ic_player_rotate),
                            label = "Rotate",
                            showLabel = showActionLabels,
                            onClick = onRotate,
                        )
                        // Перемотка на длину заставки — тот же переход, что был у «+89» над полосой.
                        PlayerActionButton(
                            icon = painterResource(R.drawable.ic_player_number_89),
                            label = "Skip Intro",
                            showLabel = showActionLabels,
                            onClick = {
                                player.seekTo(
                                    seekForwardTarget(player.currentPosition, duration, SEEK_FORWARD_MS)
                                )
                            },
                        )
                        // Подпись описывает ДЕЙСТВИЕ, а не текущее состояние: когда кадр обрезан,
                        // нажатие вернёт видео целиком («Fit»), иначе — расширит с обрезкой («Fill»).
                        PlayerActionButton(
                            icon = painterResource(
                                if (fit == VideoFit.CROP) {
                                    R.drawable.ic_player_fit_off
                                } else {
                                    R.drawable.ic_player_fit
                                }
                            ),
                            label = if (fit == VideoFit.CROP) "Fit" else "Fill",
                            showLabel = showActionLabels,
                            onClick = {
                                onSetFit(
                                    if (fit == VideoFit.CROP) VideoFit.ORIGINAL else VideoFit.CROP
                                )
                            },
                        )
                        PlayerActionButton(
                            icon = painterResource(R.drawable.ic_player_lock),
                            label = "Lock",
                            showLabel = showActionLabels,
                            onClick = { locked = true; controlsVisible = false },
                        )
                        PlayerActionButton(
                            icon = painterResource(R.drawable.ic_player_pip),
                            label = "PiP",
                            showLabel = showActionLabels,
                            onClick = onEnterPip,
                        )
                    }
                }
            }
        }

        val kind = menuKind
        if (kind != null && (menuState.targetState || menuState.currentState || !menuState.isIdle)) {
            OptionMenu(
                state = menuState,
                menu = kind,
                audioTracks = audioTracks,
                subtitleOptions = subtitleOptions,
                speed = speed,
                onSelectSpeed = { onSelectSpeed(it); menuState.targetState = false },
                onSelectAudio = { onSelectAudio(it); menuState.targetState = false },
                onSelectSubtitle = { onSelectSubtitle(it); menuState.targetState = false },
                onDismiss = { menuState.targetState = false },
            )
        }
    }
}

/**
 * Полоса прокрутки: белая (пройденное — сплошной белый, хвост — полупрозрачный) с оранжевым
 * круглым бегунком. В покое тонкая, на время перетаскивания раздувается вместе с бегунком и
 * показывает всплывающее время над собой.
 *
 * Геометрия (отступы под бегунок и его ход) считается по ФИКСИРОВАННОМУ слоту [THUMB_SLOT], а
 * анимируется только видимый размер — иначе на время анимации заливка уезжала бы от бегунка.
 */
/**
 * «0:42 / 23:40». Пересобирается раз в секунду — когда меняется показанная секунда, — а не на
 * каждом тике часов плеера.
 */
@Composable
private fun PlaybackTimeLabel(position: () -> Long, duration: Long) {
    val seconds by remember(position) { derivedStateOf { position() / 1_000L } }
    Text(
        text = "${formatTime(seconds * 1_000L)} / ${formatTime(duration)}",
        style = MaterialTheme.typography.labelLarge.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Medium),
        color = Color.White,
    )
}

@Composable
private fun SeekBar(
    position: () -> Long,
    buffered: () -> Long,
    duration: Long,
    thumbColor: Color,
    onScrubStart: () -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val slotPx = with(density) { THUMB_SLOT.toPx() }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    // Держим бегунок на отпущенной позиции, пока плеер реально не доедет туда — иначе он на кадр
    // «отскакивает» к старому месту (лаг).
    var pendingSeek by remember { mutableStateOf<Float?>(null) }
    var bubbleWidthPx by remember { mutableIntStateOf(0) }

    val trackHeight by animateDpAsState(
        if (isScrubbing) TRACK_SCRUBBING else TRACK_IDLE,
        MotionTokens.standard(),
        label = "seekTrackHeight",
    )
    val thumbSize by animateDpAsState(
        if (isScrubbing) THUMB_SCRUBBING else THUMB_IDLE,
        MotionTokens.standard(),
        label = "seekThumbSize",
    )

    // Читается только в лямбдах отрисовки и слоя: ход позиции перерисовывает полосу, не
    // пересобирая её. Все входы — состояния, поэтому ключи remember — лишь то, что не состояние.
    val fraction by remember(position, duration) {
        derivedStateOf {
            val pending = pendingSeek
            when {
                isScrubbing -> scrubFraction
                pending != null -> pending
                duration > 0 -> (position().toFloat() / duration).coerceIn(0f, 1f)
                else -> 0f
            }
        }
    }
    // Отпускаем удержание бегунка, когда плеер доехал до отпущенного места.
    LaunchedEffect(position, duration, pendingSeek) {
        val ps = pendingSeek ?: return@LaunchedEffect
        if (duration <= 0) return@LaunchedEffect
        snapshotFlow { position() }.first { kotlin.math.abs(it.toFloat() / duration - ps) < 0.02f }
        pendingSeek = null
    }

    BoxWithConstraints(
        modifier = modifier
            .height(30.dp)
            .pointerInput(duration) {
                val padPx = slotPx / 2f
                fun xToFraction(x: Float): Float {
                    val usable = (size.width - slotPx).coerceAtLeast(1f)
                    return ((x - padPx) / usable).coerceIn(0f, 1f)
                }
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        down.consume()
                        isScrubbing = true
                        onScrubStart()
                        performHaptic(view, Haptic.Light)
                        scrubFraction = xToFraction(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                            if (!change.pressed) {
                                change.consume()
                                performHaptic(view, Haptic.Heavy)
                                isScrubbing = false
                                pendingSeek = scrubFraction
                                onScrubEnd(scrubFraction)
                                break
                            }
                            change.consume()
                            scrubFraction = xToFraction(change.position.x)
                        }
                    }
                }
            },
    ) {
        val travelPx = with(density) { (maxWidth - THUMB_SLOT).toPx() }

        // Полоса: хвост + буфер + белое пройденное.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .align(Alignment.Center)
                .clip(Capsule)
                .drawBehind {
                    val h = size.height
                    val r = h / 2f
                    val pad = slotPx / 2f
                    val usable = (size.width - slotPx).coerceAtLeast(1f)
                    val bufferedFraction =
                        if (duration > 0) (buffered().toFloat() / duration).coerceIn(0f, 1f) else 0f
                    drawRoundRect(color = Color.White.copy(alpha = 0.28f), cornerRadius = CornerRadius(r, r))
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.48f),
                        size = Size((pad + usable * bufferedFraction).coerceAtLeast(h), h),
                        cornerRadius = CornerRadius(r, r),
                    )
                    drawRoundRect(
                        color = Color.White,
                        size = Size((pad + usable * fraction).coerceAtLeast(h), h),
                        cornerRadius = CornerRadius(r, r),
                    )
                },
        )

        // Оранжевый круглый бегунок в фиксированном слоте.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer { translationX = travelPx * fraction }
                .size(THUMB_SLOT),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(thumbColor),
            )
        }

        // Всплывающее время над бегунком — только пока тащим.
        if (isScrubbing && duration > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged { bubbleWidthPx = it.width }
                    .graphicsLayer {
                        translationX = (travelPx * fraction + slotPx / 2f - bubbleWidthPx / 2f)
                            .coerceIn(0f, (travelPx + slotPx - bubbleWidthPx).coerceAtLeast(0f))
                        translationY = -size.height - with(density) { 6.dp.toPx() }
                    },
            ) {
                Text(
                    text = formatTime((fraction * duration).toLong()),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                )
            }
        }
    }
}

/** Геометрия «линзы» дабл-тапа: радиусы в долях экрана и вынос центра за внешний край. */
private const val RIPPLE_RX = 0.54f
private const val RIPPLE_RY = 0.62f
private const val RIPPLE_CX = -0.039f

private val THUMB_SLOT = 20.dp
private val TRACK_IDLE = 4.dp
private val TRACK_SCRUBBING = 11.dp
private val THUMB_IDLE = 13.dp
private val THUMB_SCRUBBING = 19.dp

/**
 * Индикатор перемотки дабл-тапом: не капсула, а мягкая «линза» во всю высоту, прижатая к краю
 * экрана, с двумя стрелками и накопленной суммой внутри. Форма — большой овал, у которого видна
 * только часть: в середине он выпирает к центру экрана, к верху и низу уходит за край.
 */
@Composable
private fun SeekRipple(burst: SeekBurst, modifier: Modifier = Modifier) {
    val seconds = burst.steps * SEEK_STEP_MS / 1000
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            // Овал вылезает за верх, низ и внешний край экрана — видна только его внутренняя дуга.
            // Радиусы подобраны так, чтобы дуга шла ~0.72 ширины у краёв и ~0.50 в середине:
            // тот самый заметный выгиб к центру, а не почти прямая вертикаль.
            val rx = size.width * RIPPLE_RX
            val ry = size.height * RIPPLE_RY
            val cx = if (burst.forward) size.width * (1f - RIPPLE_CX) else size.width * RIPPLE_CX
            val left = cx - rx
            val top = size.height / 2f - ry
            val inner = Color.White.copy(alpha = 0.05f)
            val outer = Color.White.copy(alpha = 0.20f)
            drawOval(
                brush = Brush.horizontalGradient(
                    colors = if (burst.forward) listOf(inner, outer) else listOf(outer, inner),
                    startX = left,
                    endX = left + rx * 2f,
                ),
                topLeft = Offset(left, top),
                size = Size(rx * 2f, ry * 2f),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .fillMaxHeight()
                .align(if (burst.forward) Alignment.CenterEnd else Alignment.CenterStart),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                repeat(2) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { scaleX = if (burst.forward) 1f else -1f },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (playerIsRu()) "$seconds секунд" else "$seconds seconds",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun SkipButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = Color.White
    Row(
        modifier = modifier
            .playerOutlineCapsule()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (playerIsRu()) "Пропустить" else "Skip",
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold),
            color = content,
        )
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
    }
}

/**
 * Отмена автопропуска: тот же силуэт, что у [SkipButton], — та же капсула, тот же ambient-материал,
 * то же место.
 *
 * Отличают её три вещи: текст, обратная иконка и счётчик оставшихся секунд. Счётчик здесь не
 * украшение, а единственный способ показать, что предложение временное: кнопка появилась сама, без
 * нажатия, и молча исчезающий элемент читался бы как сбой.
 */
@Composable
private fun UndoSkipButton(
    secondsLeft: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = Color.White
    Row(
        modifier = modifier
            .playerOutlineCapsule()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (playerIsRu()) "Вернуть" else "Undo",
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
            ),
            color = content,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.AutoMirrored.Rounded.Undo,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                // Счётчик — белое кольцо, в тон рамке кнопки.
                .border(1.dp, content.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = secondsLeft.toString(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                ),
                color = content,
            )
        }
    }
}

/** Позиция после тапа по «+89»: вперёд на [amountMs], не дальше конца, если он уже известен. */
internal fun seekForwardTarget(position: Long, duration: Long, amountMs: Long): Long =
    if (duration > 0L) (position + amountMs).coerceAtMost(duration) else position + amountMs

/**
 * Меню аудио/скорости в стиле стопки ресурсов на карточках: столбик плоских пилюль,
 * «вылетающих» из верхнего-правого угла (нота/спидометр) пружиной [MotionTokens.menuPop],
 * поверх затемняющего скрима. Закрытие — обратное «всасывание».
 */
@Composable
private fun OptionMenu(
    state: MutableTransitionState<Boolean>,
    menu: PlayerMenu,
    audioTracks: List<AudioTrackOption>,
    subtitleOptions: List<SubtitleOption>,
    speed: Float,
    onSelectSpeed: (Float) -> Unit,
    onSelectAudio: (AudioTrackOption) -> Unit,
    onSelectSubtitle: (SubtitleOption) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = true) { onDismiss() }
    val origin = TransformOrigin(1f, 0f) // «вытекает» из верхнего-правого дока
    val labels = when (menu) {
        PlayerMenu.AUDIO -> audioTracks.map { it.label }
        PlayerMenu.SUBTITLES -> subtitleOptions.map { it.label }
        PlayerMenu.SPEED -> SPEED_OPTIONS.map { s ->
            if (s == 1f) (if (playerIsRu()) "Обычная (1×)" else "Normal (1×)") else "${trimSpeed(s)}×"
        }
    }
    val selectedIndex = when (menu) {
        PlayerMenu.AUDIO -> audioTracks.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        PlayerMenu.SUBTITLES -> subtitleOptions.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        PlayerMenu.SPEED -> SPEED_OPTIONS.indexOfFirst { it == speed }.coerceAtLeast(0)
    }
    fun commit(index: Int) {
        when (menu) {
            PlayerMenu.AUDIO -> audioTracks.getOrNull(index)?.let(onSelectAudio)
            PlayerMenu.SUBTITLES -> subtitleOptions.getOrNull(index)?.let(onSelectSubtitle)
            PlayerMenu.SPEED -> SPEED_OPTIONS.getOrNull(index)?.let(onSelectSpeed)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visibleState = state,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(MotionTokens.menuPop()),
            exit = fadeOut(MotionTokens.sheetDismissForced()),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
                    // Скрим глушит ПЕРЕТАСКИВАНИЯ: тап-детектор их не потребляет, и раньше любое
                    // движение пальцем по открытому меню проваливалось в жесты плеера под ним —
                    // список «нельзя было прокрутить», вместо этого шла перемотка.
                    .pointerInput(Unit) { consumeDragsOnly() },
            )
        }
        AnimatedVisibility(
            visibleState = state,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(MotionTokens.menuPop()),
            exit = fadeOut(MotionTokens.sheetDismissForced()),
        ) {
            if (labels.size >= ARC_MENU_THRESHOLD) {
                // Длинный список — барабан-дуга от правого края (см. ArcCapsuleMenu).
                ArcCapsuleMenu(
                    labels = labels,
                    selectedIndex = selectedIndex,
                    onCommit = { index ->
                        commit(index)
                        onDismiss()
                    },
                    modifier = Modifier.statusBarsPadding().displayCutoutPadding(),
                )
            } else {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .displayCutoutPadding()
                            .padding(top = 66.dp, end = 14.dp)
                            .verticalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior()),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (menu) {
                            PlayerMenu.AUDIO -> audioTracks.forEach { opt ->
                                MenuPill(opt.label, opt.isSelected) { onSelectAudio(opt) }
                            }
                            PlayerMenu.SUBTITLES -> subtitleOptions.forEach { opt ->
                                MenuPill(opt.label, opt.isSelected) { onSelectSubtitle(opt) }
                            }
                            PlayerMenu.SPEED -> SPEED_OPTIONS.forEach { s ->
                                MenuPill(
                                    label = if (s == 1f) (if (playerIsRu()) "Обычная (1×)" else "Normal (1×)") else "${trimSpeed(s)}×",
                                    selected = s == speed,
                                ) { onSelectSpeed(s) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Съедает только движения пальца, оставляя «чистые» тапы вышестоящему детектору: у тапа нет
 * смещения, поэтому потреблять нечего и обработчик закрытия по клику продолжает работать.
 */
private suspend fun PointerInputScope.consumeDragsOnly() {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { change ->
                if (change.positionChange() != Offset.Zero) change.consume()
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}

@Composable
private fun MenuPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .playerOutlineCapsule()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(BrandOrangeBright))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = SnProFamily,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (selected) BrandOrangeBright else Color.White,
        )
    }
}

/** Состояние превью перемотки свайпом: база, цель, длительность (для строки времени). */
private data class SeekPreview(val base: Long, val target: Long, val duration: Long)

@Composable
private fun SeekPreviewOverlay(preview: SeekPreview, modifier: Modifier = Modifier) {
    val delta = preview.target - preview.base
    val sign = if (delta >= 0) "+" else "−"
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatTime(preview.target),
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "$sign${formatTime(kotlin.math.abs(delta))}",
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Medium),
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

/**
 * Плашка уровня — капсула с иконкой, полосой и процентом, как у системных регуляторов.
 *
 * Полоса без анимации: значение обязано стоять ровно под пальцем, а любой `animate*AsState` между
 * ними вставил бы задержку — на быстром свайпе плашка бы отставала от звука.
 */
@Composable
private fun LevelHud(zone: VerticalZone, level: Float, modifier: Modifier = Modifier) {
    val fraction = level.coerceIn(0f, 1f)
    val icon = when {
        zone == VerticalZone.Brightness -> R.drawable.ic_player_brightness
        fraction <= 0f -> R.drawable.ic_player_volume_off
        else -> R.drawable.ic_player_volume
    }
    Row(
        modifier = modifier
            .fillMaxWidth(0.7f)
            .clip(Capsule)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(Capsule)
                .background(Color.White.copy(alpha = 0.28f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(Capsule)
                    .background(Color.White),
            )
        }
        Spacer(Modifier.width(18.dp))
        Text(
            // Фиксированная ширина: без неё полоса дёргалась бы туда-сюда на каждом переходе
            // через 9 → 10 → 100.
            modifier = Modifier.width(42.dp),
            text = (fraction * 100).roundToInt().toString(),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Medium,
            ),
            color = Color.White,
        )
    }
}

/** Иконка транспорта без фона. Без окраски по нажатию. */
@Composable
private fun TransportIcon(
    icon: ImageVector,
    size: Dp,
    iconSize: Dp,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(iconSize))
    }
}

/**
 * То же, но для иконок из ресурсов. Пак плеера — контурный (Tabler, обводка 2), в отличие от
 * заливных Material-иконок, поэтому он приходит как [Painter], а не [ImageVector].
 */
@Composable
private fun TransportIcon(
    icon: Painter,
    size: Dp,
    iconSize: Dp,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(iconSize))
    }
}

/**
 * Действие в нижнем ряду плеера: иконка и подпись рядом, без подложки.
 *
 * Подпись убирается в портрете ([showLabel]) — пять пар «иконка+текст» туда не влезают.
 * Иконка крупнее доковой: ряд лежит прямо на кадре, и мелкая контурная графика на нём теряется.
 */
@Composable
private fun PlayerActionButton(
    icon: Painter,
    label: String,
    showLabel: Boolean,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    Row(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Medium,
                ),
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/** Иконка-кнопка плеера: белая иконка без подложки, как все контролы поверх кадра. */
@Composable
private fun DockIconButton(
    icon: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    contentDescription: String? = null,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Иконка-кнопка плеера: белая иконка без подложки, как все контролы поверх кадра. */
@Composable
private fun DockIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

private fun trimSpeed(s: Float): String =
    if (s % 1f == 0f) s.toInt().toString() else s.toString().trimEnd('0').trimEnd('.')

/**
 * Общая форма боковых кнопок и пунктов меню плеера: капсула без заливки с белым кантом — тот же
 * язык, что у остальных контролов поверх кадра (белые иконки и текст без подложек).
 *
 * Заменила «амбиентный» материал, который раз в секунду снимал кадр через PixelCopy, размывал
 * его и перекрашивал кнопки под сцену: ради двух боковых кнопок и меню это были два битмапа,
 * блюр на CPU и четыре цветовые анимации, пересобиравшие хост плеера на каждом кадре.
 */
private fun Modifier.playerOutlineCapsule(): Modifier =
    this.border(1.dp, Color.White.copy(alpha = 0.9f), Capsule)
