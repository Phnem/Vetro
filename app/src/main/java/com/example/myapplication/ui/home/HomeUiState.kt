package com.example.myapplication.ui.home

import androidx.compose.runtime.Immutable
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.data.models.SortOption
import com.example.myapplication.network.ApiSearchResult
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

@Immutable
data class ApiSearchUiModel(
    val result: ApiSearchResult,
    val isAdded: Boolean
)

/**
 * Immutable UI state для HomeScreen.
 * Список аниме реактивно берётся из animeListFlow (БД + фильтр/сортировка в памяти).
 * ImmutableList даёт Compose стабильность и убирает микрофризы при скролле.
 */
data class HomeUiState(
    val searchQuery: String = "",
    val sortOption: SortOption = SortOption.RATING,
    val sortAscending: Boolean = false,
    val filterTags: ImmutableList<String> = persistentListOf(),
    val filterCategory: String = "",
    val isGenreFilterVisible: Boolean = false,
    val isCheckingUpdates: Boolean = false,
    val updates: ImmutableList<AnimeUpdate> = persistentListOf(),
    val statsAnimeList: ImmutableList<Anime> = persistentListOf(),
    val statsFooterPhrase: String = "",
    val isListLoaded: Boolean = false,
    val apiSearchResults: ImmutableList<ApiSearchResult> = persistentListOf(),
    val apiSearchLoading: Boolean = false,
    val apiSearchError: String? = null,
    val addingFromApiId: String? = null,
    /**
     * Ключи результатов поиска, добавление которых уже подтверждено кнопкой, но ещё не доехало до
     * БД. Кнопка переключается на «✓» отсюда, не дожидаясь сохранения: раньше она читала только
     * состояние БД и на время скачивания постера откатывалась обратно на «+», из-за чего
     * пользователь дожимал её ещё несколько раз и получал по записи на нажатие.
     *
     * При ошибке фонового сохранения ключ убирается, и кнопка честно возвращается в «+».
     */
    val optimisticallyAddedKeys: PersistentSet<String> = persistentSetOf(),
    val libraryMediaTypeFilter: com.example.myapplication.data.models.MediaType? = null,
    val searchMediaTypeFilter: com.example.myapplication.data.models.MediaType = com.example.myapplication.data.models.MediaType.ANIME,
    /** Поиск в разделе «Книги»: аудиокниги у источников вместо каталогов тайтлов. */
    val searchBooks: Boolean = false,
)
