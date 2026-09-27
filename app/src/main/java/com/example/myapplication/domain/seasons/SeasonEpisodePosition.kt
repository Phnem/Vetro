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
 * Два источника и у каждого своя сильная сторона:
 *  • снимок выхода ([airing]) переписывается каждой проверкой серий — у него свежее ЧИСЛО серий,
 *    но номер сезона он считает по цепочке приквелов и на франшизах со спешлами промахивается
 *    (у JoJo: «S8» при восьми сезонах до выходящего);
 *  • расклад собран по полному графу франшизы — у него верный НОМЕР, но он перерезолвится по TTL
 *    и может отстать на сезон.
 * Поэтому: выходящий сезон есть в раскладе — номер из расклада, серии — свежие из снимка; снимок
 * ушёл дальше расклада (новый сезон ещё не заведён) — целиком снимок.
 *
 * null — ни расклада, ни номера выходящего сезона: подпись остаётся прежней, «12 eps.».
 */
fun latestSeasonEpisode(layout: SeasonEpisodesEntry?, airing: AiringProgress?): SeasonEpisode? {
    val last = layout.regularSeasons().lastOrNull()
    val airingSeason = airing?.seasonNumber?.takeIf { airing.airedEpisodes > 0 }
    return when {
        airingSeason != null && (last == null || airingSeason > last.seasonNumber) ->
            SeasonEpisode(airingSeason, airing.airedEpisodes)
        last != null && last.ongoing && airing != null && airing.airedEpisodes > 0 ->
            SeasonEpisode(last.seasonNumber, airing.airedEpisodes)
        last != null -> SeasonEpisode(last.seasonNumber, last.episodes)
        airingSeason != null -> SeasonEpisode(airingSeason, airing.airedEpisodes)
        else -> null
    }
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
