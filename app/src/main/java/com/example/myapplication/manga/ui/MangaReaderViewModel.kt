package com.example.myapplication.manga.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.manga.data.ChapterReadingProgress
import com.example.myapplication.manga.data.MangaReaderMode
import com.example.myapplication.manga.data.MangaBindingStore
import com.example.myapplication.manga.data.MangaReadingStore
import com.example.myapplication.manga.data.PageDirection
import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.domain.MangaPage
import com.example.myapplication.manga.domain.MangaPagePrefetcher
import com.example.myapplication.manga.download.MangaPageResolver
import com.example.myapplication.manga.translate.ChapterTranslationContext
import com.example.myapplication.manga.translate.ChapterTranslationHost
import com.example.myapplication.manga.translate.ChapterTranslationPlan
import com.example.myapplication.manga.translate.MangaPageTranslationService
import com.example.myapplication.manga.translate.MangaTranslateSettings
import com.example.myapplication.manga.translate.MangaWorkContextProvider
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface MangaReaderUiState {
    data object Loading : MangaReaderUiState
    data class Ready(
        val chapter: MangaChapter,
        val pages: List<MangaPage>,
        /** Страница, с которой открываем: сохранённый прогресс, иначе первая. */
        val startPage: Int,
        /**
         * Докрутка внутри [startPage], 0..1 её высоты. Осмысленна только в вебтун-ленте;
         * постраничный режим её игнорирует — там страница и так занимает ровно экран.
         */
        val startOffsetFraction: Float,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
        /** Не null, когда глава на другом языке и её страницы переводятся на лету. */
        val translation: ChapterTranslationHost? = null,
    ) : MangaReaderUiState

    data class Error(val message: String) : MangaReaderUiState
}

/**
 * Ридер одной главы с переходом на соседние.
 *
 * Список глав берётся из [MangaChapterHandoff] — ридер принципиально не ходит в источник за
 * оглавлением: он открывается из Details, где список уже загружен.
 */
