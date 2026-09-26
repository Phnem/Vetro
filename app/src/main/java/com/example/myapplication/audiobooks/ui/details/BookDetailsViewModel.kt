package com.example.myapplication.audiobooks.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.data.AudiobookRepository.Companion.normalize
import com.example.myapplication.audiobooks.data.OpenedBook
import com.example.myapplication.audiobooks.data.SavedProgress
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookDetails
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.audiobooks.playback.AudiobookLauncher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Страница книги (AB-31): детали озвучки у источника, другие озвучки того же произведения и другие
 * книги автора. Открытая книга сразу записывается в БД — у неё появляются избранное и позиция.
 */
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailsViewModel(
    sourceId: String,
    key: String,
    sources: List<AudiobookSource>,
    private val repository: AudiobookRepository,
    private val launcher: AudiobookLauncher,
) : ViewModel() {

    private val source = sources.firstOrNull { it.id.value == sourceId }
    private val _state = MutableStateFlow(BookDetailsState(key = key))
    val state: StateFlow<BookDetailsState> = _state.asStateFlow()

    val favorite: StateFlow<Boolean> = _state
        .map { it.opened?.workId }
        .flatMapLatest { id -> id?.let(repository::isFavorite) ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var loadJob: Job? = null

    init {
        load(key)
    }

    /** Другая озвучка: та же страница, но детали, главы и позиция — выбранного чтеца. */
    fun selectNarration(book: SourceBook) {
        if (book.ref.key != _state.value.key) load(book.ref.key)
    }

    fun retry() = load(_state.value.key)

    fun listen(fromChapter: Int? = null) {
        val details = _state.value.details ?: return
        val startMs = fromChapter?.let { index ->
            details.chapterDurationsSec.take(index).sumOf { it ?: 0L } * 1000
        }
        _state.update { it.copy(launching = true, launchFailed = false) }
        viewModelScope.launch {
            val result = launcher.play(details.book, startGlobalMs = startMs)
            _state.update { it.copy(launching = false, launchFailed = result != AudiobookLauncher.Result.Started) }
        }
    }

    fun toggleFavorite() {
        val work = _state.value.opened?.workId ?: return
        viewModelScope.launch { repository.setFavorite(work, !favorite.value) }
    }

    private fun load(key: String) {
        val source = source ?: run {
            _state.update { it.copy(loading = false, error = true) }
            return
        }
        loadJob?.cancel()
        _state.update { it.copy(key = key, loading = true, error = false, launchFailed = false) }
        loadJob = viewModelScope.launch {
            val details = when (val r = source.details(SourceBookRef(source.id, key))) {
                is SourceResult.Ok -> r.value
                is SourceResult.Restricted -> {
                    _state.update { it.copy(loading = false, restricted = true) }
                    return@launch
                }
                is SourceResult.Failed -> {
                    _state.update { it.copy(loading = false, error = true) }
                    return@launch
                }
            }
            val opened = repository.saveOpened(source, details)
            val progress = repository.progress(opened.narrationId)
            _state.update { it.copy(loading = false, details = details, opened = opened, progress = progress) }
            // Остальное — после основной карточки и независимо: не нашлось — секции просто нет.
            val narrations = async { narrationsOf(source, details) }
            val byAuthor = async {
                details.authorShelfId?.let { id ->
                    (source.shelf(id) as? SourceResult.Ok)?.value.orEmpty()
                        .filter { normalize(it.title) != normalize(details.book.title) }
                        .distinctBy { normalize(it.title) }
                        .take(AUTHOR_BOOKS)
                }.orEmpty()
            }
            _state.update { it.copy(narrations = narrations.await(), authorBooks = byAuthor.await()) }
        }
    }

    private suspend fun narrationsOf(source: AudiobookSource, details: SourceBookDetails): List<SourceBook> {
        val title = normalize(details.book.title)
        val authorWords = details.book.authors.flatMap { normalize(it).split(' ') }.filter { it.length > 2 }.toSet()
        val hits = (source.search(details.book.title) as? SourceResult.Ok)?.value.orEmpty().filter { hit ->
            normalize(hit.title) == title &&
                hit.authors.any { a -> normalize(a).split(' ').any(authorWords::contains) }
        }
        // Текущая озвучка — всегда в списке и первой.
        return (listOf(details.book) + hits.filter { it.ref.key != details.book.ref.key }).distinctBy { it.ref.key }
    }

    private companion object {
        const val AUTHOR_BOOKS = 12
    }
}

data class BookDetailsState(
    val key: String,
    val loading: Boolean = true,
    val error: Boolean = false,
    val restricted: Boolean = false,
    val details: SourceBookDetails? = null,
    val opened: OpenedBook? = null,
    val progress: SavedProgress? = null,
    val narrations: List<SourceBook> = emptyList(),
    val authorBooks: List<SourceBook> = emptyList(),
    val launching: Boolean = false,
    val launchFailed: Boolean = false,
)
