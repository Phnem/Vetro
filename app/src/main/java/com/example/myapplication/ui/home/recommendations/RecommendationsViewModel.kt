package com.example.myapplication.ui.home.recommendations

import com.example.myapplication.data.local.AppLanguagePrefs
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.domain.recommendations.ContentMixResolver
import com.example.myapplication.domain.recommendations.ContentType
import com.example.myapplication.domain.recommendations.FeedbackVerdict
import com.example.myapplication.domain.recommendations.Quota
import com.example.myapplication.domain.recommendations.QuotaAllocator
import com.example.myapplication.domain.recommendations.RecommendationEngine
import com.example.myapplication.domain.recommendations.RecommendationItem
import com.example.myapplication.domain.recommendations.RecommendationsSnapshot
import com.example.myapplication.domain.search.AddFromApiUseCase
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.ExternalIds
import com.example.myapplication.network.AppLanguage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


sealed interface RecommendationsUiState {
    /** Первый пересчёт (кэша ещё нет). */
    data object Loading : RecommendationsUiState

    data class Ready(
        val items: ImmutableList<RecommendationItem>,
        val confidence: Float,
        val isColdStart: Boolean,
        /** Ключи карточек, добавленных в коллекцию в этой сессии. */
        val addedKeys: ImmutableSet<String> = persistentSetOf(),
        /** Идёт фоновый пересчёт (показанные данные из кэша). */
        val isRefreshing: Boolean = false,
    ) : RecommendationsUiState

    /** Источники недоступны и кэша нет → Discovery-карточка скрывается. */
    data object Unavailable : RecommendationsUiState
}

/**
 * Состояние рекомендаций: кэш мгновенно → фоновый пересчёт (stale-while-revalidate).
 * Изменения библиотеки (оценка/добавление/удаление) дебаунсятся и триггерят
 * пересчёт в фоне — без блокирующего запроса на пути пользователя.
 */
