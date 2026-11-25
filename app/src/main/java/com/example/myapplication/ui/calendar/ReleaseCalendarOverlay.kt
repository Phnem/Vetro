package com.example.myapplication.ui.calendar

import com.example.myapplication.ui.shared.components.extendUnderGrabber
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.typeManga
import com.example.myapplication.data.models.typeMovie
import com.example.myapplication.data.models.typeSeries
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.ui.home.AnimeCardBody
import com.example.myapplication.ui.home.AnimeCardState
import com.example.myapplication.ui.shared.components.IosSegmentedControl
import com.example.myapplication.ui.shared.theme.MotionTokens
import com.example.myapplication.ui.shared.theme.SquircleShape
import com.example.myapplication.utils.getStrings
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.myapplication.data.repository.ImageStorageRepository
import com.example.myapplication.domain.calendar.CalendarRelease
import com.example.myapplication.domain.calendar.ReleaseCalendarRepository
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.components.rememberIosSheetSwipe
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosScroll
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.mascot.MascotEmptyState
import com.example.myapplication.ui.shared.mascot.MascotScene
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ==========================================
// ReleaseCalendarOverlay — шторка «Календарь выходов» (пункт меню «Ещё»).
// Дни, в которые выходят серии, помечены обложкой тайтла; если в день выходит несколько —
// стопка обложек со счётчиком. Тап по дню раскрывает список серий этого дня.
// ==========================================

private class CalendarStrings(val ru: Boolean) {
    val title = if (ru) "Календарь выходов" else "Release calendar"
    val loading = if (ru) "Собираем расписание…" else "Collecting the schedule…"
    val empty = if (ru) "Сейчас ничего не выходит" else "Nothing is airing right now"
    val emptyHint = if (ru) {
        "Здесь появятся тайтлы, у которых идёт сезон: аниме с отметкой «выходит» и сериалы в эфире."
    } else {
        "Titles with a season in progress appear here: airing anime and series."
    }
    val nothingThatDay = if (ru) "В этот день серий нет" else "No episodes on this day"
    val nothingThatDayHint = if (ru) "Выберите другой день недели или растяните календарь" else "Pick another day or pull the calendar open"
    val month = if (ru) "Месяц" else "Month"
    val week = if (ru) "Неделя" else "Week"
    fun episodes(list: List<Int?>): String {
        val known = list.filterNotNull().distinct().sorted()
        return when {
            known.isEmpty() -> episode(null)
            known.size == 1 -> episode(known.first())
            else -> (if (ru) "Серии " else "Episodes ") + "${known.first()}–${known.last()}"
        }
    }
    val projectedNote = if (ru) "по недельному ритму" else "by weekly rhythm"
    val released = if (ru) "вышла" else "out"
    val upcoming = if (ru) "выйдет" else "airs"
    fun episode(n: Int?) = if (n == null) (if (ru) "Новая серия" else "New episode") else (if (ru) "Серия $n" else "Episode $n")
}

