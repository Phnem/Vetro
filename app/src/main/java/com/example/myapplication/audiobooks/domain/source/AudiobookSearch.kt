package com.example.myapplication.audiobooks.domain.source

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Поиск по всем источникам сразу (AB-24/25): одна книга обычно лежит на нескольких сайтах, и если
 * один её не отдаёт (убрал, лёг, у него только фрагмент), та же озвучка берётся с другого.
 *
 * Порядок [sources] — приоритет: сначала сайты со стабильными прямыми ссылками и длинами глав.
 */
class AudiobookSearch(private val sources: List<AudiobookSource>) {

    /** Выдача всех источников в порядке приоритета; медленный сайт не держит остальных дольше [timeoutMs]. */
    suspend fun searchAll(query: String, timeoutMs: Long = SEARCH_TIMEOUT_MS): List<SourceBook> = coroutineScope {
        sources.map { source ->
            async {
                withTimeoutOrNull(timeoutMs) { (source.search(query) as? SourceResult.Ok)?.value }.orEmpty()
            }
        }.awaitAll().flatten()
    }

    /** Все озвучки того же произведения у всех источников (включая саму [book], если она там есть). */
    suspend fun sameWork(title: String, authors: List<String>): List<SourceBook> =
        searchAll(WorkMatch.titleKey(title).ifBlank { title }).filter { WorkMatch.same(title, authors, it) }

    /**
     * Куда уйти, если [book] не запускается: та же книга на других сайтах. Сначала тот же чтец
     * (позиция переносится как есть), дальше — по приоритету источника, потом самая полная.
     */
    suspend fun alternatives(book: SourceBook): List<SourceBook> {
        val narrator = WorkMatch.words(book.narrators)
        return sameWork(book.title, book.authors)
            .filter { it.ref != book.ref }
            .sortedWith(
                compareByDescending<SourceBook> { narrator.isNotEmpty() && WorkMatch.words(it.narrators).any(narrator::contains) }
                    .thenBy { rank(it.ref.source) }
                    .thenByDescending { it.durationSec ?: 0L },
            )
    }

    /** Место источника в приоритете; неизвестный — в конце. */
    fun rank(source: SourceId): Int = sources.indexOfFirst { it.id == source }.let { if (it < 0) Int.MAX_VALUE else it }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 12_000L
    }
}

/** Одно ли это произведение: название без пометок о чтеце и пересечение фамилий авторов. */
object WorkMatch {
    fun normalize(s: String): String = s.lowercase().replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** «Задача трёх тел (читает Игорь Князев)», «Аудиокнига Задача трех тел» → «задача трех тел». */
    fun titleKey(title: String): String = normalize(
        title.replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""^\s*аудиокнига\s+""", RegexOption.IGNORE_CASE), ""),
    )

    fun words(names: List<String>): Set<String> = names.flatMap { normalize(it).split(' ') }.filter { it.length > 2 }.toSet()

    fun same(title: String, authors: List<String>, other: SourceBook): Boolean {
        if (titleKey(other.title) != titleKey(title)) return false
        val mine = words(authors)
        val theirs = words(other.authors)
        // У части сайтов автора в выдаче нет — тогда достаточно названия.
        return mine.isEmpty() || theirs.isEmpty() || mine.any(theirs::contains)
    }
}
