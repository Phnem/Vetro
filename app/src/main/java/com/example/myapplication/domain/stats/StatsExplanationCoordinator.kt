package com.example.myapplication.domain.stats

import com.example.myapplication.data.local.AppLanguagePrefs
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.myapplication.data.ai.AiCredentialsStore
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.StatsExplanationCacheStore
import com.example.myapplication.data.models.Anime
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch


/** Состояние AI-объяснения одной карточки статистики (для UI детального режима). */
sealed interface StatsCardExplanationState {
    data object Loading : StatsCardExplanationState
    data class Ready(val text: String) : StatsCardExplanationState

    /** Нет ключа ИИ или генерация не удалась — UI тихо приглушает AI-блок. */
    data object Unavailable : StatsCardExplanationState

    /** В карточке слишком мало данных для выводов — AI не зовём вообще. */
    data object InsufficientData : StatsCardExplanationState
}

/**
 * Фоновая генерация AI-объяснений карточек статистики «незаметно для пользователя».
 *
 * Живёт независимо от того, открыта ли шторка: подписан на реактивный поток БД
 * (debounce от массовых импортов) и язык из настроек; пересчитывает только те карточки,
 * чей фингерпринт данных изменился (см. [StatsCardSnapshot.fingerprint]).
 *
 * Осознанно НЕ WorkManager (в отличие от [com.example.myapplication.domain.titles.TitleDubbingCoordinator]):
 * задача лёгкая — три коротких текста; пересчитать при следующем старте приложения не страшно.
 */
class StatsExplanationCoordinator(
    private val localDataSource: AnimeLocalDataSource,
    private val explanationUseCase: StatsCardExplanationUseCase,
    private val credentialsStore: AiCredentialsStore,
    private val cacheStore: StatsExplanationCacheStore,
    private val settingsDataStore: DataStore<Preferences>,
    /** Общая область процесса (AppScope); объяснения — это сетевые вызовы ИИ, так что IO. */
    private val appScope: CoroutineScope,
) {
    private val _state = MutableStateFlow<Map<StatsCardKind, StatsCardExplanationState>>(emptyMap())
    val state: StateFlow<Map<StatsCardKind, StatsCardExplanationState>> = _state.asStateFlow()

    private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        val languageFlow = settingsDataStore.data
            .map { prefs -> AppLanguagePrefs.from(prefs) }
            .distinctUntilChanged()
        appScope.launch {
            combine(
                // debounce: массовый импорт не должен долбить AI-запросами на каждую запись
                localDataSource.observeAllAnime().debounce(1_000),
                languageFlow,
                // подключение/отключение ключа ИИ — тоже повод пересчитать
                credentialsStore.connectedProviders,
            ) { list, language, providers -> Triple(list, language, providers) }
                .collectLatest { (list, language, providers) ->
                    regenerateChanged(list, language, hasProvider = providers.isNotEmpty())
                }
        }
    }

    private suspend fun regenerateChanged(
        animeList: List<Anime>,
        language: AppLanguage,
        hasProvider: Boolean,
    ) {
        for (kind in StatsCardKind.entries) {
            val snapshot = StatsCardSnapshot.build(kind, animeList)
            if (!snapshot.hasSufficientData()) {
                setState(kind, StatsCardExplanationState.InsufficientData)
                continue
            }
            val fingerprint = snapshot.fingerprint()
            val cached = cacheStore.read(kind, language)
            if (cached?.dataFingerprint == fingerprint) {
                // Тёплый старт из кэша: пользователь не видит Loading, если ничего не менялось.
                setState(kind, StatsCardExplanationState.Ready(cached.text))
                continue
            }
            if (!hasProvider) {
                // Молча, без диалогов — это фоновый процесс, показывать их некому.
                setState(kind, StatsCardExplanationState.Unavailable)
                continue
            }
            // Stale-while-revalidate: пока генерируем свежий текст, показываем устаревший.
            setState(
                kind,
                if (cached != null) StatsCardExplanationState.Ready(cached.text)
                else StatsCardExplanationState.Loading,
            )
            generateWithRetry(kind, snapshot, language, fingerprint, staleText = cached?.text)
        }
    }

    private suspend fun generateWithRetry(
        kind: StatsCardKind,
        snapshot: StatsCardSnapshot,
        language: AppLanguage,
        fingerprint: String,
        staleText: String?,
    ) {
        repeat(MAX_ATTEMPTS) { attempt ->
            when (val outcome = explanationUseCase.explain(snapshot, language)) {
                is StatsCardExplanationUseCase.Outcome.Explained -> {
                    cacheStore.write(kind, language, outcome.text, fingerprint)
                    setState(kind, StatsCardExplanationState.Ready(outcome.text))
                    return
                }
                is StatsCardExplanationUseCase.Outcome.RateLimited -> {
                    if (attempt == MAX_ATTEMPTS - 1) return@repeat
                    val wait = outcome.retryAfterMs.coerceIn(1_000L, MAX_RETRY_WAIT_MS)
                    Log.i(TAG, "$kind rate-limited, retry in ${wait}ms")
                    delay(wait)
                }
                else -> {
                    // NoProvider/Failed: свежего текста не будет; устаревший лучше, чем ничего.
                    if (staleText == null) setState(kind, StatsCardExplanationState.Unavailable)
                    return
                }
            }
        }
        if (staleText == null) setState(kind, StatsCardExplanationState.Unavailable)
    }

    private fun setState(kind: StatsCardKind, value: StatsCardExplanationState) {
        _state.value = _state.value + (kind to value)
    }

    private companion object {
        const val TAG = "StatsExplainCoord"
        const val MAX_ATTEMPTS = 2
        const val MAX_RETRY_WAIT_MS = 60_000L
    }
}
