package com.example.myapplication.audiobooks.ui

import android.os.Bundle
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline
import com.example.myapplication.audiobooks.playback.AudiobookSessionCommands
import com.example.myapplication.ui.shared.components.IosSwitch
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleCornerShape
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import com.phnem.vetro.R
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

// ==========================================
// Шторки плеера. Плеер всегда тёмный (под ним обложка), поэтому и шторки тёмные независимо от темы.
// Числа выбираются колесом-линейкой с щелчком хаптика на каждом делении (референс пользователя),
// главы — в том же виде, что оглавление манги (MangaChaptersPage): группа строк одной карточкой.
// ==========================================

internal enum class PlayerSheet { CHAPTERS, SPEED, TIMER, NARRATION, MORE, MARKERS, SUBTITLES }

private val Ink = Color.White
private val Muted = Color.White.copy(alpha = 0.6f)

/** Общая оболочка шторок плеера. */
@Composable
internal fun PlayerSheetFrame(
    visible: MutableTransitionState<Boolean>,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    MotionBottomSheet(
        visibleState = visible,
        onDismiss = onDismiss,
        isDark = true,
        panelModifier = Modifier.fillMaxWidth(),
        containerColor = Color(0xFF17130F),
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 28.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            content = content,
        )
    }
}

@Composable
private fun SheetTitle(text: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text, color = Ink, fontFamily = SnProFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp,
            modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = Muted, fontFamily = SnProFamily, fontSize = 14.sp)
    }
}

/** Главная кнопка шторки — белая капсула, как «Start» в референсе. */
@Composable
private fun PrimaryCapsule(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(0xFF111111), fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) Color(0xFF111111) else Muted,
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

// ---------- Колесо-линейка ----------

/**
 * Горизонтальная линейка из делений: палец листает, деление под центральной риской — выбранное.
 * Каждое пересечение деления — щелчок хаптика. Длинные деления — каждые [majorEvery].
 */
