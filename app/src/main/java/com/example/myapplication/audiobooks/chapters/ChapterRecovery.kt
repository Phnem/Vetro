package com.example.myapplication.audiobooks.chapters

import kotlin.math.max
import kotlinx.serialization.Serializable

/**
 * Автовосстановление глав: когда у книги нет разметки (один файл на десять часов, «Track 01»),
 * главы собираются из трёх сигналов, от надёжного к догадке:
 *
 * 1. метаданные — CUE-лист рядом с файлом, встроенные главы (уже в манифесте);
 * 2. паузы — чтец делает между главами паузу заметно длиннее, чем между абзацами;
 * 3. транскрипт — сразу после паузы звучит «Глава третья» / «Chapter 12» (Whisper на устройстве).
 *
 * Здесь — только расчёт: без Android и без звука, поэтому всё проверяется юнит-тестами.
 */

/** Тишина на шкале книги. */
data class SilenceGap(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/** Найденная глава. [confirmed] — начало подтверждено заголовком в речи или метаданными. */
@Serializable
data class RecoveredChapter(
    val startMs: Long,
    val title: String,
    val confirmed: Boolean,
)

/** Заголовок, услышанный в начале главы: «Глава 3», «Пролог». */
data class Heading(val title: String, val number: Int?)

object SilenceDetector {
    /** Шаг огибающей громкости. */
    const val FRAME_MS = 100L

    /** Тишина короче этого — пауза между фразами, не рассматривается вовсе. */
    const val MIN_GAP_MS = 900L

    /**
     * Паузы по огибающей [db] (уровень кадра в дБ со сдвигом: 0 — тишина −100 дБ, 100 — 0 дБ).
     * Порог — от самой книги: между «полом» записи (шум, 5-й процентиль) и обычной речью (медиана),
     * ближе к полу. Так одинаково работают и тихая запись с шумом, и громкая чистая.
     */
    fun gaps(db: ByteArray, offsetMs: Long = 0L): List<SilenceGap> {
        if (db.isEmpty()) return emptyList()
        val levels = IntArray(db.size) { db[it].toInt() and 0xFF }
        val sorted = levels.sorted()
        val floor = sorted[(sorted.size * 0.05).toInt().coerceAtMost(sorted.lastIndex)]
        val speech = sorted[sorted.size / 2]
        // Запись без выраженной тишины (музыка под текстом) — порог по полу, иначе паузы не найти.
        val threshold = floor + max(3, (speech - floor) / 4)
        val gaps = ArrayList<SilenceGap>()
        var runStart = -1
        for (i in levels.indices) {
            val quiet = levels[i] <= threshold
            if (quiet && runStart < 0) runStart = i
            if ((!quiet || i == levels.lastIndex) && runStart >= 0) {
                val end = if (quiet) i + 1 else i
                val lengthMs = (end - runStart) * FRAME_MS
                if (lengthMs >= MIN_GAP_MS) {
                    gaps += SilenceGap(offsetMs + runStart * FRAME_MS, offsetMs + end * FRAME_MS)
                }
                runStart = -1
            }
        }
        return gaps
    }
}

object ChapterProposer {
    /** Глава короче этого — почти наверняка пауза внутри главы, а не граница. */
    const val MIN_CHAPTER_MS = 4 * 60_000L

    /** Абсолютный минимум паузы между главами; обычно она 2–5 секунд. */
    const val MIN_CHAPTER_GAP_MS = 1_800L

    /** Во сколько раз пауза между главами длиннее обычной паузы этой книги. */
    const val GAP_RATIO = 2.2

    /** Сколько кандидатов слушать Whisper'ом: самые длинные паузы. */
    const val MAX_TRANSCRIBED = 80

    /** Где чтец начинает говорить после паузы: чуть раньше конца тишины, чтобы не срезать слог. */
    fun boundaryOf(gap: SilenceGap): Long = (gap.endMs - 250L).coerceAtLeast(gap.startMs)

    /**
     * Паузы, после которых стоит послушать речь: достаточно длинные и не у самого края книги.
     * Самые длинные — первыми, не больше [MAX_TRANSCRIBED].
     */
    fun candidates(gaps: List<SilenceGap>, totalMs: Long): List<SilenceGap> {
        val usual = median(gaps.map { it.lengthMs }) ?: return emptyList()
        val minimum = max(MIN_CHAPTER_GAP_MS * 2 / 3, (usual * GAP_RATIO * 0.7).toLong())
        return gaps
            .filter { it.lengthMs >= minimum && it.endMs > 60_000L && it.endMs < totalMs - 60_000L }
            .sortedByDescending { it.lengthMs }
            .take(MAX_TRANSCRIBED)
    }

    /**
     * Итоговая разметка. [headings] — что прозвучало после паузы (ключ — пауза из [gaps]);
     * [opening] — заголовок в самом начале книги; [fixedStarts] — начала, известные наверняка
     * (границы файлов, если их несколько). [chapterWord] — «Глава»/«Chapter» для глав без заголовка.
     *
     * Если заголовков услышано хотя бы два — главы строятся по ним (надёжно), иначе — по самым
     * длинным паузам с минимальной длиной главы. Пусто — нечего предложить.
     */
    fun propose(
        totalMs: Long,
        gaps: List<SilenceGap>,
        headings: Map<SilenceGap, Heading>,
        opening: Heading? = null,
        fixedStarts: List<Long> = emptyList(),
        chapterWord: String = "Глава",
    ): List<RecoveredChapter> {
        if (totalMs < 2 * MIN_CHAPTER_MS) return emptyList()
        val starts = sortedMapOf<Long, Pair<Heading?, Boolean>>()
        starts[0L] = opening to true
        fixedStarts.filter { it in 1 until totalMs }.forEach { starts[it] = null to true }

        fun spacedFromAll(t: Long) = starts.keys.none { kotlin.math.abs(it - t) < MIN_CHAPTER_MS }

        val heard = headings.entries.sortedBy { it.key.endMs }
        if (heard.size >= 2) {
            for ((gap, heading) in heard) {
                val t = boundaryOf(gap)
                // Заголовок сильнее ограничения длины: короткие главы бывают, но не короче минуты.
                if (starts.keys.none { kotlin.math.abs(it - t) < 60_000L }) starts[t] = heading to true
            }
        } else {
            val usual = median(gaps.map { it.lengthMs }) ?: return emptyList()
            val threshold = max(MIN_CHAPTER_GAP_MS, (usual * GAP_RATIO).toLong())
            val limit = (totalMs / MIN_CHAPTER_MS).toInt()
            gaps.filter { it.lengthMs >= threshold || it in headings }
                .sortedWith(compareByDescending<SilenceGap> { it in headings }.thenByDescending { it.lengthMs })
                .forEach { gap ->
                    if (starts.size >= limit) return@forEach
                    val t = boundaryOf(gap)
                    if (t < totalMs - MIN_CHAPTER_MS / 2 && spacedFromAll(t)) starts[t] = headings[gap] to (gap in headings)
                }
        }
        if (starts.size < 2) return emptyList()

        var numbered = 0
        return starts.entries.map { (start, value) ->
            val (heading, confirmed) = value
            numbered++
            RecoveredChapter(
                startMs = start,
                title = heading?.title ?: "$chapterWord $numbered",
                confirmed = confirmed || heading != null,
            )
        }
    }

    private fun median(values: List<Long>): Long? = values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
}

/**
 * «Глава третья», «Часть 2», «Chapter Twelve», «Пролог» в начале распознанной фразы. Номер — цифрами,
 * римскими или словами (русские порядковые/количественные до 99, английские до 99).
 */
object HeadingParser {
    private val numbered = mapOf(
        "глава" to "Глава", "часть" to "Часть", "книга" to "Книга",
        "chapter" to "Chapter", "part" to "Part", "book" to "Book",
    )
    private val standalone = mapOf(
        "пролог" to "Пролог", "эпилог" to "Эпилог", "предисловие" to "Предисловие",
        "послесловие" to "Послесловие", "вступление" to "Вступление", "интерлюдия" to "Интерлюдия",
        "prologue" to "Prologue", "epilogue" to "Epilogue", "preface" to "Preface",
        "introduction" to "Introduction", "afterword" to "Afterword", "interlude" to "Interlude",
    )

    /** Заголовок ищется только в первых словах: «глава» посреди текста — не граница. */
    private const val WINDOW_WORDS = 8

    fun parse(text: String): Heading? {
        val words = text.lowercase().replace('ё', 'е')
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .take(WINDOW_WORDS)
        val parts = ArrayList<String>()
        var number: Int? = null
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val keyword = numbered[word]
            if (keyword != null && i + 1 < words.size) {
                val parsed = NumberWords.parse(words, i + 1)
                if (parsed != null) {
                    parts += "$keyword ${parsed.first}"
                    number = parsed.first
                    i += 1 + parsed.second
                    continue
                }
            }
            standalone[word]?.let { if (parts.isEmpty()) return Heading(it, null) }
            i++
        }
        return if (parts.isEmpty()) null else Heading(parts.joinToString(". "), number)
    }
}

