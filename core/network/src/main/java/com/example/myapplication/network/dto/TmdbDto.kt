package com.example.myapplication.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbSearchResponseDto(
    val results: List<TmdbSearchResultDto> = emptyList()
)

/** Поиск отдаёт `title`/`release_date` для фильмов, `name`/`first_air_date` для сериалов. */
@Serializable
data class TmdbSearchResultDto(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    /** Только в списках (recommendations/similar/trending/discover); поиск их не отдаёт. */
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
)

@Serializable
data class TmdbGenreDto(
    val id: Int,
    val name: String? = null,
)

@Serializable
data class TmdbMovieDetailsDto(
    val id: Int,
    val title: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenreDto> = emptyList(),
    /** `/3/movie/{id}` exposes this natively; TV needs `append_to_response=external_ids`. */
    @SerialName("imdb_id") val imdbId: String? = null,
)

@Serializable
data class TmdbTvDetailsDto(
    val id: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val status: String? = null,
    @SerialName("in_production") val inProduction: Boolean = false,
    val genres: List<TmdbGenreDto> = emptyList(),
    val seasons: List<TmdbSeasonDto> = emptyList(),
    @SerialName("external_ids") val externalIds: TmdbExternalIdsDto? = null,
)

@Serializable
data class TmdbExternalIdsDto(
    @SerialName("imdb_id") val imdbId: String? = null,
)

@Serializable
data class TmdbSeasonDto(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
data class TmdbSeasonDetailsResponseDto(
    @SerialName("season_number") val seasonNumber: Int = 0,
    val episodes: List<TmdbEpisodeDto> = emptyList(),
)

@Serializable
data class TmdbEpisodeDto(
    @SerialName("episode_number") val episodeNumber: Int,
    @SerialName("air_date") val airDate: String? = null,
)
