package com.example.myapplication.ui.details

import com.example.myapplication.domain.enrichment.title.TitleEnrichmentRepository
import com.example.myapplication.domain.enrichment.title.TitleEnrichment
import com.example.myapplication.data.local.AppLanguagePrefs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.data.models.effectiveWatchStatus
import com.example.myapplication.domain.enrichment.releaseYearOrNull
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.domain.enrichment.PosterCandidate
import com.example.myapplication.domain.enrichment.TitleInfoEnricher
import com.example.myapplication.domain.enrichment.TitleCandidate
import com.example.myapplication.domain.enrichment.TitleCandidateSearch
import com.example.myapplication.domain.enrichment.withRejectedSource
import com.example.myapplication.media.progress.EpisodePlaybackStore
import com.example.myapplication.media.progress.PlaybackEpisodeKey
import com.example.myapplication.media.progress.TitleTracking
import com.example.myapplication.media.progress.TitleTrackingStore
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.example.myapplication.data.repository.ImageStorageRepository
import com.example.myapplication.network.AppLanguage
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.local.SeriesSeasonsStore
import com.example.myapplication.data.local.WebLinksStore
import com.example.myapplication.domain.enrichment.weblinks.ResolvedWebLink
import com.example.myapplication.domain.seasons.SeasonCatchUp
import com.example.myapplication.domain.seasons.SeasonEpisodesResolver
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.updates.SeriesEpisodeCheckUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import com.example.myapplication.domain.seasons.ongoingSeason
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val localDataSource: AnimeLocalDataSource,
    private val playbackStore: EpisodePlaybackStore,
    private val titleCandidates: TitleCandidateSearch,
    private val titleInfoEnricher: TitleInfoEnricher,
    private val titleTrackingStore: TitleTrackingStore,
    private val seriesSeasonsStore: SeriesSeasonsStore? = null,
    private val seriesEpisodeCheck: SeriesEpisodeCheckUseCase? = null,
    private val autoWatchStatus: com.example.myapplication.domain.status.AutoWatchStatusResolver? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DetailsUiState>(DetailsUiState.Idle)
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    // Инициализация синхронно: узел sharedBounds есть в дереве на 0-м кадре — Exit transition работает.
    private val _currentAnime = MutableStateFlow<Anime?>(repository.getAnimeById(animeId))
    val currentAnime: StateFlow<Anime?> = _currentAnime.asStateFlow()

    val tracking: StateFlow<TitleTracking> = titleTrackingStore.observe(animeId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TitleTracking())

    /**
     * Обогащение карточки (логотип, трейлер, рейтинги критиков, следующая серия) — отдельно от базовой
     * карточки: грузится после неё и не мешает ей; ошибка или отсутствие ключей — просто null.
     */
    /**
     * Статус, который показывает чип в Details: выбранный пользователем или «по умолчанию» из прогресса.
     * null — пока статус «по умолчанию» не посчитан и своего выбора нет: чип в это время не рисуется,
     * чтобы не показать «Просмотрено» тому, кто на самом деле смотрит.
     */
    val effectiveWatchStatus: StateFlow<WatchStatus?> = combine(
        _currentAnime,
        // Считаем статус только этого тайтла, а не всей коллекции.
        autoWatchStatus?.observeOne(animeId, _currentAnime.map { it?.episodes ?: 0 }.distinctUntilChanged())
            ?: kotlinx.coroutines.flow.flowOf(null),
    ) { anime, auto ->
        anime?.let { a -> a.watchStatus ?: auto }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _currentAnime.value?.watchStatus)

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

    /** Серии по сезонам (фоновый резолв, см. SeasonEpisodesResolver и SeriesSeasonsStore) — реактивно из сторов. */
    val seasons: StateFlow<List<SeasonInfo>> =
        if (seriesSeasonsStore != null) {
            combine(seasonEpisodesStore.flow, seriesSeasonsStore.flow) { animeMap, seriesMap ->
                (animeMap[animeId] ?: seriesMap[animeId])?.seasons.orEmpty()
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        } else {
            seasonEpisodesStore.flow
                .map { it[animeId]?.seasons.orEmpty() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    init {
        viewModelScope.launch { webLinksStore.ensureLoaded() }
        // Фильм или сериал открыли — добираем id (TMDB, Кинопоиск, IMDb) и русское название из
        // информационного слоя, если их ещё нет: провайдеры просмотра ищут по ним. Кэш делает
        // повторное открытие бесплатным.
        viewModelScope.launch {
            val anime = _currentAnime.value ?: return@launch
            val outcome = runCatching { titleInfoEnricher.enrich(anime) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull() ?: return@launch
            if (outcome.changedFields.isNotEmpty()) {
                _currentAnime.value = outcome.anime
            }
            if (outcome.anime.mediaType == MediaType.SERIES) {
                runCatching { seriesEpisodeCheck?.ensureSeasonsResolved(outcome.anime) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            }
        }
        // Открытые детали — вне очереди: если сезонов нет или протухли, резолвим сразу
        // (внутри IO + isFresh-гейт), секция появится реактивно.
        viewModelScope.launch {
            seasonEpisodesStore.ensureLoaded()
            seriesSeasonsStore?.ensureLoaded()
            val anime = _currentAnime.value
            if (anime?.mediaType == MediaType.SERIES) {
                runCatching { seriesEpisodeCheck?.ensureSeasonsResolved(anime) }
            } else {
                runCatching { seasonEpisodesResolver.ensureResolved(animeId) }
                // Пуш сказал «5-я серия 3-го сезона», а в раскладе два сезона — значит он отстал, и
                // пользователю нечего доказывать это кнопкой «Найти ещё». Проверка дешёвая: сверка
                // с уже посчитанным прогрессом выходящего сезона, сеть трогается только при
                // доказанном расхождении.
                runCatching { seasonCatchUp.catchUp(animeId) }
            }
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

    /**
     * Статус просмотра, выбранный пользователем; null возвращает «авто». Точечная запись в БД, а
     * копия в [currentAnime] обновляется следом — иначе следующая полная запись записи (например,
     * из «Просмотрено») вернула бы старый статус.
     */
    /** Шторка выбора статуса: что выбрано вручную и что показывается сейчас. */
    fun openStatusPicker() {
        val anime = _currentAnime.value ?: return
        _picker.value = DetailsPickerState.Status(
            chosen = anime.watchStatus,
            effective = effectiveWatchStatus.value ?: anime.effectiveWatchStatus(null),
        )
    }

    fun pickStatus(status: WatchStatus?) {
        _picker.value = DetailsPickerState.None
        setWatchStatus(status)
    }

    fun setWatchStatus(status: WatchStatus?) {
        viewModelScope.launch {
            runCatching { localDataSource.setWatchStatus(animeId, status) }
                .onSuccess { _currentAnime.value = _currentAnime.value?.copy(watchStatus = status) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    say("Не удалось сохранить статус", "Couldn't save the status")
                }
        }
    }

    /** Короткие ответы на действия из меню «⋮» — экран показывает их тостом. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun say(ru: String, en: String) {
        _messages.tryEmit(if (_currentLanguage.value == AppLanguage.RU) ru else en)
    }

    init {
        // Players can change the watch status while Details is in the back stack.
        viewModelScope.launch {
            localDataSource.observeAllAnime().map { list -> list.find { it.id == animeId } }
                .distinctUntilChanged().collect { _currentAnime.value = it }
        }
        viewModelScope.launch {
            tracking.collect { state ->
                if (state.preferences.ratingResetNotice) {
                    say("Все оценки эпизодов удалены. Рейтинг переключён на Auto", "All episode ratings were removed. Rating switched to Auto")
                    titleTrackingStore.acknowledgeRatingReset(animeId)
                }
            }
        }
    }

    fun openRatingModePicker() {
        _picker.value = DetailsPickerState.RatingMode(tracking.value.preferences.manualRating)
    }

    fun pickRatingMode(manual: Boolean) {
        viewModelScope.launch {
            runCatching { titleTrackingStore.setManualRating(animeId, manual) }
                .onSuccess { accepted ->
                    if (accepted) dismissPicker() else say(
                        "Нет оценок эпизодов: среднее пока посчитать нельзя. Manual rating недоступен",
                        "No episodes have a rating yet. Manual rating needs at least one rated episode",
                    )
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    say("Не удалось сохранить режим рейтинга", "Couldn't save the rating mode")
                }
        }
    }

    fun openDateModePicker() {
        _picker.value = DetailsPickerState.DateMode(tracking.value.preferences)
    }

    fun pickDateMode(manual: Boolean) {
        if (manual) {
            val prefs = tracking.value.preferences
            _picker.value = if (prefs.manualStartedOn != null) DetailsPickerState.WatchDates(
                LocalDate.ofEpochDay(prefs.manualStartedOn), prefs.manualFinishedOn?.let(LocalDate::ofEpochDay),
            ) else DetailsPickerState.WatchDates(prefs.startedOn, prefs.finishedOn)
        } else viewModelScope.launch {
            runCatching { titleTrackingStore.useAutoDates(animeId) }
                .onSuccess { dismissPicker() }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    say("Не удалось сохранить режим дат", "Couldn't save the date mode")
                }
        }
    }

    fun saveWatchDates(start: LocalDate, finish: LocalDate?) {
        if (finish != null && finish.isBefore(start)) {
            say("Дата окончания не может быть раньше начала", "Finish date cannot precede the start date")
            return
        }
        viewModelScope.launch {
            runCatching {
                titleTrackingStore.saveManualDates(animeId, start, finish)
                if (finish != null) {
                    localDataSource.setWatchStatus(animeId, WatchStatus.COMPLETED)
                    _currentAnime.value = _currentAnime.value?.copy(watchStatus = WatchStatus.COMPLETED)
                }
            }.onSuccess { dismissPicker() }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    say("Не удалось сохранить даты просмотра", "Couldn't save watch dates")
                }
        }
    }

    /**
     * «Просмотрено»: тайтл досмотрели вне приложения. Серии всех сезонов расклада получают отметку
     * в прогрессе воспроизведения — по нему и меню серий, и карточка главной считают просмотр, —
     * а у аниме заодно поднимается счётчик записи (его читают синхронизации и экран серий).
     */
    fun markWatched() {
        viewModelScope.launch {
            val anime = _currentAnime.value ?: return@launch
            val regular = seasons.value.filter { !it.isSpecial && it.episodes > 0 }.ifEmpty {
                listOf(
                    SeasonInfo(
                        seasonNumber = 1,
                        episodes = anime.episodes.coerceAtLeast(1),
                        source = "local",
                    ),
                )
            }
            val keys = regular.flatMap { season ->
                (1..season.episodes).map { PlaybackEpisodeKey(season.seasonNumber, it) }
            }
            runCatching {
                playbackStore.markWatched(animeId, keys)
                val total = regular.sumOf { it.episodes }
                if (anime.mediaType == MediaType.ANIME && total > anime.episodes) {
                    val updated = anime.copy(episodes = total)
                    localDataSource.updateAnime(updated)
                    _currentAnime.value = updated
                }
                // Досмотрел вне приложения — статус тоже «Просмотрено», иначе карточка осталась бы «Смотрю».
                localDataSource.setWatchStatus(animeId, WatchStatus.COMPLETED)
                _currentAnime.value = _currentAnime.value?.copy(watchStatus = WatchStatus.COMPLETED)
            }.onSuccess {
                say("Отмечено просмотренным: ${keys.size} сер.", "Marked as watched: ${keys.size} ep.")
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                say("Не удалось отметить просмотренным", "Couldn't mark as watched")
            }
        }
    }

    /** Открытая шторка выбора из меню «⋯»: обложка или вариант источника. */
    private val _picker = MutableStateFlow<DetailsPickerState>(DetailsPickerState.None)
    val picker: StateFlow<DetailsPickerState> = _picker.asStateFlow()

    private var pickerJob: kotlinx.coroutines.Job? = null

    fun dismissPicker() {
        pickerJob?.cancel()
        _picker.value = DetailsPickerState.None
    }

    /** «Сменить обложку»: обложки того же тайтла из других каталогов, выбирает человек. */
    fun openCoverPicker() {
        val anime = _currentAnime.value ?: return
        pickerJob?.cancel()
        _picker.value = DetailsPickerState.Covers(loading = true)
        pickerJob = viewModelScope.launch {
            val items = runCatching { titleCandidates.posters(anime) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrDefault(emptyList())
            _picker.value = DetailsPickerState.Covers(items = items)
        }
    }

    fun pickCover(poster: PosterCandidate) {
        val anime = _currentAnime.value ?: return
        val current = _picker.value as? DetailsPickerState.Covers ?: return
        _picker.value = current.copy(saving = true)
        viewModelScope.launch {
            val saved = imageStorage.saveImageFromUrl(poster.url, animeId).getOrNull()
            if (saved == null) {
                _picker.value = current.copy(saving = false)
                say("Не удалось загрузить обложку", "Couldn't download the cover")
                return@launch
            }
            val updated = anime.copy(imageFileName = saved)
            runCatching { localDataSource.updateAnime(updated) }
                .onSuccess {
                    _currentAnime.value = updated
                    _picker.value = DetailsPickerState.None
                    say("Обложка обновлена (${poster.source})", "Cover updated (${poster.source})")
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    _picker.value = current.copy(saving = false)
                    say("Не удалось сохранить обложку", "Couldn't save the cover")
                }
        }
    }

    /**
     * «Источник неверен»: варианты тайтла из нескольких каталогов по русскому названию (или по
     * введённому запросу / id) — человек выбирает верный или отвечает «ни один из них».
     */
    fun openSourcePicker() {
        pickerJob?.cancel()
        searchSources("")
    }

    fun searchSources(query: String) {
        val anime = _currentAnime.value ?: return
        pickerJob?.cancel()
        _picker.value = DetailsPickerState.Sources(query = query, loading = true)
        pickerJob = viewModelScope.launch {
            val items = runCatching { titleCandidates.titles(anime, query) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrDefault(emptyList())
            _picker.value = DetailsPickerState.Sources(query = query, items = items)
        }
    }

    /**
     * Выбрано другое название: записываем именно его (английское название и id каталогов), снимаем
     * отметки «не найдено» и подтягиваем под него обложку и расклад сезонов.
     */
    fun pickSource(candidate: TitleCandidate) {
        val anime = _currentAnime.value ?: return
        val current = _picker.value as? DetailsPickerState.Sources ?: return
        _picker.value = current.copy(saving = true)
        viewModelScope.launch {
            val poster = candidate.posterUrl?.let { imageStorage.saveImageFromUrl(it, animeId).getOrNull() }
            val updated = anime.copy(
                titleEn = candidate.titleEn,
                anilistId = candidate.anilistId,
                malId = candidate.malId,
                // У аниме id Shikimori совпадает с MAL id.
                shikimoriId = candidate.shikimoriId ?: candidate.malId,
                anilistNotFoundAt = null,
                malNotFoundAt = null,
                shikimoriNotFoundAt = null,
                imageFileName = poster ?: anime.imageFileName,
            )
            runCatching {
                localDataSource.updateAnime(updated)
                seasonEpisodesStore.remove(animeId)
            }.onSuccess {
                _currentAnime.value = updated
                _picker.value = DetailsPickerState.None
                _enrichment.value = null
                loadDetails(updated, _currentLanguage.value)
                launch { loadEnrichment(refresh = true) }
                launch { runCatching { seasonEpisodesResolver.ensureResolved(animeId) } }
                say("Источник: ${candidate.titleEn}", "Source: ${candidate.titleEn}")
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                _picker.value = current.copy(saving = false)
                say("Не удалось сохранить источник", "Couldn't save the source")
            }
        }
    }

    /**
     * «Ни один из них»: снять чужую привязку (id каталогов, английское название, обложку, расклад
     * сезонов) и пометить тайтл, чтобы автоподбор не вернул её на место.
     */
    fun rejectSource() {
        val current = _picker.value as? DetailsPickerState.Sources
        if (current != null) _picker.value = current.copy(saving = true)
        viewModelScope.launch {
            val anime = _currentAnime.value ?: return@launch
            val cleared = anime.withRejectedSource().copy(imageFileName = null)
            runCatching {
                localDataSource.updateAnime(cleared)
                seasonEpisodesStore.remove(animeId)
            }.onSuccess {
                _currentAnime.value = cleared
                _picker.value = DetailsPickerState.None
                _enrichment.value = null
                loadDetails(cleared, _currentLanguage.value)
                say("Источник сброшен, тайтл помечен как ошибка", "Source cleared, title flagged as wrong match")
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                if (current != null) _picker.value = current.copy(saving = false)
                say("Не удалось сбросить источник", "Couldn't clear the source")
            }
        }
    }

    /** Год релиза из открытой карточки уходит в запись: по нему работает фильтр года в шторке сортировки. */
    private suspend fun rememberReleaseYear(year: Int?) {
        if (year == null || _currentAnime.value?.releaseYear == year) return
        runCatching { localDataSource.setReleaseYear(animeId, year) }
            .onSuccess { _currentAnime.value = _currentAnime.value?.copy(releaseYear = year) }
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
                        if (details != null) rememberReleaseYear(details.releaseYearOrNull())
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
