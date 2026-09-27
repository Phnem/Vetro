package com.example.myapplication.network.tmdb

import java.time.LocalDate

/**
 * Released-vs-known расчёт для TMDB TV — единственное место, где решается, сколько серий
 * сериала реально вышло. Чистая логика без сети, намеренно отделена от [TmdbRemoteDataSource]:
 * это самая рискованная часть всей MOVIE/SERIES-фичи (см. .scratch/movie-series-infra/spec.md
 * → Domain rules), должна быть тестируема без моков HTTP.
 */
object TmdbEpisodeCalculator {

    /** Всего заявлено TMDB (может включать ещё не вышедшие серии текущего сезона) — только display. */
    fun knownEpisodes(seasons: List<TmdbSeasonSummary>): Int =
        seasons.filter { it.seasonNumber > 0 }.sumOf { it.episodeCount }

    /**
     * Вышедшие на сегодня серии.
     *
     * Сезон засчитывается целиком по `episodeCount` БЕЗ обращения к деталям эпизодов только
     * если существует более поздний сезон, который уже начал выходить (season.airDate — дата
     * премьеры, не завершения; сама по себе она ничего не доказывает). Самый поздний из уже
     * начавших выходить сезонов всегда считается потенциально текущим: без списка его эпизодов
     * ([episodesOfLatestStartedSeason] == null) он не засчитывается вовсе — лучше не заметить
     * одну вышедшую серию, чем ложно уведомить о ещё не вышедших.
     */
    fun releasedEpisodes(
        seasons: List<TmdbSeasonSummary>,
        episodesOfLatestStartedSeason: List<TmdbEpisodeAirDate>?,
        today: LocalDate,
    ): Int {
        val realSeasons = seasons.filter { it.seasonNumber > 0 }
        val started = realSeasons.filter { it.airDate != null && !it.airDate.isAfter(today) }
        if (started.isEmpty()) return 0

        val latestStarted = started.maxBy { it.seasonNumber }
        val provenClosed = started.filter { it.seasonNumber < latestStarted.seasonNumber }
        val closedTotal = provenClosed.sumOf { it.episodeCount }

        val latestSeasonReleased = episodesOfLatestStartedSeason
            ?.count { it.airDate != null && !it.airDate.isAfter(today) }
            ?: 0

        return closedTotal + latestSeasonReleased
    }

    /**
     * То же, что [releasedEpisodes], но по сезонам: сумма [SeriesSeasonProgress.released] всегда
     * совпадает с [releasedEpisodes]. Ещё не начавшиеся сезоны в список не попадают.
     */
    fun releasedBySeason(
        seasons: List<TmdbSeasonSummary>,
        episodesOfLatestStartedSeason: List<TmdbEpisodeAirDate>?,
        today: LocalDate,
    ): List<SeriesSeasonProgress> {
        val started = seasons
            .filter { it.seasonNumber > 0 && it.airDate != null && !it.airDate.isAfter(today) }
            .sortedBy { it.seasonNumber }
        val latest = started.lastOrNull() ?: return emptyList()
        return started.map { season ->
            val released = if (season.seasonNumber < latest.seasonNumber) {
                season.episodeCount
            } else {
                episodesOfLatestStartedSeason?.count { it.airDate != null && !it.airDate.isAfter(today) } ?: 0
            }
            SeriesSeasonProgress(season.seasonNumber, released, season.episodeCount)
        }
    }

    /** TMDB `status` → нормализованный статус; не полагается на голый `in_production`. */
    fun status(rawStatus: String?, inProduction: Boolean): SeriesStatus = when (rawStatus?.trim()?.lowercase()) {
        "ended" -> SeriesStatus.ENDED
        "canceled", "cancelled" -> SeriesStatus.CANCELLED
        "returning series" -> SeriesStatus.ONGOING
        "in production" -> if (inProduction) SeriesStatus.UPCOMING else SeriesStatus.UNKNOWN
        "planned", "pilot" -> SeriesStatus.UPCOMING
        else -> SeriesStatus.UNKNOWN
    }
}
