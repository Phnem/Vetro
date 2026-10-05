package com.example.myapplication.ui.home.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.domain.stats.ActivityFilter
import com.example.myapplication.domain.stats.ActivityHeatmap
import com.example.myapplication.domain.stats.CollectionInsights
import com.example.myapplication.domain.stats.CollectionInsightsLoader
import com.example.myapplication.domain.stats.GenreQuality
import com.example.myapplication.domain.stats.GenreShare
import com.example.myapplication.domain.stats.InsightsData
import com.example.myapplication.domain.stats.MonthBucket
import com.example.myapplication.domain.stats.ProfileShift
import com.example.myapplication.domain.stats.ReturnsSummary
import com.example.myapplication.domain.stats.StatsInsightsStrings
import com.example.myapplication.domain.stats.statsInsightsStrings
import com.example.myapplication.media.intelligence.SourceIntelligence
import com.example.myapplication.media.intelligence.SourceStanding
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.StatusSuccess
import com.example.myapplication.ui.shared.theme.glassEdge
import com.example.myapplication.ui.shared.theme.glassFill
import org.koin.compose.koinInject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// ==========================================
// StatsInsightsSection — блок под колодой статистики:
// активность по дням (heatmap), по месяцам (area), возвраты, жанры «досмотр/бросание»,
// профиль последних 90 дней против всей коллекции. Палитра — только оранжевый, зелёный и серые.
// ==========================================

private val TileShape = RoundedCornerShape(OverlayThemeTokens.TileCornerRadius)

@Composable
fun StatsInsightsSection(
    animeList: List<Anime>,
    appLanguage: AppLanguage,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    if (animeList.isEmpty()) return
    val loader: CollectionInsightsLoader = koinInject()
    val genreRepository: GenreRepository = koinInject()
    val strings = remember(appLanguage) { statsInsightsStrings(appLanguage) }
    val locale = remember(appLanguage) {
        if (appLanguage == AppLanguage.RU) Locale.forLanguageTag("ru") else Locale.ENGLISH
    }
    val data: InsightsData? by produceState<InsightsData?>(initialValue = null, animeList) {
        value = runCatching { loader.load(animeList) }.getOrNull()
    }
    val insights = data ?: return

    var filter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val heatmap = remember(insights, filter) {
        CollectionInsights.heatmap(insights.events, filter, today, zone)
    }
    val months = remember(insights, filter) {
        CollectionInsights.monthly(insights.events, filter, today, zone)
    }
    fun genreName(tagId: String) = genreRepository.getLabel(tagId, appLanguage)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = strings.sectionTitle,
            style = OverlayThemeTokens.SectionLabel,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = OverlayThemeTokens.LabelMutedAlpha),
        )

        InsightTile(isDark) {
            TileTitle(strings.activityTitle, isDark)
            Spacer(Modifier.height(10.dp))
            FilterChips(filter, strings, isDark) { filter = it }
            Spacer(Modifier.height(12.dp))
            if (heatmap.isEmpty) {
                MutedText(strings.activityEmpty, isDark)
            } else {
                HeatmapGrid(heatmap, isDark)
                Spacer(Modifier.height(8.dp))
                HeatmapLegend(strings, isDark)
                Spacer(Modifier.height(8.dp))
                MutedText(heatmapSummary(heatmap, strings, locale), isDark)
            }
        }

        InsightTile(isDark) {
            TileTitle(strings.monthlyTitle, isDark)
            Spacer(Modifier.height(10.dp))
            if (months.all { it.units == 0 }) {
                MutedText(strings.monthlyEmpty, isDark)
            } else {
                MonthlyAreaChart(months, locale, isDark)
                val peak = months.maxByOrNull { it.units }
                if (peak != null && peak.units > 0) {
                    Spacer(Modifier.height(6.dp))
                    MutedText(
                        strings.monthlyBest(
                            peak.month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale),
                            peak.units,
                        ),
                        isDark,
                    )
                }
            }
        }

        InsightTile(isDark) {
            TileTitle(strings.returnsTitle, isDark)
            Spacer(Modifier.height(12.dp))
            ReturnsRow(insights.returns, strings, isDark)
            Spacer(Modifier.height(10.dp))
            MutedText(strings.returnsCaption, isDark)
        }

        InsightTile(isDark) {
            TileTitle(strings.genreQualityTitle, isDark)
            Spacer(Modifier.height(10.dp))
            if (insights.genreQuality.isEmpty()) {
                MutedText(strings.genreQualityEmpty, isDark)
            } else {
                insights.genreQuality.forEachIndexed { i, g ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    GenreQualityRow(g, genreName(g.tagId), strings, isDark)
                }
                Spacer(Modifier.height(10.dp))
                MutedText(strings.genreQualityNote, isDark)
            }
        }

        InsightTile(isDark) {
            TileTitle(strings.profileTitle, isDark)
            Spacer(Modifier.height(10.dp))
            ProfileBlock(insights.profile, strings, isDark, ::genreName)
        }

        SourceStandingsTile(strings, appLanguage, isDark)
    }
}

