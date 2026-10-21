package com.example.myapplication.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.SortOptionRow
import com.example.myapplication.data.models.LibraryFilter
import com.example.myapplication.data.models.RatingScale
import com.example.myapplication.data.models.SortOption
import com.example.myapplication.data.models.UiStrings
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.getAccentColor
import com.example.myapplication.getIcon
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.components.GenreFilterPillSelection
import com.example.myapplication.ui.shared.components.GlassRangeSlider
import com.example.myapplication.ui.shared.components.GrabberHandle
import com.example.myapplication.ui.shared.components.GrabberReservedTop
import com.example.myapplication.ui.shared.components.extendUnderGrabber
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.components.accent
import com.example.myapplication.ui.shared.components.rememberIosSheetSwipe
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.IosScroll
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleShape
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.utils.Haptic
import com.example.myapplication.utils.performHaptic
import kotlin.math.pow
import kotlin.math.roundToInt

private const val EPISODES_SPAN = (LibraryFilter.EPISODES_MAX - LibraryFilter.EPISODES_MIN).toFloat()

/**
 * Положение ползунка серий (0..1) → число серий. Кубическая шкала: линейный ползунок на 1..2000
 * держал бы типичные 12–26 серий в первых 1% хода, и выбрать их было бы нельзя.
 */
internal fun episodesFromSlider(t: Float): Int =
    (LibraryFilter.EPISODES_MIN + EPISODES_SPAN * t.coerceIn(0f, 1f).pow(3)).roundToInt()
        .coerceIn(LibraryFilter.EPISODES_MIN, LibraryFilter.EPISODES_MAX)

internal fun episodesToSlider(episodes: Int): Float =
    ((episodes - LibraryFilter.EPISODES_MIN).coerceAtLeast(0) / EPISODES_SPAN).pow(1f / 3f)

/** Высота плавающей кнопки с отступами: столько свободного места нужно под последней секцией. */
private val FloatingButtonClearance = 54.dp + 12.dp + 16.dp

private fun roundToTenth(value: Float): Float = (value * 10f).roundToInt() / 10f

/**
 * Шторка «Сортировка и фильтры»: порядок списка плюс статус, жанры, год, рейтинг и число серий.
 * Всё выбранное — черновик, который уходит в [onApply] одной кнопкой: список не перестраивается
 * на каждый тап по чипсу.
 */
@Composable
fun SortFilterOverlay(
    visibleState: MutableTransitionState<Boolean>,
    strings: UiStrings,
    currentLanguage: AppLanguage,
    sortOption: SortOption,
    sortAscending: Boolean,
    filter: LibraryFilter,
    filterSelectedTags: List<String>,
    filterCategoryType: String,
    /** Сколько тайтлов без года: под суженный фильтр года они не попадут, и это надо сказать. */
    yearUnknownCount: Int,
    onDismiss: () -> Unit,
    onApply: (SortOption, Boolean, LibraryFilter, List<String>, String) -> Unit,
) {
    val isDark = isAppInDarkTheme()
    val ru = currentLanguage == AppLanguage.RU
    val t = remember(ru) { SortFilterStrings.of(ru) }
    val onSurface = MaterialTheme.colorScheme.onSurface

    val yearMax = remember { LibraryFilter.currentYear() }
    val isOpening = visibleState.targetState

    // Черновик — один объект со своими состояниями: каждая секция читает только свои поля, поэтому драг
    // ползунка перерисовывает его секцию, а не весь лист (раньше любое движение пересобирало всё).
    val draft = remember(sortOption, sortAscending, filter, filterSelectedTags, filterCategoryType, isOpening) {
        SortFilterDraft(sortOption, sortAscending, filter, filterSelectedTags, filterCategoryType, yearMax)
    }

    val swipe = rememberIosSheetSwipe { onDismiss() }
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    BackHandler { onDismiss() }

    Box(modifier = Modifier.fillMaxSize()) {
        MotionBottomSheet(
            visibleState = visibleState,
            onDismiss = onDismiss,
            isDark = isDark,
            swipe = swipe,
            panelModifier = Modifier.fillMaxWidth().heightIn(max = maxSheetHeight),
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                GrabberHandle(isDark, modifier = swipe.handleModifier)
                // Кнопка плавает поверх прокрутки без своей подложки: содержимое уходит под неё.
                Box(modifier = Modifier.weight(1f, fill = false)) {
                Column(
                    modifier = Modifier
                        .extendUnderGrabber()
                        .verticalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(Modifier.height(GrabberReservedTop))
                    SheetTitleRow(draft = draft, strings = strings, t = t, onSurface = onSurface)
                    SortRows(draft = draft, strings = strings, isDark = isDark)
                    StatusSection(draft = draft, t = t, ru = ru, isDark = isDark, onSurface = onSurface)
                    GenresSection(draft = draft, strings = strings, currentLanguage = currentLanguage, t = t, isDark = isDark)
                    YearSection(draft = draft, t = t, yearMax = yearMax, yearUnknownCount = yearUnknownCount, isDark = isDark, onSurface = onSurface)
                    RatingSection(draft = draft, t = t, isDark = isDark)
                    EpisodesSection(draft = draft, t = t, isDark = isDark)
                    // Запас под плавающую кнопку: последняя секция должна выезжать выше неё.
                    Spacer(Modifier.height(FloatingButtonClearance))
                }

                ApplyButton(
                    draft = draft,
                    label = strings.sortApply,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onClick = {
                        onApply(draft.sort.first, draft.sort.second, draft.toFilter(), draft.tags, draft.category)
                        onDismiss()
                    },
                )
                }
            }
        }
    }
}

