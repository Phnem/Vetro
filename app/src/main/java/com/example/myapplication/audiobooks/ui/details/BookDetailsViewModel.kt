package com.example.myapplication.audiobooks.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.data.AudiobookRepository.Companion.normalize
import com.example.myapplication.audiobooks.data.OpenedBook
import com.example.myapplication.audiobooks.data.SavedProgress
import com.example.myapplication.audiobooks.domain.source.AudiobookSearch
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.WorkMatch
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
    /** Название из маршрута: по нему ищется та же книга на других сайтах, если этот её не отдал. */
    private val title: String,
    private val sources: List<AudiobookSource>,
    private val repository: AudiobookRepository,
    private val launcher: AudiobookLauncher,
    private val search: AudiobookSearch,
) : ViewModel() {

    private val _state = MutableStateFlow(BookDetailsState(source = sourceId, key = key))
    val state: StateFlow<BookDetailsState> = _state.asStateFlow()

    val favorite: StateFlow<Boolean> = _state
        .map { it.opened?.workId }
        .flatMapLatest { id -> id?.let(repository::isFavorite) ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var loadJob: Job? = null

    init {
        load(sourceId, key)
    }

    /** Другая озвучка (в том числе с другого сайта): та же страница, но детали, главы и позиция — её. */
    fun selectNarration(book: SourceBook) {
        val s = _state.value
        if (book.ref.key != s.key || book.ref.source.value != s.source) load(book.ref.source.value, book.ref.key)
    }

    fun retry() = _state.value.let { load(it.source, it.key) }

    fun listen(fromChapter: Int? = null) {
        val details = _state.value.details ?: return
        _state.update { it.copy(launching = true, launchFailed = false) }
        viewModelScope.launch {
            // Глава — индексом: у части сайтов длины глав известны только после запуска.
            val result = launcher.play(details.book, startChapter = fromChapter)
            _state.update { it.copy(launching = false, launchFailed = result != AudiobookLauncher.Result.Started) }
        }
    }

    fun toggleFavorite() {
        val work = _state.value.opened?.workId ?: return
        viewModelScope.launch { repository.setFavorite(work, !favorite.value) }
    }

    private fun load(sourceId: String, key: String, allowFallback: Boolean = true) {
        val source = sources.firstOrNull { it.id.value == sourceId } ?: run {
            _state.update { it.copy(source = sourceId, key = key, loading = false, error = true) }
            return
        }
        loadJob?.cancel()
        _state.update {
            it.copy(source = sourceId, sourceName = source.displayName, key = key, loading = true, error = false,
                restricted = false, launchFailed = false)
        }
        loadJob = viewModelScope.launch {
            val r = source.details(SourceBookRef(source.id, key))
            val details = when (r) {
                is SourceResult.Ok -> r.value
                is SourceResult.Restricted, is SourceResult.Failed -> {
                    // Этот сайт книгу не отдал (фрагмент ЛитРеса, убрана, сайт лёг) — открываем ту же
                    // книгу на другом сайте, а не страницу «недоступно».
                    val alt = if (allowFallback) fallbackFor(source) else null
                    if (alt != null) {
                        load(alt.ref.source.value, alt.ref.key, allowFallback = false)
                    } else {
                        _state.update {
                            it.copy(loading = false, restricted = r is SourceResult.Restricted, error = r is SourceResult.Failed)
                        }
                    }
                    return@launch
                }
            }
            val opened = repository.saveOpened(source, details)
            val progress = repository.progress(opened.narrationId)
            _state.update { it.copy(loading = false, details = details, opened = opened, progress = progress) }
            // Остальное — после основной карточки и независимо: не нашлось — секции просто нет.
            val narrations = async { narrationsOf(details) }
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

    /** Та же книга на другом сайте, чьи детали открываются; сначала — по приоритету источников. */
    private suspend fun fallbackFor(failed: AudiobookSource): SourceBook? =
        search.sameWork(title, emptyList())
            .filter { it.ref.source != failed.id }
            .sortedBy { search.rank(it.ref.source) }
            .take(3)
            .firstOrNull { alt ->
                sources.firstOrNull { it.id == alt.ref.source }?.details(alt.ref) is SourceResult.Ok
            }

    /**
     * Озвучки произведения со всех сайтов. Один и тот же чтец на нескольких сайтах — одна озвучка:
     * показываем её копию с лучшего источника (остальные — запасные для запуска). Текущая — первой.
     */
    private suspend fun narrationsOf(details: SourceBookDetails): List<SourceBook> {
        val current = details.book
        val hits = search.sameWork(current.title, current.authors)
            .sortedBy { search.rank(it.ref.source) }
        fun voice(b: SourceBook) = WorkMatch.words(b.narrators).sorted().joinToString(" ")
        val currentVoice = voice(current)
        val others = hits
            .filter { it.ref != current.ref && it.narrators.isNotEmpty() && voice(it) != currentVoice }
            .distinctBy(::voice)
        return listOf(current) + others
    }

    private companion object {
        const val AUTHOR_BOOKS = 12
    }
}

data class BookDetailsState(
    val source: String,
    val key: String,
    val sourceName: String = "",
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
