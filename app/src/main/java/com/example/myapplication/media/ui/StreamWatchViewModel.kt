package com.example.myapplication.media.ui

import com.example.myapplication.utils.runSuspendCatching
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.models.Anime
import com.example.myapplication.media.MediaGateway
import com.example.myapplication.media.player.VetroVideoCache
import com.example.myapplication.media.source.VetroHoster
import com.example.myapplication.media.source.VetroVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface StreamWatchUiState {
    data object Loading : StreamWatchUiState
    data class Ready(val hosters: List<VetroHoster>) : StreamWatchUiState
    data class Error(val message: String) : StreamWatchUiState
}

class StreamWatchViewModel(
    private val anime: Anime,
    private val episodeNumber: Int,
    private val mediaGateway: MediaGateway,
) : ViewModel() {

    private val _state = MutableStateFlow<StreamWatchUiState>(StreamWatchUiState.Loading)
    val state: StateFlow<StreamWatchUiState> = _state.asStateFlow()

    private var cachedBest: VetroVideo? = null

    fun load() {
        viewModelScope.launch {
            _state.value = StreamWatchUiState.Loading
            runSuspendCatching {
                mediaGateway.resolveHosters(anime, episodeNumber)
            }.onSuccess { hosters ->
                if (hosters.isEmpty() || hosters.all { it.videos.isNullOrEmpty() }) {
                    _state.value = StreamWatchUiState.Error("No streams found")
                } else {
                    cachedBest = mediaGateway.resolveBestVideo(hosters)
                    cachedBest?.let { best ->
                        VetroVideoCache.put(
                            VetroVideoCache.key(anime.id, episodeNumber, "best", best.label),
                            best,
                        )
                    }
                    _state.value = StreamWatchUiState.Ready(hosters)
                }
            }.onFailure {
                _state.value = StreamWatchUiState.Error(it.message ?: "Resolve failed")
            }
        }
    }

    fun best(): VetroVideo? = cachedBest
}