// --- Source Intelligence: как источники ведут себя именно на этом устройстве -------------------

@Composable
private fun SourceStandingsTile(strings: StatsInsightsStrings, appLanguage: AppLanguage, isDark: Boolean) {
    val intelligence: SourceIntelligence = koinInject()
    val standings: List<SourceStanding> by produceState(emptyList<SourceStanding>(), appLanguage) {
        value = runCatching { intelligence.leaderboard() }.getOrDefault(emptyList())
            .filter { it.language == appLanguage && it.stats.evidence > 0 }
    }
    InsightTile(isDark) {
        TileTitle(strings.sourcesTitle, isDark)
        Spacer(Modifier.height(10.dp))
        if (standings.isEmpty()) {
            MutedText(strings.sourcesEmpty, isDark)
            return@InsightTile
        }
        standings.take(6).forEachIndexed { i, s ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = s.provider,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Medium),
                    color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = s.score.value.toString(),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Bold),
                    color = if (i == 0) StatusSuccess else mutedColor(isDark),
                )
            }
            Spacer(Modifier.height(4.dp))
            ShareBar(s.score.value / 100f, if (i == 0) StatusSuccess else inkOnTile(isDark, 0.35f), isDark)
            val detail = strings.sourcesDetail(
                s.score.resolveSuccessRate?.let { (it * 100).roundToInt() },
                s.stats.startupMs.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it / 1000.0) },
                s.stats.heightPx.takeIf { it > 0 },
            )
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily, fontSize = 11.sp),
                    color = mutedColor(isDark),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MutedText(strings.sourcesNote, isDark)
    }
}

// --- Плитка ---------------------------------------------------------------------------------

@Composable
private fun InsightTile(isDark: Boolean, content: @Composable () -> Unit) {
    val tileBg = if (isDark) OverlayThemeTokens.TileBackgroundDark else MaterialTheme.colorScheme.surfaceVariant
    val tileRim = if (isDark) OverlayThemeTokens.RimDark else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TileShape)
            .background(tileBg)
            .glassFill(isDark)
            .glassEdge(OverlayThemeTokens.TileCornerRadius, isDark)
            .border(1.dp, tileRim, TileShape)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun TileTitle(text: String, isDark: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold),
        color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun MutedText(text: String, isDark: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = SnProFamily, fontSize = 12.sp, lineHeight = 17.sp),
        color = mutedColor(isDark),
    )
}

@Composable
private fun mutedColor(isDark: Boolean): Color =
    if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant

private fun inkOnTile(isDark: Boolean, alpha: Float): Color =
    (if (isDark) Color.White else Color.Black).copy(alpha = alpha)

// --- Фильтр ---------------------------------------------------------------------------------

@Composable
private fun FilterChips(
    selected: ActivityFilter,
    strings: StatsInsightsStrings,
    isDark: Boolean,
    onSelect: (ActivityFilter) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ActivityFilter.entries.forEach { f ->
            val label = when (f) {
                ActivityFilter.ALL -> strings.filterAll
                ActivityFilter.WATCH -> strings.filterWatch
                ActivityFilter.READ -> strings.filterRead
                ActivityFilter.LISTEN -> strings.filterListen
            }
            val active = f == selected
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (active) BrandOrange else inkOnTile(isDark, 0.08f))
                    .fluidClickable { onSelect(f) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Medium),
                    color = if (active) Color.White else mutedColor(isDark),
                    maxLines = 1,
                )
            }
        }
    }
}

// --- Heatmap --------------------------------------------------------------------------------

private fun levelColor(level: Int, isDark: Boolean): Color = when (level) {
    0 -> inkOnTile(isDark, 0.07f)
    1 -> BrandOrange.copy(alpha = 0.28f)
    2 -> BrandOrange.copy(alpha = 0.5f)
    3 -> BrandOrange.copy(alpha = 0.75f)
    else -> BrandOrange
}

