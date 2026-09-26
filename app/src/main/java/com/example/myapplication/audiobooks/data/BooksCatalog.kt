package com.example.myapplication.audiobooks.data

import com.example.myapplication.audiobooks.data.remote.KnigavuheMetadata
import com.example.myapplication.audiobooks.domain.source.AudiobookSearch
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.WorkMatch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceId
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.data.local.JsonMapFileStore
import kotlinx.serialization.Serializable

/**
 * Что показывает дом «Книги», пока своей библиотеки нет (решение Q10): витрина из семи книг
 * пользователя и полки-подборки источников. Всё кэшируется в файле: дом открывается с прошлой
 * выдачей мгновенно и обновляется в фоне, если она старше [TTL_MS].
 */
class BooksCatalog(
    private val sources: List<AudiobookSource>,
    private val store: JsonMapFileStore<CachedShelf>,
    /** Каталог только для метаданных: обложка и чтец для книг, которых нет у звуковых источников. */
    private val metadata: KnigavuheMetadata? = null,
    /** Поиск по всем сайтам сразу; null — по [sources] последовательно (тесты). */
    private val search: AudiobookSearch? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Полки всех источников по порядку; ключ — `"<source>/<shelfId>"`. */
    val shelves: List<CatalogShelf> = sources.flatMap { source ->
        source.shelves.map { CatalogShelf("${source.id.value}/${it.id}", it.title, it.subtitle) }
    }

    suspend fun cached(key: String): CachedShelf? {
        store.ensureLoaded()
        return store[key]
    }

    fun isStale(shelf: CachedShelf): Boolean = nowMs() - shelf.fetchedAt > TTL_MS

    /** Свежая выдача полки из сети; при сбое — null, кэш не трогаем. */
    suspend fun refreshShelf(key: String): CachedShelf? {
        val source = sources.firstOrNull { key.startsWith("${it.id.value}/") } ?: return null
        val books = (source.shelf(key.substringAfter('/')) as? SourceResult.Ok)?.value ?: return null
        return save(key, books.map { CachedBook.of(it, narrations = 1) })
    }

    /**
     * Витрина: каждую книгу ищем на всех сайтах сразу по названию и фамилии автора. Из совпадений
     * берём озвучку с самого надёжного источника (порядок источников), при равенстве — самую полную;
     * запомненное число озвучек — по разным чтецам. Остальные копии — запасные: не запустится на
     * выбранном сайте, запуск уйдёт к ним. Обложку берём у любого сайта, где она есть. Не найденная
     * книга остаётся в витрине без источника — честно, без Play.
     */
    suspend fun refreshShowcase(): CachedShelf? = coroutineScope {
        val books = SHOWCASE.map { pick -> async { showcaseBook(pick) } }.awaitAll()
        // Сеть легла целиком — не затираем прошлую удачную витрину пустышками.
        if (books.any { it.second }) save(SHOWCASE_KEY, books.map { it.first }) else null
    }

    private suspend fun showcaseBook(pick: Pick): Pair<CachedBook, Boolean> {
        val candidates = (search?.searchAll(pick.title) ?: sources.flatMap { source ->
            (source.search(pick.title) as? SourceResult.Ok)?.value.orEmpty()
        }).filter { pick.matches(it) }
        val rank: (SourceBook) -> Int = { b -> search?.rank(b.ref.source) ?: sources.indexOfFirst { it.id == b.ref.source } }
        val best = candidates.sortedWith(compareBy(rank).thenByDescending { it.durationSec ?: 0L }).firstOrNull()
        val voices = candidates.map { WorkMatch.words(it.narrators).sorted().joinToString(" ") }.filter { it.isNotEmpty() }
            .distinct().size.coerceAtLeast(if (candidates.isEmpty()) 0 else 1)
        val cover = best?.coverUrl ?: candidates.firstNotNullOfOrNull { it.coverUrl }
        // Настоящая обложка нужна и книге без звука: берём её из каталога метаданных.
        val meta = if (cover == null) metadata?.search(pick.title)?.firstOrNull { pick.matchesMeta(it) } else null
        val book = best?.let { CachedBook.of(it, narrations = voices).copy(coverUrl = cover ?: meta?.coverUrl) }
            ?: CachedBook(source = "", key = "", title = pick.title, authors = listOf(pick.author),
                narrators = meta?.narrators.orEmpty(), coverUrl = meta?.coverUrl, durationSec = null, narrations = 0)
        return book to (best != null || meta != null)
    }

    private suspend fun save(key: String, books: List<CachedBook>): CachedShelf {
        val shelf = CachedShelf(books, nowMs())
        store.update { it + (key to shelf) }
        return shelf
    }

    /** Одна из книг витрины: название на языке источника и фамилия для проверки автора. */
    private data class Pick(val title: String, val author: String, val surname: String) {
        fun matches(book: SourceBook): Boolean = matches(book.title, book.authors)

        fun matchesMeta(hit: KnigavuheMetadata.Hit): Boolean = matches(hit.title, hit.authors)

        private fun matches(bookTitle: String, authors: List<String>): Boolean =
            WorkMatch.titleKey(bookTitle) == WorkMatch.titleKey(title) &&
                authors.any { surname in AudiobookRepository.normalize(it).split(' ') }
    }

    companion object {
        // v3: витрина со всех сайтов — старая (только Aknigi24) пересобирается сразу.
        const val SHOWCASE_KEY = "showcase_v3"
        private const val TTL_MS = 12 * 60 * 60 * 1000L

        /** Семь книг пользователя (issues/39), трилогия Лю Цысиня — по порядку. */
        private val SHOWCASE = listOf(
            Pick("Задача трёх тел", "Лю Цысинь", "цысинь"),
            Pick("Тёмный лес", "Лю Цысинь", "цысинь"),
            Pick("Вечная жизнь Смерти", "Лю Цысинь", "цысинь"),
            Pick("Ложная слепота", "Питер Уоттс", "уоттс"),
            Pick("Марсианин", "Энди Вейер", "вейер"),
            Pick("Солярис", "Станислав Лем", "лем"),
            Pick("Свидание с Рамой", "Артур Кларк", "кларк"),
        )
    }
}

data class CatalogShelf(val key: String, val title: String, val subtitle: String)

@Serializable
data class CachedShelf(val books: List<CachedBook>, val fetchedAt: Long)

@Serializable
data class CachedBook(
    val source: String,
    val key: String,
    val title: String,
    val authors: List<String>,
    val narrators: List<String>,
    val coverUrl: String?,
    val durationSec: Long?,
    /** Сколько озвучек этой книги нашлось; 0 — источника нет. */
    val narrations: Int,
) {
    val playable: Boolean get() = source.isNotEmpty()

    fun toSourceBook(): SourceBook = SourceBook(
        ref = SourceBookRef(SourceId(source), key),
        title = title, authors = authors, narrators = narrators, coverUrl = coverUrl, durationSec = durationSec,
    )

    companion object {
        fun of(book: SourceBook, narrations: Int) = CachedBook(
            source = book.ref.source.value, key = book.ref.key, title = book.title, authors = book.authors,
            narrators = book.narrators, coverUrl = book.coverUrl, durationSec = book.durationSec,
            narrations = narrations,
        )
    }
}
