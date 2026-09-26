package com.example.myapplication.data.local

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.RatingScale

/** Atomic persistence seam for a newly created collection entry and its SERIES semantics marker. */
internal fun AnimeDatabase.insertNewAnime(
    anime: Anime,
    updatedAt: Long = System.currentTimeMillis(),
) {
    animeQueries.transaction {
        insertAnimeRow(anime, updatedAt)
        if (anime.mediaType == MediaType.SERIES) {
            animeQueries.markSeriesEpisodesNormalized(anime_id = anime.id)
        }
    }
}

/**
 * Строка коллекции и её теги — единственное место, где [Anime] раскладывается по колонкам
 * вставки. Транзакцию открывает вызывающий: одиночная вставка и пакетная ([insertNewAnime],
 * `AnimeLocalDataSource.insertAllAnime`) держат её каждая по-своему.
 */
internal fun AnimeDatabase.insertAnimeRow(anime: Anime, updatedAt: Long) {
    animeQueries.insertAnime(
        id = anime.id,
        title = anime.title,
        imagePath = anime.imageFileName,
        episodes = anime.episodes.toLong(),
        rating = RatingScale.displayToStored(anime.rating).toLong(),
        status = "watching",
        isFavorite = if (anime.isFavorite) 1L else 0L,
        updatedAt = updatedAt,
        orderIndex = anime.orderIndex.toLong(),
        dateAdded = anime.dateAdded,
        categoryType = anime.categoryType,
        comment = anime.comment,
        isAiRecommendation = 0L,
        anilist_id = anime.anilistId?.toLong(),
        mal_id = anime.malId?.toLong(),
        shikimori_id = anime.shikimoriId?.toLong(),
        anilist_not_found_at = anime.anilistNotFoundAt,
        mal_not_found_at = anime.malNotFoundAt,
        shikimori_not_found_at = anime.shikimoriNotFoundAt,
        isPrivate = 0L,
        encryptionIv = null,
        deletedAt = null,
        mediaType = anime.mediaType.name,
        title_en = anime.titleEn,
        title_ru = anime.titleRu,
        tmdb_id = anime.tmdbId?.toLong(),
        kinopoisk_id = anime.kinopoiskId?.toLong(),
        tmdb_not_found_at = anime.tmdbNotFoundAt,
        kinopoisk_not_found_at = anime.kinopoiskNotFoundAt,
        imdb_id = anime.imdbId,
    )
    anime.tags.forEach { tag ->
        animeQueries.insertAnimeTag(anime_id = anime.id, tag = tag)
    }
}
