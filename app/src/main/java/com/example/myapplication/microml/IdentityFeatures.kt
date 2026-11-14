package com.example.myapplication.microml

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Тип медиа в терминах моделей. Порядок — часть контракта признаков, менять нельзя. */
enum class MicroKind { ANIME, MANGA, MOVIE, SERIES, BOOK_WORK, AUDIOBOOK }

/** Запись в том виде, в каком её держит приложение: до четырёх названий, тип, год, авторы. Внешние ID сюда не входят. */
data class MicroRecord(
    val titles: List<String>,
    val kind: MicroKind,
    val year: Int? = null,
    val authors: List<String> = emptyList(),
)

/**
 * identity-features-v2 — зеркало tools/microml/identity/features.py.
 * Признаки симметричны: порядок записей в паре на результат не влияет.
 */
object IdentityFeatures {
    const val VERSION = "identity-features-v2"
    const val MAX_TITLES = 4
    val names = listOf(
        "ss_lev", "ss_jw", "ss_token_jaccard", "ss_token_subset", "ss_substring", "ss_bigram_jaccard", "ss_prefix",
        "sk_lev", "sk_jw", "sk_token_jaccard", "sk_token_subset", "sk_substring", "sk_bigram_jaccard",
        "enc_max", "enc_soft", "enc_mean_vec", "enc_cross_max", "enc_min_rowmax",
        "exact_key", "exact_skeleton", "has_same_script_pair", "has_cross_script_pair",
        "n_titles_min", "n_titles_max", "min_key_len", "max_key_len",
        "seq_equal", "seq_conflict", "seq_one_sided", "seq_jaccard", "variant_equal", "variant_diff",
        "title_year_conflict", "same_kind", "kind_lo", "kind_hi", "both_years", "year_equal", "year_diff",
        "both_authors", "author_sk_lev", "author_token_jaccard",
    )

    /** Предобработанная запись: считать один раз и сравнивать со многими кандидатами. */
    class Prepared internal constructor(
        val norm: List<String>,
        internal val points: List<IntArray>,
        internal val key: List<String>,
        internal val keyPoints: List<IntArray>,
        internal val tokens: List<Set<String>>,
        internal val skeleton: List<String>,
        internal val skeletonPoints: List<IntArray>,
        internal val skeletonKey: List<String>,
        internal val skeletonKeyPoints: List<IntArray>,
        internal val skeletonTokens: List<Set<String>>,
        internal val script: IntArray,
        val vectors: List<FloatArray>?,
        internal val sequence: Set<Int>,
        internal val variants: Set<String>,
        internal val years: Set<Int>,
        internal val kind: Int,
        internal val year: Int?,
        internal val authors: List<Set<String>>,
    )

    private fun points(s: String): IntArray = s.codePoints().toArray()

    fun prepare(record: MicroRecord, encoder: MicroTable?): Prepared {
        val norm = ArrayList<String>(MAX_TITLES)
        for (title in record.titles) {
            if (title.length > 4096) continue
            val full = MicroText.normalize(title)
            val n = if (full.codePointCount(0, full.length) > 200) full.substring(0, full.offsetByCodePoints(0, 200)) else full
            if (n.isNotEmpty() && n !in norm) norm.add(n)
            if (norm.size == MAX_TITLES) break
        }
        val key = norm.map { it.replace(" ", "") }
        val skeleton = norm.map(MicroText::skeleton)
        val skeletonKey = skeleton.map { it.replace(" ", "") }
        val sequence = HashSet<Int>(); val variants = HashSet<String>(); val years = HashSet<Int>()
        for (title in record.titles.take(MAX_TITLES * 2)) {
            if (title.length > 4096) continue
            val m = MicroText.markers(title)
            sequence += m.sequence; variants += m.variants; years += m.years
        }
        val authors = record.authors.take(4).map { name ->
            MicroText.skeleton(MicroText.normalize(name)).split(' ').filter { it.codePointCount(0, it.length) >= 2 }.toSet()
        }.filter { it.isNotEmpty() }
        return Prepared(
            norm = norm, points = norm.map(::points), key = key, keyPoints = key.map(::points),
            tokens = norm.map { it.split(' ').toSet() }, skeleton = skeleton, skeletonPoints = skeleton.map(::points),
            skeletonKey = skeletonKey, skeletonKeyPoints = skeletonKey.map(::points),
            skeletonTokens = skeleton.map { it.split(' ').toSet() }, script = IntArray(norm.size) { MicroText.script(norm[it]) },
            vectors = if (encoder != null && norm.isNotEmpty()) norm.map(encoder::embed) else null,
            sequence = sequence, variants = variants, years = years, kind = record.kind.ordinal,
            year = record.year?.takeIf { it > 0 }, authors = authors,
        )
    }

