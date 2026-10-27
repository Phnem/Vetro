package com.example.myapplication.domain.recommendations

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.WatchStatus
import kotlin.math.pow

/**
 * Выбор сидов: 6–10 РАЗНООБРАЗНЫХ тайтлов нужного типа вместо первых шести по оценке.
 *
 * Вес сида — положительная оценка, избранное, пересмотр и свежесть; брошенное и оценённое ниже
 * нейтральной точки в сиды не попадает. Затем жадный MMR по тегам не даёт набрать шесть почти
 * одинаковых тайтлов: каждый следующий сид штрафуется за схожесть с уже выбранными.
 */
object SeedSelector {
    const val MIN_SEEDS = 6
    const val MAX_SEEDS = 10

    /** Рейтинг ниже — не положительный сигнал, сидом не служит. */
    const val NEUTRAL_RATING = 6f
    private const val MMR_LAMBDA = 0.7f
    private const val UNRATED_WEIGHT = 0.15f
    private const val RECENCY_HALF_LIFE_MILLIS = 365L * 24 * 60 * 60 * 1000

    /** Чем больше коллекция этого типа, тем больше сидов (6 → 10) — без лишнего трафика для маленьких. */
    fun limitFor(typeCount: Int): Int = (MIN_SEEDS + typeCount / 10).coerceIn(MIN_SEEDS, MAX_SEEDS)

    fun select(
        library: List<Anime>,
        type: ContentType,
        nowMillis: Long,
        limit: Int? = null,
    ): List<RecSeed> {
        val ofType = library.filter { ContentType.of(it.mediaType) == type }
        val eligible = ofType.filter { hasExternalId(it, type) && isPositiveOrUnrated(it) }
        if (eligible.isEmpty()) return emptyList()

        val weighted = eligible.map { it to baseWeight(it, nowMillis) }
        val maxWeight = weighted.maxOf { it.second }.takeIf { it > 0f } ?: 1f
        val maxSeeds = limit ?: limitFor(ofType.size)

        val chosen = ArrayList<Pair<Anime, Float>>()
        val remaining = weighted.toMutableList()
        while (chosen.size < maxSeeds && remaining.isNotEmpty()) {
            val next = remaining.maxBy { (anime, weight) ->
                val similarity = chosen.maxOfOrNull { (picked, _) -> jaccard(anime.tags.toSet(), picked.tags.toSet()) } ?: 0f
                MMR_LAMBDA * (weight / maxWeight) - (1f - MMR_LAMBDA) * similarity
            }
            chosen += next
            remaining -= next
        }
        return chosen.map { (anime, _) -> toSeed(anime, type) }
    }

    private fun hasExternalId(anime: Anime, type: ContentType): Boolean = when (type) {
        ContentType.ANIME -> anime.anilistId != null || anime.shikimoriId != null || anime.malId != null
        ContentType.MOVIE, ContentType.TV -> anime.tmdbId != null
    }

    /** DROPPED и «не понравилось» — не сиды: рекомендовать «похожее на брошенное» бессмысленно. */
    private fun isPositiveOrUnrated(anime: Anime): Boolean {
        if (anime.watchStatus == WatchStatus.DROPPED) return false
        return anime.rating <= 0f || anime.rating >= NEUTRAL_RATING
    }

    private fun baseWeight(anime: Anime, nowMillis: Long): Float {
        var weight = if (anime.rating > 0f) ((anime.rating - NEUTRAL_RATING) / 4f).coerceIn(0f, 1f) * 0.6f else UNRATED_WEIGHT
        if (anime.isFavorite) weight += 0.2f
        if (anime.watchStatus == WatchStatus.REWATCHING) weight += 0.1f
        val ageMillis = (nowMillis - anime.dateAdded).coerceAtLeast(0L).toDouble()
        weight += 0.1f * 0.5.pow(ageMillis / RECENCY_HALF_LIFE_MILLIS).toFloat()
        return weight
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val inter = a.count { it in b }
        return inter.toFloat() / (a.size + b.size - inter)
    }

    private fun toSeed(anime: Anime, type: ContentType): RecSeed {
        val key = when {
            type == ContentType.ANIME && anime.anilistId != null -> "anilist:${anime.anilistId}"
            type == ContentType.ANIME && anime.shikimoriId != null -> "shikimori:${anime.shikimoriId}"
            type == ContentType.ANIME -> "mal:${anime.malId}"
            else -> "tmdb:${anime.tmdbId}"
        }
        return RecSeed(
            type = type,
            key = "${type.name}:$key",
            title = anime.title,
            rating = anime.rating,
            anilistId = anime.anilistId,
            malId = anime.malId,
            shikimoriId = anime.shikimoriId,
            tmdbId = anime.tmdbId,
            imdbId = anime.imdbId,
            tags = anime.tags.toSet(),
        )
    }
}
