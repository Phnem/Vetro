package com.example.myapplication.network.movie

import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.tmdb.TmdbRemoteDataSource
import kotlin.math.abs

/** Что известно о тайтле коллекции: все варианты названия, год и тип (фильм или сериал). */
data class TmdbIdQuery(
    val titles: List<String>,
    val year: Int?,
    val contentType: AppContentType,
)

sealed interface TmdbIdResolution {
    data class Matched(
        val tmdbId: Int,
        val confidence: Double,
        val matchedTitle: String,
        val matchedYear: Int?,
    ) : TmdbIdResolution

    /** Подходящих кандидатов нет. Результат можно запомнить на время: тайтл мог просто не попасть в TMDB. */
    data object NoMatch : TmdbIdResolution

    /** Несколько равноправных кандидатов (ремейки с тем же названием, год неизвестен) — id не угадываем. */
    data object Ambiguous : TmdbIdResolution

    /** Нет ключа или сеть отказала: отрицательный результат запоминать нельзя. */
    data object Unavailable : TmdbIdResolution
}

/**
 * Правила проверки совпадения. Название сверяется консервативным [MovieTitleMatcher] по всем
 * вариантам обеих сторон, год допускает расхождение в один год (премьера на фестивале, разные
 * регионы), а при неизвестном годе нужно почти точное название. Равноправные кандидаты дают
 * [TmdbIdResolution.Ambiguous], а не случайный выбор.
 */
internal object TmdbMatchRules {
    /** Порог при неизвестном годе: без года подтверждением служит только почти точное название. */
    const val EXACT_TITLE = 0.95
    const val ONE_YEAR_OFF_PENALTY = 0.05
    const val AMBIGUITY_GAP = 0.03

    class Scored(val result: ApiSearchResult, val tmdbId: Int, val confidence: Double)

    fun score(query: TmdbIdQuery, candidate: ApiSearchResult): Scored? {
        val tmdbId = candidate.externalIds.tmdb ?: return null
        val candidateTitles = listOfNotNull(
            candidate.title,
            candidate.altTitle,
            candidate.originalTitle,
            candidate.titleEn,
            candidate.titleRu,
        )
        val titleScore = query.titles.maxOfOrNull { MovieTitleMatcher.bestScore(it, candidateTitles) } ?: return null

        val wantedYear = query.year
        val candidateYear = candidate.seasonYear
        val confidence = if (wantedYear == null || candidateYear == null) {
            if (titleScore >= EXACT_TITLE) titleScore else return null
        } else {
            val adjusted = when (abs(wantedYear - candidateYear)) {
                0 -> titleScore
                1 -> titleScore - ONE_YEAR_OFF_PENALTY
                else -> return null
            }
            if (adjusted >= MovieTitleMatcher.MATCH_THRESHOLD) adjusted else return null
        }
        return Scored(candidate, tmdbId, confidence)
    }

    fun pick(query: TmdbIdQuery, candidates: Collection<ApiSearchResult>): TmdbIdResolution {
        val accepted = candidates.mapNotNull { score(query, it) }
            .groupBy { it.tmdbId }
            .map { (_, group) -> group.maxBy { it.confidence } }
            .sortedByDescending { it.confidence }
        val best = accepted.firstOrNull() ?: return TmdbIdResolution.NoMatch
        val runnerUp = accepted.getOrNull(1)
        if (runnerUp != null && best.confidence - runnerUp.confidence < AMBIGUITY_GAP) return TmdbIdResolution.Ambiguous
        return TmdbIdResolution.Matched(
            tmdbId = best.tmdbId,
            confidence = best.confidence,
            matchedTitle = best.result.title,
            matchedYear = best.result.seasonYear,
        )
    }
}

internal interface TmdbSearchGateway {
    val isAvailable: Boolean

    suspend fun search(
        query: String,
        contentType: AppContentType,
        language: AppLanguage,
        year: Int?,
    ): LookupResult<List<ApiSearchResult>>
}

private class TmdbRemoteSearchGateway(private val source: TmdbRemoteDataSource) : TmdbSearchGateway {
    override val isAvailable: Boolean get() = source.hasApiKey

    override suspend fun search(
        query: String,
        contentType: AppContentType,
        language: AppLanguage,
        year: Int?,
    ): LookupResult<List<ApiSearchResult>> = when (contentType) {
        AppContentType.MOVIE -> source.searchMovie(query, language, year)
        AppContentType.SERIES -> source.searchTv(query, language, year)
        else -> error("Unsupported content type: $contentType")
    }
}

/**
 * Автоматический поиск TMDB id по названию, году и типу. Тип задаёт эндпоинт (movie или tv), поэтому
 * фильм не сопоставится с сериалом-тёзкой; кириллическое название ищется в русской локали, остальные
 * в английской. Дальше каждый кандидат проходит [TmdbMatchRules].
 */
class TmdbIdResolver internal constructor(private val gateway: TmdbSearchGateway) {
    constructor(tmdb: TmdbRemoteDataSource) : this(TmdbRemoteSearchGateway(tmdb))

    suspend fun resolve(query: TmdbIdQuery): TmdbIdResolution {
        require(query.contentType == AppContentType.MOVIE || query.contentType == AppContentType.SERIES) {
            "TmdbIdResolver supports only MOVIE/SERIES, got ${query.contentType}"
        }
        if (!gateway.isAvailable) return TmdbIdResolution.Unavailable
        val titles = query.titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_TITLES)
        if (titles.isEmpty()) return TmdbIdResolution.NoMatch
        val normalized = query.copy(titles = titles)

        val pool = LinkedHashMap<Int, ApiSearchResult>()
        var searched = false
        var failed = false
        for (title in titles) {
            val language = if (title.any { it in 'Ѐ'..'ӿ' }) AppLanguage.RU else AppLanguage.EN
            // Сначала с годом (редкое совпадение не теряется в общей выдаче), затем без: год мог разойтись на один.
            val years = if (query.year != null) listOf(query.year, null) else listOf(null)
            for (year in years) {
                when (val result = gateway.search(title, query.contentType, language, year)) {
                    is LookupResult.Found -> {
                        searched = true
                        result.value.forEach { candidate -> candidate.externalIds.tmdb?.let { pool.putIfAbsent(it, candidate) } }
                    }
                    is LookupResult.Failure -> failed = true
                    else -> searched = true
                }
                val verdict = TmdbMatchRules.pick(normalized, pool.values)
                if (verdict is TmdbIdResolution.Matched && verdict.confidence >= TmdbMatchRules.EXACT_TITLE) return verdict
            }
        }
        val verdict = TmdbMatchRules.pick(normalized, pool.values)
        return when {
            verdict !is TmdbIdResolution.NoMatch -> verdict
            failed && !searched -> TmdbIdResolution.Unavailable
            else -> TmdbIdResolution.NoMatch
        }
    }

    private companion object {
        const val MAX_TITLES = 3
    }
}
