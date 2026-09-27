package com.example.myapplication.network.tmdb

import com.phnem.vetro.network.BuildConfig
import com.example.myapplication.network.AnimeDetails
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.ExternalIds
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.executeHttpLookup
import com.example.myapplication.network.flatMap
import com.example.myapplication.network.dto.TmdbEpisodeDto
import com.example.myapplication.network.dto.TmdbMovieDetailsDto
import com.example.myapplication.network.dto.TmdbSearchResponseDto
import com.example.myapplication.network.dto.TmdbSearchResultDto
import com.example.myapplication.network.dto.TmdbSeasonDetailsResponseDto
import com.example.myapplication.network.dto.TmdbSeasonDto
import com.example.myapplication.network.dto.TmdbTvDetailsDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.URLProtocol
import io.ktor.http.appendPathSegments
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Типизированный TMDB data source (заменяет инлайн-парсинг `JsonObject`, ранее живший в
 * `VetroApiService`). Search/details остаются "результатом поиска"/"карточкой" — семантика
 * released-vs-known серий вынесена в [TmdbEpisodeCalculator] (чистая логика, без сети).
 */
class TmdbRemoteDataSource(private val client: HttpClient) {

    private fun apiKey(): String = BuildConfig.TMDB_API_KEY

    suspend fun searchMovie(query: String, language: AppLanguage, year: Int? = null): LookupResult<List<ApiSearchResult>> =
        search("movie", query, language, year, yearParam = "primary_release_year") { dto ->
            dto.toApiSearchResult(categoryType = "MOVIE", language = language)
        }

    suspend fun searchTv(query: String, language: AppLanguage, year: Int? = null): LookupResult<List<ApiSearchResult>> =
        search("tv", query, language, year, yearParam = "first_air_date_year") { dto ->
            dto.toApiSearchResult(categoryType = "SERIES", language = language)
        }

    private suspend fun search(
        path: String,
        query: String,
        language: AppLanguage,
        year: Int?,
        yearParam: String,
        map: (TmdbSearchResultDto) -> ApiSearchResult,
    ): LookupResult<List<ApiSearchResult>> = runRequest(notFoundById = false) {
        client.get {
            url {
                protocol = URLProtocol.HTTPS
                host = "api.themoviedb.org"
                appendPathSegments("3", "search", path)
                parameter("api_key", apiKey())
                parameter("query", query)
                parameter("language", language.tmdbLocale())
                if (year != null) parameter(yearParam, year)
            }
        }
    }.flatMap { response ->
        val results = response.body<TmdbSearchResponseDto>().results.map(map)
        if (results.isEmpty()) LookupResult.NoMatch else LookupResult.Found(results)
    }

    suspend fun movieDetails(id: Int, language: AppLanguage): LookupResult<AnimeDetails> = runRequest {
        client.get {
            url {
                protocol = URLProtocol.HTTPS
                host = "api.themoviedb.org"
                appendPathSegments("3", "movie", id.toString())
                parameter("api_key", apiKey())
                parameter("language", language.tmdbLocale())
            }
        }
    }.flatMap { response -> LookupResult.Found(response.body<TmdbMovieDetailsDto>().toAnimeDetails()) }

    suspend fun tvDetails(id: Int, language: AppLanguage): LookupResult<TmdbTvDetails> = runRequest {
        client.get {
            url {
                protocol = URLProtocol.HTTPS
                host = "api.themoviedb.org"
                appendPathSegments("3", "tv", id.toString())
                parameter("api_key", apiKey())
                parameter("language", language.tmdbLocale())
                // Unlike /movie, the TV details payload carries no imdb_id of its own.
                parameter("append_to_response", "external_ids")
            }
        }
    }.flatMap { response -> LookupResult.Found(response.body<TmdbTvDetailsDto>().toDomain()) }

    suspend fun seasonEpisodeAirDates(tvId: Int, seasonNumber: Int, language: AppLanguage): LookupResult<List<TmdbEpisodeAirDate>> = runRequest {
        client.get {
            url {
                protocol = URLProtocol.HTTPS
                host = "api.themoviedb.org"
                appendPathSegments("3", "tv", tvId.toString(), "season", seasonNumber.toString())
                parameter("api_key", apiKey())
                parameter("language", language.tmdbLocale())
            }
        }
    }.flatMap { response ->
        val episodes = response.body<TmdbSeasonDetailsResponseDto>().episodes.map { it.toDomain() }
        LookupResult.Found(episodes)
    }

    /** Точечная проверка "TMDB id ещё существует" — не поиск, только различение Found/NotFoundById. */
    suspend fun checkTvExists(id: Int): LookupResult<Unit> = runRequestExistenceCheck("tv", id)

    suspend fun checkMovieExists(id: Int): LookupResult<Unit> = runRequestExistenceCheck("movie", id)

    private suspend fun runRequestExistenceCheck(path: String, id: Int): LookupResult<Unit> = runRequest {
        client.get {
            url {
                protocol = URLProtocol.HTTPS
                host = "api.themoviedb.org"
                appendPathSegments("3", path, id.toString())
                parameter("api_key", apiKey())
            }
        }
    }.flatMap { LookupResult.Found(Unit) }

