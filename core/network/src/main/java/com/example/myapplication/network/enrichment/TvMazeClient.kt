package com.example.myapplication.network.enrichment

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.TokenBucketRateLimiter
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * TVmaze — расписание серий (без ключа, 20 запросов / 10 с). Главное — `airstamp`: точный UTC-момент
 * выхода серии в стране производства. Есть и у части аниме (Frieren: NTV, Asia/Tokyo).
 */
class TvMazeClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
) {
    /** Шоу по IMDb (`tt…`) или TVDB id с предыдущей и следующей серией в одном запросе. */
    suspend fun show(imdbId: String?, tvdbId: Int?): LookupResult<TvMazeShow> {
        val query = when {
            !imdbId.isNullOrBlank() -> "imdb=$imdbId"
            tvdbId != null -> "thetvdb=$tvdbId"
            else -> return LookupResult.NoMatch
        }
        // lookup отвечает редиректом на /shows/{id}; embed передаём в итоговый адрес сами.
        val id = when (val r = http.text("TVmaze", "$BASE/lookup/shows?$query", rate, notFoundById = true).parse(TvMazeParser::showId)) {
            is LookupResult.Found -> r.value
            is LookupResult.NoMatch, is LookupResult.NotFoundById -> return LookupResult.NoMatch
            is LookupResult.Failure -> return r
        }
        return http.text("TVmaze", "$BASE/shows/$id?embed[]=nextepisode&embed[]=previousepisode", rate)
            .parse(TvMazeParser::show)
    }

    /** Все серии шоу — для оценки ритма выхода и дат по сезонам. */
    suspend fun episodes(showId: Int): LookupResult<List<TvMazeEpisode>> =
        http.text("TVmaze", "$BASE/shows/$showId/episodes", rate).parse(TvMazeParser::episodes)

    private companion object {
        const val BASE = "https://api.tvmaze.com"
    }
}

data class TvMazeShow(
    val id: Int,
    val name: String,
    val status: String?,
    val imdbId: String?,
    val tvdbId: Int?,
    val network: String?,
    val timezone: String?,
    val previous: TvMazeEpisode?,
    val next: TvMazeEpisode?,
)

data class TvMazeEpisode(
    val season: Int?,
    val number: Int?,
    val name: String?,
    val airdate: LocalDate?,
    /** Точный момент выхода (UTC); null — известна только дата или ничего. */
    val airstamp: Instant?,
    val runtimeMin: Int?,
)

/** «2026-03-27T16:00:00+00:00» и «…Z». `Instant.parse` на Android 8 смещение не понимает. */
internal fun parseInstant(text: String?): Instant? = text?.takeIf { it.isNotBlank() }?.let {
    runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(it) }.getOrNull()
}

internal object TvMazeParser {
    @Serializable
    private data class ShowDto(
        val id: Int,
        val name: String,
        val status: String? = null,
        val externals: ExternalsDto? = null,
        val network: ChannelDto? = null,
        val webChannel: ChannelDto? = null,
        val _embedded: EmbeddedDto? = null,
    )

    @Serializable
    private data class ExternalsDto(val imdb: String? = null, val thetvdb: Int? = null)

    @Serializable
    private data class ChannelDto(val name: String? = null, val country: CountryDto? = null)

    @Serializable
    private data class CountryDto(val timezone: String? = null)

    @Serializable
    private data class EmbeddedDto(val nextepisode: EpisodeDto? = null, val previousepisode: EpisodeDto? = null)

    @Serializable
    private data class EpisodeDto(
        val season: Int? = null,
        val number: Int? = null,
        val name: String? = null,
        val airdate: String? = null,
        val airstamp: String? = null,
        val runtime: Int? = null,
    )

    @Serializable
    private data class IdDto(val id: Int)

    fun showId(body: String): Int? = EnrichmentJson.decodeFromString(IdDto.serializer(), body).id

    fun show(body: String): TvMazeShow {
        val d = EnrichmentJson.decodeFromString(ShowDto.serializer(), body)
        val channel = d.network ?: d.webChannel
        return TvMazeShow(
            id = d.id,
            name = d.name,
            status = d.status,
            imdbId = d.externals?.imdb,
            tvdbId = d.externals?.thetvdb,
            network = channel?.name,
            timezone = channel?.country?.timezone,
            previous = d._embedded?.previousepisode?.toDomain(),
            next = d._embedded?.nextepisode?.toDomain(),
        )
    }

    fun episodes(body: String): List<TvMazeEpisode> =
        EnrichmentJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(EpisodeDto.serializer()), body)
            .map { it.toDomain() }

    private fun EpisodeDto.toDomain() = TvMazeEpisode(
        season = season,
        number = number,
        name = name,
        airdate = airdate?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        airstamp = parseInstant(airstamp),
        runtimeMin = runtime,
    )
}
