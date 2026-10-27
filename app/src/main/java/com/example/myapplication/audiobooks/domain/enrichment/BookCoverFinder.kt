package com.example.myapplication.audiobooks.domain.enrichment

import com.example.myapplication.audiobooks.domain.source.AudiobookSearch
import com.example.myapplication.audiobooks.domain.source.WorkMatch
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.enrichment.GoogleBooksClient
import com.example.myapplication.network.enrichment.ITunesClient
import com.example.myapplication.network.enrichment.OpenLibraryClient
import com.example.myapplication.network.ids.WikidataClient
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Обложка в списке ручного выбора; [caption] — что это за книга (пусто у находок по названию). */
data class CoverOption(val url: String, val caption: String)

/**
 * Поиск обложки книги там, где сам сайт-источник её не дал (у части сайтов обложки нет вовсе, а у
 * своих папок её нет никогда).
 *
 * Источники по убыванию пригодности для аудиокниги: те же книги на других сайтах → витрина Apple
 * (квадратные обложки именно аудиокниг, до 600×600) → Open Library → Google Books. Запросов на
 * каждый источник два и больше: название как есть и его английские варианты
 * ([BookTitleAlternatives]) — в каталогах русская книга лежит под оригинальным названием.
 *
 * Скорость — как у поиска по каталогам: все ветки идут одновременно, у каждой свой таймаут, а
 * [first] отдаёт первую работающую обложку по приоритету и тут же отменяет остальные, не дожидаясь
 * самой медленной ветки. Полный список кандидатов ([candidates]) кэшируется на [CACHE_TTL_MS].
 *
 * Чужая обложка хуже, чем никакая, поэтому каждая находка сверяется: название должно совпасть с
 * искомым (или его английским вариантом) после нормализации, а автор — если оба алфавита сравнимы.
 */