/** Число из слов, начиная с [from]: (значение, сколько слов занято). */
internal object NumberWords {
    // Порядковые — основа плюс окончание («третья», «двадцатой»): без проверки окончания «семьи»
    // или «пятно» превращались бы в числа.
    private val ordinalStems: List<Pair<String, Int>> = listOf(
        "перв" to 1, "втор" to 2, "трет" to 3, "четверт" to 4, "пят" to 5, "шест" to 6, "седьм" to 7,
        "восьм" to 8, "девят" to 9, "десят" to 10, "одиннадцат" to 11, "двенадцат" to 12, "тринадцат" to 13,
        "четырнадцат" to 14, "пятнадцат" to 15, "шестнадцат" to 16, "семнадцат" to 17, "восемнадцат" to 18,
        "девятнадцат" to 19, "двадцат" to 20, "тридцат" to 30, "сороков" to 40, "пятидесят" to 50,
        "шестидесят" to 60, "семидесят" to 70, "восьмидесят" to 80, "девяност" to 90,
    )
    private val ordinalEndings = setOf(
        "ая", "ой", "ый", "ое", "ий", "ую", "ые", "ых", "ым", "ого", "ому", "ом", "ей", "ем", "его", "ему",
        "ья", "ье", "ьи", "ьей", "ьего", "ьему", "ьем", "ьим", "ью",
    )
    private val cardinals = mapOf(
        "один" to 1, "одна" to 1, "одно" to 1, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10, "одиннадцать" to 11,
        "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16,
        "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19, "двадцать" to 20, "тридцать" to 30,
        "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80,
        "девяносто" to 90,
    )