class MangaReaderViewModel(
    private val animeId: String,
    initialChapterKey: String,
    private val pageResolver: MangaPageResolver,
    private val readingStore: MangaReadingStore,
    private val prefetcher: MangaPagePrefetcher,
    private val translateSettings: MangaTranslateSettings,
    private val bindings: MangaBindingStore,
    private val workContext: MangaWorkContextProvider,
    private val translationService: MangaPageTranslationService,
) : ViewModel() {

    /** Оглавление, с которым открыли ридер: его же показывает шторка глав в доке. */
    val chapters: List<MangaChapter> = MangaChapterHandoff.chapters(animeId)

    private val _state = MutableStateFlow<MangaReaderUiState>(MangaReaderUiState.Loading)
    val state: StateFlow<MangaReaderUiState> = _state.asStateFlow()

    private var currentIndex: Int = chapters.indexOfFirst { it.key == initialChapterKey }
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var prefetchJob: Job? = null

    /** Последняя записанная страница: по ней отличаем смену страницы от докрутки внутри неё. */
    private var lastSavedPage: Int = -1

    val readerMode: StateFlow<MangaReaderMode> = readingStore.readerModeFlow(animeId)
        // Стартовое значение до первой эмиссии потока — тот же дефолт, что в хранилище:
        // вертикальная лента. Разойдись они, ридер моргал бы постраничным режимом на открытии.
        .stateIn(viewModelScope, SharingStarted.Eagerly, MangaReaderMode.Webtoon)

    val pageDirection: StateFlow<PageDirection> = readingStore.directionFlow(animeId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, PageDirection.Rtl)

    val cropBorders: StateFlow<Boolean> = readingStore.cropBordersFlow(animeId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Отметки глав для шторки оглавления: те же, что видит список глав в Details. */
    val chapterProgress: StateFlow<Map<String, ChapterReadingProgress>> =
        readingStore.progressFlow(animeId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        load()
    }

    fun load() {
        val chapter = chapters.getOrNull(currentIndex)
        if (chapter == null) {
            _state.value = MangaReaderUiState.Error(ERROR_NO_CHAPTER)
            return
        }
        loadJob?.cancel()
        prefetchJob?.cancel()
        lastSavedPage = -1
        loadJob = viewModelScope.launch {
            _state.value = MangaReaderUiState.Loading
            val pages = pageResolver.pages(chapter)
            if (pages.isEmpty()) {
                _state.value = MangaReaderUiState.Error(ERROR_NO_PAGES)
                return@launch
            }
            val saved = readingStore.chapterProgress(animeId, chapter.key)?.takeIf { !it.read }
            // Дочитанную главу открываем сначала: продолжать с последней страницы бессмысленно.
            val startPage = saved?.pageIndex?.coerceIn(0, pages.lastIndex) ?: 0
            val translation = translationHostFor(chapter)
            _state.value = MangaReaderUiState.Ready(
                chapter = chapter,
                pages = pages,
                startPage = startPage,
                startOffsetFraction = saved?.scrollOffsetFraction ?: 0f,
                hasPrevious = currentIndex > 0,
                hasNext = currentIndex < chapters.lastIndex,
                translation = translation,
            )
            translation?.let { translationService.prefetch(it.context, pages, startPage) }
            // Первый прогрев — сразу после открытия: ждать свайпа значит показать спиннер на нём.
            prefetch(pages, startPage)
        }
    }

    /**
     * Прогрев страниц вперёд. Каждый вызов отменяет предыдущий: читатель, пролиставший главу
     * насквозь, не должен тащить за собой очередь запросов на страницы, мимо которых уже прошёл.
     */
    private fun prefetch(pages: List<MangaPage>, fromPage: Int) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            prefetcher.prefetch(
                pages = pages,
                fromPage = fromPage,
                nextChapter = chapters.getOrNull(currentIndex + 1),
            )
        }
    }

    /**
     * [offsetFraction] — докрутка внутри страницы из вебтун-ленты; постраничный режим шлёт `null`.
     *
     * Смена страницы пишется сразу, докрутка внутри той же страницы — с задержкой: в ленте она
     * приходит десятками за один жест, и без паузы каждый скролл превращался бы в очередь записей
     * в DataStore. Отмена предыдущей задачи делает из этого обычный trailing-debounce.
     */
    fun onPageChanged(pageIndex: Int, offsetFraction: Float? = null) {
        val ready = _state.value as? MangaReaderUiState.Ready ?: return
        val pageChanged = pageIndex != lastSavedPage
        lastSavedPage = pageIndex
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (!pageChanged) delay(OFFSET_SAVE_DELAY_MS)
            readingStore.saveProgress(
                animeId = animeId,
                chapterKey = ready.chapter.key,
                pageIndex = pageIndex,
                pageCount = ready.pages.size,
                scrollOffsetFraction = offsetFraction,
            )
        }
        if (pageChanged) {
            prefetch(ready.pages, pageIndex)
            // Страницы вперёд переводятся заранее, чтобы к свайпу текст уже был по-русски.
            ready.translation?.let { translationService.prefetch(it.context, ready.pages, pageIndex + 1) }
        }
    }

    /**
     * Нужен ли глава перевод: функция включена и доступна, глава на языке-оригинале, а читатель
     * выбрал другой язык. Иначе страницы идут как есть.
     */
    private suspend fun translationHostFor(chapter: MangaChapter): ChapterTranslationHost? {
        if (!translateSettings.ui.value.active) return null
        bindings.ensureLoaded()
        val preferred = bindings.bindingFor(animeId)?.preferredLanguage
        if (!ChapterTranslationPlan.needsTranslation(chapter, preferred, autoTranslate = true)) return null
        val target = when (preferred) {
            "ru" -> AppLanguage.RU
            "en" -> AppLanguage.EN
            else -> return null
        }
        return ChapterTranslationHost(
            translationService,
            ChapterTranslationContext(
                sourceId = chapter.sourceId.value,
                chapterKey = chapter.key,
                target = target,
                work = { workContext.contextFor(animeId) },
                // Японская манга читается справа налево, как бы ни листал читатель.
                rightToLeft = chapter.language == "ja",
                sourceLanguage = chapter.language ?: "ja",
            ),
        )
    }

    fun openNext() = moveBy(1)

    fun openPrevious() = moveBy(-1)

    /** Переход по оглавлению. Открытая глава повторно не перезагружается. */
    fun openChapter(chapterKey: String) {
        val target = chapters.indexOfFirst { it.key == chapterKey }
        if (target < 0 || target == currentIndex) return
        currentIndex = target
        load()
    }

    fun toggleCropBorders() {
        viewModelScope.launch { readingStore.setCropBorders(animeId, !cropBorders.value) }
    }

    /**
     * Режим и направление меняются одной кнопкой-циклом, поэтому пишем их вместе.
     * Направление — первым: при переходе «вебтун → классика» пейджера ещё нет, и он соберётся
     * ровно один раз, уже с нужным направлением.
     */
    fun setLayout(mode: MangaReaderMode, direction: PageDirection) {
        viewModelScope.launch {
            if (direction != pageDirection.value) readingStore.setDirection(animeId, direction)
            if (mode != readerMode.value) readingStore.setReaderMode(animeId, mode)
        }
    }

    private fun moveBy(delta: Int) {
        val target = currentIndex + delta
        if (target !in chapters.indices) return
        currentIndex = target
        load()
    }

    private companion object {
        const val ERROR_NO_CHAPTER = "chapter_missing"
        const val ERROR_NO_PAGES = "pages_empty"

        /** Пауза перед записью докрутки: короче любой осмысленной остановки взгляда на странице. */
        const val OFFSET_SAVE_DELAY_MS = 400L
    }
}
