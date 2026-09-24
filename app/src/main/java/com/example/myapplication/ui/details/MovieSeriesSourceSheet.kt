package com.example.myapplication.ui.details

import com.example.myapplication.ui.shared.theme.IosScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.source.movieseries.SourceOption
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleShape

/**
 * Lets the viewer choose between the providers and translations that carry this movie or episode.
 *
 * Kept separate from the anime quality popover on purpose: that popover is a tightly anchored menu
 * over a single axis of choice, while movies and series routinely offer several sources each with
 * their own qualities. Anime keeps the menu it has always had.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieSeriesSourceSheet(
    sources: List<SourceOption>,
    ru: Boolean,
    onSelect: (VetroVideo) -> Unit,
    onDismiss: () -> Unit,
) {
    if (sources.isEmpty()) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState(), flingBehavior = IosScroll.flingBehavior())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (ru) "Смотреть" else "Watch",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (ru) {
                    "Доступно несколько источников. Первый — наиболее подходящий."
                } else {
                    "Several sources are available. The first is the best match."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = SnProFamily,
            )

            sources.forEach { source ->
                Surface(
                    shape = SquircleShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = source.name,
                            fontFamily = SnProFamily,
                            fontWeight = FontWeight.SemiBold,
                        )
                        source.qualities.forEach { quality ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(quality.video) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = quality.label,
                                    fontFamily = SnProFamily,
                                    modifier = Modifier.weight(1f),
                                )
                                if (quality.video.downloadAllowed) {
                                    Text(
                                        text = if (ru) "можно скачать" else "downloadable",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = SnProFamily,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