@Composable
internal fun TickRuler(
    count: Int,
    selected: Int,
    majorEvery: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 14.dp,
) {
    val view = LocalView.current
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, count - 1))
    val fling = rememberSnapFlingBehavior(lazyListState = state, snapPosition = SnapPosition.Center)
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    val centered by remember {
        derivedStateOf {
            ((state.firstVisibleItemIndex * spacingPx + state.firstVisibleItemScrollOffset) / spacingPx)
                .roundToInt().coerceIn(0, count - 1)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { centered }.distinctUntilChanged().drop(1).collect {
            performHaptic(view, Haptic.Tick)
            onSelected(it)
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(64.dp)) {
        val side = maxWidth / 2 - spacing / 2
        LazyRow(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(horizontal = side),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                // Края растворяются: линейка «уходит» за границы, а не обрывается.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent, 0.22f to Color.Black, 0.78f to Color.Black, 1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            items(count) { i ->
                Box(Modifier.width(spacing).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val major = i % majorEvery == 0
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(if (major) 28.dp else 16.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (major) 0.62f else 0.32f)),
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .width(3.dp)
                .height(40.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

// ---------- Таймер сна ----------

@Composable
internal fun ColumnScope.SleepTimerSheetContent(
    state: AudiobookPlayerState,
    strings: PlayerStrings,
    onDismiss: () -> Unit,
) {
    val controller = state.controller ?: return
    var minutes by remember { mutableStateOf(DEFAULT_SLEEP_MINUTES) }
    val remaining = state.sleepRemainingMs
    SheetTitle(strings.sleepTitle, if (remaining >= 0) strings.stopsIn(formatClock(remaining)) else null)
    Spacer(Modifier.height(18.dp))
    Text(
        strings.duration(minutes),
        color = Ink,
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Spacer(Modifier.height(10.dp))
    TickRuler(count = MAX_SLEEP_MINUTES, selected = minutes - 1, majorEvery = 5, onSelected = { minutes = it + 1 })
    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // До конца главы — пересчитываем остаток главы в минуты прослушивания с учётом скорости.
        val book = state.book
        val chapterLeftMin = book?.let {
            val pos = chapterPosition(it, state.timeline, state.trackPositionMs.longValue)
            pos.durationMs?.let { d -> ceil((d - pos.positionMs).coerceAtLeast(0L) / it.speed / 60_000.0).toInt() }
        }
        if (chapterLeftMin != null && chapterLeftMin in 1..MAX_SLEEP_MINUTES) {
            Pill(strings.endOfChapter, selected = false) {
                start(controller, chapterLeftMin)
                onDismiss()
            }
        }
        if (remaining >= 0) {
            Pill(strings.turnOff, selected = false) {
                controller.sendCustomCommand(AudiobookSessionCommands.cancelSleepTimer, Bundle.EMPTY)
                onDismiss()
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    PrimaryCapsule(strings.start) {
        start(controller, minutes)
        onDismiss()
    }
}

private fun start(controller: androidx.media3.session.MediaController, minutes: Int) {
    controller.sendCustomCommand(
        AudiobookSessionCommands.setSleepTimer,
        Bundle().apply { putInt(AudiobookSessionCommands.MINUTES, minutes) },
    )
}

// ---------- Скорость ----------

@Composable
internal fun ColumnScope.SpeedSheetContent(state: AudiobookPlayerState, strings: PlayerStrings) {
    val controller = state.controller ?: return
    val speed = state.book?.speed ?: 1f
    SheetTitle(strings.speedTitle)
    Spacer(Modifier.height(18.dp))
    Text(
        formatSpeed(speed),
        color = BrandOrange,
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Spacer(Modifier.height(10.dp))
    TickRuler(
        count = SPEED_STEPS,
        selected = ((speed - MIN_SPEED) / SPEED_STEP).roundToInt(),
        majorEvery = 10,
        onSelected = { controller.setPlaybackSpeed(MIN_SPEED + it * SPEED_STEP) },
    )
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.8f, 1f, 1.25f, 1.5f, 2f).forEach { preset ->
            Pill(formatSpeed(preset), selected = kotlin.math.abs(speed - preset) < 0.01f) {
                controller.setPlaybackSpeed(preset)
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipSilenceLevels(state, strings, controller)
    }
}

/**
 * Пропуск тишины уровнями: «Выкл / Мягко / Обычно / Сильно». Выбранный уровень — оранжевый
 * (правило «активное — оранжевое»), «Выкл» выбранным белым не подсвечивается как настройка.
 */
@Composable
private fun SkipSilenceLevels(
    state: AudiobookPlayerState,
    strings: PlayerStrings,
    controller: androidx.media3.session.MediaController,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(strings.skipSilence, color = Ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(strings.skipSilenceHint, color = Muted, fontFamily = SnProFamily, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            strings.skipSilenceLevels.forEachIndexed { index, label ->
                val selected = state.skipSilenceLevel == index
                Text(
                    text = label,
                    color = when {
                        selected && index > 0 -> Color.White
                        selected -> Color(0xFF111111)
                        else -> Muted
                    },
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            when {
                                selected && index > 0 -> BrandOrange
                                selected -> Color.White
                                else -> Color.White.copy(alpha = 0.08f)
                            },
                        )
                        .clickable {
                            controller.sendCustomCommand(
                                AudiobookSessionCommands.setSkipSilence,
                                Bundle().apply { putInt(AudiobookSessionCommands.SKIP_SILENCE_LEVEL, index) },
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ---------- Главы (вид оглавления манги) ----------

@androidx.media3.common.util.UnstableApi
@Composable
internal fun ColumnScope.ChaptersSheetContent(
    state: AudiobookPlayerState,
    strings: PlayerStrings,
    onDismiss: () -> Unit,
    markers: List<com.example.myapplication.audiobooks.data.AudiobookMarker> = emptyList(),
) {
    val book = state.book ?: return
    val timeline: BookTimeline = state.timeline ?: run {
        Text(book.chapterTitle, color = Muted, fontFamily = SnProFamily)
        return
    }
    val current = chapterPosition(book, timeline, state.trackPositionMs.longValue)
    var onlyNotListened by remember { mutableStateOf(false) }
    val chapters = timeline.chapters.withIndex().filter { !onlyNotListened || it.index >= current.index }

    Text(book.title, color = Ink, fontFamily = SnProFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetaPill(strings.chapterCount(timeline.chapters.size))
        timeline.totalMs?.let { MetaPill(formatClock(it)) }
        if (book.narrator.isNotBlank()) MetaPill(book.narrator)
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(strings.allChapters, selected = !onlyNotListened) { onlyNotListened = false }
        Pill(strings.notListened, selected = onlyNotListened) { onlyNotListened = true }
    }
    Spacer(Modifier.height(14.dp))
    com.example.myapplication.audiobooks.domain.model.TrackUriCodec.decode(book.uri)?.variant?.let { variant ->
        ChapterRecoveryPanel(variant, timeline, strings)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current.index - 2).coerceAtLeast(0))
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.heightIn(max = 460.dp),
    ) {
        itemsIndexed(chapters, key = { _, c -> c.value.index }) { i, (index, chapter) ->
            val isCurrent = index == current.index
            val listened = index < current.index
            ChapterRow(
                title = chapter.title,
                subtitle = listOfNotNull(
                    chapter.durationMs?.let(::formatClock),
                    when {
                        isCurrent -> strings.nowPlaying
                        listened -> strings.listened
                        else -> null
                    },
                ).joinToString(" • "),
                isCurrent = isCurrent,
                playing = isCurrent && state.book?.isPlaying == true,
                listened = listened,
                // У главы с маркером — та же иконка, что у кнопки маркера в плеере.
                marked = markers.any { m ->
                    m.globalMs >= chapter.startMs && (chapter.durationMs == null || m.globalMs < chapter.startMs + chapter.durationMs!!)
                },
                progress = if (isCurrent) current.durationMs?.let { d -> current.positionMs.toFloat() / d } else null,
                shape = groupRowShape(i, chapters.size),
            ) {
                state.seekToGlobal(chapter.startMs)
                onDismiss()
            }
        }
    }
}

@Composable
private fun MetaPill(text: String) {
    Text(
        text, color = Muted, fontFamily = SnProFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.07f)).padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun ChapterRow(
    title: String,
    subtitle: String,
    isCurrent: Boolean,
    listened: Boolean,
    progress: Float?,
    shape: Shape,
    playing: Boolean = false,
    marked: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.Black.copy(alpha = if (isCurrent) 0.34f else 0.22f))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (listened) Muted else Ink,
                fontFamily = SnProFamily,
                fontWeight = if (listened) FontWeight.Medium else FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Оранжевая точка — ещё не прослушано; у прослушанных её роль берёт галочка.
                if (!listened) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(BrandOrange))
                    Spacer(Modifier.width(6.dp))
                }
                Text(subtitle, color = Muted, fontFamily = SnProFamily, fontSize = 11.sp, maxLines = 1)
            }
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).background(BrandOrange))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (marked) {
            PhIcon(R.drawable.ph_bookmark_simple_fill, 16.dp, Color.White)
            Spacer(Modifier.width(8.dp))
        }
        if (isCurrent) {
            PlayingBars(playing, 18.dp, BrandOrange)
        } else {
            PhIcon(
                icon = R.drawable.ph_check,
                size = 18.dp,
                tint = if (listened) Muted else Color.White.copy(alpha = 0.22f),
            )
        }
    }
}

/** Как у оглавления манги: крайние строки скруглены наружу, группа читается одной карточкой. */
private fun groupRowShape(index: Int, size: Int): Shape {
    val top = if (index == 0) 22.dp else 6.dp
    val bottom = if (index == size - 1) 22.dp else 6.dp
    return SquircleCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

// ---------- Озвучки ----------

@Composable
internal fun ColumnScope.NarrationSheetContent(
    model: NarrationChoice,
    strings: PlayerStrings,
    onPick: (NarrationOption) -> Unit,
) {
    SheetTitle(strings.narrationTitle)
    Spacer(Modifier.height(14.dp))
    when {
        model.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = BrandOrange, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(strings.narrationSearching, color = Muted, fontFamily = SnProFamily, fontSize = 14.sp)
        }
        model.options.size <= 1 -> Text(strings.narrationNone, color = Muted, fontFamily = SnProFamily, fontSize = 14.sp)
    }
    if (model.options.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.heightIn(max = 460.dp)) {
            itemsIndexed(model.options, key = { _, o -> o.key }) { i, option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(groupRowShape(i, model.options.size))
                        .background(Color.Black.copy(alpha = if (option.current) 0.34f else 0.22f))
                        .clickable(enabled = !option.current) { onPick(option) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhIcon(R.drawable.ph_microphone, 20.dp, if (option.current) BrandOrange else Muted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(option.narrators.ifBlank { "—" }, color = Ink, fontFamily = SnProFamily,
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(option.duration, option.source, if (option.current) strings.narrationCurrent else null)
                                .joinToString(" • "),
                            color = Muted, fontFamily = SnProFamily, fontSize = 12.sp, maxLines = 1,
                        )
                    }
                    if (option.current) PhIcon(R.drawable.ph_check, 18.dp, BrandOrange)
                }
            }
        }
    }
    model.message?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, color = Muted, fontFamily = SnProFamily, fontSize = 13.sp)
    }
}

/** Состояние выбора озвучки — готовит его хост плеера. */
internal data class NarrationChoice(
    val loading: Boolean = true,
    val options: List<NarrationOption> = emptyList(),
    val message: String? = null,
)

internal data class NarrationOption(
    val key: String,
    val narrators: String,
    val duration: String?,
    val source: String,
    val current: Boolean,
    val book: com.example.myapplication.audiobooks.domain.source.SourceBook,
)

// ---------- Ещё ----------

@Composable
internal fun ColumnScope.MoreSheetContent(
    state: AudiobookPlayerState,
    strings: PlayerStrings,
    favorite: Boolean,
    onFavorite: () -> Unit,
    markerCount: Int = 0,
    onMarkers: () -> Unit = {},
    /** «Субтитры · Текст книги»; null — пункта нет. */
    subtitlesLabel: String? = null,
    onSubtitles: () -> Unit = {},
) {
    val controller = state.controller ?: return
    SheetTitle(strings.moreTitle)
    Spacer(Modifier.height(14.dp))
    if (subtitlesLabel != null) {
        NavRow(subtitlesLabel, R.drawable.ph_subtitles, onSubtitles)
        Spacer(Modifier.height(3.dp))
    }
    // Пункт есть, только когда у книги есть хотя бы один маркер.
    if (markerCount > 0) {
        NavRow(strings.backToMarker(markerCount), R.drawable.ph_bookmark_simple_fill, onMarkers)
        Spacer(Modifier.height(3.dp))
    }
    SettingRow(strings.favorite, checked = favorite, onChange = { onFavorite() })
    Spacer(Modifier.height(3.dp))
    SmartRewindRow(strings)
    Spacer(Modifier.height(3.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        SkipSilenceLevels(state, strings, controller)
    }
}

@Composable
private fun NavRow(title: String, icon: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(icon, 18.dp, Ink)
        Spacer(Modifier.width(12.dp))
        Text(title, color = Ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            modifier = Modifier.weight(1f))
        PhIcon(R.drawable.ph_caret_right, 16.dp, Muted)
    }
}

// ---------- Маркеры ----------

/** Список маркеров книги: время на шкале книги и глава; тап — перейти, крестик — удалить. */
@Composable
internal fun ColumnScope.MarkersSheetContent(
    state: AudiobookPlayerState,
    strings: PlayerStrings,
    markers: List<com.example.myapplication.audiobooks.data.AudiobookMarker>,
    onDelete: (com.example.myapplication.audiobooks.data.AudiobookMarker) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetTitle(strings.markersTitle, trailing = markers.size.toString())
    Spacer(Modifier.height(14.dp))
    val timeline = state.timeline
    LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.heightIn(max = 460.dp)) {
        itemsIndexed(markers, key = { _, m -> m.id }) { i, marker ->
            val chapter = timeline?.chapterAt(marker.globalMs)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(groupRowShape(i, markers.size))
                    .background(Color.Black.copy(alpha = 0.22f))
                    .clickable {
                        state.seekToGlobal(marker.globalMs)
                        onDismiss()
                    }
                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhIcon(R.drawable.ph_bookmark_simple_fill, 16.dp, Ink)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(formatClock(marker.globalMs), color = Ink, fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    chapter?.let {
                        Text(it.title, color = Muted, fontFamily = SnProFamily, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { onDelete(marker) },
                    contentAlignment = Alignment.Center,
                ) {
                    PhIcon(R.drawable.ph_x, 16.dp, Muted)
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            modifier = Modifier.weight(1f))
        IosSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Выключатель умной перемотки. Сервис читает тот же файл настроек при каждом возобновлении, поэтому
 * отдельной команды сессии не нужно: приложение и сервис живут в одном процессе.
 */
@Composable
private fun SmartRewindRow(strings: PlayerStrings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("audiobook_player_options", android.content.Context.MODE_PRIVATE)
    }
    var enabled by remember { mutableStateOf(prefs.getBoolean("smart_rewind", true)) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                strings.smartRewind, color = Ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp, modifier = Modifier.weight(1f),
            )
            IosSwitch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.edit().putBoolean("smart_rewind", it).apply()
                },
            )
        }
        Text(strings.smartRewindHint, color = Muted, fontFamily = SnProFamily, fontSize = 12.sp)
    }
}

private const val DEFAULT_SLEEP_MINUTES = 25
private const val MAX_SLEEP_MINUTES = 240
private const val MIN_SPEED = 0.5f
private const val SPEED_STEP = 0.05f
private const val SPEED_STEPS = 51 // 0,5× … 3,0×
