package com.example.myapplication.ui.details

import com.example.myapplication.domain.enrichment.title.TitleEnrichmentRepository
import com.example.myapplication.domain.enrichment.title.TitleEnrichment
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
import com.example.myapplication.domain.seasons.ongoingSeason
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
    private val titleEnrichment: TitleEnrichmentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DetailsUiState>(DetailsUiState.Idle)
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    // Инициализация синхронно: узел sharedBounds есть в дереве на 0-м кадре — Exit transition работает.
    private val _currentAnime = MutableStateFlow<Anime?>(repository.getAnimeById(animeId))
    val currentAnime: StateFlow<Anime?> = _currentAnime.asStateFlow()

    /**
     * Обогащение карточки (логотип, трейлер, рейтинги критиков, следующая серия) — отдельно от базовой
     * карточки: грузится после неё и не мешает ей; ошибка или отсутствие ключей — просто null.
     */
    private val _enrichment = MutableStateFlow<TitleEnrichment?>(null)
    val enrichment: StateFlow<TitleEnrichment?> = _enrichment.asStateFlow()

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
            loadEnrichment(refresh = false)
            // Источник не ответил (AniList сразу после старта отвечает 429, пока идёт проверка
            // серий) — перечитываем позже, иначе у выходящего тайтла не было бы ни отсчёта, ни трейлера.
            for (wait in ENRICHMENT_RETRY_DELAYS_MS) {
                if (_enrichment.value?.incomplete != true) break
                kotlinx.coroutines.delay(wait)
                loadEnrichment(refresh = false)
            }
        }
    }

    /** Отсчёт одной и той же серии перечитывается один раз: без этого сломанное расписание крутило бы запросы. */
    private var refreshedReleaseAt: java.time.Instant? = null

    /** Время вышло: секция уже скрыта, расписание перечитывается мимо свежего кэша. */
    fun onReleaseElapsed() {
        val at = _enrichment.value?.nextRelease?.at ?: return
        if (refreshedReleaseAt == at) return
        refreshedReleaseAt = at
        _enrichment.value = _enrichment.value?.copy(nextRelease = null)
        viewModelScope.launch { loadEnrichment(refresh = true) }
    }

    private suspend fun loadEnrichment(refresh: Boolean) {
        val anime = _currentAnime.value ?: return
        // Расписание — у выходящего сезона франшизы, а не у сезона, на который указывает запись.
        seasonEpisodesStore.ensureLoaded()
        val airing = seasonEpisodesStore.entryFor(animeId).ongoingSeason()?.let {
            com.example.myapplication.domain.enrichment.title.AiringSeasonRef(it.anilistId, it.malId)
        }
        _enrichment.value = runCatching { titleEnrichment.load(anime, _currentLanguage.value, refresh, airing) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()?.takeUnless { it.isEmpty && it.russianDubs.isEmpty() && !it.incomplete }
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

/** Когда перечитать неполную карточку: AniList после 429 просит подождать около минуты. */
private val ENRICHMENT_RETRY_DELAYS_MS = listOf(20_000L, 60_000L)
