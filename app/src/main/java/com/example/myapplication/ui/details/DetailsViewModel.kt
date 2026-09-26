package com.example.myapplication.ui.details

import com.example.myapplication.data.local.AppLanguagePrefs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.data.repository.ImageStorageRepository
import com.example.myapplication.network.AppLanguage
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.local.WebLinksStore
import com.example.myapplication.domain.enrichment.weblinks.ResolvedWebLink
import com.example.myapplication.domain.seasons.SeasonCatchUp
import com.example.myapplication.domain.seasons.SeasonEpisodesResolver
import com.example.myapplication.domain.seasons.SeasonInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailsViewModel(
    private val animeId: String,
    private val repository: AnimeRepository,
    private val settingsDataStore: DataStore<Preferences>,
    private val imageStorage: ImageStorageRepository,
    private val webLinksStore: WebLinksStore,
    private val seasonEpisodesStore: SeasonEpisodesStore,
    private val seasonEpisodesResolver: SeasonEpisodesResolver,
    private val seasonCatchUp: SeasonCatchUp,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DetailsUiState>(DetailsUiState.Idle)
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    // Инициализация синхронно: узел sharedBounds есть в дереве на 0-м кадре — Exit transition работает.
    private val _currentAnime = MutableStateFlow<Anime?>(repository.getAnimeById(animeId))
    val currentAnime: StateFlow<Anime?> = _currentAnime.asStateFlow()

    private val _currentLanguage = MutableStateFlow(AppLanguage.EN)
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    /** Найденные ссылки этого тайтла для текущего языка (реактивно из [WebLinksStore]). */
    val webLinks: StateFlow<List<ResolvedWebLink>> =
        combine(webLinksStore.flow, _currentLanguage) { map, lang ->
            val e = map[animeId]
            if (lang == AppLanguage.RU) e?.ruLinks.orEmpty() else e?.enLinks.orEmpty()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Серии по сезонам (фоновый резолв, см. SeasonEpisodesResolver) — реактивно из стора. */
    val seasons: StateFlow<List<SeasonInfo>> =
        seasonEpisodesStore.flow
            .map { it[animeId]?.seasons.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { webLinksStore.ensureLoaded() }
        // Открытые детали — вне очереди: если сезонов нет или протухли, резолвим сразу
        // (внутри IO + isFresh-гейт), секция появится реактивно.
        viewModelScope.launch {
            seasonEpisodesStore.ensureLoaded()
            runCatching { seasonEpisodesResolver.ensureResolved(animeId) }
            // Пуш сказал «5-я серия 3-го сезона», а в раскладе два сезона — значит он отстал, и
            // пользователю нечего доказывать это кнопкой «Найти ещё». Проверка дешёвая: сверка
            // с уже посчитанным прогрессом выходящего сезона, сеть трогается только при
            // доказанном расхождении.
            runCatching { seasonCatchUp.catchUp(animeId) }
        }
        viewModelScope.launch {
            val prefs = settingsDataStore.data.first()
            _currentLanguage.value = AppLanguagePrefs.from(prefs)
            _currentAnime.value?.let { loadDetails(it, _currentLanguage.value) }
        }
    }

    fun getImgPath(name: String?): String? {
        if (name == null) return null
        return imageStorage.getImageFilePath(name)
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            repository.toggleFavorite(animeId)?.let { _currentAnime.value = it }
        }
    }

    private fun loadDetails(anime: Anime, language: AppLanguage) {
        viewModelScope.launch {
            if (!anime.canLookupDetails(language)) {
                _uiState.value = DetailsUiState.MissingEnglishTitle
                return@launch
            }

            _uiState.value = DetailsUiState.Loading
            val startTime = System.currentTimeMillis()

            val request = anime.toDetailsLookupRequest(language)

            repository.fetchDetails(request)
                .fold(
                    onSuccess = { details ->
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed < 500) delay(500 - elapsed)
                        _uiState.value = if (details != null) {
                            DetailsUiState.Success(details)
                        } else {
                            DetailsUiState.Error
                        }
                    },
                    onFailure = {
                        _uiState.value = DetailsUiState.Error
                    }
                )
        }
    }
}
