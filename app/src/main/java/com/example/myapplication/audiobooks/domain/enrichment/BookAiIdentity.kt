package com.example.myapplication.audiobooks.domain.enrichment

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Книга в латинице: так её знают каталоги Apple, Open Library и Google. */
data class BookIdentity(val englishTitle: String?, val englishAuthors: List<String>)

/**
 * ИИ пользователя (BYOK) в роли справочника по книгам. Две задачи, обе — про русскую книгу и
 * каталоги на английском:
 *  1. [identify] — перевод названия и автора («Все эти миры», «Деннис Тейлор» → «All These Worlds»,
 *     «Dennis E. Taylor»): с ними запросы в каталоги и на сайты находят книгу;
 *  2. [pickWork] — выбор нужной книги из списка работ автора, когда перевод названия неизвестен:
 *     ИИ отвечает номером из списка, а не выдумывает название.
 *
 * Без ключа ([ask] = null или ошибка) обе задачи молча возвращают null — остальной поиск идёт как был.
 * Бесплатные квоты невелики, поэтому ответы и промахи кэшируются: промах из-за ошибки — на минуты,
 * честное «не знаю» — на часы.
 */
class BookAiIdentity(
    /** (запрос, системная подсказка) → текст ответа в JSON; null — ИИ не подключён. */
    private val ask: (suspend (user: String, system: String) -> Result<String>)?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Entry<T>(val at: Long, val value: T?, val ttl: Long)

    private val identities = ConcurrentHashMap<String, Entry<BookIdentity>>()
    private val picks = ConcurrentHashMap<String, Entry<Int>>()

    suspend fun identify(title: String, authors: List<String>): BookIdentity? {
        val ask = ask ?: return null
        if (!title.hasCyrillic() && authors.none { it.hasCyrillic() }) return null
        val key = BookWorkMatching.normalize(title) + "|" + authors.joinToString(",") { BookWorkMatching.normalize(it) }
        cached(identities, key)?.let { return it.value }
        val user = "Title: \"$title\"\n" +
            (if (authors.isNotEmpty()) "Authors: ${authors.joinToString()}\n" else "") +
            "Return JSON exactly: {\"english_title\": \"<official English title or empty string>\", " +
            "\"english_authors\": [\"<author name in Latin letters as on the original edition>\"]}"
        val result = guarded { ask(user, IDENTIFY_SYSTEM) }
        val identity = result?.getOrNull()?.let(::parseIdentity)
        remember(identities, key, identity, failed = result?.isFailure != false)
        return identity
    }

    /**
     * Номер из [candidates] (с нуля), который тот же литературный труд, что [title]; null — ни один
     * или ИИ не уверен. Кандидаты — английские названия работ автора.
     */
    suspend fun pickWork(title: String, authors: List<String>, candidates: List<String>): Int? {
        val ask = ask ?: return null
        if (candidates.isEmpty()) return null
        val key = BookWorkMatching.normalize(title) + "|" + candidates.joinToString("|") { BookWorkMatching.normalize(it) }
        cached(picks, key)?.let { return it.value }
        val list = candidates.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n")
        val user = "Russian audiobook title: \"$title\"\n" +
            (if (authors.isNotEmpty()) "Authors: ${authors.joinToString()}\n" else "") +
            "Candidate works by this author (English titles):\n$list\n" +
            "Which candidate is the same literary work as the Russian title? " +
            "Return JSON exactly: {\"number\": <candidate number, or 0 if none or you are not sure>}"
        val result = guarded { ask(user, PICK_SYSTEM) }
        val index = result?.getOrNull()?.let { parsePick(it, candidates.size) }
        remember(picks, key, index, failed = result?.isFailure != false)
        return index
    }

    private fun <T> cached(map: ConcurrentHashMap<String, Entry<T>>, key: String): Entry<T>? =
        map[key]?.takeIf { nowMs() - it.at < it.ttl }

    private fun <T> remember(map: ConcurrentHashMap<String, Entry<T>>, key: String, value: T?, failed: Boolean) {
        map[key] = Entry(nowMs(), value, if (value != null) HIT_TTL_MS else if (failed) FAILED_TTL_MS else MISS_TTL_MS)
    }

    private suspend fun <T> guarded(block: suspend () -> Result<T>): Result<T>? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    internal companion object {
        const val HIT_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val MISS_TTL_MS = 6L * 60 * 60 * 1000
        const val FAILED_TTL_MS = 5L * 60 * 1000

        private const val IDENTIFY_SYSTEM = "You are a book database expert. Given an audiobook title (probably " +
            "Russian) and its authors, return the OFFICIAL English title of the same literary work, as listed on " +
            "Goodreads / Open Library, and the authors' names in Latin letters. Do not translate literally. If you " +
            "are not confident about the title, return an empty string. Respond ONLY with a single JSON object, no " +
            "prose, no markdown."

        private const val PICK_SYSTEM = "You are a book database expert. You match a Russian book title with the " +
            "English-titled works of the same author. Match only when you are confident it is the same work " +
            "(a translation of the same book), otherwise answer 0. Respond ONLY with a single JSON object, no prose."

        fun parseIdentity(text: String): BookIdentity? {
            val obj = json(text) ?: return null
            val title = obj["english_title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                .takeIf { it.isNotEmpty() && it.hasLatin() && !it.equals("unknown", true) && !it.equals("null", true) }
            val authors = (obj["english_authors"] as? JsonArray).orEmpty()
                .mapNotNull { runCatching { it.jsonPrimitive.contentOrNull?.trim() }.getOrNull() }
                .filter { it.hasLatin() && !it.equals("unknown", true) && !it.equals("null", true) }
                .distinctBy { it.lowercase() }
                .take(3)
            return if (title == null && authors.isEmpty()) null else BookIdentity(title, authors)
        }

        /** Номер кандидата (с единицы) → индекс (с нуля); 0, мусор и выход за список — null. */
        fun parsePick(text: String, size: Int): Int? {
            val number = json(text)?.get("number")?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
            return number?.takeIf { it in 1..size }?.minus(1)
        }

        private fun json(text: String): JsonObject? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
        }
    }
}
