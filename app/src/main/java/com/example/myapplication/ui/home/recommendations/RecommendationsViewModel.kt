package com.example.myapplication.ui.home.recommendations

import com.example.myapplication.data.local.AppLanguagePrefs
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.domain.recommendations.RecommendationEngine
import com.example.myapplication.domain.recommendations.RecommendationItem
import com.example.myapplication.domain.recommendations.RecommendationsSnapshot
import com.example.myapplication.domain.search.AddFromApiUseCase
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppLanguage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
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

    private fun applySnapshot(snapshot: RecommendationsSnapshot, isRefreshing: Boolean) {
        val previousAdded = (_uiState.value as? RecommendationsUiState.Ready)?.addedKeys ?: persistentSetOf()
        _uiState.value = RecommendationsUiState.Ready(
            items = sessionSample(snapshot.items).toImmutableList(),
            confidence = snapshot.confidence,
            isColdStart = snapshot.isColdStart,
            addedKeys = previousAdded,
            isRefreshing = isRefreshing,
        )
    }

    /**
     * Срез сессии: score + джиттер, детерминированный по (key, sessionSalt).
     * Порядок стабилен внутри сессии (фоновый refresh не перетасовывает колоду),
     * но различается между запусками приложения.
     */
    private fun sessionSample(items: List<RecommendationItem>): List<RecommendationItem> {
        if (items.size <= SESSION_DECK_SIZE) return items
        return items
            .sortedByDescending { item ->
                val jitter = kotlin.random.Random(item.key.hashCode() xor sessionSalt).nextFloat()
                item.score + jitter * SESSION_JITTER
            }
            .take(SESSION_DECK_SIZE)
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

    companion object {
        const val LIBRARY_CHANGE_DEBOUNCE_MS = 3_000L

        /** Сколько карточек показывается за сессию (пул в кэше шире — 40). */
        const val SESSION_DECK_SIZE = 20

        /** Амплитуда джиттера в единицах score: заметно тасует, не ломая релевантность. */
        const val SESSION_JITTER = 0.45f
    }
}

fun RecommendationItem.toApiSearchResult() = ApiSearchResult(
    title = title,
    altTitle = altTitle,
    posterUrl = coverUrl,
    episodes = episodes,
    description = description,
    type = "",
    genres = genres,
    rating = externalRating,
    source = source,
    categoryType = categoryType,
    externalId = externalId,
)