    /**
     * Released-vs-known серии + статус одним заходом: [TmdbTvDetails] → при наличии
     * начавшегося сезона без деталей эпизодов дотягивает [seasonEpisodeAirDates] для него,
     * иначе не делает второй запрос. Все ветки идут через [TmdbEpisodeCalculator].
     */
    suspend fun episodeState(tvId: Int, language: AppLanguage, clock: Clock): LookupResult<SeriesEpisodeState> {
        val details = when (val result = tvDetails(tvId, language)) {
            is LookupResult.Found -> result.value
            is LookupResult.NoMatch -> return LookupResult.NoMatch
            is LookupResult.NotFoundById -> return LookupResult.NotFoundById
            is LookupResult.Failure -> return result
        }
        val today = LocalDate.now(clock)
        val known = TmdbEpisodeCalculator.knownEpisodes(details.seasons)
        val latestStarted = details.seasons
            .filter { it.seasonNumber > 0 && it.airDate != null && !it.airDate.isAfter(today) }
            .maxByOrNull { it.seasonNumber }

        val latestSeasonEpisodes = if (latestStarted != null) {
            when (val seasonResult = seasonEpisodeAirDates(tvId, latestStarted.seasonNumber, language)) {
                is LookupResult.Found -> seasonResult.value
                else -> null // Failure/NoMatch/NotFoundById — не считаем текущий сезон, не проваливаем весь запрос
            }
        } else {
            null
        }

        val released = TmdbEpisodeCalculator.releasedEpisodes(details.seasons, latestSeasonEpisodes, today)
        val perSeason = TmdbEpisodeCalculator.releasedBySeason(details.seasons, latestSeasonEpisodes, today)
        return LookupResult.Found(SeriesEpisodeState(released, known, details.status, perSeason))
    }

    private suspend fun runRequest(
        notFoundById: Boolean = true,
        block: suspend () -> io.ktor.client.statement.HttpResponse,
    ): LookupResult<io.ktor.client.statement.HttpResponse> = executeHttpLookup(
        providerName = "TMDB",
        notFoundById = notFoundById,
        block = block,
    )
}

private fun AppLanguage.tmdbLocale(): String = when (this) {
    AppLanguage.RU -> "ru-RU"
    AppLanguage.EN -> "en-US"
}

private fun String.toLocalDateOrNull(): LocalDate? = try {
    takeIf { it.isNotBlank() }?.let(LocalDate::parse)
} catch (e: DateTimeParseException) {
    null
}

internal fun TmdbSearchResultDto.toApiSearchResult(
    categoryType: String,
    language: AppLanguage,
): ApiSearchResult {
    val displayTitle = title ?: name.orEmpty()
    val posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
    val year = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
    return ApiSearchResult(
        title = displayTitle,
        altTitle = null,
        posterUrl = posterUrl,
        episodes = if (categoryType == "MOVIE") 1 else 0,
        description = overview.orEmpty(),
        type = if (categoryType == "MOVIE") "Movie" else "TV",
        genres = emptyList(),
        rating = voteAverage?.let { (it * 10).toInt() },
        source = "TMDB",
        categoryType = categoryType,
        seasonYear = year,
        originalTitle = (originalTitle ?: originalName)?.takeIf { it.isNotBlank() },
        titleEn = displayTitle.takeIf { language == AppLanguage.EN },
        titleRu = displayTitle.takeIf { language == AppLanguage.RU },
        externalId = id.toString(),
        externalIds = ExternalIds(tmdb = id),
    )
}

private fun TmdbMovieDetailsDto.toAnimeDetails(): AnimeDetails = AnimeDetails(
    title = title.orEmpty(),
    altTitle = null,
    description = overview.orEmpty(),
    type = "Movie",
    status = "",
    episodesAired = 1,
    episodesTotal = 1,
    nextEpisode = null,
    genres = genres.mapNotNull { it.name },
    rating = voteAverage?.let { (it * 10).toInt() },
    posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
    source = "TMDB",
    airedOn = releaseDate,
    imdbId = imdbId?.takeIf(String::isNotBlank),
)

private fun TmdbTvDetailsDto.toDomain(): TmdbTvDetails = TmdbTvDetails(
    id = id,
    name = name.orEmpty(),
    overview = overview.orEmpty(),
    posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
    voteAverage = voteAverage?.let { (it * 10).toInt() },
    genres = genres.mapNotNull { it.name },
    status = TmdbEpisodeCalculator.status(status, inProduction),
    seasons = seasons.map { it.toDomain() },
    imdbId = externalIds?.imdbId?.takeIf(String::isNotBlank),
)

fun TmdbTvDetails.toAnimeDetails(): AnimeDetails = AnimeDetails(
    title = name,
    altTitle = null,
    description = overview,
    type = "TV",
    status = status.name,
    episodesAired = 0,
    episodesTotal = null,
    nextEpisode = null,
    genres = genres,
    rating = voteAverage,
    posterUrl = posterUrl,
    source = "TMDB",
    imdbId = imdbId,
)

private fun TmdbSeasonDto.toDomain(): TmdbSeasonSummary = TmdbSeasonSummary(
    seasonNumber = seasonNumber,
    episodeCount = episodeCount,
    airDate = airDate?.toLocalDateOrNull(),
)

private fun TmdbEpisodeDto.toDomain(): TmdbEpisodeAirDate = TmdbEpisodeAirDate(
    episodeNumber = episodeNumber,
    airDate = airDate?.toLocalDateOrNull(),
)

data class TmdbTvDetails(
    val id: Int,
    val name: String,
    val overview: String,
    val posterUrl: String?,
    val voteAverage: Int?,
    val genres: List<String>,
    val status: SeriesStatus,
    val seasons: List<TmdbSeasonSummary>,
    val imdbId: String? = null,
)
