package com.example.myapplication.ui.inspect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.myapplication.data.models.RatingScale
import com.example.myapplication.domain.search.apiRatingTo10
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.OverlayThemeTokens
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleShape
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.phnem.vetro.R

/**
 * Карточка найденного по кадру тайтла. Своя, а не [com.example.myapplication.ui.home.ApiSearchResultCard]:
 * на главном экране карточка — строка списка результатов поиска, здесь же это единственный ответ
 * движка, поэтому она подаётся крупно — кадр во всю ширину, чипы поверх него, название и
 * основное действие во всю ширину.
 *
 * Показываем ровно то, что отдаёт пайплайн (постер, рейтинг, тип/серии, источник, жанры) — макет
 * задаёт стиль, а не набор полей.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InspectResultCard(
    result: ApiSearchResult,
    isAdded: Boolean,
    isLoading: Boolean,
    addLabel: String,
    addedLabel: String,
    episodesUnit: String,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isAppInDarkTheme()
    val cardShape = SquircleShape(IosDesign.GroupRadius, smoothing = 0.6f)
    val accent = BrandOrange
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = if (isDark) OverlayThemeTokens.LabelMutedDark else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(IosDesign.groupBackground(isDark))
            .border(1.dp, IosDesign.separator(isDark).copy(alpha = 0.08f), cardShape)
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .clip(SquircleShape(18.dp, smoothing = 0.6f))
                .background(IosDesign.groupRowBackground(isDark)),
        ) {
            if (!result.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = result.posterUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            // Скрим снизу — чип с типом/сериями читается на любом кадре.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.65f),
                        ),
                    ),
            )

            // Та же конвертация, что и у карточек поиска, — не своя арифметика.
            val rating10 = apiRatingTo10(result.rating).takeIf { it > 0f }
            if (rating10 != null) {
                OverlayChip(
                    text = RatingScale.format(rating10),
                    icon = R.drawable.ph_star_fill,
                    container = Color.Black.copy(alpha = 0.55f),
                    content = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                )
            }
            OverlayChip(
                text = buildString {
                    append(if (result.categoryType == "MOVIE") result.type.ifBlank { "Movie" } else "${result.episodes} $episodesUnit")
                    append(" · ")
                    append(result.source)
                },
                container = Color.Black.copy(alpha = 0.55f),
                content = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = result.title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            ),
            color = onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        result.altTitle?.takeIf { it.isNotBlank() && it != result.title }?.let { alt ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = alt,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        val genres = result.genres.filter { it.isNotBlank() }.take(3)
        if (genres.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                modifier = Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                genres.forEachIndexed { index, genre ->
                    // Первый жанр — акцентный, как ведущий тег в референсе; остальные нейтральные.
                    if (index == 0) {
                        OverlayChip(
                            text = genre,
                            container = accent.copy(alpha = if (isDark) 0.18f else 0.14f),
                            content = accent,
                        )
                    } else {
                        OverlayChip(
                            text = genre,
                            container = IosDesign.groupRowBackground(isDark),
                            content = onSurface.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        AddToCollectionButton(
            isAdded = isAdded,
            isLoading = isLoading,
            addLabel = addLabel,
            addedLabel = addedLabel,
            accent = accent,
            isDark = isDark,
            onClick = onAddClick,
        )
    }
}

@Composable
private fun OverlayChip(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    icon: Int? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
            ),
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
private fun AddToCollectionButton(
    isAdded: Boolean,
    isLoading: Boolean,
    addLabel: String,
    addedLabel: String,
    accent: Color,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val container = if (isAdded) IosDesign.groupRowBackground(isDark) else accent
    val content = if (isAdded) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f) else Color.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(shape)
            .background(container)
            .then(if (!isAdded && !isLoading) Modifier.fluidClickable { onClick() } else Modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            isLoading -> CircularProgressIndicator(
                color = content,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )

            else -> {
                Icon(
                    painter = painterResource(if (isAdded) R.drawable.ph_check else R.drawable.ph_plus),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isAdded) addedLabel else addLabel,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SnProFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = content,
                )
            }
        }
    }
}