    private fun <T> jaccard(a: Set<T>, b: Set<T>): Float {
        if (a.isEmpty() && b.isEmpty()) return 0f
        var common = 0
        for (item in a) if (item in b) common++
        return (common.toDouble() / (a.size + b.size - common)).toFloat() // деление в double, как в Python
    }

    private fun bigrams(p: IntArray): Set<Long> {
        val out = HashSet<Long>(p.size)
        for (i in 0 until p.size - 1) out.add((p[i].toLong() shl 21) or p[i + 1].toLong())
        return out
    }

    internal fun levenshteinSimilarity(a: IntArray, b: IntArray): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        var previous = IntArray(b.size + 1) { it }
        var current = IntArray(b.size + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = min(min(previous[j + 1] + 1, current[j] + 1), previous[j] + if (a[i] == b[j]) 0 else 1)
            }
            val swap = previous; previous = current; current = swap
        }
        return (1.0 - previous[b.size].toDouble() / max(a.size, b.size)).toFloat()
    }

    internal fun jaroWinkler(a: IntArray, b: IntArray): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a.contentEquals(b)) return 1f
        val window = max(0, max(a.size, b.size) / 2 - 1)
        val am = BooleanArray(a.size); val bm = BooleanArray(b.size)
        var matches = 0
        for (i in a.indices) {
            for (j in max(0, i - window)..min(b.lastIndex, i + window)) if (!bm[j] && a[i] == b[j]) {
                am[i] = true; bm[j] = true; matches++; break
            }
        }
        if (matches == 0) return 0f
        var transpositions = 0
        var k = 0
        for (i in a.indices) if (am[i]) {
            while (!bm[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.size + m / b.size + (m - transpositions / 2) / m) / 3
        var prefix = 0
        while (prefix < min(4, min(a.size, b.size)) && a[prefix] == b[prefix]) prefix++
        return (if (jaro > 0.7) jaro + prefix * 0.1 * (1 - jaro) else jaro).toFloat()
    }

    /** lev, jw, token_jaccard, token_subset, substring, bigram_jaccard, prefix. */
    private fun stringSims(
        x: IntArray, y: IntArray, kx: String, ky: String, px: IntArray, py: IntArray, tx: Set<String>, ty: Set<String>, out: FloatArray,
    ) {
        val longest = max(max(px.size, py.size), 1)
        val shortest = min(px.size, py.size)
        var prefix = 0
        while (prefix < shortest && px[prefix] == py[prefix]) prefix++
        out[0] = levenshteinSimilarity(x, y)
        out[1] = jaroWinkler(x, y)
        out[2] = jaccard(tx, ty)
        out[3] = if (ty.containsAll(tx) || tx.containsAll(ty)) 1f else 0f
        out[4] = if (kx.isNotEmpty() && ky.isNotEmpty() && (kx in ky || ky in kx)) (shortest.toDouble() / longest).toFloat() else 0f
        out[5] = jaccard(bigrams(px), bigrams(py))
        out[6] = (prefix.toDouble() / longest).toFloat()
    }

    fun compute(a: Prepared, b: Prepared): FloatArray {
        val f = FloatArray(names.size)
        if (a.norm.isEmpty() || b.norm.isEmpty()) return f
        val sims = FloatArray(7)
        var exactKey = 0f; var exactSkeleton = 0f; var hasSame = 0f; var hasCross = 0f
        for (i in a.norm.indices) for (j in b.norm.indices) {
            if (a.script[i] == b.script[j]) {
                hasSame = 1f
                stringSims(a.points[i], b.points[j], a.key[i], b.key[j], a.keyPoints[i], b.keyPoints[j], a.tokens[i], b.tokens[j], sims)
                for (k in 0 until 7) if (sims[k] > f[k]) f[k] = sims[k]
                if (a.key[i] == b.key[j]) exactKey = 1f
            } else hasCross = 1f
            stringSims(a.skeletonPoints[i], b.skeletonPoints[j], a.skeletonKey[i], b.skeletonKey[j],
                a.skeletonKeyPoints[i], b.skeletonKeyPoints[j], a.skeletonTokens[i], b.skeletonTokens[j], sims)
            for (k in 0 until 6) if (sims[k] > f[7 + k]) f[7 + k] = sims[k]
            if (a.skeletonKey[i].isNotEmpty() && a.skeletonKey[i] == b.skeletonKey[j]) exactSkeleton = 1f
        }
        val va = a.vectors; val vb = b.vectors
        if (va != null && vb != null) {
            val rows = FloatArray(va.size) { -2f }; val cols = FloatArray(vb.size) { -2f }
            var best = -2f; var cross = -2f; var anyCross = false
            for (i in va.indices) for (j in vb.indices) {
                val s = MicroTable.dot(va[i], vb[j])
                if (s > rows[i]) rows[i] = s
                if (s > cols[j]) cols[j] = s
                if (s > best) best = s
                if (a.script[i] != b.script[j]) { anyCross = true; if (s > cross) cross = s }
            }
            f[13] = best
            f[14] = (rows.average().toFloat() + cols.average().toFloat()) / 2f
            val dim = va[0].size
            val ma = FloatArray(dim); val mb = FloatArray(dim)
            for (v in va) for (d in 0 until dim) ma[d] += v[d]
            for (v in vb) for (d in 0 until dim) mb[d] += v[d]
            val denominator = sqrt(MicroTable.dot(ma, ma)) * sqrt(MicroTable.dot(mb, mb))
            f[15] = if (denominator > 0f) MicroTable.dot(ma, mb) / denominator else 0f
            f[16] = if (anyCross) cross else 0f
            f[17] = min(rows.min(), cols.min())
            // Do not let accumulation order or BLAS rounding determine branches near identical vectors.
            for (k in 13..17) f[k] = (floor(f[k].toDouble().coerceIn(-1.0, 1.0) * 10000 + 0.5) / 10000).toFloat()
        }
        f[18] = exactKey; f[19] = exactSkeleton; f[20] = hasSame; f[21] = hasCross
        f[22] = min(a.norm.size, b.norm.size).toFloat()
        f[23] = max(a.norm.size, b.norm.size).toFloat()
        var shortest = Int.MAX_VALUE; var longest = 0
        for (p in a.keyPoints) { shortest = min(shortest, p.size); longest = max(longest, p.size) }
        for (p in b.keyPoints) { shortest = min(shortest, p.size); longest = max(longest, p.size) }
        f[24] = min(shortest, 40).toFloat()
        f[25] = min(longest, 80).toFloat()
        val commonSequence = a.sequence.any { it in b.sequence }
        f[26] = if (a.sequence == b.sequence) 1f else 0f
        f[27] = if (a.sequence.isNotEmpty() && b.sequence.isNotEmpty() && !commonSequence) 1f else 0f
        f[28] = if (a.sequence.isEmpty() != b.sequence.isEmpty()) 1f else 0f
        f[29] = jaccard(a.sequence, b.sequence)
        f[30] = if (a.variants == b.variants) 1f else 0f
        val difference = a.variants.count { it !in b.variants } + b.variants.count { it !in a.variants }
        f[31] = (min(difference, 3) / 3.0).toFloat()
        f[32] = if (a.years.isNotEmpty() && b.years.isNotEmpty() && a.years.none { it in b.years }) 1f else 0f
        f[33] = if (a.kind == b.kind) 1f else 0f
        f[34] = min(a.kind, b.kind).toFloat()
        f[35] = max(a.kind, b.kind).toFloat()
        if (a.year != null && b.year != null) {
            f[36] = 1f
            f[37] = if (a.year == b.year) 1f else 0f
            f[38] = (min(abs(a.year - b.year), 10) / 10.0).toFloat()
        }
        if (a.authors.isNotEmpty() && b.authors.isNotEmpty()) {
            f[39] = 1f
            var bestLev = 0f; var bestJaccard = 0f
            for (x in a.authors) {
                val sx = points(x.sorted().joinToString(" "))
                for (y in b.authors) {
                    bestLev = max(bestLev, levenshteinSimilarity(sx, points(y.sorted().joinToString(" "))))
                    bestJaccard = max(bestJaccard, jaccard(x, y))
                }
            }
            f[40] = bestLev; f[41] = bestJaccard
        }
        return f
    }
}
