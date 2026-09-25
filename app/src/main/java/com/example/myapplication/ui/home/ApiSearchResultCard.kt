package com.example.myapplication.ui.home

import com.example.myapplication.ui.shared.theme.BrandLightGray
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.example.myapplication.data.models.RatingScale
import com.example.myapplication.domain.search.apiRatingTo10
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.icons.HeroCheck
import com.example.myapplication.ui.shared.theme.LightBorder
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.getRatingColor
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class ApiSearchResultCardState(
    val title: String,
    /** 10-балльная шкала (одна десятая). */
    val rating: Float?,
    val genres: PersistentList<String>,
    val episodesText: String,
    val posterUrl: String?,
    val isAdded: Boolean,
    val isLoading: Boolean,
    val mediaTypeLabel: String
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiSearchResultCard(
    result: ApiSearchResult,
    isAdded: Boolean,
    isLoading: Boolean,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Localized genre labels; if null, raw API strings are used. */
    displayGenres: PersistentList<String>? = null,
    addLabel: String = "Add",
    addedLabel: String = "Added",
    mediaTypeLabel: String = "",
    /** На экранах с локальной тёмной темой (Visual Search) глобальный [isAppInDarkTheme] может быть false — форсируем тёмный вид карточки. */
    forceDarkCardStyle: Boolean = false
) {
    val state = remember(result, isAdded, isLoading, displayGenres) {
        val r10 = apiRatingTo10(result.rating)
        ApiSearchResultCardState(
            title = result.title,
            rating = r10.takeIf { it > 0f },
            genres = displayGenres ?: persistentListOf(*(result.genres.take(3).toTypedArray())),
            episodesText = buildString {
                append(
                    if (result.categoryType == "MOVIE") {
                        "1 film"
                    } else {
                        searchResultCountText(result.episodes, "eps")
                    },
                )
                append(" · ")
                append(result.source)
            },
            posterUrl = result.posterUrl,
            isAdded = isAdded,
            isLoading = isLoading,
            mediaTypeLabel = mediaTypeLabel
        )
    }

    val isDark = forceDarkCardStyle || isAppInDarkTheme()
    val cardBg = if (isDark) Color(0xFF1C1C1C) else MaterialTheme.colorScheme.surface
    val borderStroke = if (isDark) Color.White.copy(alpha = 0.15f) else LightBorder
    val cardShadowColor = if (isDark) Color.Black.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.08f)
    val subtitleColor = if (isDark) BrandLightGray else Color(0xFF8E8E93)
    val chipBg = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.08f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .shadow(
                elevation = if (isDark) 8.dp else 4.dp,
                shape = RoundedCornerShape(24.dp),
                spotColor = cardShadowColor
            )
            .clip(RoundedCornerShape(24.dp))
            .background(cardBg)
            .border(1.dp, borderStroke, RoundedCornerShape(24.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isDark) Color(0xFF2C2C2C) else Color(0xFFE8E8E8)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.title.take(1).uppercase(),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = if (isDark) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.15f),
                    fontFamily = SnProFamily
                )
                state.posterUrl?.let { url ->
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(url)
                            .size(Size(280, 400))
                            .crossfade(true)
                            .build(),
                        contentDescription = state.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (state.mediaTypeLabel.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = state.mediaTypeLabel,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SnProFamily
                        )
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            fontFamily = SnProFamily
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    state.rating?.let { rating10 ->
                        if (rating10 > 0f) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Text(
                                    text = "★ ${RatingScale.format(rating10)}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = getRatingColor(rating10),
                                        fontFamily = SnProFamily
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                if (state.genres.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        state.genres.forEach { genre ->
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(chipBg)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = genre,
                                    fontSize = 12.sp,
                                    color = subtitleColor,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = SnProFamily
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.episodesText,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.sp,
                            fontFamily = SnProFamily
                        ),
                        color = subtitleColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        modifier = Modifier
                            .fluidClickable(
                                scaleDown = 0.92f,
                                onClick = { if (!state.isAdded && !state.isLoading) onAddClick() }
                            )
                            .clip(CircleShape)
                            .background(
                                if (state.isAdded) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.primary
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (state.isAdded) HeroCheck else Icons.Default.Add,
                                contentDescription = if (state.isAdded) addedLabel else addLabel,
                                tint = if (state.isAdded) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (state.isLoading) "..." else if (state.isAdded) addedLabel else addLabel,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = SnProFamily,
                                    fontSize = 13.sp
                                ),
                                color = if (state.isAdded) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
