package com.example.myapplication.domain.recommendations

import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.tmdb.TmdbGenres
import com.example.myapplication.network.tmdb.TmdbRemoteDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * TMDB: основной источник кино и сериалов (P0). Фильмы и сериалы идут РАЗНЫМИ эндпоинтами
 * (`/movie/{id}/recommendations` и `/tv/{id}/recommendations`) — id одного типа в другой не подставляется.
 *
 * `/recommendations` — голосование зрителей, основной связный граф; `/similar` — похожесть по жанрам и
 * ключевым словам, подмешивается только когда рекомендаций мало и весит меньше ([SignalKind.SIMILAR]).
 * Локаль влияет на текст карточки, но не на отбор: жанры берутся из стабильной таблицы [TmdbGenres].
 */
class TmdbCandidateProvider(private val tmdb: TmdbRemoteDataSource) : CandidateProvider {

    override val id = "tmdb"
    override val supportedTypes = setOf(ContentType.MOVIE, ContentType.TV)

    override fun isEnabled(flags: RecommendationFlags) = flags.tmdbRec && tmdb.hasApiKey

    override suspend fun getRelated(type: ContentType, seeds: List<RecSeed>, language: AppLanguage): List<PoolEntry> {
        val usable = seeds.filter { it.tmdbId != null }
        if (usable.isEmpty()) return emptyList()
        val isTv = type == ContentType.TV
        val gate = Semaphore(MAX_PARALLEL)

        val outcomes = supervisorScope {
            usable.map { seed ->
                async {
                    gate.withPermit {
                        runCatching { fetchSeed(isTv, type, seed, language) }.getOrElse { SeedFetch(emptyList(), failed = true) }
                    }
                }
            }.awaitAll()
        }
        if (outcomes.isNotEmpty() && outcomes.all { it.failed }) throw SourceUnavailableException(id)
        return outcomes.flatMap { it.entries }
    }

    override suspend fun getTrending(type: ContentType, language: AppLanguage): List<PoolEntry> {
        val isTv = type == ContentType.TV
        val results = tmdb.trending(isTv, language).foundOrEmpty()
        return results.take(TRENDING_LIMIT).mapIndexed { rank, r ->
            PoolEntry(r, seedTitle = "", seedRating = 0f, contentType = type, signal = SignalKind.TRENDING, rank = rank)
        }
    }

    override suspend fun getDiscover(type: ContentType, genreNames: List<String>, language: AppLanguage): List<PoolEntry> {
        val isTv = type == ContentType.TV
        val genreIds = genreNames.mapNotNull { TmdbGenres.idFor(it, isTv) }.distinct().take(MAX_DISCOVER_GENRES)
        if (genreIds.isEmpty()) return emptyList()
        val results = tmdb.discover(isTv, genreIds, language).foundOrEmpty()
        return results.take(TRENDING_LIMIT).mapIndexed { rank, r ->
            PoolEntry(r, seedTitle = "", seedRating = 0f, contentType = type, signal = SignalKind.DISCOVER, rank = rank)
        }
    }

    private class SeedFetch(val entries: List<PoolEntry>, val failed: Boolean)

    private suspend fun fetchSeed(isTv: Boolean, type: ContentType, seed: RecSeed, language: AppLanguage): SeedFetch {
        val tmdbId = seed.tmdbId ?: return SeedFetch(emptyList(), failed = false)
        val recommended = tmdb.recommendations(isTv, tmdbId, language)
        if (recommended is LookupResult.Failure) return SeedFetch(emptyList(), failed = true)
        val recs = recommended.foundOrEmpty().take(PER_SEED)
        val entries = recs.mapIndexed { rank, r -> entry(r, type, seed, SignalKind.RECOMMENDATION, rank) }.toMutableList()

        // `/similar` — добор только когда зрительских рекомендаций мало; весит меньше.
        if (recs.size < SIMILAR_THRESHOLD) {
            val similar = tmdb.similar(isTv, tmdbId, language).foundOrEmpty()
            val have = recs.mapNotNull { it.externalId }.toSet()
            similar.filter { it.externalId !in have }.take(PER_SEED - recs.size).forEachIndexed { rank, r ->
                entries += entry(r, type, seed, SignalKind.SIMILAR, rank)
            }
        }
        return SeedFetch(entries, failed = false)
    }

    private fun entry(r: ApiSearchResult, type: ContentType, seed: RecSeed, kind: SignalKind, rank: Int) = PoolEntry(
        result = r,
        seedTitle = seed.title,
        seedRating = seed.rating,
        contentType = type,
        seedKey = seed.key,
        signal = kind,
        rank = rank,
    )

    companion object {
        const val PER_SEED = 12
        const val SIMILAR_THRESHOLD = 8
        const val TRENDING_LIMIT = 24
        const val MAX_PARALLEL = 4
        const val MAX_DISCOVER_GENRES = 3
    }
}
