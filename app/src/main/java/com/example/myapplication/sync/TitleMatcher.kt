package com.example.myapplication.sync

import com.example.myapplication.network.normalizeTitleWords
import com.example.myapplication.network.levenshteinSimilarity
import kotlin.math.max


/**
 * Title matching без внешних ID.
 *
 * Правила:
 * 1) exact по нормализованной строке;
 * 2) subset (одно название содержится в другом);
 * 3) fuzzy: Jaccard по словам или Levenshtein similarity.
 */
object TitleMatcher {
    /** Минимальный балл для «это та же запись». */
    const val MATCH_THRESHOLD = 0.85

    private const val AMBIGUOUS_FLOOR = 0.70

    fun isMatch(localTitle: String, remoteCandidates: List<String>): Boolean {
        val best = bestScore(localTitle, remoteCandidates)
        return best >= MATCH_THRESHOLD
    }

    fun bestScore(localTitle: String, remoteCandidates: List<String>): Double {
        val localNorm = normalize(localTitle)
        if (localNorm.isBlank()) return 0.0
        return remoteCandidates.asSequence()
            .map(::normalize)
            .filter { it.isNotBlank() }
            .map { remoteNorm -> score(localNorm, remoteNorm) }
            .maxOrNull() ?: 0.0
    }

    fun isAmbiguous(localTitle: String, remoteCandidates: List<String>): Boolean {
        val best = bestScore(localTitle, remoteCandidates)
        return best in AMBIGUOUS_FLOOR..<MATCH_THRESHOLD
    }

    private fun score(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0

        val aTokens = tokenize(a)
        val bTokens = tokenize(b)

        // Все слова «короткого» названия есть в «длинном» (без коротких односложных ловушек).
        if (aTokens.isNotEmpty() && bTokens.isNotEmpty()) {
            val (smallerTok, largerTok) =
                if (aTokens.size <= bTokens.size) aTokens to bTokens else bTokens to aTokens
            val allowSubset = smallerTok.size >= 2 || smallerTok.sumOf { it.length } >= 5
            if (allowSubset && smallerTok.all { st -> largerTok.any { it == st } }) {
                return 0.92
            }
        }

        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        // Раньше: любая подстрока давала 0.95 — из-за этого «a» совпадало с любым названием с «a».
        if (short.length >= 4 && long.contains(short)) return 0.87

        val jaccard = jaccardIndex(aTokens, bTokens)
        val lev = levenshteinSimilarity(a, b)
        return max(jaccard, lev)
    }

    private fun normalize(input: String): String = normalizeTitleWords(input)

    private fun tokenize(input: String): Set<String> =
        input.split(' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun jaccardIndex(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }
}
