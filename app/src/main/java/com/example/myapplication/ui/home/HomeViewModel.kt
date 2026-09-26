package com.example.myapplication.ui.home

import com.example.myapplication.domain.BackgroundSchedule
import kotlinx.coroutines.flow.collectLatest
import com.example.myapplication.data.local.AppLanguagePrefs
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.data.repository.ImageStorageRepository
import com.example.myapplication.domain.normalizeForSearch
import com.example.myapplication.domain.search.AddFromApiUseCase
import com.example.myapplication.domain.stats.ResolveStatsFooterPhraseUseCase
import com.example.myapplication.updates.EpisodeUpdateCheckCoordinator
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.data.models.SortOption
import com.example.myapplication.notifications.AnimeNotifier
import com.example.myapplication.notifications.animeUpdateNotificationId
import com.example.myapplication.SyncReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.work.*
import com.example.myapplication.worker.AnimeUpdateWorker

private val KEY_CONTENT_TYPE = stringPreferencesKey("contentType")

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: AnimeRepository,
    private val localDataSource: AnimeLocalDataSource,
    private val notifier: AnimeNotifier,
    private val imageStorage: ImageStorageRepository,
    private val settingsDataStore: DataStore<Preferences>,
    private val addFromApiUseCase: AddFromApiUseCase,
    private val statsFooterPhraseUseCase: ResolveStatsFooterPhraseUseCase,
    private val episodeUpdateCheckCoordinator: EpisodeUpdateCheckCoordinator,
    private val webLinksStore: com.example.myapplication.data.local.WebLinksStore,
    private val seasonEpisodesStore: com.example.myapplication.data.local.SeasonEpisodesStore,
    private val episodePlaybackStore: com.example.myapplication.media.progress.EpisodePlaybackStore,
    private val mangaBindingStore: com.example.myapplication.manga.data.MangaBindingStore,
    private val mangaChapterCacheStore: com.example.myapplication.manga.data.MangaChapterCacheStore,
    private val mangaReadingStore: com.example.myapplication.manga.data.MangaReadingStore,
) : ViewModel() {

    /** Найденные прямые ссылки по одобренным сайтам (animeId → запись). Реактивно для карточек. */
    val webLinks: StateFlow<Map<String, com.example.myapplication.domain.enrichment.weblinks.WebLinksEntry>> =
        webLinksStore.flow
    init { viewModelScope.launch { webLinksStore.ensureLoaded() } }

    /**
     * Сколько серий тайтла пользователь досмотрел, сквозной нумерацией по франшизе
     * (animeId → число). Считаем от самой дальней серии, до которой он дошёл: серии всех
     * предыдущих сезонов плюс номер текущей. Разбивка по сезонам берётся из
     * [com.example.myapplication.data.local.SeasonEpisodesStore]; для первого сезона она не нужна,
     * поэтому прогресс появляется даже без неё.
     */
    val watchedEpisodes: StateFlow<Map<String, Int>> =
        localDataSource.observeAllAnime()
            .map { list -> list.map { anime -> anime.id } }
            .distinctUntilChanged()
            .flatMapLatest { ids -> episodePlaybackStore.furthestEpisodeFlow(ids) }
            .combine(seasonEpisodesStore.flow) { furthest, seasons ->
                furthest.mapValues { (animeId, mark) ->
                    val chain = seasons[animeId]?.seasons.orEmpty()
                    val before = chain
                        .filter { it.seasonNumber < mark.season }
                        .sumOf { it.episodes }
                    before + mark.episode
                }.filterValues { it > 0 }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Разложение тайтлов по сезонам (animeId → расклад) для знаменателя прогресса на карточке.
     *
     * Тот же самый расклад, по которому считается [watchedEpisodes]: числитель и знаменатель
     * обязаны жить в одной шкале, иначе на карточке снова выйдет «просмотрено 15 / 12».
     * Сумму считает `franchiseEpisodeTotal`.
     */
    val seasonLayouts: StateFlow<Map<String, com.example.myapplication.domain.seasons.SeasonEpisodesEntry>> =
        seasonEpisodesStore.flow

    init { viewModelScope.launch { seasonEpisodesStore.ensureLoaded() } }

    /**
     * Прогресс чтения манги (animeId → сводка) — то же место на карточке, что прогресс серий у
     * аниме, только считается по главам.
     *
     * Считаем только по тайтлам с подтверждённой привязкой к источнику: без неё нет и оглавления,
     * а значит нет знаменателя. Оглавление берётся из файлового кэша — своей проверки новых глав
     * по сети у манги нет (см. `.scratch/vetro-todo/issues/11-manga-chapter-refresh.md`), поэтому
     * список обновляется в момент, когда пользователь открывает вкладку «Главы».
     */
    val mangaReading: StateFlow<Map<String, com.example.myapplication.manga.domain.MangaReadingSummary>> =
        mangaBindingStore.flow
            .flatMapLatest { bindings ->
                if (bindings.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        mangaReadingStore.progressFlow(bindings.keys.toList()),
                        mangaChapterCacheStore.flow,
                    ) { progressByTitle, chapterCache ->
                        bindings.mapValues { (animeId, binding) ->
                            val cached = chapterCache[
                                mangaChapterCacheStore.entryKey(binding.sourceId, binding.mangaKey)
                            ]
                            com.example.myapplication.manga.domain.summarizeMangaReading(
                                chapters = com.example.myapplication.manga.domain.chaptersForLanguage(
                                    chapters = cached?.chapters.orEmpty(),
                                    preferredLanguage = binding.preferredLanguage,
                                ),
                                progress = progressByTitle[animeId].orEmpty(),
                            )
                        }.filterValues { it.hasProgress }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            mangaBindingStore.ensureLoaded()
            mangaChapterCacheStore.ensureLoaded()
        }
    }

    /** Выходящие сейчас сезоны (animeId → прогресс) — карточки «в процессе». */
    val airingProgress: StateFlow<Map<String, com.example.myapplication.data.models.AiringProgress>> =
        localDataSource.observeAiringProgress()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var apiSearchJob: Job? = null
    private var pullToRefreshJob: Job? = null

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val settingsContentType: StateFlow<AppContentType> = settingsDataStore.data
        .map { prefs ->
            runCatching { AppContentType.valueOf(prefs[KEY_CONTENT_TYPE] ?: "ANIME") }
                .getOrElse { AppContentType.ANIME }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppContentType.ANIME)

    /** Language from DataStore — use this in UI instead of a separate SettingsViewModel (avoids duplicate VM scope). */
    val uiLanguage: StateFlow<AppLanguage> = settingsDataStore.data
        .map { prefs ->
            AppLanguagePrefs.from(prefs)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppLanguage.EN)

    val syncReport = MutableStateFlow(com.example.myapplication.SyncReport())

    val animeListFlow: StateFlow<kotlinx.collections.immutable.ImmutableList<Anime>> = repository.observeAnimeList(
        searchQuery = _uiState.map { it.searchQuery },
        sortOption = _uiState.map { it.sortOption },
        sortAscending = _uiState.map { it.sortAscending },
        filterTags = _uiState.map { it.filterTags },
        mediaTypeFilter = _uiState.map { it.libraryMediaTypeFilter }
    ).map { it.toImmutableList() }
     .onEach { if (!_uiState.value.isListLoaded) _uiState.update { s -> s.copy(isListLoaded = true) } }
     .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = persistentListOf()
    )

    // Пути к обложкам резолвятся здесь, на IO, как только пришёл список: карточка берёт путь из
    // композиции, и без прогрева первый показ каждой обложки ходил бы по файловой системе на
    // главном потоке (stat'ы, а для вложений — listFiles()). Репозиторий пути запоминает.
    init {
        viewModelScope.launch(Dispatchers.IO) {
            animeListFlow.collectLatest { list ->
                list.forEach { anime -> anime.imageFileName?.let(imageStorage::getImageFilePath) }
            }
        }
    }

    val apiSearchWithStatus: StateFlow<kotlinx.collections.immutable.ImmutableList<ApiSearchUiModel>> = combine(
        _uiState.map { it.apiSearchResults }.distinctUntilChanged(),
        _uiState.map { it.optimisticallyAddedKeys }.distinctUntilChanged(),
        animeListFlow
    ) { apiResults, optimisticKeys, localList ->
        apiResults.map { result ->
            ApiSearchUiModel(
                result = result,
                // Оптимистичный ключ ИЛИ факт в БД: кнопка обязана переключиться сразу по нажатию,
                // не дожидаясь скачивания постера, иначе она откатывается и пользователь дожимает.
                isAdded = searchResultKey(result) in optimisticKeys || isAddedInMemory(result, localList)
            )
        }.toImmutableList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentListOf())

    private var hasCheckedForUpdatesThisSession = false

    init {
        viewModelScope.launch {
            localDataSource.observeUpdates().collect { list ->
                _uiState.update { it.copy(updates = list.toImmutableList()) }
            }
        }
        viewModelScope.launch {
            checkForUpdates()
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { statsFooterPhraseUseCase.warmCatalog() }
        }
    }

    fun refreshList() {
        if (pullToRefreshJob?.isActive == true) return
        pullToRefreshJob = viewModelScope.launch {
            _isRefreshing.value = true
            try {
                checkForUpdates(force = true)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun scheduleBackgroundWork(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val updateRequest = PeriodicWorkRequestBuilder<AnimeUpdateWorker>(
            BackgroundSchedule.EPISODE_CHECK_HOURS, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "AnimeUpdateWork",
            ExistingPeriodicWorkPolicy.KEEP,
            updateRequest
        )
        // Серии по сезонам: разовый прогон прямо сейчас — периодик выше может
        // сработать только через часы, а данные нужны в Details сразу.
        com.example.myapplication.worker.SeasonEpisodesWorker.enqueueOnce(context)
    }

    fun updateSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                apiSearchError = null,
                apiSearchResults = if (query.isBlank()) persistentListOf() else it.apiSearchResults
            )
        }
        apiSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(apiSearchLoading = false, apiSearchResults = persistentListOf()) }
            return
        }
        apiSearchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            if (_uiState.value.searchQuery.trim() != trimmed) return@launch
            _uiState.update { it.copy(apiSearchLoading = true, apiSearchError = null) }
            val mediaType = _uiState.value.searchMediaTypeFilter
            val contentType = when (mediaType) {
                com.example.myapplication.data.models.MediaType.ANIME -> AppContentType.ANIME
                com.example.myapplication.data.models.MediaType.MANGA -> AppContentType.MANGA
                com.example.myapplication.data.models.MediaType.MOVIE -> AppContentType.MOVIE
                com.example.myapplication.data.models.MediaType.SERIES -> AppContentType.SERIES
            }
            val language = uiLanguage.value
            repository.searchApi(trimmed, contentType, language)
                .fold(
                    onSuccess = { results ->
                        if (_uiState.value.searchQuery.trim() == trimmed) {
                            _uiState.update {
                                it.copy(
                                    apiSearchResults = results.toImmutableList(),
                                    apiSearchLoading = false,
                                    apiSearchError = null
                                )
                            }
                        }
                    },
                    onFailure = { e ->
                        if (_uiState.value.searchQuery.trim() == trimmed) {
                            _uiState.update {
                                it.copy(
                                    apiSearchLoading = false,
                                    apiSearchError = e.message ?: "Search failed"
                                )
                            }
                        }
                    }
                )
        }
    }

    /** Ключ результата поиска — тот же и для оптимистичного состояния, и для индикатора загрузки. */
    private fun searchResultKey(result: ApiSearchResult): String =
        "${result.source}_${result.externalId ?: result.title}"

    private fun isAddedInMemory(
        result: ApiSearchResult,
        localList: List<Anime>
    ): Boolean {
        val q = result.title.normalizeForSearch()
        if (q.isEmpty()) return false
        // Раздел поиска штампуется в categoryType (см. ApiService.searchApi), запись хранит тот же
        // факт в mediaType — сравниваем их одним общим правилом, а не строками: у «Фильмов» и
        // «Сериалов» тип записи один (TV_SERIES), и посимвольное сравнение их не сводило.
        val resultType = com.example.myapplication.data.models.MediaType.fromCategoryType(result.categoryType)
        return localList.any { anime ->
            if (resultType != null && anime.mediaType != resultType) return@any false


            val keys = listOfNotNull(anime.title, anime.titleEn, anime.titleRu)
                .map { it.normalizeForSearch() }
                .filter { it.isNotEmpty() }
            keys.any { t -> t.contains(q) || q.contains(t) }
        }
    }

    fun addFromApi(result: ApiSearchResult) {
        val key = searchResultKey(result)

        // Второе нажатие по той же карточке игнорируется: без этого «добавляю» и «уже добавлено»
        // не спасают — два вызова успевают пройти проверку дубликата до того, как первый допишет
        // запись в БД.
        if (key in _uiState.value.optimisticallyAddedKeys) return

        // Кнопка переключается здесь, до какой-либо работы. Всё остальное — фоном.
        _uiState.update { it.copy(optimisticallyAddedKeys = it.optimisticallyAddedKeys.add(key)) }

        viewModelScope.launch {
            _uiState.update { it.copy(addingFromApiId = key) }
            addFromApiUseCase(result)
                .fold(
                    onSuccess = {
                        // И ADDED, и ALREADY_IN_COLLECTION — успех с точки зрения кнопки: тайтл в
                        // коллекции, галочка правдива. Откатывать её во втором случае значило бы
                        // предлагать пользователю добавить то, что уже добавлено.
                        _uiState.update { it.copy(addingFromApiId = null) }
                    },
                    onFailure = { e ->
                        e.printStackTrace()
                        // Молча оставить галочку нельзя — она соврала бы про сохранённый тайтл.
                        _uiState.update {
                            it.copy(
                                addingFromApiId = null,
                                optimisticallyAddedKeys = it.optimisticallyAddedKeys.remove(key),
                                apiSearchError = e.message ?: "Не удалось добавить тайтл",
                            )
                        }
                    }
                )
        }
    }

    fun applySort(option: SortOption, isAscending: Boolean) {
        _uiState.update { current ->
            current.copy(sortOption = option, sortAscending = isAscending)
        }
    }

    fun toggleGenreFilter() {
        _uiState.update { it.copy(isGenreFilterVisible = !it.isGenreFilterVisible) }
    }

    fun setGenreFilterVisible(visible: Boolean) {
        _uiState.update { it.copy(isGenreFilterVisible = visible) }
    }

    fun updateFilterTags(tags: List<String>, category: String) {
        _uiState.update {
            it.copy(filterTags = tags.toImmutableList(), filterCategory = category)
        }
    }

    fun setLibraryMediaTypeFilter(filter: com.example.myapplication.data.models.MediaType?) {
        _uiState.update { it.copy(libraryMediaTypeFilter = filter) }
    }

    fun setSearchMediaTypeFilter(filter: com.example.myapplication.data.models.MediaType) {
        _uiState.update { it.copy(searchMediaTypeFilter = filter) }
        updateSearchQuery(_uiState.value.searchQuery)
    }

    fun deleteAnime(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val anime = localDataSource.getAnimeById(id) ?: return@launch
                anime.imageFileName?.let { imageStorage.deleteImage(it) }
                localDataSource.deleteAnime(id)
            }.onFailure { it.printStackTrace() }
        }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val anime = localDataSource.getAnimeById(id) ?: return@launch
                localDataSource.updateAnime(anime.copy(isFavorite = !anime.isFavorite))
            }.onFailure { it.printStackTrace() }
        }
    }

    fun checkForUpdates(force: Boolean = false) {
        if (!force && hasCheckedForUpdatesThisSession) return
        if (_uiState.value.isCheckingUpdates) return
        hasCheckedForUpdatesThisSession = true
        _uiState.update { it.copy(isCheckingUpdates = true) }
        viewModelScope.launch {
            runCatching {
                val language = readLanguageFromSettings()
                episodeUpdateCheckCoordinator.detectAndStore(language, force = force)
                _uiState.update { it.copy(isCheckingUpdates = false) }
                // Приложение открыто → системные пуши не показываем: обновления живут
                // in-app стопкой сверху. Убираем из шторки всё, что мог оставить
                // фоновый воркер, чтобы уведомления не дублировали интерфейс.
                clearSystemUpdateNotifications()
            }.onFailure {
                it.printStackTrace()
                _uiState.update { it.copy(isCheckingUpdates = false) }
            }
        }
    }

    /**
     * Снять из системной шторки все пуши обновлений серий. Вызывается при выходе
     * приложения на передний план (ON_START) и после проверки обновлений: пока
     * приложение открыто, обновления показываются in-app стопкой, а не в шторке.
     */
    fun clearSystemUpdateNotifications() {
        // Вызывается на каждом выходе на передний план — чтение из БД не на главном потоке.
        viewModelScope.launch(Dispatchers.IO) {
            notifier.cancelAllUpdateNotifications(localDataSource.getUpdates().map { it.animeId })
        }
    }

    private suspend fun readLanguageFromSettings(): AppLanguage = AppLanguagePrefs.current(settingsDataStore)

    /**
     * Смахнули карточку «вышла новая серия». Серия уже проставлена автоматически
     * при проверке — здесь только убираем плашку (и её пуш из шторки).
     */
    fun dismissUpdate(update: AnimeUpdate, ctx: Context) {
        viewModelScope.launch {
            localDataSource.removeUpdate(update.animeId)
            cancelAnimeUpdateNotification(ctx, update.animeId)
        }
    }

    /** «Очистить всё» центра уведомлений: убрать все обновления и пометить их прочитанными. */
    fun markAllUpdatesRead(updates: List<AnimeUpdate>, ctx: Context) {
        if (updates.isEmpty()) return
        viewModelScope.launch {
            localDataSource.markUpdatesRead(updates)
            updates.forEach { cancelAnimeUpdateNotification(ctx, it.animeId) }
        }
    }

    private fun cancelAnimeUpdateNotification(ctx: Context, animeId: String) {
        // Только снимаем пуш этого тайтла из шторки. Сводку НЕ переотправляем —
        // при открытом приложении системные уведомления не показываем вовсе.
        val nm = ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(animeUpdateNotificationId(animeId))
    }

    fun getAnimeById(id: String): Anime? {
        return localDataSource.getAnimeById(id)
    }

    fun getImgPath(name: String?): String? {
        if (name == null) return null
        return imageStorage.getImageFilePath(name)
    }

    fun loadStatsAnimeList() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { localDataSource.getAllAnimeList() }.toImmutableList()
            val avgRating = if (list.isEmpty()) {
                0.0
            } else {
                list.map { it.rating.toDouble() }.average()
            }
            val totalEpisodes = list.sumOf { it.episodes }
            val language = uiLanguage.value
            val ratingFormatted = String.format(Locale.getDefault(), "%.1f", avgRating)
            val footer = statsFooterPhraseUseCase(
                language = language,
                avgRating = avgRating,
                totalEpisodes = totalEpisodes,
                ratingFormattedForUi = ratingFormatted
            )
            _uiState.update {
                it.copy(
                    statsAnimeList = list,
                    statsFooterPhrase = footer
                )
            }
        }
    }
}
