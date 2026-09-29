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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.example.myapplication.domain.seasons.isAiringNow
import com.example.myapplication.domain.seasons.ongoingSeason
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

/** `categoryType` найденной аудиокниги: у книг свой раздел и своё хранилище, не коллекция тайтлов. */
internal const val BOOK_CATEGORY = "BOOK"

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
    private val seriesSeasonsStore: com.example.myapplication.data.local.SeriesSeasonsStore,
    private val episodePlaybackStore: com.example.myapplication.media.progress.EpisodePlaybackStore,
    private val mangaBindingStore: com.example.myapplication.manga.data.MangaBindingStore,
    private val mangaChapterCacheStore: com.example.myapplication.manga.data.MangaChapterCacheStore,
    private val mangaReadingStore: com.example.myapplication.manga.data.MangaReadingStore,
    /** null — раздел аудиокниг выключен в сборке, вкладки «Книги» в поиске нет. */
    private val bookSearch: com.example.myapplication.audiobooks.data.BookSearchAdder? = null,
) : ViewModel() {

    /** Найденные прямые ссылки по одобренным сайтам (animeId → запись). Реактивно для карточек. */
    val webLinks: StateFlow<Map<String, com.example.myapplication.domain.enrichment.weblinks.WebLinksEntry>> =
        webLinksStore.flow
    init { viewModelScope.launch { webLinksStore.ensureLoaded() } }

    /**
     * Самая дальняя серия, до которой пользователь дошёл (animeId → сезон и серия в нём).
     * Карточка показывает прогресс внутри сезона («S3 · 6 / 12 ep.»), а не сквозной счёт по
     * франшизе, поэтому числитель берётся как есть, без пересчёта через расклад.
     */
    val watchedMarks: StateFlow<Map<String, com.example.myapplication.media.progress.PlaybackEpisodeKey>> =
        localDataSource.observeAllAnime()
            .map { list -> list.map { anime -> anime.id } }
            .distinctUntilChanged()
            .flatMapLatest { ids -> episodePlaybackStore.furthestEpisodeFlow(ids) }
            .map { furthest -> furthest.filterValues { it.episode > 0 } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Разложение тайтлов по сезонам (animeId → расклад): аниме — по графу франшизы AniList
     * ([com.example.myapplication.data.local.SeasonEpisodesStore]), сериалы — по TMDB
     * ([com.example.myapplication.data.local.SeriesSeasonsStore]). Ключи не пересекаются: у каждого
     * тайтла ровно один тип.
     */
    val seasonLayouts: StateFlow<Map<String, com.example.myapplication.domain.seasons.SeasonEpisodesEntry>> =
        combine(seasonEpisodesStore.flow, seriesSeasonsStore.flow) { anime, series -> series + anime }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        viewModelScope.launch { seasonEpisodesStore.ensureLoaded() }
        viewModelScope.launch { seriesSeasonsStore.ensureLoaded() }
    }

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

    /**
     * Выходящие сейчас сезоны (animeId → прогресс) — карточки «в процессе».
     *
     * Аниме — снимок проверки серий (таблица airing_progress). У сериалов своего снимка нет: их
     * выходящий сезон — последний сезон TMDB-расклада, у которого вышли ещё не все серии.
     */
    val airingProgress: StateFlow<Map<String, com.example.myapplication.data.models.AiringProgress>> =
        combine(localDataSource.observeAiringProgress(), seriesSeasonsStore.flow) { anime, series ->
            seriesAiring(series) + anime
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private fun seriesAiring(
        series: Map<String, com.example.myapplication.domain.seasons.SeasonEpisodesEntry>,
    ): Map<String, com.example.myapplication.data.models.AiringProgress> =
        series.mapNotNull { (animeId, entry) ->
            val season = entry.ongoingSeason() ?: return@mapNotNull null
            animeId to com.example.myapplication.data.models.AiringProgress(
                animeId = animeId,
                seasonNumber = season.seasonNumber,
                airedEpisodes = season.episodes,
                totalEpisodes = season.totalEpisodes,
                updatedAt = entry.resolvedAt,
            )
        }.toMap()

    /**
     * Кто «выходит сейчас» для ПОРЯДКА списка — снимок на сессию, а не живой поток.
     *
     * Живой поток переставлял карточки прямо под пальцем: сначала список приходил без этой группы
     * (снимок ещё не прочитан), потом проверка серий при входе переписывала снимок, и несколько
     * секунд карточки прыгали. Теперь порядок берётся из сохранённого снимка один раз, когда оба
     * источника загружены (до этого список не показывается), а обновляется только по явному
     * «потянуть, чтобы обновить» — и один раз, если снимка ещё не было вовсе (первый запуск).
     * Полоски выхода и подписи на карточках при этом живые: они читают [airingProgress].
     *
     * Между полными пересборками набор только РАСТЁТ: тайтл, у которого в этой сессии вышла
     * серия (непрочитанное уведомление) или начался сезон, поднимается наверх сразу — это
     * событие, а не дрожание. Выпадают из группы тайтлы только на pull-to-refresh, чтобы
     * смахнутое уведомление не роняло карточку вниз под пальцем.
     */
    private val airingOrder = MutableStateFlow<Set<String>?>(null)

    private suspend fun airingNowIds(): Set<String> {
        seriesSeasonsStore.ensureLoaded()
        val anime = localDataSource.observeAiringProgress().first()
        val airing = (seriesAiring(seriesSeasonsStore.flow.value) + anime).filterValues { it.isAiringNow() }.keys
        val unread = withContext(Dispatchers.IO) { localDataSource.getUpdates() }.map { it.animeId }
        return airing + unread
    }

    /** Дополнить порядок новыми «актуальными», не выкидывая прежних (см. [airingOrder]). */
    private fun growAiringOrder(ids: Set<String>) {
        airingOrder.update { current -> if (current == null || current.containsAll(ids)) current else current + ids }
    }

    init {
        viewModelScope.launch {
            airingOrder.value = runCatching { airingNowIds() }.getOrElse { emptySet() }
            // Новые серии, найденные уже в этой сессии (проверка при входе, фоновый воркер).
            localDataSource.observeUpdates().collect { list ->
                growAiringOrder(list.mapTo(HashSet()) { it.animeId })
            }
        }
    }

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
    ).combine(airingOrder.filterNotNull()) { list, airing ->
        // Порядок групп: избранное → выходящие сейчас → остальное. Сортировка стабильна, так что
        // внутри групп сохраняется выбранная пользователем сортировка; избранное репозиторий уже
        // поднял наверх, здесь достаточно не сломать это и поднять выходящие под ним.
        if (airing.isEmpty()) list
        else list.sortedWith(
            compareByDescending<Anime> { it.isFavorite }.thenByDescending { it.id in airing }
        )
    }.flowOn(Dispatchers.Default)
     .map { it.toImmutableList() }
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
        animeListFlow,
        bookSearch?.libraryTitles ?: flowOf(emptySet()),
    ) { apiResults, optimisticKeys, localList, bookTitles ->
        apiResults.map { result ->
            val inDb = if (result.categoryType == BOOK_CATEGORY) {
                bookSearch?.key(result.title) in bookTitles
            } else {
                isAddedInMemory(result, localList)
            }
            ApiSearchUiModel(
                result = result,
                // Оптимистичный ключ ИЛИ факт в БД: кнопка обязана переключиться сразу по нажатию,
                // не дожидаясь скачивания постера, иначе она откатывается и пользователь дожимает.
                isAdded = searchResultKey(result) in optimisticKeys || inDb
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
            if (_uiState.value.searchBooks && bookSearch != null) {
                val books = runCatching { bookSearch.search(trimmed) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else emptyList() }
                if (_uiState.value.searchQuery.trim() != trimmed) return@launch
                val ru = uiLanguage.value == com.example.myapplication.network.AppLanguage.RU
                foundBooks = books.associateBy { "${it.ref.source.value}:${it.ref.key}" }
                _uiState.update {
                    it.copy(apiSearchResults = books.map { b -> b.toSearchResult(ru) }.toImmutableList(), apiSearchLoading = false)
                }
                return@launch
            }
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

    /** Найденные книги по ключу результата — чтобы «Добавить» открыло страницу книги у её источника. */
    private var foundBooks: Map<String, com.example.myapplication.audiobooks.domain.source.SourceBook> = emptyMap()

    private fun com.example.myapplication.audiobooks.domain.source.SourceBook.toSearchResult(ru: Boolean): ApiSearchResult {
        val hours = durationSec?.let { s -> if (s >= 3600) "${s / 3600} ${if (ru) "ч" else "h"}" else "${(s / 60).coerceAtLeast(1)} ${if (ru) "мин" else "min"}" }
        val byline = listOfNotNull(
            authors.joinToString(", ").takeIf { it.isNotBlank() },
            narrators.joinToString(", ").takeIf { it.isNotBlank() }?.let { (if (ru) "читает " else "read by ") + it },
            hours,
        ).joinToString(" · ")
        return ApiSearchResult(
            title = title, altTitle = byline.ifBlank { null }, posterUrl = coverUrl, episodes = 0, description = "",
            type = BOOK_CATEGORY, genres = genres, rating = null, source = BOOK_CATEGORY, categoryType = BOOK_CATEGORY,
            externalId = "${ref.source.value}:${ref.key}",
        )
    }

    private fun addBook(result: ApiSearchResult, key: String) {
        val book = foundBooks[result.externalId] ?: return
        val adder = bookSearch ?: return
        _uiState.update { it.copy(optimisticallyAddedKeys = it.optimisticallyAddedKeys.add(key), addingFromApiId = key) }
        viewModelScope.launch {
            val ok = runCatching { adder.add(book) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else false }
            _uiState.update {
                if (ok) it.copy(addingFromApiId = null) else it.copy(
                    addingFromApiId = null,
                    optimisticallyAddedKeys = it.optimisticallyAddedKeys.remove(key),
                    apiSearchError = if (uiLanguage.value == com.example.myapplication.network.AppLanguage.RU) "Источник не отдал книгу" else "The source didn't return this book",
                )
            }
        }
    }

    fun addFromApi(result: ApiSearchResult) {
        val key = searchResultKey(result)
        if (result.categoryType == BOOK_CATEGORY) {
            if (key !in _uiState.value.optimisticallyAddedKeys) addBook(result, key)
            return
        }

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
        _uiState.update { it.copy(searchMediaTypeFilter = filter, searchBooks = false) }
        updateSearchQuery(_uiState.value.searchQuery)
    }

    /** Вкладка «Книги» есть, только если раздел аудиокниг включён в сборке. */
    val canSearchBooks: Boolean get() = bookSearch != null

    fun setSearchBooks() {
        if (bookSearch == null) return
        _uiState.update { it.copy(searchBooks = true, apiSearchResults = persistentListOf()) }
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
                // Полная пересборка порядка — только по явному обновлению или если его ещё не было;
                // иначе лишь дополняем тем, что проверка нашла выходящим сейчас.
                val fresh = runCatching { airingNowIds() }.getOrNull()
                if (force || airingOrder.value.isNullOrEmpty()) {
                    airingOrder.value = fresh ?: airingOrder.value.orEmpty()
                } else if (fresh != null) {
                    growAiringOrder(fresh)
                }
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
