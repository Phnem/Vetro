package com.example.myapplication.audiobooks.data

import com.example.myapplication.audiobooks.catalog.BookCatalog
import com.example.myapplication.audiobooks.catalog.BookNorm
import com.example.myapplication.audiobooks.catalog.CatalogBook
import com.example.myapplication.audiobooks.domain.enrichment.BookTitleAlternatives
import com.example.myapplication.audiobooks.domain.model.WorkId
import com.example.myapplication.audiobooks.domain.source.AudiobookSearch
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.BookLanguage
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceId
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.audiobooks.domain.source.WorkMatch
import com.example.myapplication.data.local.JsonMapFileStore
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** Источник «каталог Vetro» в выдаче поиска: ключ книги — девятизначный id каталога. */
val CATALOG_SOURCE = SourceId("vetro-catalog")

/** Каталожная книга, добавленная без озвучки: её ищут у источников позже, пока не найдут. */
@Serializable
data class PendingCatalogAudio(
    val vid: String,
    val titles: List<String>,
    val authors: List<String>,
    val triedAtMs: Long = 0L,
)

/**
 * Аудиокниги в общем поиске главной: сначала каталог Vetro (телефонный индекс → сервер), потом поиск по
 * всем аудио-источникам; «Добавить» — книга встаёт в библиотеку раздела «Книги» как своя, без запуска плеера.
 * Название, автор и обложка — из каталога; озвучка — у сайта-источника, а если её пока нет, книга добавляется
 * без звука и озвучка подцепляется позже ([attachPending]).
 */