/** Черновик шторки. Поля — отдельные состояния, чтобы секции подписывались каждая на своё. */
@Stable
private class SortFilterDraft(
    sortOption: SortOption,
    sortAscending: Boolean,
    filter: LibraryFilter,
    tags: List<String>,
    category: String,
    private val yearMax: Int,
) {
    var sort by mutableStateOf(sortOption to sortAscending)
    var statuses by mutableStateOf(filter.statuses)
    var year by mutableStateOf(
        (filter.yearRange?.first ?: LibraryFilter.YEAR_MIN).toFloat()..(filter.yearRange?.last ?: yearMax).toFloat(),
    )
    var rating by mutableStateOf(
        (filter.ratingRange?.start ?: LibraryFilter.RATING_MIN)..(filter.ratingRange?.endInclusive ?: LibraryFilter.RATING_MAX),
    )
    var episodes by mutableStateOf(
        episodesToSlider(filter.episodesRange?.first ?: LibraryFilter.EPISODES_MIN)..
            episodesToSlider(filter.episodesRange?.last ?: LibraryFilter.EPISODES_MAX),
    )
    var tags by mutableStateOf(tags)
    var category by mutableStateOf(category)

    fun toFilter(): LibraryFilter = LibraryFilter(
        statuses = statuses,
        yearRange = year.start.roundToInt()..year.endInclusive.roundToInt(),
        ratingRange = roundToTenth(rating.start)..roundToTenth(rating.endInclusive),
        episodesRange = episodesFromSlider(episodes.start)..episodesFromSlider(episodes.endInclusive),
    ).normalized()

    val hasFilters: Boolean get() = toFilter().isActive || tags.isNotEmpty()

    fun resetFilters() {
        statuses = emptySet()
        year = LibraryFilter.YEAR_MIN.toFloat()..yearMax.toFloat()
        rating = LibraryFilter.RATING_MIN..LibraryFilter.RATING_MAX
        episodes = 0f..1f
        tags = emptyList()
        category = ""
    }
}

