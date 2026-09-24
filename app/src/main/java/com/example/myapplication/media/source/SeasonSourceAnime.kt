package com.example.myapplication.media.source

import com.example.myapplication.data.models.Anime
import com.example.myapplication.domain.seasons.SeasonInfo

/**
 * Запрос к источнику, сужённый до выбранного сезона.
 *
 * Заменяет прежний `Anime.scopedToSeason(): Anime?`, у которого одно значение `null` означало
 * сразу три вещи — «сезон не выбран», «сезон не опознать по названию» и «источник запускать
 * не надо». Вызывающие трактовали его по-разному и молча выключали источники: `SourceEngine`
 * убирал AnimeGo, `KodikSource` — yummy-путь. Здесь неопознаваемость выражена флагом, а набор
 * алиасов остаётся работоспособным при любом номере сезона.
 */
internal data class SeasonSourceQuery(
    /**
     * Аниме с алиасами для поиска. Сезонное название стоит в [Anime.title] и [Anime.titleEn],
     * франшизное русское — в [Anime.titleRu] и НЕ стирается: AnimeGo, Kodik и AniLibria —
     * русские каталоги, и это единственный алиас, которым они реально находят тайтл.
     */
    val anime: Anime,
    /** Номер сезона в шкале КАТАЛОГА (не в шкале источника). 1, если сезон не выбран. */
    val seasonNumber: Int,
    /**
     * У сезона есть собственное название, значит принадлежность релиза сезону можно требовать
     * доказательно. Когда флаг снят, источник обязан опираться на порядковые признаки и не
     * имеет права выдавать соседний сезон за запрошенный.
     */
    val seasonIdentifiable: Boolean,
)

/**
 * Сужает поиск до выбранного сезона, не теряя франшизных алиасов.
 *
 * Сезонное название ДОБАВЛЯЕТСЯ впереди набора, а не заменяет его: точность сопоставления
 * даёт порядок приоритета плюс порог матчера, а не выпиливание остальных названий.
 */
internal fun Anime.seasonSourceQuery(seasonInfo: SeasonInfo?): SeasonSourceQuery {
    if (seasonInfo == null) {
        return SeasonSourceQuery(this, seasonNumber = 1, seasonIdentifiable = false)
    }
    // Спецвыпуск — самостоятельный релиз, а не N-й сезон: его номер в расклад вписан лишь затем,
    // чтобы строки не делили один ключ. Отдать его источнику значило бы попросить несуществующий
    // «сезон 5» вместо OVA, у которой своя страница.
    val seasonNumber = if (seasonInfo.isSpecial) 1 else seasonInfo.seasonNumber.coerceAtLeast(1)
    val seasonTitle = seasonInfo.title?.trim().orEmpty()
    if (seasonTitle.isEmpty()) {
        // Строка сезона от источника просмотра («Найти ещё») названия не несёт. Раньше здесь
        // возвращался null и источник выключался целиком — теперь он ищет франшизными алиасами.
        return SeasonSourceQuery(this, seasonNumber, seasonIdentifiable = false)
    }
    return SeasonSourceQuery(
        anime = copy(
            title = seasonTitle,
            titleEn = seasonTitle,
            episodes = seasonInfo.episodes,
            anilistId = seasonInfo.anilistId,
            malId = seasonInfo.malId,
            shikimoriId = null,
        ),
        seasonNumber = seasonNumber,
        seasonIdentifiable = true,
    )
}
