package com.example.myapplication.ui.calendar

import androidx.activity.compose.BackHandler
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
        value = runCatching { repository.load(language) }.getOrDefault(emptyList())
    }
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf<LocalDate?>(today) }
    val byDate = remember(releases) { releases.orEmpty().groupBy { it.date } }
    val ink = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = strings.title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = SnProFamily,
                fontSize = 24.sp,
                letterSpacing = (-0.3).sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))

        MonthHeader(month, locale, ink, isDark, onPrev = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) })
        Spacer(Modifier.height(8.dp))
        WeekdayHeader(locale, muted)
        Spacer(Modifier.height(4.dp))

        if (releases == null) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(strings.loading, color = muted, fontFamily = SnProFamily, fontSize = 13.sp)
            }
        } else {
            MonthGrid(
                month = month,
                today = today,
                selected = selected,
                byDate = byDate,
                imagePath = { name -> name?.let(imageStorage::getImageFilePath) },
                isDark = isDark,
                onSelect = { selected = it },
            )
            if (releases.orEmpty().isEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(strings.empty, color = ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(strings.emptyHint, color = muted, fontFamily = SnProFamily, fontSize = 12.sp, lineHeight = 17.sp)
            }
            selected?.let { day ->
                Spacer(Modifier.height(16.dp))
                DayList(
                    day = day,
                    items = byDate[day].orEmpty(),
                    strings = strings,
                    locale = locale,
                    isDark = isDark,
                    imagePath = { name -> name?.let(imageStorage::getImageFilePath) },
                    onOpenTitle = onOpenTitle,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MonthHeader(
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
private fun WeekdayHeader(locale: Locale, muted: Color) {
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
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate?,
    byDate: Map<LocalDate, List<CalendarRelease>>,
    imagePath: (String?) -> String?,
    isDark: Boolean,
    onSelect: (LocalDate) -> Unit,
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
                        isSelected = date == selected,
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
            .background(if (inMonth) base else Color.Transparent)
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

@Composable
private fun DayList(
    day: LocalDate,
    items: List<CalendarRelease>,
    strings: CalendarStrings,
    locale: Locale,
    isDark: Boolean,
    imagePath: (String?) -> String?,
    onOpenTitle: (String) -> Unit,
) {
    val ink = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)).replaceFirstChar { it.titlecase(locale) },
        style = OverlayThemeTokens.SectionLabel,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = OverlayThemeTokens.LabelMutedAlpha),
    )
    Spacer(Modifier.height(8.dp))
    if (items.isEmpty()) {
        Text(strings.nothingThatDay, color = muted, fontFamily = SnProFamily, fontSize = 13.sp)
        return
    }
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.sortedBy { it.title }.forEach { release ->
            val path = imagePath(release.imageFileName)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isDark) OverlayThemeTokens.TileBackgroundDark else MaterialTheme.colorScheme.surfaceVariant)
                    .fluidClickable { onOpenTitle(release.animeId) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 62.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF2A2A2A)),
                ) {
                    if (path != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(File(path)).crossfade(true).build(),
                            contentDescription = release.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = release.title,
                        color = ink,
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    val status = if (release.released) strings.released else strings.upcoming
                    Text(
                        text = "${strings.episode(release.episode)} · $status",
                        color = muted,
                        fontFamily = SnProFamily,
                        fontSize = 12.sp,
                    )
                    if (release.projected) {
                        Text(
                            text = strings.projectedNote,
                            color = muted.copy(alpha = 0.7f),
                            fontFamily = SnProFamily,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}
