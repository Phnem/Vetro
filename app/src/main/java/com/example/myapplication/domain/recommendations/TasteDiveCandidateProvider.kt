package com.example.myapplication.domain.recommendations

import com.example.myapplication.domain.normalizeForSearch
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.enrichment.TasteDiveClient
import com.example.myapplication.network.enrichment.TasteType
import com.example.myapplication.network.enrichment.similarMany
import com.example.myapplication.network.tmdb.TmdbRemoteDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * TasteDive: экспериментальный мультисид (P2, по флагу `tasteDive`): ОДИН запрос по нескольким любимым
 * названиям даёт независимый сигнал общих вкусов. Это legacy-API (сервис мигрирует на Qloo), поэтому
 * включается только после офлайн-проверки на тестовых коллекциях.
 *
 * TasteDive отдаёт только название и тип, без id — каждый кандидат сопоставляется с TMDB поиском и
 * принимается ТОЛЬКО при однозначном совпадении нормализованного названия; неоднозначное отклоняется.
 */
class TasteDiveCandidateProvider(
    private val tasteDive: TasteDiveClient,
    private val tmdb: TmdbRemoteDataSource,
) : CandidateProvider {

    override val id = "tastedive"
    override val supportedTypes = setOf(ContentType.MOVIE, ContentType.TV)

    override fun isEnabled(flags: RecommendationFlags) = flags.tasteDive && tasteDive.isConfigured && tmdb.hasApiKey

    override suspend fun getRelated(type: ContentType, seeds: List<RecSeed>, language: AppLanguage): List<PoolEntry> {
        val isTv = type == ContentType.TV
        val top = seeds.filter { it.rating > 0f }.sortedByDescending { it.rating }.take(MAX_SEEDS)
        if (top.size < MIN_SEEDS) return emptyList()

        val tasteType = if (isTv) TasteType.SHOW else TasteType.MOVIE
        val similar = when (val r = tasteDive.similarMany(top.map { it.title }, tasteType, limit = RESULT_LIMIT)) {
            is LookupResult.Found -> r.value
            is LookupResult.Failure -> throw SourceUnavailableException(id)
            else -> return emptyList()
        }

        val gate = Semaphore(MAX_PARALLEL)
        val matched = supervisorScope {
            similar.map { item ->
                async { gate.withPermit { runCatching { match(isTv, item.name, language) }.getOrNull() } }
            }.awaitAll()
        }
        val pseudoSeed = "multi:tastedive"
        val avgRating = top.map { it.rating }.average().toFloat()
        return matched.filterNotNull().mapIndexed { rank, result ->
            PoolEntry(
                result = result,
                seedTitle = top.first().title,
                seedRating = avgRating,
                contentType = type,
                // Один псевдо-сид на весь запрос: мультисид не должен считаться несколькими независимыми сидами.
                seedKey = pseudoSeed,
                signal = SignalKind.MULTISEED,
                rank = rank,
            )
        }
    }

    private suspend fun match(isTv: Boolean, name: String, language: AppLanguage): ApiSearchResult? {
        val found = if (isTv) tmdb.searchTv(name, language) else tmdb.searchMovie(name, language)
        val candidates = (found as? LookupResult.Found)?.value.orEmpty()
        val wanted = name.normalizeForSearch()
        if (wanted.isEmpty()) return null
        val exact = candidates.filter { c ->
            c.title.normalizeForSearch() == wanted || c.originalTitle?.normalizeForSearch() == wanted
        }
        // Два и более точных совпадения (ремейк, омоним) без года не разрешить — отклоняем.
        val only = exact.singleOrNull() ?: return null
        return only.copy(source = "TasteDive")
    }

    companion object {
        const val MIN_SEEDS = 2
        const val MAX_SEEDS = 6
        const val RESULT_LIMIT = 15
        const val MAX_PARALLEL = 3
    }
}
