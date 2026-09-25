package com.example.myapplication.network

/**
 * Расстояние Левенштейна — одна реализация на всё приложение (раньше их было три, одна строила
 * полную матрицу n×m на каждое сравнение названий). Две строки таблицы, O(min) памяти.
 */
fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(a.length + 1) { it }
    var curr = IntArray(a.length + 1)
    for (j in 1..b.length) {
        curr[0] = j
        for (i in 1..a.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[i] = minOf(prev[i - 1] + cost, prev[i] + 1, curr[i - 1] + 1)
        }
        val swap = prev
        prev = curr
        curr = swap
    }
    return prev[a.length]
}

/** Похожесть 0..1 по расстоянию Левенштейна: 1 — совпадение, пустые строки считаются равными. */
fun levenshteinSimilarity(a: String, b: String): Double {
    val maxLen = maxOf(a.length, b.length)
    if (maxLen == 0) return 1.0
    return 1.0 - levenshtein(a, b).toDouble() / maxLen
}

/**
 * Returns true when two titles are "close enough" to be considered duplicates.
 */
internal fun isTitleSimilar(a: String, b: String): Boolean {
    val q = a.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    if (q.isEmpty()) return false
    if (b.isBlank()) return false
    val normalized = b.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    if (normalized.isEmpty()) return false
    if (normalized.contains(q) || q.contains(normalized)) return true
    val dist = levenshtein(q, normalized)
    val maxLen = maxOf(q.length, normalized.length)
    return (1.0 - dist.toDouble() / maxLen) > 0.7
}

/**
 * True if [query] is similar to any non-blank entry in [targets].
 */
internal fun isTitleSimilar(query: String, vararg targets: String?): Boolean {
    for (t in targets) {
        if (t.isNullOrBlank()) continue
        if (isTitleSimilar(query, t)) return true
    }
    return false
}
