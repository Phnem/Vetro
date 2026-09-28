package com.example.myapplication.audiobooks.text

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Текст книги для синхронизации с аудио. [display] — оригинал без изменений (он и показывается);
 * всё остальное — разметка поверх него по смещениям символов: главы, предложения, токены сравнения.
 */
class BookText(
    val display: String,
    val chapters: List<TextChapter> = emptyList(),
    val language: String? = null,
) {
    val sentences: List<TextSentence> by lazy { TextSegmenter.sentences(display) }
    val tokens: BookTokenStream by lazy { BookTokenStream.of(display, sentences) }

    /** Отпечаток текста: выравнивание, посчитанное для другого текста, к этому не прикладывается. */
    val fingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(display.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}

data class TextChapter(val title: String, val start: Int)

/** Предложение (или короткий кусок длинного): [start, end) в display. */
data class TextSentence(val index: Int, val start: Int, val end: Int)

/**
 * Поток слов для сравнения: ключ слова и где оно в display. Одно число даёт несколько слов
 * («1895» → «тысяча восемьсот девяносто пять»), все с одним и тем же диапазоном.
 */
class BookTokenStream(
    val keys: Array<String>,
    val starts: IntArray,
    val ends: IntArray,
    /** Номер предложения каждого слова. */
    val sentence: IntArray,
) {
    val size: Int get() = keys.size

    companion object {
        fun of(display: String, sentences: List<TextSentence>): BookTokenStream {
            val words = MatchText.tokens(display)
            val sentenceOf = IntArray(words.size)
            var s = 0
            for ((i, w) in words.withIndex()) {
                while (s < sentences.lastIndex && w.start >= sentences[s].end) s++
                sentenceOf[i] = s
            }
            return BookTokenStream(
                keys = Array(words.size) { words[it].key },
                starts = IntArray(words.size) { words[it].start },
                ends = IntArray(words.size) { words[it].end },
                sentence = sentenceOf,
            )
        }
    }
}

/** Слово для сравнения и его место в исходной строке. */
data class MatchToken(val key: String, val start: Int, val end: Int)

/**
 * Нормализация для сравнения (не для показа): регистр, ё→е, Unicode NFC, числа и римские цифры
 * словами, «№» → «номер», служебные пометки распознавания («[музыка]») выброшены. Ключ слова —
 * первые [STEM] букв: русские окончания и ошибки распознавания в них не должны ломать совпадение.
 */
object MatchText {
    const val STEM = 5
    private val NBSP = Char(0x00A0)
    /** Знак ударения в изданиях для детей и учебных. */
    private val STRESS = Char(0x0301)
    private val SOFT_HYPHEN = Char(0x00AD)

    private val chapterWords = setOf("глава", "часть", "книга", "том", "chapter", "part", "book", "volume")

    fun key(word: String): String = if (word.length > STEM) word.substring(0, STEM) else word

    /** [asr] — строка распознавания: в ней «(смеётся)» — пометка, а не текст. Сноски «[1]» выбрасываются всегда. */
    fun tokens(text: String, asr: Boolean = false): List<MatchToken> {
        val out = ArrayList<MatchToken>(text.length / 6)
        var i = 0
        var previous: String? = null
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '[' || (asr && c == '(' && isAsrTag(text, i)) -> {
                    val close = text.indexOf(if (c == '[') ']' else ')', i)
                    i = if (close in i + 1..i + 40) close + 1 else i + 1
                }
                c == '№' -> {
                    out += MatchToken("номер", i, i + 1); previous = "номер"; i++
                }
                c.isDigit() -> {
                    var j = i
                    while (j < n && text[j].isDigit()) j++
                    // Разряды через пробел: «1 000 000».
                    while (j - i <= 11 && j < n && (text[j] == ' ' || text[j] == NBSP) &&
                        j + 4 <= n && (j + 1 until j + 4).all { text[it].isDigit() } && (j + 4 == n || !text[j + 4].isDigit())
                    ) j += 4
                    val digits = text.substring(i, j).filter(Char::isDigit)
                    NumberWords.spell(digits, isRussianContext(text, i)).forEach { out += MatchToken(key(it), i, j) }
                    previous = digits
                    i = j
                }
                c.isLetter() -> {
                    var j = i
                    while (j < n && (text[j].isLetter() || text[j] == STRESS || text[j] == SOFT_HYPHEN)) j++
                    val raw = text.substring(i, j)
                    val word = normalizeWord(raw)
                    val roman = if (isRoman(raw) && (raw.length >= 2 || previous in chapterWords)) romanValue(raw) else null
                    if (roman != null) {
                        NumberWords.spell(roman.toString(), isRussianContext(text, i)).forEach { out += MatchToken(key(it), i, j) }
                    } else if (word.isNotEmpty()) {
                        out += MatchToken(key(word), i, j)
                    }
                    previous = word
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    private fun isAsrTag(text: String, at: Int): Boolean {
        val close = text.indexOf(')', at)
        return close in at + 1..at + 25 && text.substring(at + 1, close).none { it == '.' || it == ',' }
    }

    private fun normalizeWord(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFC)
            .replace(STRESS.toString(), "").replace(SOFT_HYPHEN.toString(), "")
            .lowercase()
            .replace('ё', 'е')

    private fun isRussianContext(text: String, at: Int): Boolean {
        val from = (at - 60).coerceAtLeast(0)
        val to = (at + 60).coerceAtMost(text.length)
        var cyr = 0
        var lat = 0
        for (k in from until to) {
            val ch = text[k]
            if (ch in 'а'..'я' || ch in 'А'..'Я' || ch == 'ё' || ch == 'Ё') cyr++ else if (ch in 'a'..'z' || ch in 'A'..'Z') lat++
        }
        return cyr >= lat
    }

    private fun isRoman(raw: String): Boolean = raw.length <= 7 && raw.all { it in "IVXLC" }

    private fun romanValue(raw: String): Int? {
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100)
        var total = 0
        for (k in raw.indices) {
            val v = values.getValue(raw[k])
            val next = raw.getOrNull(k + 1)?.let(values::getValue) ?: 0
            total += if (v < next) -v else v
        }
        return total.takeIf { it in 1..399 }
    }
}

/**
 * Предложения для показа: по концу предложения (. ! ? …) и по абзацам, с учётом сокращений и
 * инициалов («А. П. Чехов», «т. е.», «Mr.»). Слишком длинные делятся на естественных паузах
 * (; : —), слишком короткие («— Да.») приклеиваются к следующему — реплика не мигает.
 */
object TextSegmenter {
    private const val MAX = 220
    private const val MIN = 24

    private val abbreviations = setOf(
        "т", "е", "д", "п", "г", "гг", "им", "см", "ср", "стр", "тыс", "млн", "руб", "коп", "св", "проф", "акад", "др",
        "mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "etc", "no", "vol", "ch", "prof", "rev", "gen", "col", "capt", "lt",
    )

    fun sentences(text: String): List<TextSentence> {
        val raw = ArrayList<IntRange>()
        var start = skipSpace(text, 0)
        var i = start
        while (i < text.length) {
            val c = text[i]
            val paragraph = c == '\n' && (i + 1 < text.length && text[i + 1] == '\n' || isLineBreakParagraph(text, i))
            if (paragraph) {
                if (hasLetters(text, start, i)) raw += start until i
                start = skipSpace(text, i + 1)
                i = start
                continue
            }
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var j = i + 1
                while (j < text.length && (text[j] in ".!?…" || text[j] in "»\"”’)")) j++
                val nextStart = skipSpace(text, j)
                val boundary = nextStart >= text.length ||
                    (nextStart > j && (text[nextStart].isUpperCase() || text[nextStart] in "—–-«\"“" || text[nextStart].isDigit()))
                if (boundary && !(c == '.' && isAbbreviation(text, start, i))) {
                    raw += start until j
                    start = nextStart
                    i = nextStart
                    continue
                }
                i = j
                continue
            }
            i++
        }
        if (start < text.length && hasLetters(text, start, text.length)) raw += start until trimEnd(text, text.length)

        val sized = ArrayList<IntRange>()
        for (r in raw) sized += splitLong(text, r)
        val merged = ArrayList<IntRange>()
        var pending: IntRange? = null
        for (r in sized) {
            val cur = pending?.let { it.first..r.last } ?: r
            if (cur.last - cur.first + 1 < MIN && !endsParagraph(text, cur.last)) pending = cur else { merged += cur; pending = null }
        }
        pending?.let { merged += it }
        return merged.mapIndexed { idx, r -> TextSentence(idx, r.first, trimEnd(text, r.last + 1)) }
    }

    private fun splitLong(text: String, r: IntRange): List<IntRange> {
        if (r.last - r.first + 1 <= MAX) return listOf(r)
        val parts = ArrayList<IntRange>()
        var from = r.first
        while (r.last - from + 1 > MAX) {
            val limit = from + MAX
            var cut = -1
            for (k in limit downTo from + MAX / 3) {
                val ch = text[k]
                if (ch == ';' || ch == ':' || (ch == '—' && k > 0 && text[k - 1] == ' ') || ch == ',') { cut = if (ch == '—') k - 1 else k + 1; break }
            }
            if (cut <= from) {
                cut = text.lastIndexOf(' ', limit).takeIf { it > from } ?: limit
            }
            parts += from until cut
            from = skipSpace(text, cut)
        }
        if (from <= r.last) parts += from..r.last
        return parts
    }

    private fun isAbbreviation(text: String, sentenceStart: Int, dot: Int): Boolean {
        var k = dot - 1
        while (k >= sentenceStart && text[k].isLetter()) k--
        val word = text.substring(k + 1, dot)
        if (word.isEmpty()) return false
        // Инициал: одна заглавная буква перед точкой («А. П. Чехов»).
        if (word.length == 1 && word[0].isUpperCase()) return true
        return word.lowercase() in abbreviations
    }

    private fun isLineBreakParagraph(text: String, at: Int): Boolean {
        // Одиночный перевод строки — абзац, если за ним отступ или тире реплики (FB2/TXT так и пишут).
        val next = at + 1
        return next < text.length && (text[next] == '\t' || text[next] == '—' || text[next] == '–' || text.startsWith("   ", next))
    }

    private fun endsParagraph(text: String, last: Int): Boolean {
        var k = last + 1
        while (k < text.length && text[k] == ' ') k++
        return k < text.length && text[k] == '\n' && k + 1 < text.length && text[k + 1] == '\n'
    }

    private fun hasLetters(text: String, from: Int, to: Int) = (from until to).any { text[it].isLetterOrDigit() }

    private fun skipSpace(text: String, from: Int): Int {
        var k = from
        while (k < text.length && text[k].isWhitespace()) k++
        return k
    }

    private fun trimEnd(text: String, end: Int): Int {
        var k = end
        while (k > 0 && text[k - 1].isWhitespace()) k--
        return k
    }
}
