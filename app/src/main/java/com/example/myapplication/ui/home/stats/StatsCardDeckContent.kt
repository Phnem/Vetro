package com.example.myapplication.ui.home.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.UiStrings
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.domain.stats.StatsCardKind
import com.example.myapplication.domain.stats.buildBarChartData
import com.example.myapplication.domain.stats.buildDonutChartData
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.home.StatsBarChartCanvas
import com.example.myapplication.ui.home.StatsDonutChartCanvas
import com.example.myapplication.ui.home.StatsGenrePalette
import com.example.myapplication.ui.home.StatsMetricTile
import com.example.myapplication.ui.shared.components.SwipeableCardDeck
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.glassEdge
import com.example.myapplication.ui.shared.theme.glassFill
import androidx.compose.ui.graphics.Color
import org.koin.compose.koinInject
import java.util.Locale

// ==========================================
// StatsCardDeckContent — режим «колода» шторки статистики:
// три «постерные» карточки. Раскладка внутри карточки — вертикальная (как в референсах):
// график во всю ширину сверху, легенда полноширинными строками снизу.
// ==========================================

/** Высота карточки статистики = ширина × ratio (выше «постерной» 5/4, чтобы уместить график + строки). */
internal const val StatsCardHeightRatio = 1.52f

private val StatsCardShape = RoundedCornerShape(30.dp)

@Composable
fun StatsCardDeckContent(
    animeList: List<Anime>,
    strings: UiStrings,
    appLanguage: AppLanguage,
    isDark: Boolean,
    onCardTap: (StatsCardKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SwipeableCardDeck(
            items = StatsCardKind.entries.toList(),
            key = { it.name },
            cardShape = StatsCardShape,
            cardHeightRatio = StatsCardHeightRatio,
            onTopCardTap = onCardTap,
            modifier = Modifier.fillMaxWidth(),
        ) { kind, _ ->
            StatsCardSurface(isDark = isDark) {
                StatsCardBody(
                    kind = kind,
                    animeList = animeList,
                    strings = strings,
                    appLanguage = appLanguage,
                    isDark = isDark,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = strings.statsDeckHint,
            fontFamily = SnProFamily,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp),
        )
    }
}

/** Опаковая «стеклянная» подложка карточки — общая для колоды и детального режима. */
@Composable
internal fun StatsCardSurface(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val tileBg = if (isDark) {
        OverlayThemeTokens.TileBackgroundDark
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val tileRim = if (isDark) {
        OverlayThemeTokens.RimDark
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(StatsCardShape)
            .background(tileBg)
            .glassFill(isDark)
            .glassEdge(30.dp, isDark)
            .border(1.dp, tileRim, StatsCardShape),
        content = content,
    )
}

/** Внутренность карточки: caps-заголовок + график/сетка метрик. Переиспользуется в detail-режиме. */
@Composable
internal fun StatsCardBody(
    kind: StatsCardKind,
    animeList: List<Anime>,
    strings: UiStrings,
    appLanguage: AppLanguage,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val title = when (kind) {
        StatsCardKind.RATING_BY_GENRE -> strings.statsCardRatingTitle
        StatsCardKind.GENRE_FREQUENCY -> strings.statsCardFrequencyTitle
        StatsCardKind.OVERVIEW -> strings.statsCardOverviewTitle
    }
    Column(modifier = modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(
            text = title,
            style = OverlayThemeTokens.SectionLabel,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(14.dp))
        when (kind) {
            StatsCardKind.RATING_BY_GENRE -> RatingByGenreCard(animeList, strings, appLanguage, isDark)
            StatsCardKind.GENRE_FREQUENCY -> GenreFrequencyCard(animeList, strings, appLanguage, isDark)
            StatsCardKind.OVERVIEW -> OverviewCard(animeList, strings, isDark)
        }
    }
}

@Composable
private fun RatingByGenreCard(
    animeList: List<Anime>,
    strings: UiStrings,
    appLanguage: AppLanguage,
    isDark: Boolean,
) {
    val genreRepository: GenreRepository = koinInject()
    val barData = remember(animeList) { buildBarChartData(animeList) }
    if (barData.isEmpty()) {
        NoChartData(strings, isDark)
        return
    }
    val palette = StatsGenrePalette
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 6.dp)
        ) {
            StatsBarChartCanvas(entries = barData, barColors = palette, isDark = isDark)
        }
        Spacer(Modifier.height(10.dp))
        Column {
            barData.forEachIndexed { i, e ->
                StatsLegendRow(
                    color = palette.getOrElse(i) { palette[i % palette.size] },
                    name = genreRepository.getLabel(e.tagId, appLanguage),
                    detail = "%.2f  ·  %d".format(e.averageRating, e.titleCount),
                    isDark = isDark,
                    showDivider = i > 0,
                )
            }
        }
    }
}

