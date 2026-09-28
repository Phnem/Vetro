package com.example.myapplication.audiobooks.text

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

/** Слово распознавания (Whisper, пословные метки) на шкале книги. */
data class AsrWord(val key: String, val startMs: Long, val endMs: Long)

/** Якорь: слово распознавания в [timeMs] — это слово текста [token]. */
@Serializable
data class TextAnchor(val timeMs: Long, val token: Int)

/**
 * Реплика субтитров из текста книги: предложение [sentence] (display[textStart, textEnd)) звучит
 * с [startMs] по [endMs]. [openEnd] — конец предложения в следующем блоке, конец реплики берётся
 * по началу следующей. [confidence] 0..1 — см. [BookAudioAligner.SHOW_THRESHOLD].
 */
@Serializable
data class AlignedCue(
    val startMs: Long,
    val endMs: Long,
    val textStart: Int,
    val textEnd: Int,
    val sentence: Int,
    val confidence: Float,
    val openEnd: Boolean = false,
) {
    fun text(book: BookText): String = book.display.substring(textStart, textEnd)
}

/** Итог блока: якоря, реплики и доля распознанных слов, нашедших место в тексте. */
data class BlockAlignment(
    val anchors: List<TextAnchor>,
    val cues: List<AlignedCue>,
    val matchRatio: Float,
    /** Окно пришлось расширять — прежняя позиция в тексте не подтвердилась. */
    val resynced: Boolean,
)

/**
 * BOOK ↔ AUDIO: сопоставление распознанной речи блока с текстом книги.
 *
 * Не ищет каждую фразу по всей книге: сначала — в окне вокруг ожидаемого места (по уже найденным
 * якорям), при потере синхронизации окно расширяется до ±15 % книги, затем до всей. Якоря — точные
 * совпадения трёх слов подряд, собранные в цепочку, возрастающую и по речи, и по тексту; одиночное
 * совпадение не в цепочке отбрасывается, поэтому одна ошибка не сдвигает остаток книги.
 */
class BookAudioAligner(private val book: BookText) {
    private val tokens = book.tokens
    private val index: Map<String, IntArray> = buildIndex()

    /**
     * [words] — распознанная речь блока по порядку; [expectedToken] — где в тексте ожидается начало
     * блока (null — неизвестно, ищем по всей книге); [blockEndMs] — конец блока (для открытых реплик).
     */
    fun align(words: List<AsrWord>, expectedToken: Int?, blockEndMs: Long): BlockAlignment {
        if (words.size < GRAM || tokens.size < GRAM) return BlockAlignment(emptyList(), emptyList(), 0f, false)
        val attempts = buildList {
            if (expectedToken != null) {
                val local = max(LOCAL_WINDOW_MIN, words.size * 3)
                add(window(expectedToken - local / 3, expectedToken + local))
                val wide = max(tokens.size * 15 / 100, local * 2)
                add(window(expectedToken - wide, expectedToken + wide))
            }
            add(0 until tokens.size)
        }
        var best: Pair<List<Pair<Int, Int>>, Int>? = null
        for ((attempt, range) in attempts.withIndex()) {
            val chain = chain(words, range)
            val ratio = chain.size.toFloat() / words.size
            if (best == null || chain.size > best.first.size) best = chain to attempt
            if (ratio >= GOOD_RATIO) break
        }
        val (pairs, attempt) = best!!
        val ratio = pairs.size.toFloat() / words.size
        if (ratio < MIN_RATIO) return BlockAlignment(emptyList(), emptyList(), ratio, attempt > 0)
        val anchors = pairs.map { (a, b) -> TextAnchor(words[a].startMs, b) }
        return BlockAlignment(anchors, cues(words, pairs, blockEndMs), ratio, attempt > 0)
    }

