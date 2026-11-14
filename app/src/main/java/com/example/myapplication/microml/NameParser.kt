package com.example.myapplication.microml

import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

/** Откуда пришло имя: это знание — признак модели. Порядок фиксирован контрактом name-features-v1. */
enum class NameDomain { UNKNOWN, VIDEO, AUDIOBOOK, MANGA }

/** Поля, прочитанные из «грязного» имени файла, папки или релиза. Отсутствующее поле — null. */
data class ParsedName(
    val title: String?,
    val altTitle: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val volume: Int? = null,
    val chapter: Int? = null,
    val author: String? = null,
    val narrator: String? = null,
    val group: String? = null,
    /** Наименьшая уверенность модели среди токенов: низкое значение — повод оставить разбор прежнему алгоритму. */
    val confidence: Float = 0f,
)

/**
 * name-features-v1 — зеркало tools/microml/catalog/names.py: токенизация, признаки токенов, сборка полей.
 * Каждому токену лес присваивает одну метку (название, год, сезон, серия, автор, чтец, техника…).
 */
class NameParser(
    private val scorer: MicroScorer,
    /** Лексикон «слово → класс» из манифеста пакета (tech, season, episode…). */
    private val lexicon: Map<String, Int>,
    /** Таблица «похоже на имя человека» (dim = 1) или null, если её нет в пакете. */
    private val person: MicroTable?,
) {
    init { require(scorer.features == FEATURES && scorer.classes == LABELS.size) { "parser model shape" } }

    internal class Token(val text: String, val start: Int, val end: Int, val depth: Int, val bracketType: Int,
        val firstInBracket: Int, val bracketNo: Int, val sepBefore: Int, val dashesBefore: Int, val bracketId: Int) {
        var bracketLen = 0
        var bracketPos = 0
    }

    fun parse(raw: String, domain: NameDomain): ParsedName? {
        if (raw.length > 512) return null
        val text = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val tokens = tokenize(text)
        if (tokens.isEmpty() || tokens.size > 60) return null
        val rows = features(tokens, domain.ordinal)
        val labels = IntArray(tokens.size)
        var confidence = 1f
        for (i in tokens.indices) {
            val p = scorer.predict(rows[i])
            var best = 0
            for (k in 1 until p.size) if (p[k] > p[best]) best = k
            labels[i] = best
            confidence = min(confidence, p[best])
        }
        return assemble(text, tokens, labels, confidence)
    }

    private fun isWordChar(cp: Int) = Character.isLetter(cp) || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()

    internal fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        val stack = ArrayList<Int>(); val ids = ArrayList<Int>()
        var fresh = false; var bracketNo = 0; var dashes = 0
        var i = 0; var lastEnd = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isWordChar(cp)) {
                var j = i
                while (j < text.length) { val c = text.codePointAt(j); if (!isWordChar(c)) break; j += Character.charCount(c) }
                val cls = separatorClass(text.substring(lastEnd, i), tokens.isEmpty())
                if (cls == 4) dashes++
                tokens.add(Token(text.substring(i, j), i, j, min(stack.size, 2), stack.lastOrNull() ?: 0, if (fresh) 1 else 0,
                    min(bracketNo, 5), cls, min(dashes, 3), ids.lastOrNull() ?: 0))
                fresh = false; lastEnd = j; i = j
                continue
            }
            val open = OPEN[cp]
            if (open != null) { stack.add(open); bracketNo++; ids.add(bracketNo); fresh = true }
            else if (cp in CLOSE && stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); ids.removeAt(ids.lastIndex) }
            i += Character.charCount(cp)
        }
        val seen = HashMap<Int, Int>()
        for (t in tokens) if (t.bracketId != 0) { t.bracketPos = seen[t.bracketId] ?: 0; seen[t.bracketId] = t.bracketPos + 1 }
        for (t in tokens) if (t.bracketId != 0) t.bracketLen = seen.getValue(t.bracketId)
        return tokens
    }

    private fun separatorClass(sep: String, atStart: Boolean): Int {
        if (sep.isEmpty()) return if (atStart) 0 else 10
        var bracket = false; var slash = false; var dash = false; var comma = false; var colon = false
        var dot = false; var underscore = false; var allSpace = true; var spacedDash = false
        var i = 0
        while (i < sep.length) {
            val cp = sep.codePointAt(i)
            val c = sep[i]
            if (OPEN.containsKey(cp) || cp in CLOSE) bracket = true
            if (c == '/' || c == '|' || c == '\\') slash = true
            if (c in DASHES) {
                dash = true
                // пробел, тире (одно или несколько подряд), пробел
                var k = i
                while (k < sep.length && sep[k] in DASHES) k++
                if (i > 0 && sep[i - 1].isWhitespace() && k < sep.length && sep[k].isWhitespace()) spacedDash = true
            }
            if (c == ',' || c == ';') comma = true
            if (c == ':') colon = true
            if (c == '.') dot = true
            if (c == '_') underscore = true
            if (!c.isWhitespace()) allSpace = false
            i += Character.charCount(cp)
        }
        return when {
            bracket -> 8
            slash -> 7
            spacedDash -> 4
            dash -> 5
            comma -> 6
            colon -> 9
            dot -> 2
            underscore -> 3
            allSpace -> 1
            else -> 10
        }
    }

    private fun lexClass(lower: String): Int {
        lexicon[lower]?.let { return it }
        for ((cls, pattern) in PATTERNS) if (pattern.matches(lower)) return cls
        if (HEX8.matches(lower) && lower.any { it in '0'..'9' } && lower.any { it in 'a'..'f' }) return 4
        return 0
    }

    private fun caseClass(text: String): Int {
        val lower = text.lowercase(Locale.ROOT); val upper = text.uppercase(Locale.ROOT)
        if (lower == upper) return 4
        if (text == lower) return 0
        if (text == upper) return 2
        val first = text.codePointAt(0)
        val rest = text.substring(Character.charCount(first))
        val restLower = rest.lowercase(Locale.ROOT)
        return if (Character.isUpperCase(first) && restLower != rest.uppercase(Locale.ROOT) && rest == restLower) 1 else 3
    }

    private fun scriptClass(text: String): Int {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isDigit(cp)) continue
            return when {
                cp < 0x250 -> 0
                cp in 0x400 until 0x530 -> 1
                cp in 0x3040 until 0xA000 -> 2
                else -> 3
            }
        }
        return 4
    }

    private fun personScore(lower: String): Float {
        val table = person ?: return 0f
        val ids = MicroText.bucketIds(MicroText.normalize(lower), table.buckets)
        if (ids.isEmpty()) return 0f
        return table.sum(ids)[0] / ids.size
    }

    internal fun features(tokens: List<Token>, domain: Int): Array<FloatArray> {
        val n = tokens.size
        val own = Array(n) { FloatArray(OWN) }
        for ((i, t) in tokens.withIndex()) {
            val lower = t.text.lowercase(Locale.ROOT)
            val length = t.text.codePointCount(0, t.text.length)
            val digits = t.text.count { it in '0'..'9' }
            val isDigit = digits == length
            val value = if (isDigit) min(t.text.take(9).toInt(), 3000) else -1
            val f = own[i]
            f[0] = min(length, 20).toFloat(); f[1] = if (isDigit) 1f else 0f; f[2] = if (digits == 0) 1f else 0f
            f[3] = min(digits, 8).toFloat(); f[4] = caseClass(t.text).toFloat(); f[5] = scriptClass(t.text).toFloat()
            f[6] = value.toFloat(); f[7] = if (isDigit && length == 4 && value in 1900..2099) 1f else 0f
            f[8] = if (isDigit && length > 1 && t.text[0] == '0') 1f else 0f
            f[9] = min(i, 6).toFloat(); f[10] = if (n > 1) (i.toDouble() / (n - 1)).toFloat() else 0f
            f[11] = min(n, 20).toFloat(); f[12] = t.depth.toFloat(); f[13] = t.bracketType.toFloat()
            f[14] = t.firstInBracket.toFloat(); f[15] = t.sepBefore.toFloat()
            f[16] = if (i + 1 < n) tokens[i + 1].sepBefore.toFloat() else 0f
            f[17] = lexClass(lower).toFloat(); f[18] = min(n - 1 - i, 6).toFloat(); f[19] = t.dashesBefore.toFloat()
            f[20] = t.bracketNo.toFloat(); f[21] = domain.toFloat()
            f[22] = min(t.bracketLen, 8).toFloat(); f[23] = min(t.bracketPos, 8).toFloat()
            f[28] = if (digits == 0) personScore(lower) else 0f
        }
        var years = 0; var numbers = 0
        for (i in 0 until n) { own[i][24] = min(years, 2).toFloat(); own[i][26] = min(numbers, 3).toFloat(); years += own[i][7].toInt(); numbers += own[i][1].toInt() }
        years = 0; numbers = 0
        for (i in n - 1 downTo 0) { own[i][25] = min(years, 2).toFloat(); own[i][27] = min(numbers, 3).toFloat(); years += own[i][7].toInt(); numbers += own[i][1].toInt() }
        return Array(n) { i ->
            val row = FloatArray(FEATURES)
            System.arraycopy(own[i], 0, row, 0, OWN)
            var at = OWN
            for (offset in OFFSETS) {
                val j = i + offset
                for (index in CONTEXT) row[at++] = if (j in 0 until n) own[j][index] else -1f
            }
            row
        }
    }

    private fun assemble(text: String, tokens: List<Token>, labels: IntArray, confidence: Float): ParsedName {
        fun runs(label: Int): List<IntRange> {
            val found = ArrayList<IntRange>(); var start = -1
            for (i in 0..labels.size) {
                val hit = i < labels.size && labels[i] == label
                if (hit && start < 0) start = i
                if (!hit && start >= 0) { found.add(start until i); start = -1 }
            }
            return found
        }
        fun span(run: IntRange): String {
            val piece = text.substring(tokens[run.first].start, tokens[run.last].end)
            val glued = run.last > run.first && (run.first + 1..run.last).all { tokens[it].sepBefore == 2 || tokens[it].sepBefore == 3 }
            return (if (glued) piece.replace('_', ' ').replace('.', ' ') else piece).trim()
        }
        val titles = runs(TITLE)
        var season: Int? = null; var episode: Int? = null; var year: Int? = null; var volume: Int? = null; var chapter: Int? = null
        for (i in labels.indices) {
            val lower = tokens[i].text.lowercase(Locale.ROOT)
            if (labels[i] == SE) {
                val m = SEASON_EPISODE.find(lower) ?: NUMBER_X_NUMBER.matchEntire(lower)
                if (m != null) {
                    if (season == null || season == 0) season = m.groupValues[1].toInt()
                    if (episode == null || episode == 0) episode = m.groupValues[2].toInt()
                }
                continue
            }
            val number = NUMBER.find(lower)?.value?.toInt() ?: continue
            when (labels[i]) {
                YEAR -> if (year == null) year = number
                SEASON -> if (season == null) season = number
                EPISODE -> if (episode == null) episode = number
                VOLUME -> if (volume == null) volume = number
                CHAPTER -> if (chapter == null) chapter = number
            }
        }
        return ParsedName(
            title = titles.getOrNull(0)?.let(::span), altTitle = titles.getOrNull(1)?.let(::span),
            year = year, season = season, episode = episode, volume = volume, chapter = chapter,
            author = runs(AUTHOR).firstOrNull()?.let(::span), narrator = runs(NARRATOR).firstOrNull()?.let(::span),
            group = runs(GROUP).firstOrNull()?.let(::span), confidence = confidence,
        )
    }

    companion object {
        const val VERSION = "name-features-v1"
        val LABELS = listOf("TITLE", "YEAR", "SEASON", "EPISODE", "VOLUME", "CHAPTER", "GROUP", "AUTHOR", "NARRATOR", "TECH", "CUE", "SE", "OTHER")
        private const val TITLE = 0
        private const val YEAR = 1
        private const val SEASON = 2
        private const val EPISODE = 3
        private const val VOLUME = 4
        private const val CHAPTER = 5
        private const val GROUP = 6
        private const val AUTHOR = 7
        private const val NARRATOR = 8
        private const val SE = 11
        private const val OWN = 29
        /** is_digit, is_alpha, lex, len, sep_before, depth, year_like, person — индексы в собственных признаках. */
        private val CONTEXT = intArrayOf(1, 2, 17, 0, 15, 12, 7, 28)
        private val OFFSETS = intArrayOf(-2, -1, 1, 2)
        const val FEATURES = OWN + 4 * 8
        private val OPEN: Map<Int, Int> = mapOf('[' to 1, '【' to 1, '(' to 2, '（' to 2, '{' to 3, '「' to 3, '『' to 3, '《' to 3, '«' to 3).mapKeys { it.key.code }
        private val CLOSE: Set<Int> = "]】)）}」』》»".map { it.code }.toSet()
        private const val DASHES = "-–—"
        // Только [0-9]: у ICU на Android и у JVM разное понимание \d.
        private val PATTERNS = listOf(
            13 to Regex("s[0-9]{1,2}e[0-9]{1,4}(?:v[0-9])?"), 14 to Regex("s[0-9]{1,2}"),
            15 to Regex("(?:e|ep|эп)[0-9]{1,4}(?:v[0-9])?"), 16 to Regex("[0-9]{1,2}x[0-9]{1,3}"),
            17 to Regex("v[0-9]{1,3}"), 18 to Regex("(?:c|ch|гл)[0-9]{1,4}"),
            2 to Regex("(?:bd|dvd|web)?[0-9]{3,4}[pi]|[48]k"), 20 to Regex("[0-9]{1,4}v[0-9]"),
        )
        private val HEX8 = Regex("[0-9a-f]{8}")
        private val SEASON_EPISODE = Regex("s([0-9]{1,2})e([0-9]{1,4})")
        private val NUMBER_X_NUMBER = Regex("([0-9]{1,2})x([0-9]{1,3})")
        private val NUMBER = Regex("[0-9]{1,4}")
    }
}
