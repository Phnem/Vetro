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
 * Поэтому: снимок не отстаёт от расклада (тот же сезон или новый, ещё не заведённый) — целиком
 * снимок; выходящий сезон расклада дальше снимка — номер из расклада, серии — свежие из снимка.
 *
 * null — ни расклада, ни номера выходящего сезона: подпись остаётся прежней, «12 eps.».
 */
fun latestSeasonEpisode(layout: SeasonEpisodesEntry?, airing: AiringProgress?): SeasonEpisode? {
    val last = layout.regularSeasons().lastOrNull()
    val airingSeason = airing?.seasonNumber?.takeIf { airing.airedEpisodes > 0 }
    return when {
        airingSeason != null && (last == null || airingSeason >= last.seasonNumber) ->
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

/**
 * Счётчик просмотренных серий тайтла (`Anime.episodes`) → сезон и серия в нём по раскладу.
 *
 * У счётчика два масштаба (так его пишет проверка новых серий): серии своей записи, пока их не
 * больше, чем в её сезоне ([ownSeasonNumber] — сезон, на который указывают anilist/mal id тайтла),
 * иначе — сумма по франшизе. 62 при сезонах 11/12/13/12/24 и своём первом сезоне — это «S5 E14»;
 * 14 у записи «Season 3» — «S5 E14» её собственного сезона. Спецвыпуски в счёт не входят; счётчик
 * больше всех известных серий — последняя серия последнего сезона.
 *
 * null — смотреть нечего (0) или раскладывать не по чему.
 */
fun watchedPositionFromCount(count: Int, seasons: List<SeasonInfo>, ownSeasonNumber: Int? = null): SeasonEpisode? {
    if (count <= 0) return null
    val regular = seasons.filter { !it.isSpecial && it.episodes > 0 }.sortedBy { it.seasonNumber }
    if (regular.isEmpty()) return null
    regular.firstOrNull { it.seasonNumber == ownSeasonNumber }
        ?.takeIf { count <= it.episodes }
        ?.let { return SeasonEpisode(it.seasonNumber, count) }
    var remaining = count
    for (season in regular) {
        if (remaining <= season.episodes) return SeasonEpisode(season.seasonNumber, remaining)
        remaining -= season.episodes
    }
    val last = regular.last()
    return SeasonEpisode(last.seasonNumber, last.episodes)
}
