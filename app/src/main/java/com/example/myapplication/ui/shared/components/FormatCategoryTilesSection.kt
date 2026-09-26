package com.example.myapplication.ui.shared.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.models.GenreCategory
import com.example.myapplication.data.models.UiStrings
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.icons.HeroCheck
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.lightTileShadowInLightTheme
import org.koin.compose.koinInject

/**
 * Shared tile row + expandable genre chips (same UI as Add/Edit format category).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormatCategoryTilesWithGenres(
    saveableStateKey: String,
    strings: UiStrings,
    currentLanguage: AppLanguage,
    selectedTags: List<String>,
    activeCategory: String,
    onTagToggle: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val genreRepository: GenreRepository = koinInject()
    val animeGenres = remember { genreRepository.getGenresForCategory(GenreCategory.ANIME) }
    val movieGenres = remember { genreRepository.getGenresForCategory(GenreCategory.MOVIE) }
    val seriesGenres = remember { genreRepository.getGenresForCategory(GenreCategory.SERIES) }

    var expandedCategory by rememberSaveable(saveableStateKey) { mutableStateOf<String?>(null) }
    var hasAutoExpanded by remember(saveableStateKey) { mutableStateOf(false) }
    LaunchedEffect(activeCategory, saveableStateKey) {
        if (!hasAutoExpanded && activeCategory.isNotEmpty()) {
            val match = listOf("Anime", "Movies", "Series").find {
                it.equals(activeCategory, ignoreCase = true)
            }
            if (match != null) {
                expandedCategory = match
                hasAutoExpanded = true
            }
        }
    }

    val categoryIcons = mapOf(
        "Anime" to Icons.Outlined.AutoAwesome,
        "Movies" to Icons.Outlined.Movie,
        "Series" to Icons.Outlined.Tv
    )

    val tileLabels = mapOf(
        "Anime" to strings.genreAnime,
        "Movies" to strings.genreMovies,
        "Series" to strings.genreSeries
    )

    val categories = listOf(
        // Разные насыщенные цвета вместо прежних «бренд + два нейтрала» (#8A8A8E серый,
        // #D9C3AB бежевый): рядом с брендовым оранжевым нейтралы читались как выключенные плитки.
        GenreCategoryData("Anime", strings.genreAnime, animeGenres, OverlayThemeTokens.AccentOrange),
        GenreCategoryData("Movies", strings.genreMovies, movieGenres, OverlayThemeTokens.AccentBlue),
        GenreCategoryData("Series", strings.genreSeries, seriesGenres, OverlayThemeTokens.AccentGreen)
    )

    val isDark = isAppInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val tileShape = RoundedCornerShape(14.dp)
    val tileBg =
        if (isDark) scheme.surfaceContainerHigh.copy(alpha = 0.42f)
        else scheme.surfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            categories.forEach { catData ->
                val icon = categoryIcons[catData.key] ?: Icons.Outlined.AutoAwesome
                val label = tileLabels[catData.key] ?: catData.key
                val isExpandedTile = expandedCategory == catData.key
                val matchesActive = activeCategory.equals(catData.key, ignoreCase = true)
                val isCollapsed = expandedCategory == null
                val showAccentBorder = isExpandedTile || (isCollapsed && matchesActive)
                val useAccentIcon = showAccentBorder
                val selectedCount = selectedTags.count { tag -> catData.genres.any { it.id == tag } }

                FormatCategoryTile(
                    icon = icon,
                    accentColor = catData.accentColor,
                    label = label,
                    showAccentBorder = showAccentBorder,
                    useAccentIcon = useAccentIcon,
                    selectedCount = selectedCount,
                    isDark = isDark,
                    tileShape = tileShape,
                    tileBg = tileBg,
                    onClick = {
                        expandedCategory = if (isExpandedTile) null else catData.key
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        val expandedData = categories.find { it.key == expandedCategory }
        AnimatedVisibility(
            visible = expandedData != null,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                clip = true,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) + slideInVertically(
                initialOffsetY = { full -> -(full / 14).coerceAtLeast(1) },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) + fadeIn(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                clip = true,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + slideOutVertically(
                targetOffsetY = { full -> -(full / 14).coerceAtLeast(1) },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeOut(animationSpec = tween(160))
        ) {
            expandedData?.let { catData ->
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    catData.genres.forEach { genreDef ->
                        val isSelected = selectedTags.contains(genreDef.id)
                        val displayName = if (currentLanguage == AppLanguage.RU) {
                            genreDef.ru
                        } else {
                            genreDef.en
                        }
                        FormatCategoryGenreChip(
                            label = displayName,
                            selected = isSelected,
                            accentColor = catData.accentColor,
                            isDark = isDark,
                            onClick = { onTagToggle(genreDef.id, catData.key) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FormatCategoryGenreChip(
    label: String,
    selected: Boolean,
    accentColor: Color,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val checkTint =
        if (isDark) Color.White.copy(alpha = 0.95f)
        else scheme.onSurface.copy(alpha = 0.92f)
    val labelColor =
        if (selected) accentColor
        else scheme.onSurface
    val bg =
        when {
            selected && isDark -> accentColor.copy(alpha = 0.15f)
            selected && !isDark -> lerp(scheme.surfaceContainerHighest, accentColor, 0.22f)
            isDark -> scheme.surfaceContainerHighest.copy(alpha = 0.35f)
            else -> scheme.surfaceContainerHighest
        }
    val strokeColor =
        if (selected) accentColor.copy(alpha = 0.45f)
        else scheme.outline.copy(alpha = if (isDark) 0.2f else 0.22f)

    Row(
        modifier = modifier
            .lightTileShadowInLightTheme(isDark, shape)
            .clip(shape)
            .background(bg)
            .border(1.dp, strokeColor, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (selected) {
            Icon(
                imageVector = HeroCheck,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = checkTint
            )
        }
        Text(
            text = label,
            fontSize = 13.sp,
            fontFamily = SnProFamily,
            color = labelColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun FormatCategoryTile(
    icon: ImageVector,
    accentColor: Color,
    label: String,
    showAccentBorder: Boolean,
    useAccentIcon: Boolean,
    selectedCount: Int,
    isDark: Boolean,
    tileShape: RoundedCornerShape,
    tileBg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compactLayout: Boolean = false,
    alwaysAccentIcon: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val mutedIconTint = scheme.onSurfaceVariant.copy(alpha = 0.42f)
    val targetIconTint = if (alwaysAccentIcon || useAccentIcon) accentColor else mutedIconTint
    val animatedIconTint by animateColorAsState(
        targetValue = targetIconTint,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "formatTileIconTint"
    )
    val targetBorderColor = if (showAccentBorder) accentColor else Color.Transparent
    val animatedBorderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "formatTileBorder"
    )
    val labelColor = when {
        showAccentBorder -> accentColor
        alwaysAccentIcon -> accentColor.copy(alpha = 0.88f)
        else -> scheme.onSurface
    }
    val iconSize = if (compactLayout) 24.dp else 28.dp
    val verticalPad = if (compactLayout) 8.dp else 10.dp
    val iconLabelGap = if (compactLayout) 4.dp else 8.dp
    val textStyle = if (compactLayout) {
        MaterialTheme.typography.labelSmall.copy(
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            lineHeight = 12.sp,
        )
    } else {
        MaterialTheme.typography.labelLarge.copy(
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold
        )
    }
    val boxModifier = if (compactLayout) {
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 84.dp)
    } else {
        modifier.aspectRatio(1f)
    }

    Box(modifier = boxModifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .lightTileShadowInLightTheme(isDark, tileShape)
                .clip(tileShape)
                .background(tileBg)
                .border(1.5.dp, animatedBorderColor, tileShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 6.dp, vertical = verticalPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animatedIconTint,
                modifier = Modifier.size(iconSize)
            )
            Spacer(Modifier.height(iconLabelGap))
            Text(
                text = label,
                style = textStyle,
                color = labelColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        if (selectedCount > 0) {
            val badgeBg =
                if (isDark) accentColor.copy(alpha = 0.92f)
                else scheme.surface
            val badgeCheckTint =
                if (isDark) Color.White.copy(alpha = 0.95f)
                else scheme.onSurface.copy(alpha = 0.92f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(badgeBg)
                    .then(
                        if (!isDark) {
                            Modifier.border(1.dp, accentColor.copy(alpha = 0.55f), CircleShape)
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HeroCheck,
                    contentDescription = selectedCount.toString(),
                    modifier = Modifier.size(15.dp),
                    tint = badgeCheckTint
                )
            }
        }
    }
}
