package com.example.myapplication.domain.recommendations

import android.util.Log
import com.example.myapplication.network.ApiService
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

private const val TAG = "AnimeGraphProvider"

/**
 * Related-графы аниме: AniList (батч одним запросом), Shikimori similar, MAL/Jikan recommendations.
 * Логика маршрутизации сидов перенесена из движка без изменений; новое — сохранение типизированного
 * ключа сида и позиции в выдаче источника, чтобы вес связи и независимость подтверждений не терялись.
 */
class AnimeGraphProvider(private val apiService: ApiService) : CandidateProvider {

    override val id = "anime-graph"
    override val supportedTypes = setOf(ContentType.ANIME)

    override fun isEnabled(flags: RecommendationFlags) = true

    override suspend fun getRelated(type: ContentType, seeds: List<RecSeed>, language: AppLanguage): List<PoolEntry> {
        if (seeds.isEmpty()) return emptyList()

        // RU: первичный источник — Shikimori (русские тайтлы/жанры), AniList добирает co-occurrence и
        // сиды без shikimoriId. EN: наоборот — AniList батч первичен.
        val shikimoriSeeds: List<RecSeed>
        val anilistSeeds: List<RecSeed>
        if (language == AppLanguage.RU) {
            shikimoriSeeds = seeds.filter { it.shikimoriId != null }
            anilistSeeds = seeds.filter { it.anilistId != null }
        } else {
            anilistSeeds = seeds.filter { it.anilistId != null }
            shikimoriSeeds = seeds.filter { it.anilistId == null && it.shikimoriId != null }.take(MAX_FALLBACK_SEEDS)
        }
        val malSeeds = seeds
            .filter { it.anilistId == null && it.shikimoriId == null && it.malId != null }
            .take(MAX_FALLBACK_SEEDS)

        return supervisorScope {
            val anilistDeferred = async {
                if (anilistSeeds.isEmpty()) return@async emptyList()
                val byId = anilistSeeds.associateBy { it.anilistId!! }
                apiService.anilistRecommendationsBatch(byId.keys.toList(), perSeed = PER_SEED)
                    .getOrElse {
                        Log.w(TAG, "AniList batch failed", it)
                        emptyMap()
                    }
                    .flatMap { (seedId, results) ->
                        val seed = byId[seedId] ?: return@flatMap emptyList()
                        results.mapIndexed { rank, r -> entry(r, seed, rank) }
                    }
            }
            val shikimoriDeferred = shikimoriSeeds.map { seed ->
                async {
                    apiService.shikimoriSimilar(seed.shikimoriId!!, language)
                        .getOrElse { emptyList() }
                        .take(PER_SEED)
                        .mapIndexed { rank, r -> entry(r, seed, rank) }
                }
            }
            val malDeferred = malSeeds.map { seed ->
                async {
                    apiService.malRecommendations(seed.malId!!)
                        .getOrElse { emptyList() }
                        .take(PER_SEED)
                        .mapIndexed { rank, r -> entry(r, seed, rank) }
                }
            }

            val pool = mutableListOf<PoolEntry>()
            pool += runCatching { anilistDeferred.await() }.getOrElse { emptyList() }
            shikimoriDeferred.forEach { d -> pool += runCatching { d.await() }.getOrElse { emptyList() } }
            malDeferred.forEach { d -> pool += runCatching { d.await() }.getOrElse { emptyList() } }
            pool
        }
    }

    override suspend fun getTrending(type: ContentType, language: AppLanguage): List<PoolEntry> =
        apiService.anilistTrending(limit = TRENDING_LIMIT)
            .getOrElse { emptyList() }
            .mapIndexed { rank, r ->
                PoolEntry(r, seedTitle = "", seedRating = 0f, contentType = ContentType.ANIME, signal = SignalKind.TRENDING, rank = rank)
            }

    private fun entry(r: com.example.myapplication.network.ApiSearchResult, seed: RecSeed, rank: Int) = PoolEntry(
        result = r,
        seedTitle = seed.title,
        seedRating = seed.rating,
        contentType = ContentType.ANIME,
        seedKey = seed.key,
        signal = SignalKind.RECOMMENDATION,
        rank = rank,
    )

    companion object {
        const val PER_SEED = 12
        const val MAX_FALLBACK_SEEDS = 3
        const val TRENDING_LIMIT = 24
    }
}