class BookSearchAdder(
    private val sources: List<AudiobookSource>,
    private val search: AudiobookSearch,
    private val repository: AudiobookRepository,
    /** Английские названия запроса: русская книга на английских сайтах лежит под оригинальным именем. */
    private val alternatives: BookTitleAlternatives? = null,
    /** Каталог книг — первый источник; null — только аудио-источники, как раньше. */
    private val catalog: BookCatalog? = null,
    private val pending: JsonMapFileStore<PendingCatalogAudio>? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Книги, которые нашли чужие сайты, а каталога Vetro среди совпадений нет: уходят владельцу, чтобы пополнить каталог
     * (только если пользователь включил сбор в настройках; без него вызывающий код ничего не делает).
     */
    private val onExternalOnly: (List<SourceBook>) -> Unit = {},
) {
    /** Записи каталога из последней выдачи: «Добавить» по ключу достаёт отсюда автора, год и обложку. */
    private val catalogBooks: MutableMap<String, CatalogBook> = bounded()
    /** Озвучка, уже найденная в той же выдаче для каталожной книги. */
    private val audioHints: MutableMap<String, SourceBook> = bounded()

    /**
     * Одна строка на книгу: сперва каталожные (по смыслу запроса), затем озвучки, которых в каталоге нет.
     * Одна и та же озвучка лежит на нескольких сайтах — остаётся копия с лучшего (порядок = приоритет источников).
     */
    suspend fun search(query: String): List<SourceBook> {
        val cat = catalog ?: return searchAudio(query)
        return coroutineScope {
            val audioJob = async { searchAudio(query) }
            val books = runCatching { cat.search(query, CATALOG_LIMIT) }.getOrElse { if (it is CancellationException) throw it else emptyList() }
            // Каталог уверенно нашёл книги — сайты нужны лишь как подсказка «озвучка есть», ждём их недолго.
            val audio = if (books.size >= CONFIDENT) {
                withTimeoutOrNull(AUDIO_HINT_WAIT_MS) { audioJob.await() }.also { if (it == null) audioJob.cancel() }.orEmpty()
            } else {
                audioJob.await()
            }
            val english = !BookNorm.hasCyrillic(query)
            val matched = HashSet<SourceBookRef>()
            val fromCatalog = books.map { b ->
                val mine = audio.filter { sameBook(b, it) }
                matched += mine.map { it.ref }
                catalogBooks[b.vid] = b
                mine.firstOrNull()?.let { audioHints[b.vid] = it }
                val hint = mine.firstOrNull()
                SourceBook(
                    ref = SourceBookRef(CATALOG_SOURCE, b.vid),
                    title = b.title(english),
                    authors = listOf(b.author(english)).filter { it.isNotBlank() },
                    narrators = hint?.narrators.orEmpty(),
                    coverUrl = cat.coverUrl(b) ?: hint?.coverUrl,
                    durationSec = hint?.durationSec,
                    year = b.year,
                )
            }
            val external = audio.filter { it.ref !in matched }
            if (external.isNotEmpty()) runCatching { onExternalOnly(external) }
            fromCatalog + external
        }
    }

    private suspend fun searchAudio(query: String): List<SourceBook> {
        // Сайты ищут по-своему: «Мы — Легион. Мы — Боб» с тире и точками у части из них не находится,
        // поэтому запрос уходит и как есть, и очищенным от знаков.
        val plain = WorkMatch.normalize(query)
        val (found, englishQuery, englishFound) = coroutineScope {
            val raw = async { search.searchAll(query, pages = PAGES) }
            val cleaned = if (plain.isNotBlank() && plain != query.trim().lowercase()) async { search.searchAll(plain, pages = PAGES) } else null
            // Английский вариант названия ищется параллельно: он не задерживает основную выдачу.
            val english = async {
                val alt = withTimeoutOrNull(ALTERNATIVE_TIMEOUT_MS) { alternatives?.english(query)?.firstOrNull() }
                    ?: return@async null to emptyList<SourceBook>()
                alt to search.searchAll(alt, pages = 1)
            }
            val (altQuery, altFound) = english.await()
            Triple(raw.await() + cleaned?.await().orEmpty(), altQuery, altFound)
        }
        // Русская выдача — первой; английские сайты по оригинальному названию — следом, ранжируются
        // по английскому запросу, иначе фильтр совпадений выбросил бы их все.
        val ranked = BookSearchRanking.rank(query, found) +
            if (englishQuery != null) BookSearchRanking.rank(englishQuery, englishFound) else emptyList()
        return ranked
            .distinctBy { key(it.title) + "|" + WorkMatch.words(it.authors).sorted() + "|" + WorkMatch.words(it.narrators).sorted() }
    }

    /** Названия произведений библиотеки — чтобы кнопка у уже добавленной книги стояла «✓». */
    val libraryTitles: Flow<Set<String>> = repository.library().map { books -> books.map { key(it.title) }.toSet() }

    fun key(title: String): String = WorkMatch.titleKey(title)

    /** Страница книги у источника → записи в БД → в библиотеку. false — источник книгу не отдал. */
    suspend fun add(book: SourceBook): Boolean {
        if (book.ref.source == CATALOG_SOURCE) return addCatalog(book)
        val source = sources.firstOrNull { it.id == book.ref.source } ?: return false
        val details = (source.details(book.ref) as? SourceResult.Ok)?.value ?: return false
        val opened = repository.saveOpened(source, details)
        repository.setInLibrary(opened.workId, true)
        return true
    }

    /** Книга каталога: озвучку берём у источников, всё остальное (название, автор, обложка) — из каталога. */
    private suspend fun addCatalog(book: SourceBook): Boolean {
        val cat = catalogBooks[book.ref.key] ?: return false
        val titles = listOfNotNull(book.title, cat.ru, cat.en).distinct()
        val authors = book.authors
        val audio = audioHints[cat.vid] ?: findAudio(titles, listOfNotNull(cat.ruAuthor, cat.enAuthor).filter { it.isNotBlank() })
        val source = audio?.let { a -> sources.firstOrNull { it.id == a.ref.source } }
        val details = if (audio != null && source != null) (source.details(audio.ref) as? SourceResult.Ok)?.value else null
        if (source != null && details != null) {
            val opened = repository.saveOpened(source, details)
            repository.applyCatalogIdentity(opened.workId, book.title, authors, book.year, book.coverUrl)
            applyCatalogDetails(opened.workId, cat.vid, english = !BookNorm.hasCyrillic(book.title))
            repository.setInLibrary(opened.workId, true)
            return true
        }
        // Озвучки пока нет ни у одного источника: книга всё равно встаёт на полку, озвучку ищем позже.
        val language = if (BookNorm.hasCyrillic(book.title)) BookLanguage.RU else BookLanguage.EN
        val workId = repository.saveCatalogWork(book.title, authors, book.year, book.coverUrl, language)
        applyCatalogDetails(workId, cat.vid, english = language == BookLanguage.EN)
        repository.setInLibrary(workId, true)
        pending?.update { it + (workId.value to PendingCatalogAudio(cat.vid, titles, authors, triedAtMs = nowMs())) }
        return true
    }

    /**
     * Описание, жанры, серия и оригинальное название каталога — в произведение (только в пустые поля). Не ждём дольше
     * [DETAILS_WAIT_MS] и не падаем: без подробностей книга всё равно добавляется, их доберёт страница книги.
     */
    private suspend fun applyCatalogDetails(workId: WorkId, vid: String, english: Boolean) {
        val cat = catalog ?: return
        try {
            val details = withTimeoutOrNull(DETAILS_WAIT_MS) { cat.details(vid) } ?: return
            repository.applyCatalogDetails(workId, details, english)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // подробности — приятное дополнение к добавлению, не его условие
        }
    }

    /**
     * Дочищает каталожные книги без озвучки: пара попыток за проход, не чаще раза в [RETRY_AFTER_MS] на книгу.
     * Вызывается при открытии раздела «Книги».
     */
    suspend fun attachPending() {
        val store = pending ?: return
        store.ensureLoaded()
        val due = store.value.entries.filter { nowMs() - it.value.triedAtMs >= RETRY_AFTER_MS }.take(MAX_PER_PASS)
        for ((workId, p) in due) {
            val audio = findAudio(p.titles, p.authors)
            val source = audio?.let { a -> sources.firstOrNull { it.id == a.ref.source } }
            val details = if (audio != null && source != null) (source.details(audio.ref) as? SourceResult.Ok)?.value else null
            if (source != null && details != null) {
                repository.attachVariant(WorkId(workId), source, details)
                store.update { it - workId }
            } else {
                store.update { map -> map[workId]?.let { map + (workId to it.copy(triedAtMs = nowMs())) } ?: map }
            }
        }
    }

    /** Лучшая озвучка этого произведения у источников: по приоритету источника, потом самая полная. */
    private suspend fun findAudio(titles: List<String>, authors: List<String>): SourceBook? {
        for (t in titles.take(2)) {
            val found = withTimeoutOrNull(FIND_AUDIO_TIMEOUT_MS) { search.sameWork(t, authors) }.orEmpty()
            if (found.isNotEmpty()) {
                return found.minWithOrNull(compareBy<SourceBook> { search.rank(it.ref.source) }.thenByDescending { it.durationSec ?: 0L })
            }
        }
        return null
    }

    private fun sameBook(cat: CatalogBook, audio: SourceBook): Boolean {
        val authors = listOfNotNull(cat.ruAuthor, cat.enAuthor).filter { it.isNotBlank() }
        return listOfNotNull(cat.ru, cat.en).any { WorkMatch.same(it, authors, audio) }
    }

    private fun <K, V> bounded(): MutableMap<K, V> = Collections.synchronizedMap(object : LinkedHashMap<K, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > REMEMBERED
    })
}

