package com.example.myapplication.data.local

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.RatingScale
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Разделитель тегов в `GROUP_CONCAT(anime_tags.tag, CHAR(31))` из `Anime.sq`. */
private const val TAG_CONCAT_DELIMITER = ''

/**
 * Единственный маппинг строки коллекции в [Anime]. Его получают все запросы с тегами через
 * `GROUP_CONCAT` — у них один и тот же список колонок, заданный в `Anime.sq`.
 *
 * Параметры без значений по умолчанию намеренно: раньше у маппинга были дефолты, вызовы
 * «забывали» `mal_not_found_at` и id TMDB/Кинопоиска, и сохранение из AddEdit молча
 * затирало их в БД. Теперь новая колонка, не протянутая сюда, — ошибка компиляции.
 */
@Suppress("UNUSED_PARAMETER", "LongParameterList")
internal fun concatRowToAnime(
    id: String,
    title: String,
    imagePath: String?,
    episodes: Long,
    rating: Long,
    status: String,
    isFavorite: Long,
    updatedAt: Long,
    orderIndex: Long,
    dateAdded: Long,
    categoryType: String,
    syncStatus: String,
    comment: String,
    isAiRecommendation: Long,
    anilistId: Long?,
    malId: Long?,
    shikimoriId: Long?,
    anilistNotFoundAt: Long?,
    malNotFoundAt: Long?,
    shikimoriNotFoundAt: Long?,
    mediaType: String,
    titleEn: String?,
    titleRu: String?,
    tmdbId: Long?,
    tmdbNotFoundAt: Long?,
    kinopoiskId: Long?,
    kinopoiskNotFoundAt: Long?,
    imdbId: String?,
    tagsConcat: String?,
): Anime = Anime(
    id = id,
    title = title,
    titleEn = titleEn,
    titleRu = titleRu,
    episodes = episodes.toInt(),
    rating = RatingScale.storedToDisplay(rating),
    imageFileName = imagePath,
    orderIndex = orderIndex.toInt(),
    dateAdded = dateAdded,
    isFavorite = isFavorite == 1L,
    tags = parseTagsConcat(tagsConcat),
    categoryType = categoryType,
    comment = comment,
    anilistId = anilistId?.toInt(),
    malId = malId?.toInt(),
    shikimoriId = shikimoriId?.toInt(),
    anilistNotFoundAt = anilistNotFoundAt,
    malNotFoundAt = malNotFoundAt,
    shikimoriNotFoundAt = shikimoriNotFoundAt,
    mediaType = MediaType.fromPersistedValue(mediaType),
    tmdbId = tmdbId?.toInt(),
    kinopoiskId = kinopoiskId?.toInt(),
    tmdbNotFoundAt = tmdbNotFoundAt,
    kinopoiskNotFoundAt = kinopoiskNotFoundAt,
    imdbId = imdbId,
)

internal fun parseTagsConcat(tagsConcat: String?): ImmutableList<String> {
    val raw = tagsConcat?.trim().orEmpty()
    if (raw.isEmpty()) return persistentListOf()
    return raw.split(TAG_CONCAT_DELIMITER)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toImmutableList()
}
