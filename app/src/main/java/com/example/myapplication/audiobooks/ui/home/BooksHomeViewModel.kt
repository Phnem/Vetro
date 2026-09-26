package com.example.myapplication.audiobooks.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.data.BooksCatalog
import com.example.myapplication.audiobooks.data.CachedBook
import com.example.myapplication.audiobooks.data.CachedShelf
import com.example.myapplication.audiobooks.data.CatalogShelf
import com.example.myapplication.audiobooks.data.ContinueItem
import com.example.myapplication.audiobooks.playback.AudiobookLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Дом «Книги»: «Продолжить» из БД, витрина и полки из кэша каталога с обновлением в фоне. */
@UnstableApi
class BooksHomeViewModel(
    private val catalog: BooksCatalog,
    private val repository: AudiobookRepository,
    private val launcher: AudiobookLauncher,
) : ViewModel() {

    val shelves: List<CatalogShelf> = catalog.shelves

    val continueItems: StateFlow<List<ContinueItem>> = repository.continueListening()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _content = MutableStateFlow<Map<String, CachedShelf>>(emptyMap())
    /** Ключ — `BooksCatalog.SHOWCASE_KEY` или ключ полки. */
    val content: StateFlow<Map<String, CachedShelf>> = _content.asStateFlow()

    /** Полки, которые не удалось загрузить: карточка говорит об этом и повторяет по нажатию. */
    private val _failed = MutableStateFlow<Set<String>>(emptySet())
    val failed: StateFlow<Set<String>> = _failed.asStateFlow()

    private val _weekListenedMs = MutableStateFlow(0L)
    val weekListenedMs: StateFlow<Long> = _weekListenedMs.asStateFlow()

    private val _launch = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val launch: StateFlow<LaunchState> = _launch.asStateFlow()

    init {
        viewModelScope.launch {
            _weekListenedMs.value = repository.listenedSince(System.currentTimeMillis() - WEEK_MS)
        }
        viewModelScope.launch {
            val keys = listOf(BooksCatalog.SHOWCASE_KEY) + shelves.map { it.key }
            // Сначала всё из кэша — дом появляется целиком; потом по очереди обновляем устаревшее.
            keys.forEach { key -> catalog.cached(key)?.let { put(key, it) } }
            keys.forEach { key ->
                val cached = _content.value[key]
                if (cached == null || catalog.isStale(cached)) refresh(key)
            }
        }
    }

    fun retry(key: String) {
        viewModelScope.launch { refresh(key) }
    }

    private suspend fun refresh(key: String) {
        _failed.update { it - key }
        val fresh = if (key == BooksCatalog.SHOWCASE_KEY) catalog.refreshShowcase() else catalog.refreshShelf(key)
        if (fresh != null) put(key, fresh) else if (_content.value[key] == null) _failed.update { it + key }
    }

    fun play(book: CachedBook) {
        if (!book.playable || _launch.value is LaunchState.Starting) return
        start(book.key) { launcher.play(book.toSourceBook()) }
    }

    fun resume(item: ContinueItem) {
        val variant = item.variantId?.value ?: return
        val source = variant.substringBefore(':')
        val key = variant.substringAfter(':')
        val book = CachedBook(source, key, item.title, item.authors, item.narrators, item.coverUrl, null, 1)
        start(key) { launcher.play(book.toSourceBook(), expand = false) }
    }

    fun consumeLaunch() {
        if (_launch.value !is LaunchState.Starting) _launch.value = LaunchState.Idle
    }

    private fun start(key: String, block: suspend () -> AudiobookLauncher.Result) {
        _launch.value = LaunchState.Starting(key)
        viewModelScope.launch {
            _launch.value = when (runCatching { block() }.getOrNull()) {
                AudiobookLauncher.Result.Started -> LaunchState.Started
                AudiobookLauncher.Result.Restricted -> LaunchState.Restricted
                else -> LaunchState.Unavailable
            }
        }
    }

    private fun put(key: String, shelf: CachedShelf) = _content.update { it + (key to shelf) }

    sealed interface LaunchState {
        data object Idle : LaunchState
        data class Starting(val key: String) : LaunchState
        data object Started : LaunchState
        data object Restricted : LaunchState
        data object Unavailable : LaunchState
    }

    private companion object {
        const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