    /**
     * Где в тексте должно звучать время [timeMs]: между соседними известными якорями — по ним, с
     * одной стороны — по среднему темпу, без якорей — по доле книги. Не по «словам в минуту»
     * вообще, а по фактическому темпу этой записи, где он уже известен.
     */
    fun expectedToken(timeMs: Long, anchors: List<TextAnchor>, totalMs: Long?): Int? {
        if (anchors.isEmpty()) {
            val total = totalMs ?: return null
            return (tokens.size.toDouble() * timeMs / total).toInt().coerceIn(0, tokens.size - 1)
        }
        val sorted = anchors.sortedBy { it.timeMs }
        val after = sorted.firstOrNull { it.timeMs >= timeMs }
        val before = sorted.lastOrNull { it.timeMs <= timeMs }
        if (before != null && after != null && after.timeMs > before.timeMs) {
            val f = (timeMs - before.timeMs).toDouble() / (after.timeMs - before.timeMs)
            return (before.token + f * (after.token - before.token)).toInt()
        }
        val rate = if (sorted.size >= 2 && sorted.last().timeMs > sorted.first().timeMs) {
            (sorted.last().token - sorted.first().token).toDouble() / (sorted.last().timeMs - sorted.first().timeMs)
        } else DEFAULT_WORDS_PER_MS
        val ref = before ?: after!!
        return (ref.token + (timeMs - ref.timeMs) * rate.coerceIn(0.5 * DEFAULT_WORDS_PER_MS, 3 * DEFAULT_WORDS_PER_MS))
            .toInt().coerceIn(0, tokens.size - 1)
    }

    private fun window(from: Int, to: Int) = from.coerceAtLeast(0) until to.coerceAtMost(tokens.size)

    /** Цепочка пар (слово речи, слово текста), возрастающая по обеим осям, с поддержкой соседей. */
    private fun chain(words: List<AsrWord>, range: IntRange): List<Pair<Int, Int>> {
        val grams = ArrayList<Pair<Int, Int>>()
        for (a in 0..words.size - GRAM) {
            val key = gramKey(words[a].key, words[a + 1].key, words[a + 2].key)
            val positions = index[key] ?: continue
            if (positions.size > COMMON_GRAM) continue
            var inWindow = 0
            val first = grams.size
            for (b in positions) if (b in range) { grams += a to b; inWindow++ }
            // Фраза, повторяющаяся рядом много раз («сказал он»), — не якорь.
            if (inWindow > LOCAL_AMBIGUITY) while (grams.size > first) grams.removeAt(grams.size - 1)
        }
        if (grams.isEmpty()) return emptyList()
        grams.sortWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        // Взвешенная возрастающая цепочка: несогласованный прыжок стоит дороже, чем даёт.
        val n = grams.size
        val score = FloatArray(n)
        val prev = IntArray(n) { -1 }
        var bestEnd = 0
        for (i in 0 until n) {
            score[i] = 1f
            val (ai, bi) = grams[i]
            var j = i - 1
            var looked = 0
            while (j >= 0 && looked < MAX_LOOKBACK) {
                val (aj, bj) = grams[j]
                if (aj < ai && bj < bi) {
                    val da = ai - aj
                    val db = bi - bj
                    val penalty = if (abs(db - da) <= 30 + max(da, db) / 2) 0f else JUMP_PENALTY
                    val s = score[j] + 1f - penalty
                    if (s > score[i]) { score[i] = s; prev[i] = j }
                }
                j--
                looked++
            }
            if (score[i] > score[bestEnd]) bestEnd = i
        }
        val picked = ArrayList<Pair<Int, Int>>()
        var k = bestEnd
        while (k >= 0) { picked += grams[k]; k = prev[k] }
        picked.reverse()
        // Одиночная тройка без соседней (a±1, b±1) — случайность; оставляем только серии.
        val supported = picked.filterIndexed { i, (a, b) ->
            val before = picked.getOrNull(i - 1)
            val after = picked.getOrNull(i + 1)
            (before != null && before.first == a - 1 && before.second == b - 1) ||
                (after != null && after.first == a + 1 && after.second == b + 1)
        }
        // Тройка даёт три слова-якоря.
        val words2 = LinkedHashMap<Int, Int>()
        for ((a, b) in supported) for (d in 0 until GRAM) {
            if (!words2.containsKey(a + d) && (words2.values.lastOrNull() ?: -1) < b + d) words2[a + d] = b + d
        }
        return words2.entries.map { it.key to it.value }.sortedBy { it.first }
    }