private const val PAGES = 2
private const val ALTERNATIVE_TIMEOUT_MS = 8_000L
private const val CATALOG_LIMIT = 20
private const val CONFIDENT = 3
private const val AUDIO_HINT_WAIT_MS = 4_000L
private const val FIND_AUDIO_TIMEOUT_MS = 12_000L
private const val DETAILS_WAIT_MS = 3_000L
private const val RETRY_AFTER_MS = 12L * 60 * 60 * 1000
private const val MAX_PER_PASS = 3
private const val REMEMBERED = 400

/**
 * Порядок выдачи по смыслу запроса, а не по тому, что вернул сайт: «Марсианин» должен стоять выше
 * «Последнего марсианина», а книга, где название не совпало, но автор — по имени автора.
 */
internal object BookSearchRanking {
    /** Лучшие первыми; при равенстве остаётся порядок источников (приоритет). Книги без единого совпадения отбрасываются. */
    fun rank(query: String, books: List<SourceBook>): List<SourceBook> {
        val q = tokens(query)
        if (q.isEmpty()) return books
        return books.map { it to score(q, it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second } // sortedBy стабилен
            .map { it.first }
    }

    internal fun score(q: List<String>, book: SourceBook): Int {
        val title = tokens(WorkMatch.titleKey(book.title))
        val authors = tokens(book.authors.joinToString(" "))
        if (title == q) return 1000
        val inTitle = q.count { qt -> title.any { it == qt } }
        val inTitleStem = q.count { qt -> title.any { stem(it) == stem(qt) } }
        val inAuthor = q.count { qt -> authors.any { stem(it) == stem(qt) } }
        val covered = q.indices.count { i -> title.any { stem(it) == stem(q[i]) } || authors.any { stem(it) == stem(q[i]) } }
        if (covered == 0) return 0
        var s = covered * 100 + inTitle * 20 + (inTitleStem - inTitle) * 10 + inAuthor * 10
        if (covered == q.size) s += 200
        // Лишние слова в названии («Последний марсианин» на запрос «марсианин») — чуть ниже точного.
        s -= (title.size - inTitleStem).coerceAtLeast(0) * 5
        if (title.take(q.size) == q) s += 50
        return s
    }

    private fun tokens(s: String): List<String> = WorkMatch.normalize(s).split(' ').filter { it.length > 1 }

    /** Грубая основа слова для русских окончаний: «марсианина» ≈ «марсианин». */
    private fun stem(w: String): String = if (w.length > 5) w.dropLast(2) else if (w.length > 3) w.dropLast(1) else w
}