@Composable
private fun GenreFrequencyCard(
    animeList: List<Anime>,
    strings: UiStrings,
    appLanguage: AppLanguage,
    isDark: Boolean,
) {
    val genreRepository: GenreRepository = koinInject()
    val donutData = remember(animeList) { buildDonutChartData(animeList) }
    if (donutData == null) {
        NoChartData(strings, isDark)
        return
    }
    val palette = StatsGenrePalette
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            StatsDonutChartCanvas(data = donutData, sliceColors = palette)
        }
        Spacer(Modifier.height(10.dp))
        Column {
            donutData.slices.forEachIndexed { i, s ->
                StatsLegendRow(
                    color = palette.getOrElse(i) { palette[i % palette.size] },
                    name = genreRepository.getLabel(s.tagId, appLanguage),
                    detail = "%.0f%%  ·  %d".format(s.share * 100.0, s.count),
                    isDark = isDark,
                    showDivider = i > 0,
                )
            }
        }
    }
}

@Composable
private fun OverviewCard(
    animeList: List<Anime>,
    strings: UiStrings,
    isDark: Boolean,
) {
    val totalAnime = animeList.size
    // Проход по всей коллекции — один раз на её изменение, а не на каждую рекомпозицию карточки.
    val ratingFormatted = remember(animeList) {
        val avgRating = if (animeList.isNotEmpty()) animeList.map { it.rating }.average() else 0.0
        String.format(Locale.getDefault(), "%.1f", avgRating)
    }
    val totalEpisodes = remember(animeList) { animeList.sumOf { it.episodes } }
    val favorites = remember(animeList) { animeList.count { it.isFavorite } }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatsMetricTile(
            modifier = Modifier.fillMaxWidth().weight(1f),
            value = totalAnime.toString(),
            label = strings.statsTotal,
            accent = OverlayThemeTokens.AccentNeonBlue,
            icon = Icons.Default.Visibility,
            isDark = isDark,
            minHeight = 0.dp,
            horizontal = true,
        )
        StatsMetricTile(
            modifier = Modifier.fillMaxWidth().weight(1f),
            value = ratingFormatted,
            label = strings.avgRating,
            accent = OverlayThemeTokens.AccentNeonYellow,
            icon = Icons.Rounded.Star,
            isDark = isDark,
            minHeight = 0.dp,
            horizontal = true,
        )
        StatsMetricTile(
            modifier = Modifier.fillMaxWidth().weight(1f),
            value = totalEpisodes.toString(),
            label = strings.episodesWatched,
            accent = OverlayThemeTokens.AccentNeonPurple,
            icon = Icons.Default.Layers,
            isDark = isDark,
            minHeight = 0.dp,
            horizontal = true,
        )
        StatsMetricTile(
            modifier = Modifier.fillMaxWidth().weight(1f),
            value = favorites.toString(),
            label = strings.favorites,
            accent = OverlayThemeTokens.AccentNeonPink,
            icon = Icons.Default.Favorite,
            isDark = isDark,
            minHeight = 0.dp,
            horizontal = true,
        )
    }
}

/** Полноширинная строка легенды: цвет-точка · название · «значение · счётчик» · шеврон. */
@Composable
private fun StatsLegendRow(
    color: Color,
    name: String,
    detail: String,
    isDark: Boolean,
    showDivider: Boolean,
) {
    val nameColor = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val detailColor = if (isDark) OverlayThemeTokens.LabelMutedDark
    else MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = (if (isDark) Color.White else Color.Black).copy(alpha = 0.06f)

    if (showDivider) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(dividerColor)
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(11.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Medium,
            ),
            color = nameColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
            color = detailColor,
            maxLines = 1,
        )
        Spacer(Modifier.size(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = detailColor.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun NoChartData(strings: UiStrings, isDark: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = strings.statsNoGenreData,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = SnProFamily,
                lineHeight = 20.sp,
            ),
            color = if (isDark) OverlayThemeTokens.LabelMutedDark
            else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
        )
    }
}
