package com.example.myapplication.localplayer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.localplayer.data.LocalSourceStore
import com.example.myapplication.localplayer.domain.LocalLibraryUseCase
import com.example.myapplication.localplayer.model.LocalSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LocalPlayerUiState {
    data object Loading : LocalPlayerUiState
    data object NeedSource : LocalPlayerUiState
    data object Scanning : LocalPlayerUiState
    data class Library(val source: LocalSource, val usedAi: Boolean) : LocalPlayerUiState
    data class Rejected(val reason: RejectReason) : LocalPlayerUiState
}

enum class RejectReason { TOO_GENERAL, NO_VIDEOS }

/**
 * Состояние экрана локального плеера для одного тайтла. Держит выбор папки (SAF), запуск скана
 * ([LocalLibraryUseCase]) и готовую библиотеку. Плеер сам по себе — отдельный экран, ему передаём
 * только список URI (см. [PlayerScreen]).
 */
class LocalPlayerViewModel(
    app: Application,
    private val animeId: String,
    private val animeTitle: String,
    private val animeMalId: Int?,
    private val animeAnilistId: Int?,
    private val store: LocalSourceStore,
    private val libraryUseCase: LocalLibraryUseCase,
    settingsDataStore: DataStore<Preferences>,
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow<LocalPlayerUiState>(LocalPlayerUiState.Loading)
    val uiState: StateFlow<LocalPlayerUiState> = _uiState.asStateFlow()

    val title: String get() = animeTitle
    val malId: Int? get() = animeMalId
    val anilistId: Int? get() = animeAnilistId

    /**
     * Автоскип опенинга. По умолчанию ВЫКЛ (ключ отсутствует) — пока не добавлен пункт настроек.
     * Плеер читает это: ВКЛ → молча перескакивает, ВЫКЛ → показывает кнопку «Пропустить».
     */
    val autoSkipEnabled: StateFlow<Boolean> =
        settingsDataStore.data
            .map { it[PlayerSettingsKeys.AUTO_SKIP] ?: false }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Включать следующую серию по концу текущей. По умолчанию ВКЛ — см. [AUTO_NEXT_KEY]. */
    val autoNextEnabled: StateFlow<Boolean> =
        settingsDataStore.data
            .map { it[PlayerSettingsKeys.AUTO_NEXT] ?: true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        viewModelScope.launch {
            store.ensureLoaded()
            store.flow.collect { map ->
                val existing = map[animeId]
                // Обновляем только если мы сейчас не находимся в процессе ручного сканирования
                if (_uiState.value !is LocalPlayerUiState.Scanning) {
                    _uiState.value = if (existing != null && existing.episodes.isNotEmpty()) {
                        LocalPlayerUiState.Library(existing, usedAi = false)
                    } else {
                        LocalPlayerUiState.NeedSource
                    }
                }
            }
        }
    }

    fun onFolderPicked(treeUri: Uri) {
        // Забираем стойкое разрешение на дерево, чтобы URI работали и после перезапуска.
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        _uiState.value = LocalPlayerUiState.Scanning
        viewModelScope.launch {
            _uiState.value = when (val r = libraryUseCase.build(animeId, animeTitle, treeUri)) {
                is LocalLibraryUseCase.Result.Success -> LocalPlayerUiState.Library(r.source, r.usedAi)
                LocalLibraryUseCase.Result.TooGeneralFolder -> LocalPlayerUiState.Rejected(RejectReason.TOO_GENERAL)
                LocalLibraryUseCase.Result.NoVideoFiles -> LocalPlayerUiState.Rejected(RejectReason.NO_VIDEOS)
            }
        }
    }

    fun requestPickAgain() {
        _uiState.value = LocalPlayerUiState.NeedSource
    }

    fun removeSource() {
        viewModelScope.launch {
            store.remove(animeId)
            _uiState.value = LocalPlayerUiState.NeedSource
        }
    }
}
