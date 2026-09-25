package com.example.myapplication.ui.shared.components

import com.example.myapplication.ui.shared.theme.BrandOrange
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.isAppInDarkTheme
import com.example.myapplication.data.models.GenreCategory
import com.example.myapplication.data.models.RatingScale
import com.example.myapplication.data.models.UiStrings
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.*
import com.example.myapplication.utils.performHaptic
import org.koin.compose.koinInject
import kotlin.math.roundToInt

// ==========================================
// AnimatedOneUiTextField
// ==========================================
@Composable
fun AnimatedOneUiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    maxLines: Int = 1
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) BrandOrange else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        label = "border"
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .onFocusChanged { isFocused = it.isFocused },
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = TextStyle(
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(BrandOrange),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        fontSize = 16.sp
                    )
                }
                innerTextField()
            }
        }
    )
}

// ==========================================
// AnimatedCopyButton
// ==========================================
@Composable
fun AnimatedCopyButton(textToCopy: String) {
    val context = LocalContext.current
    IconButton(onClick = {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("", textToCopy))
        Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
    }) {
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = "Copy",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ==========================================
// EpisodeSuggestions
// ==========================================
@Composable
fun EpisodeSuggestions(onSelect: (String) -> Unit) {
    val suggestions = listOf("12", "13", "24", "25", "26")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        suggestions.forEach { ep ->
            SuggestionChip(
                onClick = { onSelect(ep) },
                label = { Text(ep) },
                modifier = Modifier.height(32.dp)
            )
        }
    }
}

// RatingSlider заменён на морфинг-виджет со смайликом:
// см. ui/shared/components/rating/ (RatingTrackWidget + RatingOverlayHost).

// ==========================================
// GenreSelectionSection — collapsible categories (delegates to ExpandableGenreFlow)
// ==========================================
@Composable
fun GenreSelectionSection(
    strings: UiStrings,
    currentLanguage: AppLanguage,
    selectedTags: List<String>,
    activeCategory: String,
    onTagToggle: (String, String) -> Unit
) {
    val genreRepository: GenreRepository = koinInject()
    val animeGenres = remember { genreRepository.getGenresForCategory(GenreCategory.ANIME) }
    val movieGenres = remember { genreRepository.getGenresForCategory(GenreCategory.MOVIE) }
    val seriesGenres = remember { genreRepository.getGenresForCategory(GenreCategory.SERIES) }

    var expandedCategory by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var hasAutoExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(activeCategory) {
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
        "Anime" to Icons.Outlined.Animation,
        "Movies" to Icons.Outlined.Movie,
        "Series" to Icons.Outlined.Tv
    )

    val categories = listOf(
        // Вторая копия того же списка — держать её в тех же токенах, что и
        // FormatCategoryTilesSection, иначе палитры разъедутся (ровно это и произошло раньше).
        GenreCategoryData("Anime", strings.genreAnime, animeGenres, OverlayThemeTokens.AccentOrange),
        GenreCategoryData("Movies", strings.genreMovies, movieGenres, OverlayThemeTokens.AccentBlue),
        GenreCategoryData("Series", strings.genreSeries, seriesGenres, OverlayThemeTokens.AccentGreen)
    )

    ExpandableGenreFlow(
        categories = categories,
        activeCategory = activeCategory,
        selectedTags = selectedTags,
        expandedCategoryKey = expandedCategory,
        onExpandedChange = { expandedCategory = it },
        onTagToggle = onTagToggle,
        currentLanguage = currentLanguage,
        header = { catData, isExpanded, hasSelected, selectedCount, onToggle ->
            val catIcon = categoryIcons[catData.key] ?: Icons.Outlined.Animation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onToggle)
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    catIcon,
                    contentDescription = null,
                    tint = catData.accentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = catData.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (hasSelected) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(catData.accentColor)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "$selectedCount",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    )
}

// ==========================================
// GenreFilterPillSelection — оверлей «По жанрам» (те же плитки, что в Add/Edit)
// ==========================================

@Composable
fun GenreFilterPillSelection(
    strings: UiStrings,
    currentLanguage: AppLanguage,
    selectedTags: List<String>,
    activeCategory: String,
    onTagToggle: (String, String) -> Unit
) {
    FormatCategoryTilesWithGenres(
        saveableStateKey = "genre_filter_overlay",
        strings = strings,
        currentLanguage = currentLanguage,
        selectedTags = selectedTags,
        activeCategory = activeCategory,
        onTagToggle = onTagToggle
    )
}
