package com.example.myapplication.updates

import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.SeriesSeasonsStore
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.domain.seasons.SeasonEpisodesEntry
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.ExternalIds
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.enrichment.TvMazeClient
import com.example.myapplication.network.movie.MovieSeriesRepository
import com.example.myapplication.network.tmdb.SeriesEpisodeState
import com.example.myapplication.network.tmdb.SeriesStatus
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** Tracks released TMDB episodes for SERIES without ever copying the known/planned count. */
class SeriesEpisodeCheckUseCase internal constructor(
    private val store: SeriesEpisodeStore,
    private val source: SeriesEpisodeSource,
    private val clock: Clock,
) {
    constructor(
        repository: MovieSeriesRepository,
        localDataSource: AnimeLocalDataSource,
        seasonsStore: SeriesSeasonsStore,
        tvMaze: TvMazeClient? = null,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        store = LocalSeriesEpisodeStore(localDataSource, seasonsStore),
        source = MovieSeriesEpisodeSource(repository, tvMaze),
        clock = clock,
    )

    /**
     * Резолвит расклад по сезонам для конкретного сериала по требованию (например, при открытии
     * деталей или меню серий). Если TMDB недоступен или не дал сезонов, пробует запасной источник
     * (TVmaze по IMDb/TVDB ID).
     */
    suspend fun ensureSeasonsResolved(anime: Anime) {
        if (anime.mediaType != MediaType.SERIES) return
        val existing = store.getSeasons(anime.id)
        if (existing != null && existing.seasons.size > 1) return

        val tmdbId = anime.tmdbId ?: source.resolveTmdbId(anime)?.also { resolved ->
            store.setTmdbId(anime.id, resolved)
        }
        if (tmdbId != null) {
            val state = (source.episodeState(tmdbId, clock) as? LookupResult.Found)?.value
            if (state != null && state.seasons.isNotEmpty()) {
                store.setSeasons(seasonsEntry(anime.id, state))
                return
            }
        }

        val fallback = source.fallbackSeasons(anime, clock)
        if (fallback != null && fallback.seasons.isNotEmpty()) {
            store.setSeasons(fallback)
        }
    }

    /** Detects, auto-applies and merges new SERIES events into the shared anime_update feed. */
    suspend fun detectAndStore(scope: Set<String>? = null): List<AnimeUpdate> {
        val all = store.getAllSeries()
        store.retainSeasons(all.map { it.id }.toSet())
        val series = if (scope == null) all else all.filter { it.id in scope }
        if (series.isEmpty()) return emptyList()

        val detected = buildList {
            for (stored in series) {
                val tmdbId = source.resolveTmdbId(stored)
                if (tmdbId == null) {
                    if (store.getSeasons(stored.id) == null) {
                        source.fallbackSeasons(stored, clock)?.takeIf { it.seasons.isNotEmpty() }
                            ?.let { store.setSeasons(it) }
                    }
                    continue
                }
                val anime = if (tmdbId != stored.tmdbId) {
                    store.setTmdbId(stored.id, tmdbId)
                    stored.copy(tmdbId = tmdbId)
                } else {
                    stored
                }
                val state = when (val result = source.episodeState(tmdbId, clock)) {
                    is LookupResult.Found -> result.value
                    else -> {
                        if (store.getSeasons(anime.id) == null) {
                            source.fallbackSeasons(anime, clock)?.takeIf { it.seasons.isNotEmpty() }
                                ?.let { store.setSeasons(it) }
                        }
                        continue
                    }
                }
                // Расклад по сезонам пишется при каждом успешном ответе, в том числе на проходе
                // нормализации: карточка и уведомления показывают «S3 E5», а не сквозной счёт.
                if (state.seasons.isNotEmpty()) store.setSeasons(seasonsEntry(anime.id, state))

                if (!store.isNormalized(anime.id)) {
                    store.normalize(anime, state.releasedEpisodes)
                    continue
                }
                if (state.releasedEpisodes > anime.episodes) {
                    add(
                        AnimeUpdate(
                            animeId = anime.id,
                            title = anime.title,
                            currentEpisodes = anime.episodes,
                            newEpisodes = state.releasedEpisodes,
                            source = "TMDB",
                        )
                    )
                }
            }
        }

        return publishEpisodeUpdates(
            detected = detected,
            getExisting = store::getUpdates,
            applyEpisodes = { update -> store.applyEpisodes(update.animeId, update.newEpisodes) },
            setUpdates = store::setUpdates,
        )
    }

    private fun seasonsEntry(animeId: String, state: SeriesEpisodeState): SeasonEpisodesEntry {
        val latest = state.seasons.maxOf { it.seasonNumber }
        // Сезон «выходит», пока сериал продолжается и у последнего начавшегося сезона вышли не все
        // заявленные серии. Перерыв между сезонами у продолжающегося сериала — уже не «выходит».
        val continuing = state.status == SeriesStatus.ONGOING || state.status == SeriesStatus.UNKNOWN
        val rows = state.seasons.filter { it.released > 0 }.map { season ->
            SeasonInfo(
                seasonNumber = season.seasonNumber,
                episodes = season.released,
                totalEpisodes = season.total.takeIf { it > 0 },
                ongoing = continuing && season.seasonNumber == latest && season.released < season.total,
                source = "TMDB",
            )
        }
        return SeasonEpisodesEntry(
            animeId = animeId,
            seasons = rows,
            complete = rows.none { it.ongoing },
            resolvedAt = clock.millis(),
            schema = SeasonEpisodesEntry.CURRENT_SCHEMA,
        )
    }
}

