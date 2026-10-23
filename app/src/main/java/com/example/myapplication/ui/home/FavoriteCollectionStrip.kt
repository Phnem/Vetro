package com.example.myapplication.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.myapplication.data.models.Anime
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.PlacedCoordinates
import com.example.myapplication.ui.shared.trackPlacement
import java.util.Locale

/** Favorites keep the current collection's sort order and search/filter results. */
@Composable
internal fun FavoriteCollectionStrip(
    favorites: List<Anime>,
    language: AppLanguage,
    cardContent: @Composable (Anime, Int, Modifier) -> Unit,
) {
    if (favorites.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (language == AppLanguage.RU) "Избранное" else "Favorite",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag("favorite_collection_heading"),
            )
            Text(
                text = favorites.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // On phones the next cover peeks out; tablets show more cards at the same size.
            val cardWidth = (maxWidth * 0.55f).coerceIn(160.dp, 240.dp)
            LazyRow(
                modifier = Modifier.fillMaxWidth().testTag("favorite_collection_strip"),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(favorites, key = { _, anime -> anime.id }) { index, anime ->
                    cardContent(anime, index + 1, Modifier.width(cardWidth))
                }
            }
        }
    }
}

@Composable
internal fun FavoriteCollectionCard(
    state: AnimeCardState,
    releaseYear: Int?,
    position: Int,
    onClick: () -> Unit,
    onLongClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    posterModifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    val placement = remember { PlacedCoordinates() }
    Box(
        modifier = modifier
            .aspectRatio(0.7f)
            .trackPlacement(placement)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { placement.boundsInRoot()?.let(onLongClick) },
            )
            .testTag("favorite_card_${state.id}"),
    ) {
        AsyncImage(
            model = state.imagePath,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = posterModifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f),
                    0.35f to Color.Transparent,
                    0.6f to Color.Black.copy(alpha = 0.25f),
                    1f to Color.Black.copy(alpha = 0.92f),
                )
            )
        )
        Text(
            text = "${position.toString().padStart(2, '0')} / ${state.mediaTypeLabel}",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 1.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).padding(18.dp),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = state.title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                releaseYear?.takeIf { it > 0 }?.toString(),
                state.genres.firstOrNull(),
            ).joinToString(" · ")
            if (subtitle.isNotEmpty() || state.rating > 0f) {
                val rating = state.rating.takeIf { it > 0f }
                    ?.let { "★ ${String.format(Locale.ROOT, "%.1f", it)}" }
                Text(
                    text = listOfNotNull(subtitle.takeIf { it.isNotEmpty() }, rating).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
