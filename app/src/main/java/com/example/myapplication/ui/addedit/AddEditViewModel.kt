package com.example.myapplication.ui.addedit

import com.example.myapplication.data.local.AppLanguagePrefs
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.repository.ImageStorageRepository
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.domain.addedit.GetAnimeForEditUseCase
import com.example.myapplication.domain.addedit.SaveAnimeParams
import com.example.myapplication.domain.addedit.SaveAnimeUseCase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


class AddEditViewModel(
    private val getAnimeUseCase: GetAnimeForEditUseCase,
    private val saveAnimeUseCase: SaveAnimeUseCase,
    private val imageStorage: ImageStorageRepository,
    settingsDataStore: DataStore<Preferences>
) : ViewModel() {

    val uiLanguage: StateFlow<AppLanguage> = settingsDataStore.data
        .map { prefs ->
            AppLanguagePrefs.from(prefs)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppLanguage.EN)

    private val _uiState = MutableStateFlow(AddEditUiState())
    val uiState: StateFlow<AddEditUiState> = _uiState.asStateFlow()

    private val _effect = Channel<AddEditEffect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    private var loadedSession: String? = null
    private var loadJob: Job? = null

    /**
     * [session] один на открытие экрана и переживает пересоздание Activity: повторный вызов с ним же
     * не затирает набранное. ViewModel общая на всю Activity, поэтому новое открытие начинает с пустой
     * формы, иначе обложка прошлого тайтла сохранилась бы в следующий.
     */
    fun loadAnime(animeId: String?, session: String) {
        if (session == loadedSession) return
        loadedSession = session
        loadJob?.cancel()
        _uiState.value = AddEditUiState(isLoading = animeId != null)
        if (animeId == null) return

        loadJob = viewModelScope.launch {
            getAnimeUseCase(animeId)
                .onSuccess { anime ->
                    anime?.let {
                        _uiState.update {
                            it.copy(
                                animeId = anime.id,
                                title = anime.title,
                                titleEn = anime.titleEn.orEmpty(),
                                showTitleEn = !anime.titleEn.isNullOrBlank(),
                                titleRu = anime.titleRu,
                                anilistId = anime.anilistId,
                                malId = anime.malId,
                                shikimoriId = anime.shikimoriId,
                                anilistNotFoundAt = anime.anilistNotFoundAt,
                                malNotFoundAt = anime.malNotFoundAt,
                                shikimoriNotFoundAt = anime.shikimoriNotFoundAt,
                                tmdbId = anime.tmdbId,
                                kinopoiskId = anime.kinopoiskId,
                                tmdbNotFoundAt = anime.tmdbNotFoundAt,
                                kinopoiskNotFoundAt = anime.kinopoiskNotFoundAt,
                                episodes = anime.episodes.toString(),
                                rating = anime.rating,
                                selectedTags = anime.tags,
                                categoryType = anime.categoryType,
                                mediaType = anime.mediaType,
                                comment = anime.comment,
                                commentMode = if (anime.comment.isBlank()) CommentMode.AddButton else CommentMode.Saved,
                                currentImageFileName = anime.imageFileName,
                                imageFilePath = anime.imageFileName?.let { fn -> imageStorage.getImageFilePath(fn) },
                                orderIndex = anime.orderIndex,
                                dateAdded = anime.dateAdded,
                                isFavorite = anime.isFavorite,
                                isLoading = false
                            )
                        }
                    } ?: run {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun onEvent(event: AddEditEvent) {
        when (event) {
            is AddEditEvent.OnTitleChanged -> _uiState.update { it.copy(title = event.title) }
            is AddEditEvent.OnTitleEnChanged -> _uiState.update { it.copy(titleEn = event.titleEn) }
            is AddEditEvent.OnToggleTitleEn -> _uiState.update { it.copy(showTitleEn = !it.showTitleEn) }
            is AddEditEvent.OnEpisodesChanged -> {
                if (event.episodes.all { it.isDigit() }) {
                    _uiState.update { it.copy(episodes = event.episodes) }
                }
            }
            is AddEditEvent.OnRatingChanged -> _uiState.update { it.copy(rating = event.rating) }
            // Пикер, закрытый без выбора, присылает null: уже выбранную обложку не сбрасываем.
            is AddEditEvent.OnImageUriChanged -> event.uri?.let { uri -> _uiState.update { it.copy(imageUri = uri) } }
            is AddEditEvent.OnTagsChanged -> _uiState.update {
                it.copy(selectedTags = event.tags, categoryType = event.categoryType)
            }
            is AddEditEvent.OnCommentModeChanged -> _uiState.update {
                it.copy(
                    commentMode = event.mode,
                    commentDraft = if (event.mode == CommentMode.Editing) it.commentDraft ?: it.comment else null,
                )
            }
            is AddEditEvent.OnCommentDraftChanged -> _uiState.update { it.copy(commentDraft = event.text) }
            is AddEditEvent.OnSaveComment -> handleSaveComment(event.comment)
            is AddEditEvent.OnSave -> saveAnime()
        }
    }

    /** Сворачивает поле. В базу комментарий уходит вместе с формой, чтобы «Назад» его отменял. */
    private fun handleSaveComment(newComment: String) {
        _uiState.update {
            it.copy(
                comment = newComment,
                commentDraft = null,
                commentMode = if (newComment.isBlank()) CommentMode.AddButton else CommentMode.Saved
            )
        }
    }

    private fun saveAnime() {
        val state = _uiState.value
        if (!state.isValid) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val params = SaveAnimeParams(
                animeId = state.animeId,
                title = state.title,
                titleEn = state.titleEn.trim().takeIf { it.isNotBlank() },
                titleRu = state.titleRu,
                episodes = state.episodes.toIntOrNull() ?: 0,
                rating = state.rating,
                imageUri = state.imageUri?.toString(),
                currentImageFileName = state.currentImageFileName,
                orderIndex = state.orderIndex,
                dateAdded = state.dateAdded,
                isFavorite = state.isFavorite,
                selectedTags = state.selectedTags,
                categoryType = state.categoryType,
                mediaType = state.mediaType,
                comment = state.commentDraft ?: state.comment,
                anilistId = state.anilistId,
                malId = state.malId,
                shikimoriId = state.shikimoriId,
                anilistNotFoundAt = state.anilistNotFoundAt,
                malNotFoundAt = state.malNotFoundAt,
                shikimoriNotFoundAt = state.shikimoriNotFoundAt,
                tmdbId = state.tmdbId,
                kinopoiskId = state.kinopoiskId,
                tmdbNotFoundAt = state.tmdbNotFoundAt,
                kinopoiskNotFoundAt = state.kinopoiskNotFoundAt,
            )
            saveAnimeUseCase(params)
                .onSuccess {
                    // Новая запись вручную — очки активности для напоминания о поддержке (правка — нет).
                    if (params.animeId == null) {
                        com.example.myapplication.support.SupportActivity.report(com.example.myapplication.support.SupportActivityEvent.TitleAdded("manual:${params.title.trim().lowercase()}"))
                    }
                    _uiState.update { it.copy(isLoading = false) }
                    _effect.send(AddEditEffect.NavigateBack)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                    _effect.send(AddEditEffect.ShowError(error.message ?: "Unknown error"))
                }
        }
    }
}
