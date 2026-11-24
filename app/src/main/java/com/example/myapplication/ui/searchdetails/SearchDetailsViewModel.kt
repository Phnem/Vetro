package com.example.myapplication.ui.searchdetails

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.AppLanguagePrefs
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.domain.search.AddFromApiUseCase
import com.example.myapplication.domain.search.isDuplicate
import com.example.myapplication.domain.search.reportUserAdded
import com.example.myapplication.domain.search.toDuplicateProbe
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Экран «Детали из поиска» (dev-флаг `SEARCH_DETAILS_PREVIEW`): тайтл показан до записи в
 * коллекцию, добавляется тем же [AddFromApiUseCase], что и кнопка на карточке поиска.
 */
class SearchDetailsViewModel(
    key: String,
    private val addFromApiUseCase: AddFromApiUseCase,
    private val repository: AnimeRepository,
    settingsDataStore: DataStore<Preferences>,
) : ViewModel() {

    sealed interface AddState {
        data object Idle : AddState
        data object Adding : AddState

        /** Тайтл лежит в коллекции под этим id — дальше экран показывает обычные «Детали». */
        data class Done(val animeId: String) : AddState
        data class Failed(val message: String?) : AddState
    }

    val result: ApiSearchResult? = SearchDetailsHolder.get(key)

    val language: StateFlow<AppLanguage> = settingsDataStore.data
        .map { AppLanguagePrefs.from(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppLanguage.EN)

    private val existing: Anime? = result?.let { findInCollection(it, loose = false) }

    /** Тайтл уже был в коллекции, когда экран открыли: анимация добавления не нужна. */
    val startedInCollection: Boolean = existing != null

    private val _addState = MutableStateFlow<AddState>(
        existing?.let { AddState.Done(it.id) } ?: AddState.Idle,
    )
    val addState: StateFlow<AddState> = _addState.asStateFlow()

    fun add() {
        val r = result ?: return
        val now = _addState.value
        if (now !is AddState.Idle && now !is AddState.Failed) return
        _addState.value = AddState.Adding
        viewModelScope.launch {
            addFromApiUseCase(r).fold(
                onSuccess = { outcome ->
                    reportUserAdded(r, outcome)
                    // Запись появляется в снимке репозитория чуть позже вызова: ждём её недолго.
                    val added = withTimeoutOrNull(4_000) {
                        var found = findInCollection(r, loose = true)
                        while (found == null) {
                            delay(80)
                            found = findInCollection(r, loose = true)
                        }
                        found
                    }
                    _addState.value = added?.let { AddState.Done(it.id) } ?: AddState.Failed(null)
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _addState.value = AddState.Failed(e.message)
                },
            )
        }
    }

    /**
     * Запись коллекции, которую этот результат продублировал бы (то же правило, что при добавлении).
     * [loose] — запасной поиск по названию для типов, у которых правило дубликата не строится.
     */
    private fun findInCollection(r: ApiSearchResult, loose: Boolean): Anime? {
        val all = repository.getAllAnimeSnapshot()
        val probe = r.toDuplicateProbe()
        val strict = probe?.let { p -> all.firstOrNull { isDuplicate(it, p) } }
        if (strict != null || !loose) return strict
        return all.filter { it.title == r.title }.maxByOrNull { it.dateAdded }
    }
}
