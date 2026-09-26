package com.example.myapplication.audiobooks.data

import com.example.myapplication.audiobooks.data.remote.KnigavuheMetadata
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
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
     * Витрина: каждую книгу ищем у источников по названию и фамилии автора. Выбираем точное совпадение
     * названия, из них — самую длинную озвучку (полная версия, а не сокращённая), и запоминаем, сколько
     * озвучек нашлось. Не найденная книга остаётся в витрине без источника — честно, без Play.
     */
    suspend fun refreshShowcase(): CachedShelf? {
        var anyFound = false
        val books = SHOWCASE.map { pick ->
            val candidates = sources.flatMap { source ->
                (source.search(pick.title) as? SourceResult.Ok)?.value.orEmpty()
            }.filter { pick.matches(it) }
            if (candidates.isNotEmpty()) anyFound = true
            val best = candidates.maxWithOrNull(compareBy({ it.durationSec ?: 0L }))
            // Настоящая обложка нужна и книге без звука: берём её из каталога метаданных.
            val meta = if (best?.coverUrl == null) {
                metadata?.search(pick.title)?.firstOrNull { pick.matchesMeta(it) }
            } else {
                null
            }
            if (meta != null) anyFound = true
            best?.let { CachedBook.of(it, narrations = candidates.size).let { b -> b.copy(coverUrl = b.coverUrl ?: meta?.coverUrl) } }
                ?: CachedBook(source = "", key = "", title = pick.title, authors = listOf(pick.author),
                    narrators = meta?.narrators.orEmpty(), coverUrl = meta?.coverUrl, durationSec = null, narrations = 0)
        }
        // Сеть легла целиком — не затираем прошлую удачную витрину пустышками.
        return if (anyFound) save(SHOWCASE_KEY, books) else null
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
            AudiobookRepository.normalize(bookTitle) == AudiobookRepository.normalize(title) &&
                authors.any { surname in AudiobookRepository.normalize(it).split(' ') }
    }

    companion object {
        // v2: обложки из каталога метаданных — старая витрина без них пересобирается сразу.
        const val SHOWCASE_KEY = "showcase_v2"
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
