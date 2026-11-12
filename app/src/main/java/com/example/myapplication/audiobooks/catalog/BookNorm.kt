package com.example.myapplication.audiobooks.catalog

import java.text.Normalizer

/**
 * Нормализация текста для каталога книг. Зеркало `norm` из book-dictionary/v2/build/common.py и
 * tools/books-api/src/norm.js: индекс собран той же функцией, поэтому запрос обязан проходить через
 * неё же. Общие примеры — book-dictionary/v2/tests/golden_norm.json (проверяет BookNormTest).
 */
object BookNorm {
    private val extra = mapOf(
        'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'ł' to "l", 'đ' to "d", 'ı' to "i", 'þ' to "th", 'ð' to "d",
    )
    private val marks = Regex("\\p{M}")
    private val nonWord = Regex("[^0-9a-zа-я]+")
    private const val YOT = ''

    private val translitTable = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s",
        'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    /** нижний регистр, без диакритики (ё→е, é→e; «й» остаётся буквой), всё не буква/цифра → один пробел. */
    fun norm(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        // «й» — отдельная буква (война ≠ воина): прячем от NFKD, который отщепил бы краткую.
        val decomposed = Normalizer.normalize(text.lowercase().replace('й', YOT), Normalizer.Form.NFKD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (marks.matches(ch.toString())) continue
            val e = extra[ch]
            if (e != null) sb.append(e) else sb.append(ch)
        }
        return nonWord.replace(sb.toString().replace(YOT, 'й'), " ").trim()
    }

    /** Латиница от уже нормализованной кириллицы: «чайка» → «chayka». */
    fun translit(normalized: String): String = buildString {
        for (ch in normalized) append(translitTable[ch] ?: ch.toString())
    }

    fun tokens(text: String?): List<String> = norm(text).split(' ').filter { it.isNotEmpty() }

    fun hasCyrillic(text: String?): Boolean = text != null && text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
}
