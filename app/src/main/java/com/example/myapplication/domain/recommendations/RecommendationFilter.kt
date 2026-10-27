package com.example.myapplication.domain.recommendations

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.domain.normalizeForSearch

/**
 * Отсев кандидатов: уже в библиотеке (по внешним id И по нормализованному тайтлу), уже оценённые
 * свайпом, без обложки — до скоринга, чтобы не тратить работу на мусор.
 *
 * TMDB id фильма и сериала живут в разных пространствах (`MOVIE:550` ≠ `TV:550`), поэтому проверка
 * «уже в коллекции» учитывает тип кандидата.
 */
class RecommendationFilter {

    fun filter(
        pool: List<PoolEntry>,
        library: List<Anime>,
        /** Уже оценённые свайпом карточки (одобренные и отклонённые) повторно не показываются. */
        judged: FeedbackIndex = FeedbackIndex.Empty,
        /**
         * false — не отсекать записи без постера до склейки дублей: подтверждение от источника без
         * картинок (Trakt) нужно сохранить, обложку берёт склеенная карточка из другого источника.
         */
        requirePoster: Boolean = true,
    ): List<PoolEntry> {
        val libraryTitleKeys = HashSet<String>(library.size * 2)
        val libraryAnilistIds = HashSet<Int>()
        val libraryMalIds = HashSet<Int>()
        val libraryShikimoriIds = HashSet<Int>()
        val libraryMovieTmdbIds = HashSet<Int>()
        val librarySeriesTmdbIds = HashSet<Int>()
        val libraryImdbIds = HashSet<String>()
        for (anime in library) {
            // Дедуп по обеим локалям: основной title + titleEn + titleRu.
            anime.title.normalizeForSearch().takeIf { it.isNotEmpty() }?.let { libraryTitleKeys.add(it) }
            anime.titleEn?.normalizeForSearch()?.takeIf { it.isNotEmpty() }?.let { libraryTitleKeys.add(it) }
            anime.titleRu?.normalizeForSearch()?.takeIf { it.isNotEmpty() }?.let { libraryTitleKeys.add(it) }
            anime.anilistId?.let { libraryAnilistIds.add(it) }
            anime.malId?.let { libraryMalIds.add(it) }
            anime.shikimoriId?.let { libraryShikimoriIds.add(it) }
            anime.tmdbId?.let {
                if (anime.mediaType == MediaType.SERIES) librarySeriesTmdbIds.add(it) else libraryMovieTmdbIds.add(it)
            }
            anime.imdbId?.let { libraryImdbIds.add(it) }
        }

        return pool.filter { entry ->
            val r = entry.result
            if (requirePoster && r.posterUrl.isNullOrBlank()) return@filter false
            if (isInLibraryById(entry, libraryAnilistIds, libraryMalIds, libraryShikimoriIds)) return@filter false
            val tmdbId = r.externalIds.tmdb ?: r.externalId?.toIntOrNull()?.takeIf { r.source.equals("tmdb", ignoreCase = true) }
            if (tmdbId != null) {
                val owned = if (entry.contentType == ContentType.TV) librarySeriesTmdbIds else libraryMovieTmdbIds
                if (tmdbId in owned) return@filter false
            }
            if (r.externalIds.imdb?.let { it in libraryImdbIds } == true) return@filter false
            val titleKey = r.title.normalizeForSearch()
            val altKey = r.altTitle?.normalizeForSearch().orEmpty()
            if (titleKey in libraryTitleKeys || (altKey.isNotEmpty() && altKey in libraryTitleKeys)) return@filter false
            !judged.isJudged(titleKey, altKey)
        }
    }

    private fun isInLibraryById(
        entry: PoolEntry,
        anilistIds: Set<Int>,
        malIds: Set<Int>,
        shikimoriIds: Set<Int>,
    ): Boolean {
        val result = entry.result
        val id = result.externalId?.toIntOrNull() ?: return false
        return when (result.source.lowercase()) {
            "anilist" -> id in anilistIds
            "mal", "jikan" -> id in malIds
            "shikimori" -> id in shikimoriIds
            else -> false
        }
    }
}
