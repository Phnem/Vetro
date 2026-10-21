package com.example.myapplication.ui.home

import com.example.myapplication.data.models.AiringProgress
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.domain.seasons.SeasonEpisodesEntry
import com.example.myapplication.domain.seasons.isAiringNow
import com.example.myapplication.domain.seasons.latestSeasonEpisode
import com.example.myapplication.domain.seasons.ongoingSeason
import com.example.myapplication.domain.seasons.regularSeasons
import com.example.myapplication.manga.domain.MangaReadingSummary
import com.example.myapplication.media.progress.PlaybackEpisodeKey

/**
 * Прогресс на карточке коллекции: выход сезона, просмотр или чтение — или null, если показывать нечего.
 * Чистая функция: её считает и карточка главной ([rememberCardProgress]), и [autoWatchStatus], поэтому
 * статус «по умолчанию» никогда не расходится с тем, что человек видит на карточке.
 */
internal fun computeCardProgress(
    layout: SeasonEpisodesEntry?,
    storedEpisodes: Int,
    watched: PlaybackEpisodeKey?,
    airing: AiringProgress?,
    reading: MangaReadingSummary?,
): AiringCardInfo? {
    fun airingInfo(a: AiringProgress) = AiringCardInfo(
        // Номер выходящего сезона — из расклада, если сезон там уже есть (см. latestSeasonEpisode):
        // снимок выхода считает сезоны по приквелам и на франшизах со спешлами промахивается.
        seasonNumber = latestSeasonEpisode(layout, a)?.season ?: layout.ongoingSeason()?.seasonNumber,
        airedEpisodes = a.airedEpisodes,
        totalEpisodes = a.totalEpisodes,
        kind = CardProgressKind.AIRING,
    )
    return when {
        reading != null -> AiringCardInfo(
            seasonNumber = null,
            airedEpisodes = reading.readChapters,
            totalEpisodes = reading.totalChapters,
            kind = CardProgressKind.READING,
            newItems = reading.newChapters,
        )

        // Сезон выходит, и вышло не меньше, чем посмотрено в нём, — бар выхода. Смотрят другой
        // сезон или сезон закрыт — сравнивать нечего, остаётся бар просмотра ниже.
        airing != null && airing.isAiringNow() && (
            watched == null || run {
                val airingSeason = airingInfo(airing).seasonNumber
                (airingSeason == null || airingSeason == watched.season) && airing.airedEpisodes >= watched.episode
            }
            ) -> airingInfo(airing)

        // Прогресс внутри сезона: «S3 · 6 / 12». Знаменатель — серии этого сезона по раскладу;
        // без расклада он известен только у первого сезона (это сохранённый счётчик). Числитель
        // не подрезается: зажимать реальный прогресс под неразрешённый расклад — значит
        // показывать пользователю не то, что он посмотрел.
        watched != null -> {
            val seasons = layout.regularSeasons()
            val season = seasons.firstOrNull { it.seasonNumber == watched.season }
            AiringCardInfo(
                seasonNumber = watched.season.takeIf { seasons.isNotEmpty() || it > 1 },
                airedEpisodes = watched.episode,
                totalEpisodes = season?.let { it.totalEpisodes ?: it.episodes }
                    ?: storedEpisodes.takeIf { seasons.isEmpty() && watched.season == 1 && it > 0 },
                kind = CardProgressKind.WATCHING,
            )
        }

        airing != null -> airingInfo(airing)

        else -> null
    }
}

/**
 * Статус просмотра «по умолчанию», пока пользователь не выбрал свой: тайтл в процессе — на карточке
 * есть полоска и она не закрыта (серий/глав меньше, чем всего, или всего ещё не известно) — это
 * «Смотрю»; всё остальное — «Просмотрено».
 */
internal fun AiringCardInfo?.autoWatchStatus(): WatchStatus {
    val total = this?.totalEpisodes
    val inProgress = this != null && (total == null || airedEpisodes < total)
    return if (inProgress) WatchStatus.WATCHING else WatchStatus.COMPLETED
}
