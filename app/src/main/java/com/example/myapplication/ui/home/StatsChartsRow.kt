package com.example.myapplication.ui.home

import com.example.myapplication.ui.shared.theme.BrandSand
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.BrandGray
import com.example.myapplication.ui.shared.theme.BrandDeepRed
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.UiStrings
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.domain.stats.BarChartEntry
import com.example.myapplication.domain.stats.DonutChartData
import com.example.myapplication.domain.stats.TagFrequencySlice
import com.example.myapplication.domain.stats.buildBarChartData
import com.example.myapplication.domain.stats.buildDonutChartData
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.glassEdge
import com.example.myapplication.ui.shared.theme.glassFill
import com.example.myapplication.ui.shared.theme.softPlateShadowForLightSheet
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Пять фиксированных контрастных цветов. Индекс i в топ-5 обоих графиков = palette[i] в графике и в легенде.
 */
internal val StatsGenrePalette: List<Color> = listOf(
    BrandOrange,
    BrandSand,
    BrandDeepRed,
    Color(0xFFFFB067),
    BrandGray
)

private const val DONUT_SEGMENT_GAP_DEG = 4f

@Composable
fun StatsChartsRow(
    animeList: List<Anime>,
    strings: UiStrings,
    appLanguage: AppLanguage,
    modifier: Modifier = Modifier
) {
    val genreRepository: GenreRepository = koinInject()
    val isDark = isAppInDarkTheme()
    val palette = StatsGenrePalette
    val donutData: DonutChartData? = remember(animeList) { buildDonutChartData(animeList) }
    val barData: List<BarChartEntry> = remember(animeList) { buildBarChartData(animeList) }
    val labelMuted = if (isDark) OverlayThemeTokens.LabelMutedDark
    else MaterialTheme.colorScheme.onSurfaceVariant

    fun labelForTag(tagId: String): String = genreRepository.getLabel(tagId, appLanguage)

    val tileShape = RoundedCornerShape(OverlayThemeTokens.TileCornerRadius)
    val innerPad = 12.dp
    val tileH = 240.dp
    
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

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = strings.statsChartsGenresTitle,
            style = OverlayThemeTokens.SectionLabel,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            modifier = Modifier.padding(bottom = 10.dp)
        )
        if (donutData == null) {
            Text(
                text = strings.statsNoGenreData,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = SnProFamily,
                    lineHeight = 20.sp
                ),
                color = labelMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isDark) Modifier else Modifier.softPlateShadowForLightSheet(
                            isDark = false,
                            shape = tileShape,
                            elevation = OverlayThemeTokens.SortOverlayGridLightShadowElevation,
                        )
                    )
                    .clip(tileShape)
                    .background(tileBg)
                    .glassFill(isDark)
                    .glassEdge(OverlayThemeTokens.TileCornerRadius, isDark)
                    .border(1.dp, tileRim, tileShape)
                    .padding(16.dp)
            )
            return
        }

        // Плитка 1: качество (столбцы слева, легенда справа)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(tileH)
                .then(
                    if (isDark) Modifier else Modifier.softPlateShadowForLightSheet(
                        isDark = false,
                        shape = tileShape,
                        elevation = OverlayThemeTokens.SortOverlayGridLightShadowElevation,
                    )
                )
                .clip(tileShape)
                .background(tileBg)
                .glassFill(isDark)
                .glassEdge(OverlayThemeTokens.TileCornerRadius, isDark)
                .border(1.dp, tileRim, tileShape)
                .padding(innerPad),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                StatsBarChartCanvas(
                    entries = barData,
                    barColors = palette,
                    isDark = isDark
                )
            }
            Column(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight()
                    .padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top)
            ) {
                if (barData.isEmpty()) {
                    Text("—", color = labelMuted, fontSize = 12.sp)
                } else {
                    barData.forEachIndexed { i, e ->
                        GenreLegendItem(
                            color = palette.getOrElse(i) { palette[i % palette.size] },
                            name = labelForTag(e.tagId),
                            detail = "%.2f  ·  %d".format(
                                e.averageRating,
                                e.titleCount
                            ),
                            isDark = isDark
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Плитка 2: частотность (легенда слева, donut справа)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(tileH)
                .then(
                    if (isDark) Modifier else Modifier.softPlateShadowForLightSheet(
                        isDark = false,
                        shape = tileShape,
                        elevation = OverlayThemeTokens.SortOverlayGridLightShadowElevation,
                    )
                )
                .clip(tileShape)
                .background(tileBg)
                .glassFill(isDark)
                .glassEdge(OverlayThemeTokens.TileCornerRadius, isDark)
                .border(1.dp, tileRim, tileShape)
                .padding(innerPad),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top)
            ) {
                donutData.slices.forEachIndexed { i, s ->
                    GenreLegendItem(
                        color = palette.getOrElse(i) { palette[i % palette.size] },
                        name = labelForTag(s.tagId),
                        detail = "%.0f%%  ·  %d".format(
                            s.share * 100.0,
                            s.count
                        ),
                        isDark = isDark
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                StatsDonutChartCanvas(
                    data = donutData,
                    sliceColors = palette
                )
            }
        }
    }
}

@Composable
internal fun GenreLegendItem(
    color: Color,
    name: String,
    detail: String,
    isDark: Boolean
) {
    val nameColor = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val detailC = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = SnProFamily),
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = SnProFamily,
                        fontSize = 10.sp
                    ),
                    color = detailC,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Столбчатая диаграмма «качество жанров» на всю высоту контейнера.
 * Шкала — 10-балльная (значок ★ = 2 балла), пять пунктирных линий сетки на уровнях 2/4/6/8/10.
 */