@OptIn(FlowPreview::class)
class RecommendationsViewModel(
    private val engine: RecommendationEngine,
    private val localDataSource: AnimeLocalDataSource,
    private val addFromApiUseCase: AddFromApiUseCase,
    private val settingsDataStore: DataStore<Preferences>,
) : ViewModel() {

    private val _uiState = MutableStateFlow<RecommendationsUiState>(RecommendationsUiState.Loading)
    val uiState: StateFlow<RecommendationsUiState> = _uiState.asStateFlow()

    /**
     * Соль сессии для сэмплинга: кэш хранит широкий пул (40), а на экран идёт срез
     * с детерминированным в рамках сессии random-джиттером — при каждом запуске
     * приложения подборка другая без единого лишнего сетевого запроса.
     */
    private val sessionSalt: Int = kotlin.random.Random.nextInt()

    /** Последний снапшот движка — из него добираются карточки, когда колода опустела. */
    private var lastSnapshot: RecommendationsSnapshot? = null

    /** Были оценки свайпом: при закрытии шторки пул тихо пересоберётся с их учётом. */
    private var feedbackDirty = false

    private val language: StateFlow<AppLanguage> = settingsDataStore.data
        .map { prefs ->
            AppLanguagePrefs.from(prefs)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppLanguage.EN)

    init {
        viewModelScope.launch {
            // 1. Мгновенно отдаём кэш (даже протухший) — карточка появляется без сети.
            val cached = engine.cached()
            if (cached != null && cached.items.isNotEmpty()) {
                applySnapshot(cached, isRefreshing = true)
            }
            // 2. Фоновый пересчёт, если кэш протух/устарел (single-flight внутри движка).
            refresh()
        }

        // Изменения библиотеки → фоновый пересчёт (сигнатура сравнивается в движке).
        viewModelScope.launch {
            localDataSource.observeAllAnime()
                .map { engine.librarySignature(it) }
                .distinctUntilChanged()
                .debounce(LIBRARY_CHANGE_DEBOUNCE_MS)
                .collect { refresh() }
        }

        // Смена языка приложения → пул пересобирается под нужный источник (RU → Shikimori).
        viewModelScope.launch {
            language
                .drop(1)
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    /** Язык — прямым чтением DataStore: StateFlow при первом запуске может ещё держать дефолт. */
    private suspend fun readLanguage(): AppLanguage = runCatching {
        AppLanguagePrefs.current(settingsDataStore)
    }.getOrElse { AppLanguage.EN }

    private suspend fun refresh(force: Boolean = false) {
        val snapshot = engine.refresh(readLanguage(), force = force)
        if (snapshot != null && snapshot.items.isNotEmpty()) {
            applySnapshot(snapshot, isRefreshing = false)
        } else if (_uiState.value !is RecommendationsUiState.Ready) {
            _uiState.value = RecommendationsUiState.Unavailable
        }
    }

    private suspend fun applySnapshot(snapshot: RecommendationsSnapshot, isRefreshing: Boolean) {
        lastSnapshot = snapshot
        // Старый кэш мог собраться до свайпов — уже оценённое в колоду не возвращаем.
        val judged = engine.feedbackIndex()
        val previousAdded = (_uiState.value as? RecommendationsUiState.Ready)?.addedKeys ?: persistentSetOf()
        _uiState.value = RecommendationsUiState.Ready(
            items = sessionSample(snapshot.items.filterNot { judged.isJudged(it) }, snapshot).toImmutableList(),
            confidence = snapshot.confidence,
            isColdStart = snapshot.isColdStart,
            addedKeys = previousAdded,
            isRefreshing = isRefreshing,
        )
    }

    /** Прошлая квота: вход гистерезиса около порога доминирования, чтобы 85/15 не «моргало» между перезапусками. */
    private var lastQuota: Quota? = null

    /**
     * Колода сессии: квота по составу коллекции (аниме / фильмы / сериалы) → внутри каждого типа
     * score + малый джиттер, детерминированный по (key, sessionSalt) → равномерное чередование типов.
     * Порядок стабилен внутри сессии (фоновый refresh не перетасовывает колоду), но различается
     * между запусками. Квота считается ПОСЛЕ ранжирования по типам и не зависит от перезапуска сети.
     */
    private fun sessionSample(items: List<RecommendationItem>, snapshot: RecommendationsSnapshot): List<RecommendationItem> {
        val byType = items.groupBy { it.contentType }
        val available = ContentType.entries.associateWith { byType[it]?.size ?: 0 }
        val quota = ContentMixResolver.resolve(snapshot.mix, available, lastQuota)
        lastQuota = quota
        // Нормализованный скор лежит в [0,1] — джиттер на порядок меньше, чем у прежней шкалы.
        val amplitude = if (snapshot.scorerVersion >= 2) SESSION_JITTER else LEGACY_SESSION_JITTER
        val ranked = byType.mapValues { (_, list) ->
            list.sortedByDescending { item ->
                val noise = kotlin.random.Random(item.key.hashCode() xor sessionSalt).nextFloat()
                item.score + noise * amplitude
            }
        }
        return QuotaAllocator.interleave(ranked, quota)
    }

    /** Быстрое добавление из колоды — той же машинерией, что и поиск по API. */
    fun addToCollection(item: RecommendationItem, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = addFromApiUseCase(item.toApiSearchResult())
            if (result.isSuccess) {
                _uiState.update { state ->
                    if (state is RecommendationsUiState.Ready) {
                        state.copy(addedKeys = (state.addedKeys + item.key).toImmutableSet())
                    } else state
                }
            }
            onDone(result.isSuccess)
        }
    }

    /**
     * Оценка свайпом: вверх — нравится, вниз — не интересно. Колоду не трогаем (она уже убрала
     * карточку сама), а движок учтёт оценку при следующем пересчёте.
     */
    fun rate(item: RecommendationItem, verdict: FeedbackVerdict) {
        feedbackDirty = true
        viewModelScope.launch { engine.recordFeedback(item, verdict) }
    }

    /** Отмена оценки: карточка вернулась в колоду. */
    fun undoRate(item: RecommendationItem) {
        viewModelScope.launch { engine.clearFeedback(item.key) }
    }

    /** Шторка закрыта: если были оценки — пул пересобирается в фоне с их учётом (кэш уже устарел по ревизии). */
    fun onSheetClosed() {
        if (!feedbackDirty) return
        feedbackDirty = false
        viewModelScope.launch { refresh() }
    }

    /** Колода опустела: добираем неоценённое из пула, а если он исчерпан — пересобираем с учётом оценок. */
    fun loadMore() {
        viewModelScope.launch {
            val judged = engine.feedbackIndex()
            val snapshotNow = lastSnapshot
            val remaining = snapshotNow?.items.orEmpty().filterNot { judged.isJudged(it) }
            if (snapshotNow != null && remaining.isNotEmpty()) {
                updateReady { copy(items = sessionSample(remaining, snapshotNow).toImmutableList(), isRefreshing = false) }
                return@launch
            }
            updateReady { copy(items = persistentListOf(), isRefreshing = true) }
            val snapshot = engine.refresh(readLanguage(), force = true)
            if (snapshot != null) {
                applySnapshot(snapshot, isRefreshing = false)
            } else {
                updateReady { copy(isRefreshing = false) }
            }
        }
    }

    private fun updateReady(transform: RecommendationsUiState.Ready.() -> RecommendationsUiState.Ready) {
        _uiState.update { state -> if (state is RecommendationsUiState.Ready) state.transform() else state }
    }

    companion object {
        const val LIBRARY_CHANGE_DEBOUNCE_MS = 3_000L

        /** Сколько карточек показывается за сессию (пул каждого типа в кэше шире — 40). */
        const val SESSION_DECK_SIZE = ContentMixResolver.TOTAL

        /**
         * Амплитуда джиттера на нормализованной шкале [0,1]: только перемешивает близкие по баллу карточки
         * (±0.03–0.05 из плана), не ломая отбор по качеству. Значение подбирается на replay-тестах.
         */
        const val SESSION_JITTER = 0.04f

        /** Прежняя шкала скора (до ~2): заметно тасует, не ломая релевантность. */
        const val LEGACY_SESSION_JITTER = 0.45f
    }
}

fun RecommendationItem.toApiSearchResult() = ApiSearchResult(
    title = title,
    altTitle = altTitle,
    posterUrl = coverUrl,
    episodes = episodes,
    description = description,
    type = when (contentType) {
        ContentType.ANIME -> ""
        ContentType.MOVIE -> "Movie"
        ContentType.TV -> "TV"
    },
    genres = genres,
    rating = externalRating,
    source = source,
    categoryType = categoryType,
    externalId = externalId,
    // Фильм/сериал привязывается к TMDB по id, а не по названию: при склейке источник карточки мог быть Trakt/TasteDive.
    externalIds = ExternalIds(tmdb = tmdbId),
)