    private val english = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90,
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12,
        "thirteenth" to 13, "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16,
        "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19, "twentieth" to 20,
        "thirtieth" to 30, "fortieth" to 40, "fiftieth" to 50,
    )

    fun parse(words: List<String>, from: Int): Pair<Int, Int>? {
        val first = words.getOrNull(from) ?: return null
        first.toIntOrNull()?.let { return it to 1 }
        roman(first)?.let { return it to 1 }
        val value = single(first) ?: return null
        if (value in 20..90 && value % 10 == 0) {
            val unit = words.getOrNull(from + 1)?.let(::single)
            if (unit != null && unit in 1..9) return value + unit to 2
        }
        return value to 1
    }

    private fun single(word: String): Int? {
        // «twenty-third» после разбиения — два слова, «third» ловится как единица.
        english[word]?.let { return it }
        cardinals[word]?.let { return it }
        return ordinalStems.firstOrNull { (stem, _) -> word.startsWith(stem) && word.removePrefix(stem) in ordinalEndings }?.second
    }

    private fun roman(word: String): Int? {
        if (!word.matches(Regex("[ivxlc]+"))) return null
        val values = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100)
        var total = 0
        for (k in word.indices) {
            val v = values.getValue(word[k])
            val next = word.getOrNull(k + 1)?.let(values::getValue) ?: 0
            total += if (v < next) -v else v
        }
        return total.takeIf { it in 1..199 }
    }
}

/**
 * CUE-лист (рядом с единственным файлом книги): TRACK/TITLE/INDEX 01 mm:ss:ff. Время — от начала файла.
 */
object CueSheet {
    fun parse(text: String): List<RecoveredChapter> {
        val chapters = ArrayList<RecoveredChapter>()
        var title: String? = null
        var inTrack = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("TRACK ", ignoreCase = true) -> { inTrack = true; title = null }
                inTrack && line.startsWith("TITLE ", ignoreCase = true) ->
                    title = line.substringAfter(' ').trim().trim('"')
                inTrack && line.startsWith("INDEX 01 ", ignoreCase = true) -> {
                    val parts = line.substringAfter("INDEX 01 ", "").trim().split(':')
                    if (parts.size == 3) {
                        val ms = runCatching {
                            parts[0].toLong() * 60_000L + parts[1].toLong() * 1_000L + parts[2].toLong() * 1000L / 75
                        }.getOrNull()
                        if (ms != null) chapters += RecoveredChapter(ms, title?.takeIf { it.isNotBlank() } ?: "${chapters.size + 1}", true)
                    }
                }
            }
        }
        return chapters.distinctBy { it.startMs }.sortedBy { it.startMs }
    }
}
