package com.example.myapplication.audiobooks.domain.enrichment

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.enrichment.OpenLibraryClient
import com.example.myapplication.network.ids.WikidataClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * Альтернативные (английские) названия книги по русскому: аудиокнига на сайтах называется
 * «Задача трёх тел», а в каталогах обложек и на английских сайтах — «The Three-Body Problem».
 *
 * Источники по порядку: Wikidata (запись с описанием «роман/книга…» → английская подпись), Open
 * Library (совпавшее издание → название произведения), и только если оба молчат — ИИ-провайдер
 * пользователя (тот же, что дублирует английские названия аниме). Без ключа ИИ последний шаг
 * просто пропускается.
 */
class BookTitleAlternatives(
    private val openLibrary: OpenLibraryClient,
    private val wikidata: WikidataClient,
    private val identity: BookAiIdentity? = null,
) {
    private val found = ConcurrentHashMap<String, List<String>>()
    private val missedAt = ConcurrentHashMap<String, Long>()

    /** Английские названия книги, самое вероятное первым; пусто — название уже не русское или не нашлось. */
    suspend fun english(title: String, authors: List<String> = emptyList()): List<String> {
        val clean = title.trim()
        if (!clean.hasCyrillic()) return emptyList()
        val key = BookWorkMatching.normalize(clean)
        found[key]?.let { return it }
        missedAt[key]?.takeIf { System.currentTimeMillis() - it < MISS_TTL_MS }?.let { return emptyList() }

        val names = LinkedHashSet<String>()
        safe { (wikidata.workEnglishTitles(clean, "ru") as? LookupResult.Found)?.value }?.let(names::addAll)
        safe {
            val works = (openLibrary.searchWorks(clean) as? LookupResult.Found)?.value
            works?.let { BookWorkMatching.best(it, clean, authors) }?.title
        }?.let(names::add)

        var result = names
            .filter { it.hasLatin() && BookWorkMatching.normalize(it) != key }
            .distinctBy(BookWorkMatching::normalize)
            .take(MAX_ALTERNATIVES)
        if (result.isEmpty()) result = listOfNotNull(safe { identity?.identify(clean, authors)?.englishTitle })

        if (result.isEmpty()) missedAt[key] = System.currentTimeMillis() else found[key] = result
        return result
    }

    private suspend fun <T> safe(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MAX_ALTERNATIVES = 3
        /** Не нашлось — не переспрашиваем сети и ИИ каждый раз: бесплатные квоты ИИ невелики. */
        const val MISS_TTL_MS = 6L * 60 * 60 * 1000
    }
}

internal fun String.hasCyrillic(): Boolean = any { it in 'Ѐ'..'ӿ' }

internal fun String.hasLatin(): Boolean = any { it in 'a'..'z' || it in 'A'..'Z' }