@Composable
private fun HeatmapGrid(heatmap: ActivityHeatmap, isDark: Boolean) {
    val weeks = heatmap.weeks
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(weeks.size / 7f),
    ) {
        val step = size.width / weeks.size
        val cell = step * 0.8f
        val inset = (step - cell) / 2f
        val radius = CornerRadius(cell * 0.28f)
        weeks.forEachIndexed { x, column ->
            column.forEachIndexed { y, day ->
                if (day.future) return@forEachIndexed
                drawRoundRect(
                    color = levelColor(day.level, isDark),
                    topLeft = Offset(x * step + inset, y * step + inset),
                    size = Size(cell, cell),
                    cornerRadius = radius,
                )
            }
        }
    }
}

@Composable
private fun HeatmapLegend(strings: StatsInsightsStrings, isDark: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = strings.legendLess,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily, fontSize = 10.sp),
            color = mutedColor(isDark),
        )
        for (level in 0..4) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(levelColor(level, isDark)))
        }
        Text(
            text = strings.legendMore,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily, fontSize = 10.sp),
            color = mutedColor(isDark),
        )
    }
}

private fun heatmapSummary(heatmap: ActivityHeatmap, strings: StatsInsightsStrings, locale: Locale): String {
    val parts = mutableListOf(strings.activeDays(heatmap.activeDays))
    heatmap.bestDay?.let { best ->
        val date = best.date.format(DateTimeFormatter.ofPattern("d MMM", locale))
        parts += strings.bestDay(date, best.units)
    }
    heatmap.peakWeekday?.let { day ->
        parts += strings.peakWeekday(day.getDisplayName(TextStyle.FULL_STANDALONE, locale))
    }
    return parts.joinToString(" · ")
}

// --- Месяцы ---------------------------------------------------------------------------------

@Composable
private fun MonthlyAreaChart(buckets: List<MonthBucket>, locale: Locale, isDark: Boolean) {
    val n = buckets.size
    val maxUnits = (buckets.maxOfOrNull { it.units } ?: 0).coerceAtLeast(1)
    val gridColor = inkOnTile(isDark, 0.08f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp),
    ) {
        val top = 10.dp.toPx()
        val usable = size.height - top
        val colW = size.width / n
        fun point(i: Int): Offset =
            Offset(colW * (i + 0.5f), top + usable * (1f - buckets[i].units.toFloat() / maxUnits))

        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

        val line = Path().apply {
            moveTo(point(0).x, point(0).y)
            for (i in 1 until n) {
                val prev = point(i - 1)
                val cur = point(i)
                val mid = (prev.x + cur.x) / 2f
                // Горизонтальные касательные в узлах: кривая гладкая и не вылетает за значения.
                cubicTo(mid, prev.y, mid, cur.y, cur.x, cur.y)
            }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(point(n - 1).x, size.height)
            lineTo(point(0).x, size.height)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(
                listOf(BrandOrange.copy(alpha = 0.38f), BrandOrange.copy(alpha = 0.0f)),
                startY = top,
                endY = size.height,
            ),
        )
        drawPath(
            line,
            BrandOrange,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val peakIndex = buckets.indices.maxByOrNull { buckets[it].units } ?: 0
        if (buckets[peakIndex].units > 0) {
            drawCircle(BrandOrange, radius = 4.5.dp.toPx(), center = point(peakIndex))
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        buckets.forEachIndexed { i, b ->
            // Подпись через одну, чтобы 12 коротких названий не слипались; последний месяц всегда подписан.
            val show = (n - 1 - i) % 2 == 0
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (show) {
                    Text(
                        text = b.month.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale).take(3),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily, fontSize = 10.sp),
                        color = mutedColor(isDark),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

// --- Возвраты -------------------------------------------------------------------------------

@Composable
private fun ReturnsRow(returns: ReturnsSummary, strings: StatsInsightsStrings, isDark: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReturnMetric(
            modifier = Modifier.weight(1f),
            value = returns.rewatchTitles,
            label = strings.rewatch,
            detail = strings.unitEpisodes(returns.rewatchEpisodes),
            isDark = isDark,
        )
        ReturnMetric(
            modifier = Modifier.weight(1f),
            value = returns.rereadTitles,
            label = strings.reread,
            detail = strings.unitChapters(returns.rereadChapters),
            isDark = isDark,
        )
        ReturnMetric(
            modifier = Modifier.weight(1f),
            value = returns.relistenBooks,
            label = strings.relisten,
            detail = strings.unitBooks(returns.relistenBooks),
            isDark = isDark,
        )
    }
}

@Composable
private fun ReturnMetric(modifier: Modifier, value: Int, label: String, detail: String, isDark: Boolean) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(inkOnTile(isDark, 0.06f))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Bold),
            color = if (value > 0) BrandOrange else mutedColor(isDark),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Medium),
            color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily, fontSize = 10.sp),
            color = mutedColor(isDark),
            maxLines = 1,
        )
    }
}

