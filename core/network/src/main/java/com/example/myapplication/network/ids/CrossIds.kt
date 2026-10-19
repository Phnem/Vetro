package com.example.myapplication.network.ids

/**
 * Один тайтл в разных каталогах. Каждый источник знает свой набор id: Кинопоиск — kp/imdb/tmdb,
 * Wikidata и карта аниме — почти все. Поэтому id сводятся в одну запись и дополняют друг друга, а
 * нужный каталог потом достаётся по своему id, а не по слабому поиску названия.
 */
data class CrossIds(
    val wikidata: String? = null,
    val kinopoisk: Int? = null,
    /** Канонический вид `tt0412142`. */
    val imdb: String? = null,
    val tmdbMovie: Int? = null,
    val tmdbTv: Int? = null,
    val tvdb: Int? = null,
    val mal: Int? = null,
    val anilist: Int? = null,
    val kitsu: Int? = null,
    val anidb: Int? = null,
    val simkl: Int? = null,
) {
    val isEmpty: Boolean
        get() = kinopoisk == null && imdb == null && tmdbMovie == null && tmdbTv == null && tvdb == null &&
            mal == null && anilist == null && kitsu == null && anidb == null && simkl == null

    /** Сколько каталогов знают тайтл — чем больше, тем надёжнее запись. */
    val weight: Int
        get() = listOfNotNull(kinopoisk, imdb, tmdbMovie, tmdbTv, tvdb, mal, anilist, kitsu, anidb, simkl).size

    /** Свои значения важнее; чужие закрывают пустые поля. */
    fun merge(other: CrossIds): CrossIds = CrossIds(
        wikidata = wikidata ?: other.wikidata,
        kinopoisk = kinopoisk ?: other.kinopoisk,
        imdb = imdb ?: other.imdb,
        tmdbMovie = tmdbMovie ?: other.tmdbMovie,
        tmdbTv = tmdbTv ?: other.tmdbTv,
        tvdb = tvdb ?: other.tvdb,
        mal = mal ?: other.mal,
        anilist = anilist ?: other.anilist,
        kitsu = kitsu ?: other.kitsu,
        anidb = anidb ?: other.anidb,
        simkl = simkl ?: other.simkl,
    )
}

/** Каталоги, по id которых можно найти тайтл в Wikidata и в карте соответствия. */
enum class ExternalIdKind(val wikidataProperty: String) {
    KINOPOISK("P2603"),
    IMDB("P345"),
    TMDB_MOVIE("P4947"),
    TMDB_TV("P4983"),
    TVDB("P4835"),
    MAL("P4086"),
    ANILIST("P8729"),
    KITSU("P11495"),
}

/** Тип записи для выбора правил поиска; у аниме свои каталоги. */
enum class TitleKind { MOVIE, SERIES, ANIME }

/** IMDb id в каноническом виде: только `tt` и цифры, иначе null. */
internal fun canonicalImdb(raw: String?): String? {
    val v = raw?.trim()?.lowercase() ?: return null
    return v.takeIf { Regex("tt\\d{5,10}").matches(it) }
}