@Composable
internal fun StatsBarChartCanvas(
    entries: List<BarChartEntry>,
    barColors: List<Color>,
    isDark: Boolean
) {
    if (entries.isEmpty()) {
        Text(
            text = "—",
            color = if (isDark) OverlayThemeTokens.LabelMutedDark
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    val maxRating = 10f
    val axisColor = (if (isDark) OverlayThemeTokens.LabelMutedDark
    else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.5f)
    val gridColor = axisColor.copy(alpha = 0.22f)
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(
            modifier = Modifier
                .width(26.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start
        ) {
            for (star in 5 downTo 1) {
                Text(
                    text = "${star}★",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = SnProFamily,
                        fontSize = 10.sp
                    ),
                    color = axisColor
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 4.dp)
        ) {
            // Пунктирная сетка на уровнях звёзд (позади столбцов)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                val stroke = 1.dp.toPx()
                for (k in 0 until 5) {
                    val y = size.height * k / 4f
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = stroke,
                        pathEffect = dash
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for ((i, e) in entries.withIndex()) {
                    val hFrac = (e.averageRating / maxRating).toFloat().coerceIn(0.06f, 1f)
                    val c = barColors.getOrElse(i) { barColors[i % barColors.size] }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(hFrac)
                                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                .background(c)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun StatsDonutChartCanvas(
    data: DonutChartData,
    sliceColors: List<Color>
) {
    val slices: List<TagFrequencySlice> = data.slices
    val centerLabel = data.top5CountSum.toString()
    val startAngles = FloatArray(slices.size)
    val fullSweeps = FloatArray(slices.size)
    var accDeg = 0f
    for (i in slices.indices) {
        startAngles[i] = -90f + accDeg
        val full = slices[i].share * 360f
        fullSweeps[i] = full
        accDeg += full
    }
    val colors: List<Color> = remember(slices.size, sliceColors) {
        slices.indices.map { i -> sliceColors.getOrElse(i) { sliceColors[i % sliceColors.size] } }
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val d = minOf(w, h)
            val strokeW = d * 0.16f
            val actualD = d - strokeW
            val topLeft = Offset((w - actualD) / 2f, (h - actualD) / 2f)
            val ringSize = Size(actualD, actualD)
            for (i in slices.indices) {
                val col = colors[i]
                val actualSweep = (fullSweeps[i] - DONUT_SEGMENT_GAP_DEG).coerceAtLeast(0.1f)
                drawArc(
                    color = col,
                    startAngle = startAngles[i],
                    sweepAngle = actualSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringSize,
                    style = Stroke(
                        width = strokeW,
                        cap = StrokeCap.Round
                    )
                )
            }
        }
        Text(
            text = centerLabel,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = SnProFamily
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