    /** Реплики предложений, начало которых попало в этот блок. */
    private fun cues(words: List<AsrWord>, pairs: List<Pair<Int, Int>>, blockEndMs: Long): List<AlignedCue> {
        val anchoredTokens = HashSet<Int>(pairs.size * 2).apply { pairs.forEach { add(it.second) } }
        val firstB = pairs.first().second
        val lastB = pairs.last().second

        /** Слово речи для слова текста [b]: между якорями — по доле пути, у краёв — не дальше EDGE. */
        fun asrIndexOf(b: Int): Int? {
            val right = pairs.binarySearchBy(b) { it.second }.let { if (it >= 0) return pairs[it].first else -it - 1 }
            val lo = pairs.getOrNull(right - 1)
            val hi = pairs.getOrNull(right)
            return when {
                lo != null && hi != null -> {
                    val f = (b - lo.second).toDouble() / (hi.second - lo.second)
                    (lo.first + f * (hi.first - lo.first)).toInt()
                }
                lo != null -> (lo.first + (b - lo.second)).takeIf { b - lo.second <= EDGE && it < words.size }
                hi != null -> (hi.first - (hi.second - b)).takeIf { hi.second - b <= EDGE && it >= 0 }
                else -> null
            }
        }

        /** Разрыв между якорями вокруг [b] правдоподобен: текста не сильно больше, чем речи. */
        fun consistentGap(b: Int): Boolean {
            val right = -(pairs.binarySearchBy(b) { it.second }) - 1
            val lo = pairs.getOrNull(right - 1) ?: return false
            val hi = pairs.getOrNull(right) ?: return false
            val db = hi.second - lo.second
            val da = hi.first - lo.first + 1
            return db.toDouble() / da in 0.4..2.5 && words[hi.first].startMs - words[lo.first].startMs < 60_000
        }

        val sentences = book.sentences
        val firstSentence = tokens.sentence[max(0, firstB - EDGE).coerceAtMost(tokens.size - 1)]
        val lastSentence = tokens.sentence[min(tokens.size - 1, lastB + EDGE)]
        val out = ArrayList<AlignedCue>()
        for (s in firstSentence..lastSentence) {
            val range = sentenceTokens(s) ?: continue
            val startA = asrIndexOf(range.first) ?: continue
            val endA = asrIndexOf(range.last)
            val anchored = range.count { it in anchoredTokens }
            val frac = anchored.toFloat() / range.count()
            val confidence = when {
                frac >= 0.5f -> 0.6f + 0.4f * frac
                frac > 0f -> 0.3f + 0.4f * frac
                consistentGap(range.first) -> 0.3f
                else -> 0.1f
            }
            val start = words[startA.coerceIn(0, words.lastIndex)].startMs
            val end = endA?.let { words[it.coerceIn(0, words.lastIndex)].endMs } ?: blockEndMs
            if (end <= start) continue
            out += AlignedCue(start, end, sentences[s].start, sentences[s].end, s, confidence, openEnd = endA == null)
        }
        // Шкала только вперёд: реплика не может начаться раньше предыдущей.
        return out.sortedBy { it.sentence }.fold(ArrayList()) { acc, c ->
            val last = acc.lastOrNull()
            if (last == null || c.startMs >= last.startMs) acc += c
            acc
        }
    }

    private val sentenceRanges: Array<IntRange?> by lazy {
        val ranges = arrayOfNulls<IntRange>(book.sentences.size)
        var i = 0
        while (i < tokens.size) {
            val s = tokens.sentence[i]
            var j = i
            while (j + 1 < tokens.size && tokens.sentence[j + 1] == s) j++
            if (s in ranges.indices) ranges[s] = i..j
            i = j + 1
        }
        ranges
    }

    private fun sentenceTokens(s: Int): IntRange? = sentenceRanges.getOrNull(s)

    private fun buildIndex(): Map<String, IntArray> {
        val lists = HashMap<String, MutableList<Int>>(tokens.size)
        for (b in 0..tokens.size - GRAM) {
            lists.getOrPut(gramKey(tokens.keys[b], tokens.keys[b + 1], tokens.keys[b + 2])) { ArrayList(1) } += b
        }
        return lists.mapValues { it.value.toIntArray() }
    }

    private fun gramKey(a: String, b: String, c: String) = "$a $b $c"

    companion object {
        const val GRAM = 3
        /** Реплики с уверенностью ниже — не показываются: пустая строка лучше неверного текста. */
        const val SHOW_THRESHOLD = 0.3f
        /** Меньше этой доли слов блока нашли место в тексте — блок не выровнен (вступление, другой текст). */
        const val MIN_RATIO = 0.12f
        /** Столько хватает, чтобы не расширять окно дальше. */
        private const val GOOD_RATIO = 0.3f
        private const val LOCAL_WINDOW_MIN = 1500
        private const val COMMON_GRAM = 400
        private const val LOCAL_AMBIGUITY = 4
        private const val MAX_LOOKBACK = 400
        private const val JUMP_PENALTY = 3f
        /** Сколько слов у края блока можно отнести к речи без якоря по соседству. */
        private const val EDGE = 8
        /** Средний темп чтения для оценки позиции, пока своего нет: ~2,4 слова в секунду. */
        private const val DEFAULT_WORDS_PER_MS = 0.0024
    }
}
