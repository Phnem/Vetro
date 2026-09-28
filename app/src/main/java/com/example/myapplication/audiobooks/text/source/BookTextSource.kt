package com.example.myapplication.audiobooks.text.source

import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.text.BookText
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Что за книга звучит: по этому ищется её текст. */
data class TextQuery(
    val title: String,
    val titleOriginal: String?,
    val authors: List<String>,
    /** Язык озвучки («ru», «en»); текст другого языка не подходит — это перевод. */
    val language: String?,
    val variant: VariantId,
    /** Сайт и ключ записи: у LibriVox по ним находится ровно тот текст, что читали. */
    val sourceId: String?,
    val sourceKey: String?,
)

/** Кандидат в текст книги у источника. [exact] — источник знает, что это тот самый текст. */
data class TextCandidate(
    val sourceId: String,
    val id: String,
    val title: String,
    val authors: List<String>,
    val language: String?,
    val exact: Boolean = false,
)

/**
 * Источник текста книги — легальный: свой файл пользователя или общественное достояние
 * (Project Gutenberg, Standard Ebooks, Викитека, открытые книги Internet Archive).
 */
interface BookTextSource {
    val id: String
    /** Для строки «Найден: …». */
    val displayName: String

    suspend fun find(query: TextQuery): List<TextCandidate>
    suspend fun load(candidate: TextCandidate): BookText?
}

/** Сеть источников текста: вежливый User-Agent, лимит размера, строки и байты. */
class TextHttp(private val client: OkHttpClient) {
    suspend fun string(url: String): String = bytes(url, MAX_PAGE).toString(Charsets.UTF_8)

    suspend fun bytes(url: String, limit: Int = TextParsers.MAX_BYTES): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty body")
            val length = body.contentLength()
            if (length > limit) throw IOException("too large: $length")
            val bytes = body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > limit) throw IOException("too large")
                }
                out.toByteArray()
            }
            bytes
        }
    }

    private companion object {
        const val USER_AGENT = "VetroCollection/3.3 (Android audiobook player; book text for subtitles)"
        const val MAX_PAGE = 4 * 1024 * 1024
    }
}

/**
 * Похоже ли найденное на книгу из запроса: название (по словам), автор (по фамилии), язык.
 * Совпадение одного названия не достаточно — нужен автор, если он известен.
 */
object TextMatch {
    fun score(query: TextQuery, candidate: TextCandidate): Float {
        if (candidate.exact) return 1f
        val lang = query.language?.take(2)?.lowercase()
        val candLang = candidate.language?.take(2)?.lowercase()
        if (lang != null && candLang != null && lang != candLang) return 0f
        val title = maxOf(similarity(query.title, candidate.title), query.titleOriginal?.let { similarity(it, candidate.title) } ?: 0f)
        if (title < 0.5f) return 0f
        if (query.authors.isEmpty() || candidate.authors.isEmpty()) return title * 0.7f
        val author = if (query.authors.any { a -> candidate.authors.any { c -> sameAuthor(a, c) } }) 1f else 0f
        return if (author == 0f) 0f else 0.4f + 0.6f * title
    }

    fun similarity(a: String, b: String): Float {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0f
        val common = x.intersect(y).size
        return common.toFloat() / minOf(x.size, y.size).coerceAtLeast(1) * (if (common == x.size || common == y.size) 1f else 0.8f)
    }

    /** «Чехов Антон Павлович» ~ «А. П. Чехов» ~ «Anton Chekhov»: по фамилии (самое длинное слово). */
    fun sameAuthor(a: String, b: String): Boolean {
        val x = words(a)
        val y = words(b)
        return x.any { w -> w.length >= 3 && w in y } || translit(x).any { w -> w.length >= 4 && w in translit(y) }
    }

    private fun words(s: String): Set<String> =
        s.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it !in STOP }.toSet()

    private val STOP = setOf("the", "and", "of", "a", "an", "или", "и", "в", "на", "a")

    private fun translit(words: Set<String>): Set<String> = words.map { w ->
        w.map { c -> TRANSLIT[c] ?: c.toString() }.joinToString("")
            .replace("kh", "h").replace("ks", "x").replace("y", "i").replace("j", "i")
    }.toSet()

    private val TRANSLIT = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s",
        'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "",
        'ы' to "i", 'ь' to "", 'э' to "e", 'ю' to "iu", 'я' to "ia",
    )
}
