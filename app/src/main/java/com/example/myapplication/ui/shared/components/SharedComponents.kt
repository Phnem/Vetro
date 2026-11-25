package com.example.myapplication.ui.shared.components

import com.example.myapplication.ui.shared.components.SpringyBasicTextField
import com.example.myapplication.ui.shared.mininotif.MiniNotif
import com.example.myapplication.ui.shared.mininotif.MiniNotifKind
import com.example.myapplication.ui.shared.theme.BrandOrange
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
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

    SpringyBasicTextField(
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

// RatingSlider заменён на морфинг-виджет со смайликом:
// см. ui/shared/components/rating/ (RatingTrackWidget + RatingOverlayHost).

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
