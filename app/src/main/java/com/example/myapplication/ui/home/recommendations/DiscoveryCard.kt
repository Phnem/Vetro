package com.example.myapplication.ui.home.recommendations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.example.myapplication.isAppInDarkTheme
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.SnProFamily

/** Строки фичи рекомендаций (локально, чтобы не раздувать UiStrings). */
data class RecommendationsStrings(
    val overline: String,
    val overlineLowConfidence: String,
    val overlineColdStart: String,
    val title: String,
    val subtitle: String,
    val sheetTitle: String,
    val addAction: String,
    val addedLabel: String,
    val skipHint: String,
    val similarToPrefix: String,
    val emptyMessage: String,
)

fun getRecommendationsStrings(language: AppLanguage): RecommendationsStrings = when (language) {
    AppLanguage.RU -> RecommendationsStrings(
        overline = "ПОДОБРАНО ДЛЯ ТЕБЯ",
        overlineLowConfidence = "ТОЧНЕЕ ПО МЕРЕ ОЦЕНОК",
        overlineColdStart = "СЕЙЧАС В ТРЕНДЕ",
        title = "Что посмотреть",
        subtitle = "Подборка по твоей коллекции",
        sheetTitle = "Рекомендации",
        addAction = "В коллекцию",
        addedLabel = "Добавлено",
        skipHint = "Свайп вниз — следующий тайтл",
        similarToPrefix = "Похоже на",
        emptyMessage = "Не удалось собрать рекомендации.\nПроверь подключение к сети.",
    )
    AppLanguage.EN -> RecommendationsStrings(
        overline = "RECOMMENDED FOR YOU",
        overlineLowConfidence = "IMPROVES AS YOU RATE",
        overlineColdStart = "TRENDING NOW",
        title = "Watch Recommended",
        subtitle = "Picks based on your collection",
        sheetTitle = "Recommendations",
        addAction = "Add to collection",
        addedLabel = "Added",
        skipHint = "Swipe down for next title",
        similarToPrefix = "Similar to",
        emptyMessage = "Couldn't build recommendations.\nCheck your connection.",
    )
}

private const val LOW_CONFIDENCE_THRESHOLD = 0.3f

fun overlineFor(state: RecommendationsUiState.Ready, strings: RecommendationsStrings): String = when {
    state.isColdStart -> strings.overlineColdStart
    state.confidence < LOW_CONFIDENCE_THRESHOLD -> strings.overlineLowConfidence
    else -> strings.overline
}

/**
 * Discovery-карточка на главном экране: коллаж обложек топ-кандидатов + CTA.
 * Обложки берутся из уже посчитанного состояния — БЕЗ отдельного запроса.
 */
@Composable
fun DiscoveryCard(
    state: RecommendationsUiState.Ready,
    strings: RecommendationsStrings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isAppInDarkTheme()
    val context = LocalContext.current
    val shape = RoundedCornerShape(28.dp)
    val covers = state.items.take(3)
    val borderStroke = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.8f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(shape)
            .border(0.5.dp, borderStroke, shape)
            .background(if (isDark) Color(0xFF161616) else Color(0xFF262626))
            .fluidClickable { onClick() }
    ) {
        // Фоновый коллаж обложек, прижат вправо
        Row(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(0.42f))
            covers.forEach { item ->
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.coverUrl)
                        .size(Size(200, 300))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .weight(0.58f / covers.size.coerceAtLeast(1))
                        .fillMaxSize()
                )
            }
        }
        // Фейд слева направо: текст читается, обложки мягко проявляются
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to (if (isDark) Color(0xFF161616) else Color(0xFF262626)),
                        0.45f to (if (isDark) Color(0xFF161616) else Color(0xFF262626)).copy(alpha = 0.96f),
                        1f to Color.Black.copy(alpha = 0.25f),
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = overlineFor(state, strings),
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                    color = BrandOrange,
                )
                Text(
                    text = strings.title,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    color = Color.White,
                )
                Text(
                    text = strings.subtitle,
                    fontFamily = SnProFamily,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(Color.White.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