// --- Жанры: досмотр / бросание / возвраты ---------------------------------------------------

@Composable
private fun GenreQualityRow(g: GenreQuality, name: String, strings: StatsInsightsStrings, isDark: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Medium),
                color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${g.started}/${g.titles}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily),
                color = mutedColor(isDark),
            )
        }
        Spacer(Modifier.height(5.dp))
        StackedRateBar(completed = g.completionRate, dropped = g.dropRate, isDark = isDark)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${strings.completedLabel} ${percent(g.completionRate)} · " +
                "${strings.droppedLabel} ${percent(g.dropRate)} · " +
                "${strings.returnedLabel} ${g.returned}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily, fontSize = 11.sp),
            color = mutedColor(isDark),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StackedRateBar(completed: Float, dropped: Float, isDark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(CircleShape)
            .background(inkOnTile(isDark, 0.08f)),
    ) {
        if (completed > 0f) Box(Modifier.weight(completed).height(7.dp).background(StatusSuccess))
        if (dropped > 0f) Box(Modifier.weight(dropped).height(7.dp).background(BrandOrange))
        val rest = (1f - completed - dropped).coerceAtLeast(0f)
        if (rest > 0f) Spacer(Modifier.weight(rest))
    }
}

private fun percent(share: Float): String = "${(share * 100f).roundToInt()}%"

// --- Профиль: последние 90 дней против всей коллекции ---------------------------------------

@Composable
private fun ProfileBlock(
    profile: ProfileShift,
    strings: StatsInsightsStrings,
    isDark: Boolean,
    genreName: (String) -> String,
) {
    if (!profile.hasRecent) {
        MutedText(strings.profileNotEnough, isDark)
        return
    }
    MutedText(strings.profileSummary(profile.recentTitles, profile.totalTitles), isDark)
    val recentRating = profile.recentAvgRating
    val overallRating = profile.overallAvgRating
    if (recentRating != null && overallRating != null) {
        Spacer(Modifier.height(2.dp))
        MutedText(
            strings.profileRating(
                String.format(Locale.getDefault(), "%.1f", recentRating),
                String.format(Locale.getDefault(), "%.1f", overallRating),
            ),
            isDark,
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendDot(BrandOrange, strings.profileRecentLabel(profile.windowDays), isDark)
        LegendDot(inkOnTile(isDark, 0.35f), strings.profileOverallLabel, isDark)
    }
    Spacer(Modifier.height(10.dp))
    profile.genres.forEachIndexed { i, g ->
        if (i > 0) Spacer(Modifier.height(10.dp))
        ProfileGenreRow(g, genreName(g.tagId), isDark)
    }
}

@Composable
private fun LegendDot(color: Color, label: String, isDark: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily, fontSize = 11.sp),
            color = mutedColor(isDark),
        )
    }
}

@Composable
private fun ProfileGenreRow(g: GenreShare, name: String, isDark: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily, fontWeight = FontWeight.Medium),
                color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val arrow = when {
                g.delta > 0.05f -> "↑ "
                g.delta < -0.05f -> "↓ "
                else -> ""
            }
            Text(
                text = "$arrow${percent(g.recentShare)} · ${percent(g.overallShare)}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily),
                color = if (g.delta > 0.05f) BrandOrange else mutedColor(isDark),
            )
        }
        Spacer(Modifier.height(4.dp))
        ShareBar(g.recentShare, BrandOrange, isDark)
        Spacer(Modifier.height(3.dp))
        ShareBar(g.overallShare, inkOnTile(isDark, 0.35f), isDark)
    }
}

@Composable
private fun ShareBar(share: Float, color: Color, isDark: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(CircleShape)
            .background(inkOnTile(isDark, 0.06f)),
    ) {
        if (share > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(share.coerceIn(0.02f, 1f))
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