@Composable
fun ReleaseCalendarOverlay(
    language: AppLanguage,
    onOpenTitle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = isAppInDarkTheme()
    var visible by remember { mutableStateOf(false) }
    val panelState = remember { MutableTransitionState(false) }
    panelState.targetState = visible
    LaunchedEffect(Unit) { visible = true }
    fun triggerDismiss() { visible = false }
    LaunchedEffect(visible) {
        if (!visible) {
            snapshotFlow { panelState.isIdle && !panelState.currentState }.first { it }
            onDismiss()
        }
    }
    BackHandler { triggerDismiss() }
    val swipe = rememberIosSheetSwipe { triggerDismiss() }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().zIndex(10f),
        contentAlignment = Alignment.BottomCenter,
    ) {
        MotionBottomSheet(
            visibleState = panelState,
            onDismiss = ::triggerDismiss,
            isDark = isDark,
            swipe = swipe,
            panelModifier = Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.92f),
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                GrabberHandle(isDark, modifier = swipe.handleModifier)
                CalendarContent(
                    language = language,
                    isDark = isDark,
                    onOpenTitle = { id ->
                        onOpenTitle(id)
                        triggerDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun CalendarContent(language: AppLanguage, isDark: Boolean, onOpenTitle: (String) -> Unit) {
    val repository: ReleaseCalendarRepository = koinInject()
    val imageStorage: ImageStorageRepository = koinInject()
    val strings = remember(language) { CalendarStrings(language == AppLanguage.RU) }
    val locale = remember(language) {
        if (language == AppLanguage.RU) Locale.forLanguageTag("ru") else Locale.ENGLISH
    }
    val releases: List<CalendarRelease>? by produceState<List<CalendarRelease>?>(null, language) {
        value = runCatching { repository.load(language) }
            .onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }
            .getOrDefault(emptyList())
    }
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }
    val byDate = remember(releases) { releases.orEmpty().groupBy { it.date } }
    val ink = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    val imagePath: (String?) -> String? = { name -> name?.let(imageStorage::getImageFilePath) }

    // Шкала: 0 — месяц, 1 — свёрнут до недели выбранного дня. Читается только в фазах раскладки и
    // рисования (лямбдами), поэтому кадры сворачивания не пересобирают сетку.
    var collapse by remember { mutableFloatStateOf(0f) }
    var weekMode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    fun settle(toWeek: Boolean, velocity: Float = 0f) {
        weekMode = toWeek
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(collapse, if (toWeek) 1f else 0f, velocity, MotionTokens.largeSurfaceEnter()) { v, _ -> collapse = v }
        }
    }
    val scaleShown by remember { derivedStateOf { collapse > 0.02f } }

    Column(
        modifier = Modifier
            .extendUnderGrabber()
            .fillMaxWidth()
            .verticalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
            .padding(top = com.example.myapplication.ui.shared.components.GrabberReservedTop)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = strings.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = SnProFamily,
                    fontSize = 24.sp,
                    letterSpacing = (-0.3).sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Переключатель шкалы проявляется вместе со сворачиванием: в режиме месяца его нет.
            IosSegmentedControl(
                optionCount = 2,
                selectedIndex = if (weekMode) 1 else 0,
                isDark = isDark,
                modifier = Modifier
                    .width(148.dp)
                    .graphicsLayer {
                        val c = collapse
                        alpha = c
                        scaleX = 0.9f + 0.1f * c
                        scaleY = 0.9f + 0.1f * c
                    },
                onSelect = { if (scaleShown) settle(toWeek = it == 1) },
            ) { index, isSelected ->
                Text(
                    text = if (index == 0) strings.month else strings.week,
                    color = ink,
                    fontFamily = SnProFamily,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        MonthHeader(
            // В режиме недели заголовок — по выбранному дню: неделя может принадлежать соседнему месяцу.
            month = if (weekMode) YearMonth.from(selected) else month,
            locale = locale,
            ink = ink,
            isDark = isDark,
            // В режиме недели стрелки листают недели: выбранный день едет на ±7 дней, месяц за ним.
            onPrev = {
                if (weekMode) { selected = selected.minusWeeks(1); month = YearMonth.from(selected) } else month = month.minusMonths(1)
            },
            onNext = {
                if (weekMode) { selected = selected.plusWeeks(1); month = YearMonth.from(selected) } else month = month.plusMonths(1)
            },
        )
        Spacer(Modifier.height(8.dp))
        WeekdayHeader(locale, muted)
        Spacer(Modifier.height(4.dp))

        if (releases == null) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(strings.loading, color = muted, fontFamily = SnProFamily, fontSize = 13.sp)
            }
        } else {
            CollapsingMonthGrid(
                month = month,
                today = today,
                selected = selected,
                byDate = byDate,
                imagePath = imagePath,
                isDark = isDark,
                collapse = { collapse },
                grabberEnabled = scaleShown,
                onSelect = { date ->
                    selected = date
                    // Месяц меняется только в режиме недели: в месяце строка дня должна остаться на месте,
                    // иначе сворачивание поедет не к той неделе.
                    if (weekMode) month = YearMonth.from(date) else settle(toWeek = true)
                },
                onDragBy = { fraction ->
                    settleJob?.cancel()
                    collapse = (collapse + fraction).coerceIn(0f, 1f)
                },
                onDragStop = { velocity ->
                    val toWeek = when {
                        velocity > FLING_FRACTION_PER_SECOND -> true
                        velocity < -FLING_FRACTION_PER_SECOND -> false
                        else -> collapse > 0.5f
                    }
                    settle(toWeek, velocity)
                },
            )
            if (releases.orEmpty().isEmpty()) {
                Spacer(Modifier.height(8.dp))
                MascotEmptyState(
                    scene = MascotScene.Shrug,
                    title = strings.empty,
                    subtitle = strings.emptyHint,
                    illustrationSize = 160.dp,
                    titleColor = ink,
                    subtitleColor = muted,
                    isDark = isDark,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (scaleShown || weekMode) {
                // Карточки встают на место, которое освободила сетка: их высота растёт вместе со
                // сворачиванием, поэтому шторка не прыгает, а сумма высот меняется плавно.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val c = collapse
                            layout(placeable.width, (placeable.height * c).roundToInt()) {
                                placeable.placeWithLayer(0, 0) {
                                    alpha = c
                                    translationY = (1f - c) * 28.dp.toPx()
                                }
                            }
                        },
                ) {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            val forward = targetState.isAfter(initialState)
                            (fadeIn(MotionTokens.tweenStandard()) + slideInHorizontally(MotionTokens.springSurface()) { w -> if (forward) w / 6 else -w / 6 })
                                .togetherWith(fadeOut(MotionTokens.tweenFast()))
                                .using(SizeTransform(clip = false))
                        },
                        label = "calendarDayCards",
                    ) { day ->
                        DayCards(
                            day = day,
                            items = byDate[day].orEmpty(),
                            strings = strings,
                            language = language,
                            locale = locale,
                            isDark = isDark,
                            imagePath = imagePath,
                            titleOf = repository::titleOf,
                            onOpenTitle = onOpenTitle,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Скорость броска ручки (доли пути сворачивания в секунду), с которой жест решает сам, без порога 50 %. */
private const val FLING_FRACTION_PER_SECOND = 0.8f

/**
 * Сетка месяца, которая сворачивается к неделе выбранного дня: высота сжимается до одной строки,
 * строка выбранного дня едет вверх, остальные гаснут. Под сеткой — ручка, как у шторок: тянешь
 * вниз — месяц раскрывается за пальцем, вверх — сворачивается обратно.
 */
@Composable
private fun CollapsingMonthGrid(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    byDate: Map<LocalDate, List<CalendarRelease>>,
    imagePath: (String?) -> String?,
    isDark: Boolean,
    collapse: () -> Float,
    grabberEnabled: Boolean,
    onSelect: (LocalDate) -> Unit,
    onDragBy: (Float) -> Unit,
    onDragStop: (Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 4.dp
        val cellHeight = (maxWidth - gap * 6) / 7 / 0.78f
        val density = LocalDensity.current
        val rowStepPx = with(density) { (cellHeight + gap).toPx() }
        val cellPx = with(density) { cellHeight.toPx() }
        val fullPx = cellPx * 6 + with(density) { gap.toPx() } * 5
        val rangePx = fullPx - cellPx
        val first = month.atDay(1)
        val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
        val selectedRow = (ChronoUnit.DAYS.between(start, selected) / 7).toInt().coerceIn(0, 5)

        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                        val c = collapse()
                        layout(placeable.width, (fullPx + (cellPx - fullPx) * c).roundToInt()) {
                            placeable.placeWithLayer(0, 0) { translationY = -selectedRow * rowStepPx * c }
                        }
                    },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    for (week in 0 until 6) {
                        val isSelectedRow = week == selectedRow
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    if (!isSelectedRow) alpha = (1f - collapse() * 1.6f).coerceIn(0f, 1f)
                                },
                            horizontalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            for (d in 0 until 7) {
                                val date = start.plusDays((week * 7 + d).toLong())
                                val inMonth = YearMonth.from(date) == month
                                // В свёрнутой неделе все семь дней равноправны: соседний месяц проявляется.
                                val lifted = isSelectedRow && !inMonth
                                DayCell(
                                    date = date,
                                    inMonth = inMonth || lifted,
                                    isToday = date == today,
                                    isSelected = date == selected,
                                    inRange = false,
                                    items = byDate[date].orEmpty(),
                                    imagePath = imagePath,
                                    isDark = isDark,
                                    onClick = { onSelect(date) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .then(if (lifted) Modifier.graphicsLayer { alpha = 0.35f + 0.65f * collapse() } else Modifier),
                                )
                            }
                        }
                    }
                }
            }
            // Ручка той же формы, что у шторок; появляется со сворачиванием.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val c = collapse()
                        layout(placeable.width, (placeable.height * (0.3f + 0.7f * c)).roundToInt()) {
                            placeable.placeWithLayer(0, 0) { alpha = c }
                        }
                    }
                    .draggable(
                        state = rememberDraggableState { delta -> onDragBy(-delta / rangePx) },
                        orientation = Orientation.Vertical,
                        enabled = grabberEnabled,
                        onDragStopped = { velocity -> onDragStop(-velocity / rangePx) },
                    )
                    .height(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 5.dp)
                        .clip(SquircleShape(2.5.dp, smoothing = 0.3f))
                        .background((if (isDark) Color.White else Color.Black).copy(alpha = 0.28f)),
                )
            }
        }
    }
}

