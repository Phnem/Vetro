package com.example.myapplication.ui.secret

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.SecretFolderStore
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.repository.ImageStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Сессия секретной папки. Живёт в Activity, а не в рабочей области: Details поверх неё не должен
 * ни закрывать папку, ни просить биометрию заново. Заново она нужна при каждом новом открытии
 * («засов»), поэтому [reset] вызывается при закрытии и при уходе приложения в фон.
 */
class SecretFolderViewModel(
    localDataSource: AnimeLocalDataSource,
    settingsDataStore: DataStore<Preferences>,
    private val imageStorage: ImageStorageRepository,
) : ViewModel() {

    private val store = SecretFolderStore(settingsDataStore)

    /** Папка раскрыта на весь экран (за засовом или после него). */
    var isOpen by mutableStateOf(false)
        private set

    /** Биометрия пройдена в текущем открытии: содержимое можно показывать. */
    var unlocked by mutableStateOf(false)
        private set

    /**
     * Тайтлы папки. Подписка начинается только вместе с показом содержимого, то есть после
     * засова: пока папка закрыта, список даже не читается.
     */
    val titles: StateFlow<List<Anime>> = combine(localDataSource.observeAllAnime(null), store.ids) { all, ids ->
        all.filter { it.id in ids }
            .sortedWith(compareByDescending<Anime> { it.isFavorite }.thenByDescending { it.dateAdded })
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun markOpen() {
        isOpen = true
    }

    fun unlock() {
        unlocked = true
    }

    fun reset() {
        isOpen = false
        unlocked = false
    }

    fun imagePath(name: String?): String? = name?.let(imageStorage::getImageFilePath)
}