internal interface SeriesEpisodeStore {
    fun getAllSeries(): List<Anime>
    fun isNormalized(animeId: String): Boolean
    fun getUpdates(): List<AnimeUpdate>
    fun getSeasons(animeId: String): SeasonEpisodesEntry? = null
    suspend fun normalize(anime: Anime, releasedEpisodes: Int)
    suspend fun setTmdbId(animeId: String, tmdbId: Int)
    suspend fun applyEpisodes(animeId: String, releasedEpisodes: Int)
    suspend fun setUpdates(updates: List<AnimeUpdate>)
    suspend fun setSeasons(entry: SeasonEpisodesEntry) {}
    suspend fun retainSeasons(seriesIds: Set<String>) {}
}

internal interface SeriesEpisodeSource {
    suspend fun resolveTmdbId(anime: Anime): Int?
    suspend fun episodeState(tmdbId: Int, clock: Clock): LookupResult<SeriesEpisodeState>
    suspend fun fallbackSeasons(anime: Anime, clock: Clock): SeasonEpisodesEntry? = null
}

private class LocalSeriesEpisodeStore(
    private val localDataSource: AnimeLocalDataSource,
    private val seasonsStore: SeriesSeasonsStore,
) : SeriesEpisodeStore {
    override fun getAllSeries(): List<Anime> =
        localDataSource.getAllAnimeList().filter { it.mediaType == MediaType.SERIES }

    override fun isNormalized(animeId: String): Boolean =
        localDataSource.isSeriesEpisodesNormalized(animeId)

    override fun getUpdates(): List<AnimeUpdate> = localDataSource.getUpdates()

    override fun getSeasons(animeId: String): SeasonEpisodesEntry? =
        seasonsStore.entryFor(animeId)

    override suspend fun normalize(anime: Anime, releasedEpisodes: Int) {
        localDataSource.updateAnime(anime.copy(episodes = releasedEpisodes))
        localDataSource.markSeriesEpisodesNormalized(anime.id)
    }

    override suspend fun setTmdbId(animeId: String, tmdbId: Int) {
        localDataSource.setTmdbId(animeId, tmdbId)
    }

    override suspend fun applyEpisodes(animeId: String, releasedEpisodes: Int) {
        val anime = localDataSource.getAnimeById(animeId) ?: return
        if (releasedEpisodes > anime.episodes) {
            localDataSource.updateAnime(anime.copy(episodes = releasedEpisodes))
        }
    }

    override suspend fun setUpdates(updates: List<AnimeUpdate>) {
        localDataSource.setUpdates(updates)
    }

    override suspend fun setSeasons(entry: SeasonEpisodesEntry) {
        seasonsStore.put(entry)
    }

    override suspend fun retainSeasons(seriesIds: Set<String>) {
        seasonsStore.ensureLoaded()
        seasonsStore.retainOnly(seriesIds)
    }
}

private class MovieSeriesEpisodeSource(
    private val repository: MovieSeriesRepository,
    private val tvMaze: TvMazeClient? = null,
) : SeriesEpisodeSource {
    override suspend fun resolveTmdbId(anime: Anime): Int? = repository.resolveTmdbId(
        externalIds = ExternalIds(tmdb = anime.tmdbId, kinopoisk = anime.kinopoiskId),
        title = anime.titleEn?.takeIf { it.isNotBlank() } ?: anime.title,
        contentType = AppContentType.SERIES,
        year = null,
    )

    override suspend fun episodeState(
        tmdbId: Int,
        clock: Clock,
    ): LookupResult<SeriesEpisodeState> = repository.episodeState(tmdbId, clock)

    override suspend fun fallbackSeasons(anime: Anime, clock: Clock): SeasonEpisodesEntry? {
        val client = tvMaze ?: return null
        val show = (client.show(imdbId = anime.imdbId, tvdbId = null) as? LookupResult.Found)?.value
            ?: return null
        val episodes = (client.episodes(show.id) as? LookupResult.Found)?.value.orEmpty()
        val regular = episodes.filter { (it.season ?: 0) > 0 && (it.number ?: 0) > 0 }
        if (regular.isEmpty()) return null

        val now = clock.instant()
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val grouped = regular.groupBy { it.season!! }.toSortedMap()
        val latestSeason = grouped.lastKey()
        val continuing = show.status?.equals("Running", ignoreCase = true) == true

        val rows = grouped.mapNotNull { (seasonNumber, eps) ->
            val total = eps.size
            val released = eps.count { ep ->
                when {
                    ep.airstamp != null -> !ep.airstamp!!.isAfter(now)
                    ep.airdate != null -> !ep.airdate!!.isAfter(today)
                    else -> true
                }
            }.let { if (it == 0 && !continuing) total else it }
            if (released <= 0) return@mapNotNull null
            SeasonInfo(
                seasonNumber = seasonNumber,
                episodes = released,
                totalEpisodes = total.takeIf { it > 0 },
                ongoing = continuing && seasonNumber == latestSeason && released < total,
                source = "TVmaze",
            )
        }
        if (rows.isEmpty()) return null
        return SeasonEpisodesEntry(
            animeId = anime.id,
            seasons = rows,
            complete = rows.none { it.ongoing },
            resolvedAt = clock.millis(),
            schema = SeasonEpisodesEntry.CURRENT_SCHEMA,
        )
    }
}
