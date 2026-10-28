package com.example.myapplication.domain.titleidentity

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Must stay numerically equal to tools/titlematch/features.py (pair-features-v1). */
object TitleMatchFeatures {
    const val VERSION = "pair-features-v1"
    const val MAX_BYTES = 192
    val names = listOf("levenshtein", "indel", "jaro_winkler", "token_jaccard", "token_subset",
        "substring_ratio", "length_ratio", "prefix_ratio", "suffix_ratio", "bigram_jaccard",
        "trigram_jaccard", "numeric_agreement", "numeric_conflict", "same_script", "normalized_equal",
        "token_count_ratio", "same_kind", "both_years", "year_agreement", "year_distance", "short_title", "truncated")

    fun normalize(text: String): String {
        val value = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val result = StringBuilder()
        var space = false
        value.codePoints().forEach { point ->
            if (Character.isLetterOrDigit(point)) {
                if (space && result.isNotEmpty()) result.append(' ')
                result.appendCodePoint(point)
                space = false
            } else space = true
        }
        return result.toString()
    }
    fun key(text: String): String = normalize(text).replace(" ", "")
    fun encode(text: String): IntArray {
        val bytes = normalize(text).toByteArray(Charsets.UTF_8)
        val selected = if (bytes.size > MAX_BYTES) bytes.take(MAX_BYTES / 2) + bytes.takeLast(MAX_BYTES / 2) else bytes.toList()
        return IntArray(MAX_BYTES).also { result -> selected.forEachIndexed { i, b -> result[i] = (b.toInt() and 255) + 1 } }
    }
    fun script(text: String): Int {
        val counts = IntArray(4)
        text.codePoints().filter { Character.isLetter(it) }.forEach { point ->
            val script = Character.UnicodeScript.of(point)
            counts[when (script) {
                Character.UnicodeScript.LATIN -> 0
                Character.UnicodeScript.CYRILLIC -> 1
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> 2
                else -> 3
            }]++
        }
        if (counts.all { it == 0 }) return -1
        return counts.indices.maxBy { counts[it] }
    }
    private fun <T> jaccard(a: Set<T>, b: Set<T>): Double =
        if (a.isEmpty() && b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size
    private fun grams(points: IntArray, size: Int): Set<Long> = (0..points.size - size).mapTo(HashSet()) { start ->
        var value = 0L
        repeat(size) { value = (value shl 21) or points[start + it].toLong() }
        value
    }
    private fun levenshtein(a: IntArray, b: IntArray): Int {
        var previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.size + 1); current[0] = i + 1
            for (j in b.indices) current[j + 1] = min(min(previous[j + 1] + 1, current[j] + 1), previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = current
        }
        return previous[b.size]
    }
    private fun lcs(a: IntArray, b: IntArray): Int {
        var previous = IntArray(b.size + 1)
        for (point in a) {
            val current = IntArray(b.size + 1)
            for (j in b.indices) current[j + 1] = if (point == b[j]) previous[j] + 1 else max(previous[j + 1], current[j])
            previous = current
        }
        return previous[b.size]
    }
    private fun jaroWinkler(a: IntArray, b: IntArray): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.contentEquals(b)) return 1.0
        val window = max(0, max(a.size, b.size) / 2 - 1)
        val am = BooleanArray(a.size); val bm = BooleanArray(b.size)
        var matches = 0
        for (i in a.indices) {
            for (j in max(0, i - window)..min(b.lastIndex, i + window)) if (!bm[j] && a[i] == b[j]) {
                am[i] = true; bm[j] = true; matches++; break
            }
        }
        if (matches == 0) return 0.0
        val aa = a.filterIndexed { i, _ -> am[i] }; val bb = b.filterIndexed { i, _ -> bm[i] }
        val transpositions = (aa.indices.count { aa[it] != bb[it] } / 2).toDouble()
        val score = (matches.toDouble() / a.size + matches.toDouble() / b.size + (matches - transpositions) / matches) / 3
        var prefix = 0
        while (prefix < min(4, min(a.size, b.size)) && a[prefix] == b[prefix]) prefix++
        return if (score > 0.7) score + prefix * 0.1 * (1 - score) else score
    }
    fun compute(a: String, b: String, kindA: String = "ANIME", kindB: String = kindA, yearA: Int? = null, yearB: Int? = null): FloatArray {
        require(a.length <= 4096 && b.length <= 4096) { "Title exceeds feature input limit" }
        val x = normalize(a); val y = normalize(b)
        val xx = x.codePoints().toArray(); val yy = y.codePoints().toArray()
        val kx = x.replace(" ", ""); val ky = y.replace(" ", "")
        val px = kx.codePoints().toArray(); val py = ky.codePoints().toArray()
        val ta = x.split(' ').filter(String::isNotBlank).toSet(); val tb = y.split(' ').filter(String::isNotBlank).toSet()
        val longest = max(1, max(px.size, py.size)).toDouble()
        var prefix = 0; var suffix = 0
        while (prefix < min(px.size, py.size) && px[prefix] == py[prefix]) prefix++
        while (suffix < min(px.size, py.size) && px[px.lastIndex - suffix] == py[py.lastIndex - suffix]) suffix++
        val numeric = Regex("\\p{Nd}+")
        val na = numeric.findAll(x).map { it.value }.toSet(); val nb = numeric.findAll(y).map { it.value }.toSet()
        val bothYears = yearA != null && yearB != null && yearA > 0 && yearB > 0
        fun flag(value: Boolean) = if (value) 1.0 else 0.0
        return doubleArrayOf(
            if (x.isNotEmpty() && y.isNotEmpty()) 1 - levenshtein(xx, yy).toDouble() / max(xx.size, yy.size) else 0.0,
            if (x.isNotEmpty() && y.isNotEmpty()) 2.0 * lcs(xx, yy) / (xx.size + yy.size) else 0.0,
            jaroWinkler(xx, yy), jaccard(ta, tb), flag(ta.isNotEmpty() && tb.isNotEmpty() && (ta.containsAll(tb) || tb.containsAll(ta))),
            if (kx.isNotEmpty() && ky.isNotEmpty() && (kx.contains(ky) || ky.contains(kx))) min(px.size, py.size) / longest else 0.0,
            min(px.size, py.size) / longest, prefix / longest, suffix / longest, jaccard(grams(px, 2), grams(py, 2)), jaccard(grams(px, 3), grams(py, 3)),
            flag(na == nb), flag(na.isNotEmpty() && nb.isNotEmpty() && na != nb), flag(script(x) == script(y)), flag(kx.isNotEmpty() && kx == ky),
            min(ta.size, tb.size).toDouble() / max(1, max(ta.size, tb.size)), flag(kindA == kindB), flag(bothYears), flag(bothYears && yearA == yearB),
            if (bothYears) min(kotlin.math.abs(yearA!! - yearB!!) / 10.0, 1.0) else 0.0, flag(min(px.size, py.size) < 4),
            flag(max(x.toByteArray(Charsets.UTF_8).size, y.toByteArray(Charsets.UTF_8).size) > MAX_BYTES)
        ).map(Double::toFloat).toFloatArray()
    }
}