/** Выходы дня — теми же карточками, что на главной. */
@Composable
private fun DayCards(
    day: LocalDate,
    items: List<CalendarRelease>,
    strings: CalendarStrings,
    language: AppLanguage,
    locale: Locale,
    isDark: Boolean,
    imagePath: (String?) -> String?,
    titleOf: (String) -> Anime?,
    onOpenTitle: (String) -> Unit,
) {
    val ink = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    val genreRepository: GenreRepository = koinInject()
    val ui = remember(language) { getStrings(language) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)).replaceFirstChar { it.titlecase(locale) },
            style = OverlayThemeTokens.SectionLabel,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = OverlayThemeTokens.LabelMutedAlpha),
        )
        Spacer(Modifier.height(10.dp))
        if (items.isEmpty()) {
            MascotEmptyState(
                scene = MascotScene.Shrug,
                title = strings.nothingThatDay,
                subtitle = strings.nothingThatDayHint,
                illustrationSize = 120.dp,
                titleColor = ink,
                subtitleColor = muted,
                isDark = isDark,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.groupBy { it.animeId }.values.sortedBy { it.first().title }.forEach { group ->
                val release = group.first()
                val anime = titleOf(release.animeId)
                val state = remember(group, anime, language) {
                    val status = if (group.all { it.released }) strings.released else strings.upcoming
                    AnimeCardState(
                        id = release.animeId,
                        title = anime?.let {
                            when (language) {
                                AppLanguage.EN -> it.titleEn?.takeIf(String::isNotBlank)
                                AppLanguage.RU -> it.titleRu?.takeIf(String::isNotBlank)
                            } ?: it.title
                        } ?: release.title,
                        rating = anime?.rating ?: 0f,
                        genres = anime?.tags.orEmpty().take(3)
                            .mapNotNull { tag -> genreRepository.getLabel(tag, language).takeIf { it.isNotBlank() } }
                            .toPersistentList(),
                        episodesLabel = "${strings.episodes(group.map { it.episode })} · $status",
                        imagePath = imagePath(anime?.imageFileName ?: release.imageFileName),
                        mediaTypeLabel = when (anime?.mediaType ?: MediaType.ANIME) {
                            MediaType.ANIME -> ui.typeAnime
                            MediaType.MANGA -> ui.typeManga
                            MediaType.MOVIE -> ui.typeMovie
                            MediaType.SERIES -> ui.typeSeries
                        },
                        mediaType = anime?.mediaType ?: MediaType.ANIME,
                        isFavorite = anime?.isFavorite == true,
                        language = language,
                    )
                }
                AnimeCardBody(state = state, onClick = { onOpenTitle(release.animeId) })
            }
        }
    }
}

