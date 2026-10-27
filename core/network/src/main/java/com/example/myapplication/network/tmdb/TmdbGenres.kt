package com.example.myapplication.network.tmdb

/**
 * Жанры TMDB (id → английские названия). Списки recommendations/similar/trending/discover отдают
 * только `genre_ids`, а отдельный запрос списка жанров на каждый показ дороже, чем держать стабильную
 * таблицу. Названия подобраны под теги приложения (Sci-Fi, Action, Adventure…): у ТВ-жанров-«гибридов»
 * («Action & Adventure», «Sci-Fi & Fantasy») один id раскрывается в два тега.
 */
object TmdbGenres {

    private val movie: Map<Int, List<String>> = mapOf(
        28 to listOf("Action"),
        12 to listOf("Adventure"),
        16 to listOf("Animation"),
        35 to listOf("Comedy"),
        80 to listOf("Crime"),
        99 to listOf("Documentary"),
        18 to listOf("Drama"),
        10751 to listOf("Family"),
        14 to listOf("Fantasy"),
        36 to listOf("History"),
        27 to listOf("Horror"),
        10402 to listOf("Music"),
        9648 to listOf("Mystery"),
        10749 to listOf("Romance"),
        878 to listOf("Sci-Fi"),
        10770 to listOf("TV Movie"),
        53 to listOf("Thriller"),
        10752 to listOf("War"),
        37 to listOf("Western"),
    )

    private val tv: Map<Int, List<String>> = mapOf(
        10759 to listOf("Action", "Adventure"),
        16 to listOf("Animation"),
        35 to listOf("Comedy"),
        80 to listOf("Crime"),
        99 to listOf("Documentary"),
        18 to listOf("Drama"),
        10751 to listOf("Family"),
        10762 to listOf("Kids"),
        9648 to listOf("Mystery"),
        10763 to listOf("News"),
        10764 to listOf("Reality"),
        10765 to listOf("Sci-Fi", "Fantasy"),
        10766 to listOf("Soap"),
        10767 to listOf("Talk"),
        10768 to listOf("War", "Politics"),
        37 to listOf("Western"),
    )

    fun names(ids: List<Int>, isTv: Boolean): List<String> {
        val table = if (isTv) tv else movie
        return ids.flatMap { table[it].orEmpty() }.distinct()
    }

    /** Обратный поиск для discover: тег приложения → id жанра TMDB (первый подходящий). */
    fun idFor(name: String, isTv: Boolean): Int? {
        val table = if (isTv) tv else movie
        return table.entries.firstOrNull { (_, names) -> names.any { it.equals(name, ignoreCase = true) } }?.key
    }
}
