package com.example.myapplication.domain.recommendations

import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.ExternalIds
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.tmdb.TmdbRemoteDataSource
import com.example.myapplication.network.trakt.TraktRemoteDataSource
import com.example.myapplication.network.trakt.TraktTitle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Trakt `/related`: второй, независимый от TMDB граф связей (P1, по флагу `traktRelated`).
 *
 * Постеров и жанров в минимальном ответе Trakt нет, поэтому для самых частых кандидатов (по числу сидов)
 * они добираются одним запросом TMDB по `ids.tmdb` — на английском, чтобы жанры совпали с таблицей тегов.
 * Кандидат с тем же tmdb id, что и из [TmdbCandidateProvider], склеивается с ним и добавляет
 * НЕЗАВИСИМОЕ подтверждение от другого источника.
 */
class TraktCandidateProvider(
    private val trakt: TraktRemoteDataSource,
    private val tmdb: TmdbRemoteDataSource,
) : CandidateProvider {

    override val id = "trakt"
    override val supportedTypes = setOf(ContentType.MOVIE, ContentType.TV)

    override fun isEnabled(flags: RecommendationFlags) = flags.traktRelated && trakt.isConfigured && tmdb.hasApiKey

    /** tmdb id → id Trakt: резолв один раз за процесс, дальше — прямой /related. */
    private val traktIds = ConcurrentHashMap<String, String>()

    private class SeedRelated(val seed: RecSeed, val titles: List<TraktTitle>, val failed: Boolean)

    override suspend fun getRelated(type: ContentType, seeds: List<RecSeed>, language: AppLanguage): List<PoolEntry> {
        val isShow = type == ContentType.TV
        val usable = seeds.filter { it.imdbId != null || it.tmdbId != null }.take(MAX_SEEDS)
        if (usable.isEmpty()) return emptyList()
        val gate = Semaphore(MAX_PARALLEL)

        val fetched = supervisorScope {
            usable.map { seed ->
                async {
                    gate.withPermit {
                        runCatching { related(isShow, seed) }.getOrElse { SeedRelated(seed, emptyList(), failed = true) }
                    }
                }
            }.awaitAll()
        }
        if (fetched.all { it.failed }) throw SourceUnavailableException(id)

        val details = enrich(isShow, fetched)
        return fetched.flatMap { rel ->
            rel.titles.take(PER_SEED).mapIndexed { rank, t ->
                PoolEntry(
                    result = toResult(t, isShow, details[t.tmdbId]),
                    seedTitle = rel.seed.title,
                    seedRating = rel.seed.rating,
                    contentType = type,
                    seedKey = rel.seed.key,
                    signal = SignalKind.RECOMMENDATION,
                    rank = rank,
                )
            }
        }
    }

    private suspend fun related(isShow: Boolean, seed: RecSeed): SeedRelated {
        val id = seed.imdbId ?: seed.tmdbId?.let { tmdbId ->
            val cacheKey = "${if (isShow) "tv" else "movie"}:$tmdbId"
            traktIds[cacheKey] ?: when (val r = trakt.resolveByTmdb(isShow, tmdbId)) {
                is LookupResult.Found -> r.value.traktId?.toString()?.also { traktIds[cacheKey] = it }
                is LookupResult.Failure -> return SeedRelated(seed, emptyList(), failed = true)
                else -> null
            }
        } ?: return SeedRelated(seed, emptyList(), failed = false)
        return when (val r = trakt.related(isShow, id, limit = PER_SEED)) {
            is LookupResult.Found -> SeedRelated(seed, r.value, failed = false)
            is LookupResult.Failure -> SeedRelated(seed, emptyList(), failed = true)
            else -> SeedRelated(seed, emptyList(), failed = false)
        }
    }

    /** Постеры/жанры для самых частых кандидатов; остальные без постера отсеются на сборке (нет cover). */
    private suspend fun enrich(isShow: Boolean, fetched: List<SeedRelated>): Map<Int?, Detail> {
        val popular = fetched.flatMap { it.titles }
            .groupingBy { it.tmdbId }.eachCount()
            .entries.sortedByDescending { it.value }
            .mapNotNull { it.key }
            .take(MAX_ENRICH)
        val gate = Semaphore(MAX_PARALLEL)
        return supervisorScope {
            popular.map { tmdbId ->
                async {
                    gate.withPermit { tmdbId to runCatching { detail(isShow, tmdbId) }.getOrNull() }
                }
            }.awaitAll()
        }.mapNotNull { (id, d) -> d?.let { id to it } }.toMap()
    }

    private class Detail(val poster: String?, val genres: List<String>, val rating: Int?)

    private suspend fun detail(isShow: Boolean, tmdbId: Int): Detail? =
        if (isShow) {
            (tmdb.tvDetails(tmdbId, AppLanguage.EN) as? LookupResult.Found)?.value
                ?.let { Detail(it.posterUrl, it.genres, it.voteAverage) }
        } else {
            (tmdb.movieDetails(tmdbId, AppLanguage.EN) as? LookupResult.Found)?.value
                ?.let { Detail(it.posterUrl, it.genres, it.rating) }
        }

    private fun toResult(t: TraktTitle, isShow: Boolean, d: Detail?) = ApiSearchResult(
        title = t.title,
        altTitle = null,
        posterUrl = d?.poster,
        episodes = if (isShow) 0 else 1,
        description = "",
        type = if (isShow) "TV" else "Movie",
        genres = d?.genres.orEmpty(),
        rating = d?.rating,
        source = "Trakt",
        categoryType = if (isShow) "SERIES" else "MOVIE",
        externalId = t.traktId?.toString(),
        seasonYear = t.year,
        titleEn = t.title,
        externalIds = ExternalIds(tmdb = t.tmdbId, imdb = t.imdbId),
    )

    companion object {
        const val MAX_SEEDS = 6
        const val PER_SEED = 12
        const val MAX_ENRICH = 12
        const val MAX_PARALLEL = 3
    }
}
