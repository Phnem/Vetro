package com.example.myapplication.domain.recommendations

/**
 * MMR (Maximal Marginal Relevance): `λ·релевантность − (1−λ)·максимальная схожесть с уже выбранными`.
 * Применяется ВНУТРИ типа контента до квот, чтобы топ не состоял из десяти одинаковых по жанрам тайтлов
 * от одного сида. Схожесть — Jaccard по жанрам плюс небольшая надбавка за общий сид.
 */
object DiversityReranker {
    const val DEFAULT_LAMBDA = 0.8f
    private const val SAME_SEED_SIMILARITY = 0.25f

    /** [items] отсортированы по убыванию score; результат — до [limit] карточек в порядке выбора. */
    fun mmr(items: List<RecommendationItem>, limit: Int, lambda: Float = DEFAULT_LAMBDA): List<RecommendationItem> {
        if (items.size <= 1 || limit <= 1) return items.take(limit)
        // Рассматриваем расширенный верх, а не только первые limit: иначе переставлять нечего.
        val pool = items.take(limit * 2).toMutableList()
        val maxScore = pool.maxOf { it.score }.takeIf { it > 0f } ?: 1f
        val selected = ArrayList<RecommendationItem>(limit)
        while (selected.size < limit && pool.isNotEmpty()) {
            val next = pool.maxBy { candidate ->
                val similarity = selected.maxOfOrNull { similarity(candidate, it) } ?: 0f
                lambda * (candidate.score / maxScore) - (1f - lambda) * similarity
            }
            selected += next
            pool -= next
        }
        // Хвост за пределами расширенного верха сохраняет исходный порядок.
        return selected + items.drop(limit * 2)
    }

    internal fun similarity(a: RecommendationItem, b: RecommendationItem): Float {
        val ga = a.genres.map { it.lowercase() }.toSet()
        val gb = b.genres.map { it.lowercase() }.toSet()
        val jaccard = if (ga.isEmpty() || gb.isEmpty()) 0f else {
            val inter = ga.count { it in gb }
            inter.toFloat() / (ga.size + gb.size - inter)
        }
        val sameSeed = if (!a.seedTitle.isNullOrBlank() && a.seedTitle == b.seedTitle) SAME_SEED_SIMILARITY else 0f
        return (jaccard * (1f - SAME_SEED_SIMILARITY) + sameSeed).coerceIn(0f, 1f)
    }
}