class BookCoverFinder(
    private val search: AudiobookSearch,
    private val openLibrary: OpenLibraryClient,
    private val googleBooks: GoogleBooksClient,
    private val itunes: ITunesClient,
    private val alternatives: BookTitleAlternatives,
    private val client: OkHttpClient,
    private val wikidata: WikidataClient,
    private val identity: BookAiIdentity,
) {
    private class Cached(val at: Long, val urls: List<String>)

    private val misses = ConcurrentHashMap<String, Long>()
    private val cache = ConcurrentHashMap<String, Cached>()

    /** Все подходящие адреса обложек в порядке предпочтения, без повторов. Картинки не проверяются. */
    suspend fun candidates(title: String, authors: List<String>): List<String> {
        val key = WorkMatch.titleKey(title)
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < CACHE_TTL_MS }?.let { return it.urls }
        val urls = coroutineScope { branches(title, authors).awaitAll().flatten() }
            .filter { it.isHttp() }.distinct()
        if (urls.isNotEmpty()) cache[key] = Cached(System.currentTimeMillis(), urls)
        return urls
    }

    /**
     * Первая обложка, которая реально открывается; null — не нашлось (повторно искать ближайшие часы
     * не будем). Ветки опрашиваются по приоритету, но ни одна не держит остальных дольше
     * [BRANCH_WAIT_MS]; нашли — остальное отменяется.
     */
    suspend fun first(title: String, authors: List<String>): String? {
        val key = WorkMatch.titleKey(title)
        misses[key]?.takeIf { System.currentTimeMillis() - it < MISS_TTL_MS }?.let { return null }
        val url = coroutineScope {
            var found: String? = null
            for ((index, branch) in branches(title, authors).withIndex()) {
                // Ветки ИИ (с третьей) делают запрос к модели и потом к каталогам — им нужно больше времени.
                val wait = if (index >= AI_BRANCHES_FROM) AI_BRANCH_WAIT_MS else BRANCH_WAIT_MS
                val urls = withTimeoutOrNull(wait) { branch.await() }.orEmpty()
                found = urls.filter { it.isHttp() }.distinct().take(MAX_CHECKS).firstOrNull { isImage(it) }
                if (found != null) break
            }
            coroutineContext.cancelChildren()
            found
        }
        if (url == null) misses[key] = System.currentTimeMillis()
        return url
    }

    /**
     * Обложки для ручного выбора, когда автоматика промолчала. Строго по названию здесь не
     * фильтруем — выбирает человек, ему нужны и неточные находки с подписью:
     *  1. то, что подошло по названию (как в [candidates]) — если [query] пуст;
     *  2. каталоги по запросу пользователя ([query]): можно вписать английское название;
     *  3. другие книги автора — когда название не перевести («Все эти миры» ↔ «All These Worlds»),
     *     нужная книга всё равно оказывается среди его работ.
     */
    suspend fun options(title: String, authors: List<String>, query: String? = null): List<CoverOption> = coroutineScope {
        val typed = query?.trim().orEmpty()
        val matched = async {
            if (typed.isEmpty()) candidates(title, authors).map { CoverOption(it, "") } else emptyList()
        }
        val fromQuery = async { if (typed.isEmpty()) emptyList() else guarded { catalogueOptions(typed) } }
        val works = guarded { authorWorks(title, authors) }
        // Если ИИ подключён и уверен, какая из работ автора — эта книга, она идёт первой.
        val chosen = guarded { listOfNotNull(aiPick(title, authors, works)) }
        val byAuthor = works.map { CoverOption(it.url, caption(it.title, it.author)) }
        (chosen.map { CoverOption(it.url, caption(it.title, it.author)) } + matched.await() + fromQuery.await() + byAuthor)
            .distinctBy { it.url }
    }

    /** Все каталоги по произвольному запросу, без сверки названия; подпись — название и автор находки. */
    private suspend fun catalogueOptions(query: String): List<CoverOption> = coroutineScope {
        val apple = async {
            guarded {
                listOf("us", "ru").flatMap { country ->
                    (itunes.audiobooks(query, country) as? LookupResult.Found)?.value.orEmpty()
                        .mapNotNull { r -> r.artworkUrl?.let { CoverOption(it, caption(r.title, r.author)) } }
                }
            }
        }
        val openLib = async {
            guarded {
                (openLibrary.searchWorks(query, OPTION_LIMIT) as? LookupResult.Found)?.value.orEmpty().mapNotNull { w ->
                    (w.matchedEdition?.coverIds?.firstOrNull() ?: w.coverId)
                        ?.let { CoverOption(OpenLibraryClient.coverUrl(it), caption(w.title, w.authors.firstOrNull())) }
                }
            }
        }
        val google = async {
            guarded {
                (googleBooks.search(query, null) as? LookupResult.Found)?.value.orEmpty().mapNotNull { v ->
                    v.thumbnail?.let { CoverOption(it.replace("&edge=curl", ""), caption(v.title, v.authors.firstOrNull())) }
                }
            }
        }
        openLib.await() + apple.await() + google.await()
    }

    /** Работа автора в каталоге: английское название, автор и адрес её обложки. */
    private class AuthorWork(val title: String, val author: String?, val url: String)

    /**
     * Работы автора из Open Library. Автор по-русски → латинская подпись из Wikidata и от ИИ
     * («Деннис Тейлор» → «Dennis E. Taylor»): так находится человек, а не однофамилец-снукерист.
     */
    private suspend fun authorWorks(title: String, authors: List<String>): List<AuthorWork> {
        val author = authors.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val names = if (author.hasCyrillic()) {
            val fromWikidata = (wikidata.authorNames(author, "ru") as? LookupResult.Found)?.value.orEmpty()
            (fromWikidata + identity.identify(title, authors)?.englishAuthors.orEmpty()).distinctBy { it.lowercase() }
        } else {
            listOf(author)
        }
        return names.take(2).flatMap { name ->
            val wanted = WorkMatch.words(listOf(name))
            (openLibrary.searchWorks(name, OPTION_LIMIT) as? LookupResult.Found)?.value.orEmpty()
                .filter { w -> WorkMatch.words(w.authors).any(wanted::contains) }
                .mapNotNull { w ->
                    (w.matchedEdition?.coverIds?.firstOrNull() ?: w.coverId)
                        ?.let { AuthorWork(w.title, w.authors.firstOrNull(), OpenLibraryClient.coverUrl(it)) }
                }
        }.distinctBy { it.url }
    }

    /** Какая из [works] — книга [title], по мнению ИИ пользователя; null — ИИ нет или он не уверен. */
    private suspend fun aiPick(title: String, authors: List<String>, works: List<AuthorWork>): AuthorWork? {
        val index = identity.pickWork(title, authors, works.map { it.title }) ?: return null
        return works.getOrNull(index)
    }

    private fun caption(title: String, author: String?): String =
        listOfNotNull(title.takeIf { it.isNotBlank() }, author?.takeIf { it.isNotBlank() }).joinToString(" — ")

    /** Адрес отдаёт непустую картинку. */
    suspend fun isImage(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body
                response.isSuccessful && body?.contentType()?.type == "image" && body.contentLength() != 0L
            }
        }.getOrDefault(false)
    }

    /**
     * Три ветки по приоритету, все стартуют сразу: другие сайты; каталоги по названию как есть;
     * каталоги по английским вариантам (их поиск идёт параллельно, а не перед остальными).
     */
    private fun CoroutineScope.branches(title: String, authors: List<String>): List<Deferred<List<String>>> {
        val original = setOf(BookWorkMatching.normalize(title)).filterTo(HashSet()) { it.isNotBlank() }
        val sites = async { guarded { search.sameWork(title, authors).mapNotNull { it.coverUrl } } }
        val direct = async { fromCatalogues(title, authors, original) }
        val english = async {
            val alts = alternatives.english(title, authors)
            if (alts.isEmpty()) return@async emptyList<String>()
            val wanted = (listOf(title) + alts).map(BookWorkMatching::normalize).filter(String::isNotBlank).toSet()
            coroutineScope { alts.map { query -> async { fromCatalogues(query, authors, wanted) } }.awaitAll().flatten() }
        }
        // ИИ пользователя (если подключён): перевод названия и автора для сайтов и каталогов и выбор
        // нужной книги среди работ автора. Идут последними: сначала пробуем то, что не тратит квоту.
        // LAZY: в [first] ветка стартует, только когда дошла очередь, — удачная находка выше не тратит квоту ИИ.
        val translated = async(start = CoroutineStart.LAZY) { fromTranslation(title, authors) }
        val picked = async(start = CoroutineStart.LAZY) {
            guarded { listOfNotNull(aiPick(title, authors, authorWorks(title, authors))?.url) }
        }
        return listOf(sites, direct, english, translated, picked)
    }

    /**
     * Название и автор, переведённые ИИ: английское название ищется на сайтах-источниках (там тоже
     * бывают англоязычные озвучки) и в каталогах — теперь вместе с латинским автором, по которому
     * Open Library и Google отличают книгу от однофамилицы.
     */
    private suspend fun fromTranslation(title: String, authors: List<String>): List<String> {
        val known = identity.identify(title, authors) ?: return emptyList()
        val english = known.englishTitle ?: return emptyList()
        val knownTitles = alternatives.english(title, authors).map(BookWorkMatching::normalize).toSet()
        val wanted = setOf(BookWorkMatching.normalize(english), BookWorkMatching.normalize(title)).filter(String::isNotBlank).toSet()
        return coroutineScope {
            val sites = async { guarded { search.sameWork(english, emptyList()).mapNotNull { it.coverUrl } } }
            // Каталоги по этому названию уже опрошены веткой «английские варианты» — не повторяем.
            val catalogues = async {
                if (BookWorkMatching.normalize(english) in knownTitles) emptyList()
                else fromCatalogues(english, known.englishAuthors + authors, wanted)
            }
            sites.await() + catalogues.await()
        }
    }

    private suspend fun fromCatalogues(query: String, authors: List<String>, wanted: Set<String>): List<String> = coroutineScope {
        val cyrillic = query.hasCyrillic()
        val apple = async {
            guarded {
                // Русская витрина знает русские названия, американская — английские.
                val country = if (cyrillic) "ru" else "us"
                (itunes.audiobooks(query, country) as? LookupResult.Found)?.value.orEmpty()
                    .filter { titleMatches(wanted, it.title) && authorMatches(authors, listOf(it.author)) }
                    .mapNotNull { it.artworkUrl }
            }
        }
        val openLib = async {
            guarded {
                val works = (openLibrary.searchWorks(query) as? LookupResult.Found)?.value ?: return@guarded emptyList()
                val work = BookWorkMatching.best(works, query, authors) ?: return@guarded emptyList()
                listOfNotNull((work.matchedEdition?.coverIds?.firstOrNull() ?: work.coverId)?.let { OpenLibraryClient.coverUrl(it) })
            }
        }
        val google = async {
            guarded {
                val volumes = (googleBooks.search(query, if (cyrillic) "ru" else "en") as? LookupResult.Found)?.value.orEmpty()
                volumes
                    .filter { titleMatches(wanted, it.title) && authorMatches(authors, it.authors) }
                    .mapNotNull { it.thumbnail?.replace("&edge=curl", "") }
            }
        }
        apple.await() + openLib.await() + google.await()
    }

    private fun titleMatches(wanted: Set<String>, candidate: String): Boolean =
        BookWorkMatching.normalize(candidate) in wanted

    /**
     * Автор сходится, если есть общая фамилия/имя. «Айзек Азимов» и «Isaac Asimov» буквами не
     * сравнить — тогда решает название, иначе обложки не нашлось бы ни у одной русской книги.
     */
    private fun authorMatches(mine: List<String>, theirs: List<String>): Boolean {
        val a = WorkMatch.words(mine)
        val b = WorkMatch.words(theirs)
        if (a.isEmpty() || b.isEmpty()) return true
        if (a.any(b::contains)) return true
        return a.all { it.hasCyrillic() } != b.all { it.hasCyrillic() }
    }

    private suspend fun <T> guarded(block: suspend () -> List<T>): List<T> = try {
        withTimeoutOrNull(SOURCE_TIMEOUT_MS) { block() }.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    private fun String.isHttp() = startsWith("http://") || startsWith("https://")

    private companion object {
        const val OPTION_LIMIT = 12
        const val SOURCE_TIMEOUT_MS = 8_000L
        /** Дольше этого одна ветка не задерживает остальные в [first]. */
        const val BRANCH_WAIT_MS = 6_000L
        const val AI_BRANCH_WAIT_MS = 15_000L
        const val AI_BRANCHES_FROM = 3
        const val MAX_CHECKS = 6
        const val MISS_TTL_MS = 6L * 60 * 60 * 1000
        const val CACHE_TTL_MS = 15L * 60 * 1000
    }
}
