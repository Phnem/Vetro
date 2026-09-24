package com.example.myapplication.media.ui

import com.example.myapplication.ui.shared.theme.IosScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.data.models.Anime
import com.example.myapplication.media.source.VetroHoster
import com.example.myapplication.media.source.VetroVideo
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamWatchSheet(
    anime: Anime,
    episodeNumber: Int,
    ru: Boolean,
    onDismiss: () -> Unit,
    viewModel: StreamWatchViewModel = koinViewModel(key = "watch_${anime.id}_$episodeNumber") {
        parametersOf(anime, episodeNumber)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { viewModel.load() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = if (ru) "Смотреть · серия $episodeNumber" else "Watch · episode $episodeNumber",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))

            when (val s = state) {
                StreamWatchUiState.Loading -> {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is StreamWatchUiState.Error -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.load() }) {
                        Text(if (ru) "Повторить" else "Retry")
                    }
                }
                is StreamWatchUiState.Ready -> {
                    LazyColumn(flingBehavior = IosScroll.flingBehavior(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(s.hosters, key = { it.name + it.url }) { hoster ->
                            HosterBlock(
                                hoster = hoster,
                                ru = ru,
                                onPick = { video ->
                                    context.startActivity(
                                        StreamPlayerActivity.intent(
                                            context = context,
                                            video = video,
                                            animeId = anime.id,
                                            animeTitle = anime.title,
                                            episode = episodeNumber,
                                        )
                                    )
                                    onDismiss()
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.best()?.let { video ->
                                context.startActivity(
                                    StreamPlayerActivity.intent(
                                        context, video, anime.id, anime.title, episodeNumber,
                                    )
                                )
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (ru) "Лучшее качество" else "Best quality")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HosterBlock(
    hoster: VetroHoster,
    ru: Boolean,
    onPick: (VetroVideo) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(hoster.name, fontWeight = FontWeight.SemiBold)
        hoster.videos.orEmpty().forEach { video ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(video) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(video.label)
                if (video.isPreferred) {
                    Text(
                        if (ru) "рекоменд." else "preferred",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