@Composable
internal fun MonthHeader(
    month: YearMonth,
    locale: Locale,
    ink: Color,
    isDark: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
                .replaceFirstChar { it.titlecase(locale) } + " " + month.year,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold),
            color = ink,
            modifier = Modifier.weight(1f),
        )
        NavButton(isDark, onPrev) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = ink) }
        Spacer(Modifier.width(8.dp))
        NavButton(isDark, onNext) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ink) }
    }
}

@Composable
private fun NavButton(isDark: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (isDark) OverlayThemeTokens.TileIconBgDark else MaterialTheme.colorScheme.surfaceVariant)
            .fluidClickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

@Composable
internal fun WeekdayHeader(locale: Locale, muted: Color) {
    Row(modifier = Modifier.fillMaxWidth()) {
        DayOfWeek.entries.forEach { day ->
            Text(
                text = day.getDisplayName(TextStyle.SHORT_STANDALONE, locale).take(2),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = muted,
                fontFamily = SnProFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
internal fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate?,
    byDate: Map<LocalDate, List<CalendarRelease>>,
    imagePath: (String?) -> String?,
    isDark: Boolean,
    onSelect: (LocalDate) -> Unit,
    rangeStart: LocalDate? = null,
    rangeEnd: LocalDate? = null,
) {
    // Понедельник первой колонкой; шесть строк всегда, чтобы шторка не прыгала по высоте между месяцами.
    val first = month.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (week in 0 until 6) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (d in 0 until 7) {
                    val date = start.plusDays((week * 7 + d).toLong())
                    DayCell(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isSelected = date == selected || date == rangeStart || date == rangeEnd,
                        inRange = rangeStart != null && rangeEnd != null && !date.isBefore(rangeStart) && !date.isAfter(rangeEnd),
                        items = byDate[date].orEmpty(),
                        imagePath = imagePath,
                        isDark = isDark,
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    inRange: Boolean,
    items: List<CalendarRelease>,
    imagePath: (String?) -> String?,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val base = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.04f)
    val ink = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val titles = items.distinctBy { it.animeId }
    Box(
        modifier = modifier
            .aspectRatio(0.78f)
            .clip(shape)
            .background(if (inRange) BrandOrange.copy(alpha = 0.16f) else if (inMonth) base else Color.Transparent)
            .then(
                when {
                    isSelected -> Modifier.border(1.5.dp, BrandOrange, shape)
                    isToday -> Modifier.border(1.dp, BrandOrange.copy(alpha = 0.55f), shape)
                    else -> Modifier
                },
            )
            .fluidClickable { onClick() },
    ) {
        if (titles.isEmpty()) {
            Text(
                text = date.dayOfMonth.toString(),
                modifier = Modifier.align(Alignment.Center),
                color = if (inMonth) ink.copy(alpha = if (isToday) 1f else 0.8f) else ink.copy(alpha = 0.25f),
                fontFamily = SnProFamily,
                fontSize = 13.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
            )
        } else {
            PosterStack(titles, items, imagePath, inMonth)
            Text(
                text = date.dayOfMonth.toString(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 4.dp, top = 2.dp),
                color = Color.White,
                fontFamily = SnProFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Обложка тайтла или стопка обложек (до трёх) со счётчиком, когда в день выходит несколько тайтлов. */
@Composable
private fun PosterStack(
    titles: List<CalendarRelease>,
    all: List<CalendarRelease>,
    imagePath: (String?) -> String?,
    inMonth: Boolean,
) {
    val shown = titles.take(3)
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().padding(3.dp)) {
        // Нижние обложки рисуются первыми и выглядывают из-за верхней со сдвигом и небольшим наклоном.
        shown.asReversed().forEachIndexed { reversedIndex, release ->
            val depth = shown.size - 1 - reversedIndex // 0 — верхняя
            val path = imagePath(release.imageFileName)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = (depth * 5).dp, y = (-depth * 3).dp)
                    .rotate(depth * 4f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFF2A2A2A)),
            ) {
                if (path != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(File(path)).crossfade(true).build(),
                        contentDescription = release.title,
                        contentScale = ContentScale.Crop,
                        alpha = if (!inMonth) 0.35f else if (release.released) 0.55f else 1f,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = release.title.take(1).uppercase(),
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White.copy(alpha = 0.6f),
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Transparent))),
                )
            }
        }
        if (all.size > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(BrandOrange)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = all.size.toString(),
                    color = Color.White,
                    fontFamily = SnProFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
