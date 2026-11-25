package com.example.myapplication.audiobooks.ui.details

import com.example.myapplication.utils.runSuspendCatching
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.CancellationException
import com.example.myapplication.audiobooks.domain.enrichment.BookCoverFinder
import com.example.myapplication.audiobooks.domain.enrichment.BookWorkEnrichment
import com.example.myapplication.audiobooks.domain.enrichment.BookWorkInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.data.AudiobookRepository.Companion.normalize
import com.example.myapplication.audiobooks.data.BookCoverManager
import com.example.myapplication.audiobooks.data.BookPeopleOverrides
import com.example.myapplication.audiobooks.domain.enrichment.CoverOption
import com.example.myapplication.audiobooks.data.OpenedBook
import com.example.myapplication.audiobooks.data.SavedProgress
import com.example.myapplication.audiobooks.data.LOCAL_SOURCE_ID
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.source.AudiobookSearch
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.domain.source.SourceId
import kotlinx.coroutines.CoroutineScope
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
    /** Произведение за озвучкой (Open Library): оригинал, год, обложка и описание, если их нет у сайта. */
    private val workEnrichment: BookWorkEnrichment? = null,
    /** Обложка из каталогов (в том числе по английскому названию), если сайт и база её не дали. */
    private val coverFinder: BookCoverFinder? = null,
    /** Ручная смена обложки из меню «⋯»; без него пункт показывает пустой выбор. */
    private val covers: BookCoverManager? = null,
    /** Манифест своей папки: из него берутся главы и их длины. */
    private val resolver: ManifestResolver? = null,
    private val language: suspend () -> AppLanguage = { AppLanguage.RU },
) : ViewModel() {

    private val _state = MutableStateFlow(BookDetailsState(source = sourceId, key = key))
    val state: StateFlow<BookDetailsState> = _state.asStateFlow()

    val favorite: StateFlow<Boolean> = _state
        .map { it.opened?.workId }
        .flatMapLatest { id -> id?.let(repository::isFavorite) ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var loadJob: Job? = null
    private var coverJob: Job? = null
    private var coverSearchJob: Job? = null
    private var noticeSeq = 0

    // ---------- Меню «⋯» ----------

    fun openPeople() = _state.update { it.copy(sheet = BookSheet.People) }

    fun closeSheet() = _state.update { it.copy(sheet = BookSheet.None) }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    private fun notice(kind: BookNoticeKind, arg: String = "") =
        _state.update { it.copy(notice = BookNotice(kind, arg, ++noticeSeq)) }

    /** Исправленные автор и чтец: пишутся в базу, и книга сама переезжает на полку нужного автора. */
    fun savePeople(authorsRaw: String, narratorsRaw: String) {
        val opened = _state.value.opened ?: return
        val authors = BookPeopleOverrides.parse(authorsRaw)
        val narrators = BookPeopleOverrides.parse(narratorsRaw)
        viewModelScope.launch {
            repository.setPeople(opened.workId, opened.narrationId, authors, narrators)
            _state.update { s ->
                s.copy(
                    details = s.details?.let { d -> d.copy(book = d.book.copy(authors = authors, narrators = narrators)) },
                    sheet = BookSheet.None,
                )
            }
            notice(BookNoticeKind.PeopleSaved)
        }
    }

    /** «Прослушано» / «Не прослушано»: переключатель по сохранённой позиции. */
    fun toggleListened() {
        val s = _state.value
        val opened = s.opened ?: return
        val finished = s.progress?.finished != true
        val total = s.details?.chapterDurationsSec?.takeIf { d -> d.isNotEmpty() && d.all { it != null } }
            ?.sumOf { it!! }?.times(1000) ?: s.details?.book?.durationSec?.times(1000)
        viewModelScope.launch {
            repository.markFinished(opened, finished, total)
            val progress = repository.progress(opened.narrationId)
            _state.update { it.copy(progress = progress) }
            notice(if (finished) BookNoticeKind.ListenedMarked else BookNoticeKind.ListenedCleared)
        }
    }

    /** Этот сайт отдал не ту книгу: помечаем его копию негодной и открываем ту же книгу на другом сайте. */
    fun rejectSource() {
        val s = _state.value
        val opened = s.opened ?: return
        // У своей папки сайта нет — отвергать нечего.
        if (s.source == LOCAL_SOURCE_ID) return
        val source = sources.firstOrNull { it.id.value == s.source } ?: return
        viewModelScope.launch {
            repository.markVariant(opened.variantId, false)
            val alt = fallbackFor(source)
            if (alt == null) {
                notice(BookNoticeKind.NoOtherSource)
            } else {
                val name = sources.firstOrNull { it.id == alt.ref.source }?.displayName.orEmpty()
                load(alt.ref.source.value, alt.ref.key, allowFallback = false)
                notice(BookNoticeKind.SwitchedSource, name)
            }
        }
    }

    // ---------- Обложка ----------

    fun openCovers() {
        _state.update { it.copy(sheet = BookSheet.Cover, coverPicker = CoverPicker(loading = true)) }
        loadCovers(null)
    }

    fun searchCovers(query: String) = loadCovers(query.trim().takeIf { it.isNotEmpty() })

    private fun loadCovers(query: String?) {
        val workId = _state.value.opened?.workId ?: return
        coverSearchJob?.cancel()
        _state.update { it.copy(coverPicker = it.coverPicker.copy(loading = true, query = query.orEmpty())) }
        coverSearchJob = viewModelScope.launch {
            val items = covers?.options(workId, query).orEmpty()
            _state.update { it.copy(coverPicker = it.coverPicker.copy(items = items, loading = false)) }
        }
    }

    fun pickCover(url: String) {
        val workId = _state.value.opened?.workId ?: return
        val manager = covers ?: return
        _state.update { it.copy(coverPicker = it.coverPicker.copy(saving = true)) }
        viewModelScope.launch { finishCover(manager.choose(workId, url)) }
    }

    fun importCover(uri: android.net.Uri) {
        val workId = _state.value.opened?.workId ?: return
        val manager = covers ?: return
        _state.update { it.copy(coverPicker = it.coverPicker.copy(saving = true)) }
        viewModelScope.launch { finishCover(manager.import(workId, uri)) }
    }

    private fun finishCover(result: BookCoverManager.Result) {
        val ok = result is BookCoverManager.Result.Changed || result is BookCoverManager.Result.ChangedOnDevice
        _state.update {
            it.copy(
                coverPicker = it.coverPicker.copy(saving = false),
                sheet = if (ok) BookSheet.None else it.sheet,
            )
        }
        notice(
            when (result) {
                is BookCoverManager.Result.Changed ->
                    if (result.coverUrl.startsWith("collection-attachment://")) BookNoticeKind.CoverImported else BookNoticeKind.CoverChanged
                is BookCoverManager.Result.ChangedOnDevice -> BookNoticeKind.CoverImportedLocal
                else -> BookNoticeKind.CoverFailed
            },
        )
    }

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
            val result = if (details.book.ref.source.value == LOCAL_SOURCE_ID) {
                // Своя папка: страницы у сайта нет, играем её напрямую.
                _state.value.opened?.let { launcher.playLocal(it, startChapter = fromChapter) }
                    ?: AudiobookLauncher.Result.Unavailable
            } else {
                launcher.play(details.book, startChapter = fromChapter)
            }
            _state.update { it.copy(launching = false, launchFailed = result != AudiobookLauncher.Result.Started) }
        }
    }

    fun toggleFavorite() {
        val work = _state.value.opened?.workId ?: return
        viewModelScope.launch { repository.setFavorite(work, !favorite.value) }
    }

    private fun load(sourceId: String, key: String, allowFallback: Boolean = true) {
        if (sourceId == LOCAL_SOURCE_ID) {
            loadLocal(key)
            return
        }
        val source = sources.firstOrNull { it.id.value == sourceId } ?: run {
            _state.update { it.copy(source = sourceId, key = key, loading = false, error = true) }
            return
        }
        loadJob?.cancel()
        _state.update {
            it.copy(source = sourceId, sourceName = source.displayName, key = key, loading = true, error = false,
                restricted = false, launchFailed = false, chapterTitles = emptyList())
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
            // Автор и чтец — как их поправил пользователь, а не как написал сайт.
            val shown = details.copy(book = repository.withPeople(opened, details.book))
            _state.update { it.copy(loading = false, details = shown, opened = opened, progress = progress) }
            followUp(details, opened) {
                details.authorShelfId?.let { id ->
                    (source.shelf(id) as? SourceResult.Ok)?.value.orEmpty()
                        .filter { normalize(it.title) != normalize(details.book.title) }
                        .distinctBy { normalize(it.title) }
                        .take(AUTHOR_BOOKS)
                }.orEmpty()
            }
        }
    }

    /**
     * Своя папка: сайта у неё нет, поэтому страница собирается из БД (теги файлов и добор по названию
     * и автору, сделанный при добавлении) и из самих файлов (главы и их длины). Чего не знаем —
     * секции просто нет; пустой, но настоящей страницы лучше, чем никакой.
     */
    private fun loadLocal(key: String) {
        loadJob?.cancel()
        _state.update {
            it.copy(source = LOCAL_SOURCE_ID, sourceName = "", key = key, loading = true, error = false,
                restricted = false, launchFailed = false)
        }
        loadJob = viewModelScope.launch {
            val variant = VariantId(key)
            val record = repository.localBook(variant)
            if (record == null) {
                _state.update { it.copy(loading = false, error = true) }
                return@launch
            }
            // Папку могли убрать или отозвать доступ: тогда остаются итоги из БД без списка глав.
            val manifest = resolver?.let { r -> runSuspendCatching { r.manifest(variant) }.getOrNull() }
            val chapters = manifest?.chapters.orEmpty()
            val durations: List<Long?> = when {
                chapters.isNotEmpty() -> chapters.map { it.durationMs?.div(1000) }
                manifest != null -> manifest.tracks.map { it.durationMs?.div(1000) }
                else -> emptyList()
            }
            val trackMs = manifest?.tracks?.map { it.durationMs }
            val totalMs = trackMs?.takeIf { l -> l.isNotEmpty() && l.all { it != null } }?.sumOf { it!! } ?: record.durationMs
            val book = SourceBook(
                ref = SourceBookRef(SourceId(LOCAL_SOURCE_ID), key),
                title = record.title,
                authors = record.authors,
                narrators = record.narrators,
                coverUrl = record.coverUrl,
                durationSec = totalMs?.div(1000),
                genres = record.genres,
                year = record.year,
            )
            val details = SourceBookDetails(book = book, description = record.description, chapterDurationsSec = durations)
            val progress = repository.progress(record.opened.narrationId)
            _state.update {
                it.copy(
                    loading = false,
                    details = details,
                    opened = record.opened,
                    progress = progress,
                    chapterTitles = chapters.map { c -> c.title },
                )
            }
            // Других книг автора «на сайте» у папки нет — секция не показывается.
            followUp(details, record.opened) { emptyList() }
        }
    }

    /**
     * Остальное — после основной карточки и независимо: не нашлось — секции просто нет. Обложка
     * живёт в БД (смена из меню и находка из каталогов видны сразу), произведение за озвучкой и
     * другие озвучки подтягиваются по названию и автору.
     */
    private suspend fun CoroutineScope.followUp(
        details: SourceBookDetails,
        opened: OpenedBook,
        authorBooks: suspend () -> List<SourceBook>,
    ) {
        coverJob?.cancel()
        coverJob = viewModelScope.launch {
            repository.coverUrl(opened.workId).collect { url -> _state.update { it.copy(cover = url) } }
        }
        // Обложки нет — ищем в каталогах; находка ложится в произведение и видна на полках.
        if (coverFinder != null && repository.coverInfo(opened.workId)?.coverUrl.isNullOrBlank()) {
            launch {
                runSuspendCatching { coverFinder.first(details.book.title, details.book.authors) }.getOrNull()
                    ?.let { runSuspendCatching { repository.setCover(opened.workId, it) } }
            }
        }
        val narrations = async { narrationsOf(details) }
        val work = async {
            workEnrichment?.let { enrichment ->
                runSuspendCatching {
                    enrichment.lookup(details.book.title, details.book.authors, details.description, language())
                }.getOrNull()
            }
        }
        val byAuthor = async { authorBooks() }
        val info = work.await()
        _state.update { it.copy(narrations = narrations.await(), authorBooks = byAuthor.await(), work = info) }
        // Подробности каталога (жанры, серия, описание, оригинал) остаются в произведении: видны на полках и без сети.
        info?.catalog?.let { catalog ->
            try {
                repository.applyCatalogDetails(opened.workId, catalog, english = language() != AppLanguage.RU)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // не получилось записать — на экране подробности всё равно есть
            }
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
    val work: BookWorkInfo? = null,
    /** Обложка произведения из базы: после смены из меню она важнее той, что отдал сайт. */
    val cover: String? = null,
    val sheet: BookSheet = BookSheet.None,
    val coverPicker: CoverPicker = CoverPicker(),
    val notice: BookNotice? = null,
    /** Названия глав из файлов своей папки; у сайтов пусто — там главы называются по номеру. */
    val chapterTitles: List<String> = emptyList(),
)

/** Какая шторка меню «⋯» открыта. */
enum class BookSheet { None, People, Cover }

/** Список обложек для выбора: [items] с подписями, [query] — то, что искал пользователь. */
data class CoverPicker(
    val items: List<CoverOption> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val query: String = "",
)

enum class BookNoticeKind {
    PeopleSaved, ListenedMarked, ListenedCleared, CoverChanged, CoverImported, CoverImportedLocal, CoverFailed,
    NoOtherSource, SwitchedSource,
}

/** Короткое сообщение под меню; [seq] отличает повтор того же сообщения от старого. */
data class BookNotice(val kind: BookNoticeKind, val arg: String, val seq: Int)
