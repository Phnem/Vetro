package com.example.myapplication.domain.recommendations

import com.example.myapplication.network.ApiSearchResult
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Скор v2 (§5 плана): все компоненты в [0,1], калибровка внутри типа контента.
 *
 * ```
 * Score = 0.30·G   совпадение жанров/тем с профилем вкуса: cosine → (cos+1)/2
 *       + 0.35·R   связь с РАЗНЫМИ сидами: сила ребра × оценка сида, шум-ИЛИ по сидам
 *       + 0.15·Q   качество с Bayesian-сглаживанием по числу голосов
 *       + 0.15·F   доверие к сиду по свайпам пользователя
 *       + 0.05·D   новизна: менее массовое, но только если релевантно
 * ```
 * Отсутствующий компонент (нет жанров у Jikan, нет сидов у тренда, нет голосов) НЕ считается нулём:
 * веса оставшихся перенормируются. При низкой уверенности профиля результат смешивается с глобальным
 * качеством, чтобы единственная оценка одного фильма не превращала выдачу в «вектор из одной точки».
 * Стартовые веса — гипотеза, подбираются на replay-тестах.
 */
class NormalizedScorer(
    private val affinity: Map<String, Float>,
    /** Сырой жанр источника → тег приложения (id GenreRepository). */
    private val genreToTagId: (String) -> String?,
    /** Источник, чьи метаданные (название/описание) предпочесть при склейке дублей. */
    private val preferredSource: String? = null,
    /** Доверие к сиду −1..1 по свайпам (см. [FeedbackSignals.seedTrust]); ключ — название сида. */
    private val seedTrust: Map<String, Float> = emptyMap(),
    /** 0..1 — уверенность профиля именно этого типа контента. */
    private val confidence: Float = 1f,
) {

    private class Draft(
        val item: RecommendationItem,
        val genre: Float?,
        val relation: Float?,
        val quality: Float?,
        val feedback: Float?,
        val votes: Int?,
    )

    private val affinityNorm: Float by lazy {
        sqrt(affinity.values.sumOf { (it * it).toDouble() }).toFloat()
    }

    fun scoreAndRank(pool: List<PoolEntry>, limit: Int): List<RecommendationItem> {
        val drafts = CandidateMerger.group(pool).mapNotNull(::draft)
        if (drafts.isEmpty()) return emptyList()
        val sortedVotes = drafts.mapNotNull { it.votes }.sorted()
        return drafts
            .map { d ->
                val novelty = d.votes?.let { 1f - percentile(sortedVotes, it) }
                // Новизна идёт только вместе с релевантностью: безвестный нерелевантный тайтл не поднимается.
                val noveltyScore = novelty?.let { it * (d.genre ?: 0.5f) }
                d.item.copy(score = combine(d, noveltyScore))
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun combine(d: Draft, novelty: Float?): Float {
        val parts = listOf(
            W_GENRE to d.genre,
            W_RELATION to d.relation,
            W_QUALITY to d.quality,
            W_FEEDBACK to d.feedback,
            W_NOVELTY to novelty,
        ).mapNotNull { (weight, value) -> value?.let { weight to it } }
        val base = if (parts.isEmpty()) 0.5f else {
            (parts.sumOf { (w, v) -> (w * v).toDouble() } / parts.sumOf { it.first.toDouble() }).toFloat()
        }
        val fallback = d.quality ?: 0.5f
        val trust = BLEND_FLOOR + (1f - BLEND_FLOOR) * confidence.coerceIn(0f, 1f)
        return trust * base + (1f - trust) * fallback
    }

    private fun draft(group: MergedGroup): Draft? {
        val entries = group.entries
        val best = entries.maxBy { completeness(it.result) }
        val cover = entries.firstNotNullOfOrNull { it.result.posterUrl?.takeIf { url -> url.isNotBlank() } } ?: return null
        val genres = entries.firstNotNullOfOrNull { e -> e.result.genres.takeIf { it.isNotEmpty() } }.orEmpty()
        val tagIds = genres.mapNotNull(genreToTagId).distinct()
        val rating100 = entries.firstNotNullOfOrNull { normalizedRating(it.result) }
        val votes = entries.firstNotNullOfOrNull { it.result.voteCount }
        val seeded = entries.filter { it.seedTitle.isNotBlank() && it.signal != SignalKind.TRENDING && it.signal != SignalKind.DISCOVER }

        val item = RecommendationItem(
            key = group.key,
            title = best.result.title,
            altTitle = best.result.altTitle,
            coverUrl = cover,
            genres = genres,
            externalRating = rating100,
            episodes = entries.maxOf { it.result.episodes },
            description = entries.map { it.result.description }.firstOrNull { it.isNotBlank() } ?: best.result.description,
            source = best.result.source,
            externalId = best.result.externalId,
            categoryType = categoryTypeFor(group.type, best.result),
            seedTitle = seeded.maxByOrNull { it.seedRating }?.seedTitle,
            contentType = group.type,
            tmdbId = entries.firstNotNullOfOrNull { it.result.externalIds.tmdb },
            evidence = evidenceOf(entries),
        )
        return Draft(
            item = item,
            genre = genreScore(tagIds),
            relation = relationScore(entries),
            quality = rating100?.let { bayesianQuality(it / 10f, votes, group.type) },
            feedback = feedbackScore(seeded),
            votes = votes,
        )
    }

    private fun categoryTypeFor(type: ContentType, result: ApiSearchResult): String = when (type) {
        ContentType.ANIME -> result.categoryType.ifBlank { "ANIME" }
        ContentType.MOVIE -> "MOVIE"
        ContentType.TV -> "SERIES"
    }

    private fun evidenceOf(entries: List<PoolEntry>): List<RecommendationEvidence> =
        entries
            .map { e ->
                RecommendationEvidence(
                    source = e.result.source,
                    seedKey = e.seedKey,
                    seedTitle = e.seedTitle.takeIf { it.isNotBlank() },
                    rawLinkWeight = e.result.linkWeight?.toDouble(),
                    rankInSource = e.rank,
                    kind = e.signal,
                )
            }
            .distinctBy { Triple(it.source, it.seedKey, it.seedTitle) }
            .take(RecommendationItem.MAX_EVIDENCE)

    /** cosine между полным вектором вкуса и мульти-хот вектором кандидата → [0,1]; null без профиля или тегов. */
    private fun genreScore(tagIds: List<String>): Float? {
        if (affinityNorm <= 0f || tagIds.isEmpty()) return null
        val dot = tagIds.sumOf { (affinity[it] ?: 0f).toDouble() }.toFloat()
        val cosine = (dot / (affinityNorm * sqrt(tagIds.size.toFloat()))).coerceIn(-1f, 1f)
        return (cosine + 1f) / 2f
    }

    /**
     * Шум-ИЛИ по РАЗНЫМ сидам: каждый сид даёт лучший из своих вкладов (два API про один тайтл от
     * одного сида не удваивают подтверждение), несколько сидов — накапливаются, но не выше 1.
     */
    private fun relationScore(entries: List<PoolEntry>): Float? {
        val perSeed = entries
            .filter { it.signal != SignalKind.TRENDING && it.signal != SignalKind.DISCOVER }
            .groupBy { it.seedKey ?: "title:${it.seedTitle}" }
            .mapValues { (_, es) -> es.maxOf(::contribution) }
        if (perSeed.isEmpty()) return null
        val miss = perSeed.values.fold(1.0) { acc, c -> acc * (1.0 - RELATION_SCALE * c) }
        return (1.0 - miss).toFloat().coerceIn(0f, 1f)
    }

    private fun contribution(e: PoolEntry): Float {
        val seedWeight = if (e.seedRating <= 0f) 0.5f else 0.3f + 0.7f * ((e.seedRating - 4f) / 6f).coerceIn(0f, 1f)
        val edge = e.result.linkWeight?.let { lw ->
            // Голоса за связь AniList: логарифмическая шкала, 30 голосов ≈ максимум.
            (ln(1f + lw.coerceAtLeast(0)) / ln(31f)).coerceIn(MIN_EDGE, 1f)
        } ?: (1f / (1f + (e.rank ?: 0) / 12f)).coerceIn(MIN_EDGE, 1f)
        val kind = when (e.signal) {
            SignalKind.SIMILAR -> 0.6f
            SignalKind.MULTISEED -> 0.8f
            else -> 1f
        }
        return seedWeight * edge * kind
    }

    /** Среднее доверие к сидам кандидата по свайпам пользователя, приведённое к [0,1]; null без истории. */
    private fun feedbackScore(seeded: List<PoolEntry>): Float? {
        if (seedTrust.isEmpty()) return null
        val trusts = seeded.map { it.seedTitle }.distinct().mapNotNull { seedTrust[it] }
        if (trusts.isEmpty()) return null
        return ((trusts.average().toFloat() + 1f) / 2f).coerceIn(0f, 1f)
    }

    /** (v/(v+m))·r + (m/(v+m))·C: рейтинг с малым числом голосов тянется к среднему по типу. */
    private fun bayesianQuality(rating10: Float, votes: Int?, type: ContentType): Float {
        val (m, c) = when (type) {
            ContentType.ANIME -> 40f to 6.9f
            ContentType.MOVIE -> 150f to 6.4f
            ContentType.TV -> 150f to 6.9f
        }
        val v = (votes ?: UNKNOWN_VOTES).toFloat()
        return (((v / (v + m)) * rating10 + (m / (v + m)) * c) / 10f).coerceIn(0f, 1f)
    }

    private fun percentile(sorted: List<Int>, votes: Int): Float {
        if (sorted.size < 2) return 0.5f
        return (sorted.count { it < votes }.toFloat() / (sorted.size - 1)).coerceIn(0f, 1f)
    }

    /** Рейтинг источника → 0..100 (AniList 0..100, Shikimori/Jikan 0..10, TMDB уже ×10). */
    private fun normalizedRating(result: ApiSearchResult): Int? =
        result.rating?.let { if (it <= 10) it * 10 else it }?.coerceIn(0, 100)

    private fun completeness(result: ApiSearchResult): Int {
        var c = 0
        if (result.source.equals(preferredSource, ignoreCase = true)) c += 100
        if (!result.posterUrl.isNullOrBlank()) c += 4
        if (result.genres.isNotEmpty()) c += 2
        if (result.rating != null) c += 1
        if (result.description.isNotBlank()) c += 1
        return c
    }

    companion object {
        const val W_GENRE = 0.30f
        const val W_RELATION = 0.35f
        const val W_QUALITY = 0.15f
        const val W_FEEDBACK = 0.15f
        const val W_NOVELTY = 0.05f

        /** Даже при нулевой уверенности профиля 50% скора остаётся за связью с сидами. */
        const val BLEND_FLOOR = 0.5f
        private const val RELATION_SCALE = 0.6
        private const val MIN_EDGE = 0.35f
        private const val UNKNOWN_VOTES = 30
    }
}
