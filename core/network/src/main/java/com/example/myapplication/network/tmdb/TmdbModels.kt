package com.example.myapplication.network.tmdb

import java.time.LocalDate

/** Сводка по сезону из `/tv/{id}` — без деталей эпизодов. */
data class TmdbSeasonSummary(
    val seasonNumber: Int,
    val episodeCount: Int,
    /** Дата премьеры сезона — НЕ дата завершения. Недостаточна сама по себе, чтобы считать сезон закрытым. */
    val airDate: LocalDate?,
)

/** Один эпизод из `/tv/{id}/season/{n}` — только то, что нужно для released-подсчёта. */
data class TmdbEpisodeAirDate(
    val episodeNumber: Int,
    val airDate: LocalDate?,
)

/** Нормализованный статус сериала — маппится из TMDB `status`, не из голого `in_production`. */
enum class SeriesStatus { UPCOMING, ONGOING, ENDED, CANCELLED, UNKNOWN }

data class SeriesEpisodeState(
    val releasedEpisodes: Int,
    val knownEpisodes: Int,
    val status: SeriesStatus,
    /** Разбивка по начавшим выходить сезонам (без «Specials» = сезон 0), по возрастанию номера. */
    val seasons: List<SeriesSeasonProgress> = emptyList(),
)

/**
 * Один начавший выходить сезон сериала: сколько серий уже вышло и сколько заявлено.
 *
 * [released] считается по тем же правилам, что [SeriesEpisodeState.releasedEpisodes]: сезоны до
 * последнего начавшегося засчитываются целиком, у последнего — только серии с прошедшей датой.
 */
data class SeriesSeasonProgress(
    val seasonNumber: Int,
    val released: Int,
    /** Заявленное число серий сезона; 0 — TMDB ещё не знает. */
    val total: Int,
)
