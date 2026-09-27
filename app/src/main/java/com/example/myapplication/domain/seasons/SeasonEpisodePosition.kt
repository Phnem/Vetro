package com.example.myapplication.domain.seasons

import com.example.myapplication.data.models.AiringProgress

/** Позиция внутри тайтла: сезон и номер серии в этом сезоне («S3 E5»). */
data class SeasonEpisode(val season: Int, val episode: Int) {
    /** Компактная подпись, одинаковая для обоих языков: «S3 E5». */
    fun label(): String = "S$season E$episode"
}

/** Обычные сезоны расклада (без спецвыпусков) с хотя бы одной вышедшей серией, по возрастанию. */
fun SeasonEpisodesEntry?.regularSeasons(): List<SeasonInfo> =
    this?.seasons.orEmpty()
        .filter { !it.isSpecial && it.episodes > 0 }
        .sortedBy { it.seasonNumber }

/**
 * Последняя вышедшая серия тайтла в разбивке по сезонам — то, что показывают карточка и
 * уведомление «вышла новая серия» вместо сквозного счёта по всей франшизе.
 *
 * Выходящий сезон ([airing]) приоритетнее расклада: расклад перерезолвится по TTL и может отстать
 * на сезон, а снимок выхода переписывается каждой проверкой серий. Без номера сезона снимок
 * бесполезен (источник без графа франшизы) — тогда решает расклад.
 *
 * null — ни расклада, ни номера выходящего сезона: подпись остаётся прежней, «12 eps.».
 */
fun latestSeasonEpisode(layout: SeasonEpisodesEntry?, airing: AiringProgress?): SeasonEpisode? {
    val airingSeason = airing?.seasonNumber
    if (airingSeason != null && airing.airedEpisodes > 0) {
        return SeasonEpisode(airingSeason, airing.airedEpisodes)
    }
    val last = layout.regularSeasons().lastOrNull() ?: return null
    return SeasonEpisode(last.seasonNumber, last.episodes)
}

/** Сезон, который прямо сейчас выходит, по раскладу (TMDB у сериалов, AniList у аниме). */
fun SeasonEpisodesEntry?.ongoingSeason(): SeasonInfo? =
    regularSeasons().lastOrNull()?.takeIf { it.ongoing }

/** Выходит ли сезон прямо сейчас: закрытая строка «сезон вышел полностью» (aired == total) — нет. */
fun AiringProgress.isAiringNow(): Boolean {
    val total = totalEpisodes ?: return true
    return airedEpisodes < total
}

/**
 * Подпись «что вышло» для уведомления о новых сериях: «S3 E5», а если вышло несколько серий
 * одного сезона — диапазон «S3 E4–5». [latest] — последняя вышедшая серия (см. [latestSeasonEpisode]).
 * Если прибавка перешагивает границу сезона, диапазон не строим: подписываем последнюю серию.
 */
fun releasedEpisodesLabel(currentEpisodes: Int, newEpisodes: Int, latest: SeasonEpisode): String {
    val added = (newEpisodes - currentEpisodes).coerceAtLeast(1)
    val first = latest.episode - added + 1
    return if (added > 1 && first >= 1) {
        "S${latest.season} E$first–${latest.episode}"
    } else {
        latest.label()
    }
}