@Composable
private fun SheetTitleRow(draft: SortFilterDraft, strings: UiStrings, t: SortFilterStrings, onSurface: Color) {
    val view = LocalView.current
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                text = strings.sortSheetTitle,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                letterSpacing = (-0.3).sp,
                color = onSurface,
            )
            Text(
                text = strings.sortSheetSubtitle,
                fontFamily = SnProFamily,
                fontSize = 13.sp,
                color = onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
            )
        }
        if (draft.hasFilters) {
            Text(
                text = t.resetFilters,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = BrandOrange,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        performHaptic(view, Haptic.Light)
                        draft.resetFilters()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SortRows(draft: SortFilterDraft, strings: UiStrings, isDark: Boolean) {
    val view = LocalView.current
    fun toggleSort(o: SortOption) {
        performHaptic(view, Haptic.Light)
        val (curOption, curAsc) = draft.sort
        draft.sort = if (curOption == o) o to !curAsc else o to false
    }
    fun orderLabel(asc: Boolean) = if (asc) strings.sortOrderAscending else strings.sortOrderDescending
    val (current, ascending) = draft.sort

    SortOptionRow(
        icon = SortOption.RATING.getIcon(),
        accent = SortOption.RATING.getAccentColor(),
        title = strings.ratingHigh,
        subtitle = if (current == SortOption.RATING) orderLabel(ascending) else strings.sortSubtitleRating,
        selected = current == SortOption.RATING,
        isDark = isDark,
        onClick = { toggleSort(SortOption.RATING) },
    )
    SortOptionRow(
        icon = SortOption.EPISODES.getIcon(),
        accent = SortOption.EPISODES.getAccentColor(),
        title = strings.sortTileEpisodesTitle,
        subtitle = if (current == SortOption.EPISODES) orderLabel(ascending) else strings.sortSubtitleEpisodes,
        selected = current == SortOption.EPISODES,
        isDark = isDark,
        onClick = { toggleSort(SortOption.EPISODES) },
    )
    SortOptionRow(
        icon = SortOption.TITLE.getIcon(),
        accent = SortOption.TITLE.getAccentColor(),
        title = strings.titleAZ,
        subtitle = if (current == SortOption.TITLE) orderLabel(ascending) else strings.sortSubtitleTitle,
        selected = current == SortOption.TITLE,
        isDark = isDark,
        onClick = { toggleSort(SortOption.TITLE) },
    )
}

/** Статус: до трёх + «Все». */
@Composable
private fun StatusSection(draft: SortFilterDraft, t: SortFilterStrings, ru: Boolean, isDark: Boolean, onSurface: Color) {
    val view = LocalView.current
    FilterSectionHeader(
        title = t.sectionStatus,
        trailing = "${draft.statuses.size}/${LibraryFilter.MAX_STATUSES}",
        isDark = isDark,
    )
    val neutral = onSurface.copy(alpha = 0.72f)
    val selected = draft.statuses
    ChipGrid(
        chips = buildList {
            add(ChipSpec(t.all, selected = selected.isEmpty(), accent = BrandOrange))
            WatchStatus.entries.forEach { status ->
                add(ChipSpec(status.label(ru), status in selected, status.accent(neutral)))
            }
        },
        isDark = isDark,
        onClick = { index ->
            if (index == 0) {
                performHaptic(view, Haptic.Light)
                draft.statuses = emptySet()
            } else {
                val status = WatchStatus.entries[index - 1]
                when {
                    status in draft.statuses -> {
                        performHaptic(view, Haptic.Light)
                        draft.statuses = draft.statuses - status
                    }
                    draft.statuses.size < LibraryFilter.MAX_STATUSES -> {
                        performHaptic(view, Haptic.Light)
                        draft.statuses = draft.statuses + status
                    }
                    else -> performHaptic(view, Haptic.Warning)
                }
            }
        },
    )
}

/** Жанры: те же плитки, что в Add/Edit; до трёх + «Все». */
@Composable
private fun GenresSection(
    draft: SortFilterDraft,
    strings: UiStrings,
    currentLanguage: AppLanguage,
    t: SortFilterStrings,
    isDark: Boolean,
) {
    val view = LocalView.current
    FilterSectionHeader(
        title = t.sectionGenres,
        trailing = "${draft.tags.size}/${LibraryFilter.MAX_STATUSES}",
        isDark = isDark,
    )
    ChipGrid(
        chips = listOf(ChipSpec(t.all, selected = draft.tags.isEmpty(), accent = BrandOrange)),
        isDark = isDark,
        onClick = {
            performHaptic(view, Haptic.Light)
            draft.tags = emptyList()
            draft.category = ""
        },
    )
    Spacer(Modifier.height(10.dp))
    GenreFilterPillSelection(
        strings = strings,
        currentLanguage = currentLanguage,
        selectedTags = draft.tags,
        activeCategory = draft.category,
        onTagToggle = { tag, categoryType ->
            if (tag in draft.tags) {
                draft.tags = draft.tags - tag
                if (draft.tags.isEmpty()) draft.category = ""
            } else if (
                draft.tags.size < LibraryFilter.MAX_STATUSES &&
                (draft.category.isEmpty() || draft.category == categoryType)
            ) {
                draft.tags = draft.tags + tag
                draft.category = categoryType
            } else {
                performHaptic(view, Haptic.Warning)
            }
        },
    )
}

@Composable
private fun YearSection(
    draft: SortFilterDraft,
    t: SortFilterStrings,
    yearMax: Int,
    yearUnknownCount: Int,
    isDark: Boolean,
    onSurface: Color,
) {
    val year = draft.year
    val narrowed = year.start.roundToInt() > LibraryFilter.YEAR_MIN || year.endInclusive.roundToInt() < yearMax
    FilterSectionHeader(
        title = t.sectionYear,
        trailing = "${year.start.roundToInt()} – ${year.endInclusive.roundToInt()}",
        isDark = isDark,
    )
    GlassRangeSlider(
        value = year,
        onValueChange = { draft.year = it },
        valueRange = LibraryFilter.YEAR_MIN.toFloat()..yearMax.toFloat(),
        divisions = (yearMax - LibraryFilter.YEAR_MIN).coerceAtLeast(1),
        hapticStride = 1,
    )
    if (narrowed && yearUnknownCount > 0) {
        Text(
            text = t.noYear(yearUnknownCount),
            fontFamily = SnProFamily,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = onSurface.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun RatingSection(draft: SortFilterDraft, t: SortFilterStrings, isDark: Boolean) {
    val rating = draft.rating
    FilterSectionHeader(
        title = t.sectionRating,
        trailing = "${RatingScale.format(roundToTenth(rating.start))} – ${RatingScale.format(roundToTenth(rating.endInclusive))}",
        isDark = isDark,
    )
    GlassRangeSlider(
        value = rating,
        onValueChange = { draft.rating = it },
        valueRange = LibraryFilter.RATING_MIN..LibraryFilter.RATING_MAX,
        divisions = 100,
        hapticStride = 10,
    )
}

/** Количество серий: шкала кубическая, см. [episodesFromSlider]. */
@Composable
private fun EpisodesSection(draft: SortFilterDraft, t: SortFilterStrings, isDark: Boolean) {
    val episodes = draft.episodes
    val low = episodesFromSlider(episodes.start)
    val high = episodesFromSlider(episodes.endInclusive)
    FilterSectionHeader(
        title = t.sectionEpisodes,
        trailing = if (high >= LibraryFilter.EPISODES_MAX) "$low – ${LibraryFilter.EPISODES_MAX}+" else "$low – $high",
        isDark = isDark,
    )
    GlassRangeSlider(
        value = episodes,
        onValueChange = { draft.episodes = it },
        valueRange = 0f..1f,
    )
}

@Composable
private fun ApplyButton(draft: SortFilterDraft, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = draft.sort.first.getAccentColor()
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(27.dp)
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .height(54.dp)
            .shadow(14.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(shape)
            .background(accent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            // По яркости заливки: акценты тёплые и светлые, белый текст на янтарном читался бы плохо.
            color = if (accent.luminance() > 0.2f) Color.Black.copy(alpha = 0.85f) else Color.White,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
        )
    }
}

@Composable
private fun FilterSectionHeader(title: String, trailing: String, isDark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = trailing,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.6f else 0.5f),
        )
    }
}

private data class ChipSpec(val label: String, val selected: Boolean, val accent: Color)

/** Чипсы в две колонки: подпись слева, круг-галочка справа. Нечётный хвост добивается пустой ячейкой. */
@Composable
private fun ChipGrid(chips: List<ChipSpec>, isDark: Boolean, onClick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.chunked(2).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { colIndex, chip ->
                    ChoicePill(
                        spec = chip,
                        isDark = isDark,
                        onClick = { onClick(rowIndex * 2 + colIndex) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChoicePill(spec: ChipSpec, isDark: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = SquircleShape(26.dp)
    val bg = if (spec.selected) spec.accent.copy(alpha = if (isDark) 0.22f else 0.14f) else IosDesign.elevatedCard(isDark)
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(shape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = spec.label,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            letterSpacing = (-0.1).sp,
            color = if (spec.selected) spec.accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f).padding(end = 6.dp),
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .then(
                    if (spec.selected) Modifier.background(spec.accent)
                    else Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)), CircleShape)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (spec.selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

