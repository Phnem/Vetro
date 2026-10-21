package com.example.myapplication.domain.search

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.RatingScale
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.data.repository.ImageStorageRepository
import com.example.myapplication.domain.IdGenerator
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.domain.addedit.SaveAnimeParams
import com.example.myapplication.domain.addedit.SaveAnimeUseCase
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppLanguage

/** Рейтинг API → 10-балльная шкала приложения (см. [RatingScale.fromApi]). */
fun apiRatingTo10(apiRating: Int?): Float = RatingScale.fromApi(apiRating)

fun mapApiGenresToTagIds(genres: List<String>, genreRepository: GenreRepository): List<String> =
    genres.mapNotNull { apiGenre ->
        val normalized = apiGenre.trim()
        if (normalized.isBlank()) return@mapNotNull null
        genreRepository.allGenres.find { def ->
            def.id.equals(normalized, ignoreCase = true) ||
                def.ru.equals(normalized, ignoreCase = true) ||
                def.en.equals(normalized, ignoreCase = true) ||
                def.id.replace("-", " ").equals(normalized.replace("-", " "), ignoreCase = true)
        }?.id
    }.distinct().take(5)

private fun MediaType?.isMovieOrSeries(): Boolean = this == MediaType.MOVIE || this == MediaType.SERIES

private data class SearchIdentityProjection(
    val mediaType: MediaType?,
    val titleEn: String?,
    val titleRu: String?,
    val anilistId: Int?,
    val malId: Int?,
    val shikimoriId: Int?,
    val tmdbId: Int?,
    val kinopoiskId: Int?,
)

/** Единственная identity-проекция; save и duplicate probe не повторяют source/id правила. */
private fun ApiSearchResult.identityProjection(): SearchIdentityProjection {
    val mediaType = MediaType.fromCategoryType(categoryType)
    val legacyExternalId = externalId?.toIntOrNull()
    val movieOrSeries = mediaType.isMovieOrSeries()
    return SearchIdentityProjection(
        mediaType = mediaType,
        titleEn = titleEn.takeIf { movieOrSeries },
        titleRu = titleRu.takeIf { movieOrSeries },
        anilistId = legacyExternalId.takeIf { source.equals("AniList", ignoreCase = true) },
        malId = malId ?: legacyExternalId.takeIf {
            source.equals("MAL", ignoreCase = true) || source.equals("Jikan", ignoreCase = true)
        },
        shikimoriId = legacyExternalId.takeIf { source.equals("Shikimori", ignoreCase = true) },
        tmdbId = externalIds.tmdb.takeIf { movieOrSeries },
        kinopoiskId = externalIds.kinopoisk.takeIf { movieOrSeries },
    )
}

/** Чистый mapper search result → save params; сетевые side effects остаются в use case. */
internal fun buildSaveAnimeParams(
    result: ApiSearchResult,
    effectiveMalId: Int?,
    imageFileName: String?,
    selectedTags: List<String>,
    rating10: Float,
    dateAdded: Long,
): SaveAnimeParams {
    val identity = result.identityProjection()
    return SaveAnimeParams(
        animeId = null,
        title = result.title,
        titleEn = identity.titleEn,
        titleRu = identity.titleRu,
        episodes = if (identity.mediaType.isMovieOrSeries()) {
            1
        } else {
            result.episodes.coerceAtLeast(1)
        },
        rating = rating10,
        imageUri = null,
        currentImageFileName = imageFileName,
        orderIndex = 0,
        dateAdded = dateAdded,
        isFavorite = false,
        selectedTags = selectedTags,
        categoryType = result.categoryType,
        mediaType = identity.mediaType,
        comment = "",
        anilistId = identity.anilistId,
        malId = effectiveMalId ?: identity.malId,
        shikimoriId = identity.shikimoriId,
        tmdbId = identity.tmdbId,
        kinopoiskId = identity.kinopoiskId,
        releaseYear = result.seasonYear?.takeIf { it > 0 },
    )
}

/** Та же identity-проекция используется перед сохранением и общим duplicate rule. */
internal fun ApiSearchResult.toDuplicateProbe(): Anime? {
    val identity = identityProjection()
    val mediaType = identity.mediaType ?: return null
    return Anime(
        id = "",
        title = title,
        titleEn = identity.titleEn,
        titleRu = identity.titleRu,
        episodes = 0,
        rating = 0f,
        imageFileName = null,
        orderIndex = 0,
        dateAdded = 0L,
        anilistId = identity.anilistId,
        malId = identity.malId,
        shikimoriId = identity.shikimoriId,
        tmdbId = identity.tmdbId,
        kinopoiskId = identity.kinopoiskId,
        mediaType = mediaType,
    )
}

/**
 * Adds a media item from API search result to the local database.
 * Downloads poster from URL, maps genres to app genre IDs, converts rating to the 10-point scale, saves via SaveAnimeUseCase.
 */
/** Чем закончилось добавление — записью или отказом, потому что тайтл уже в коллекции. */
enum class AddFromApiOutcome { ADDED, ALREADY_IN_COLLECTION }

class AddFromApiUseCase(
    private val saveAnimeUseCase: SaveAnimeUseCase,
    private val imageStorage: ImageStorageRepository,
    private val idGenerator: IdGenerator,
    private val genreRepository: GenreRepository,
    private val repository: AnimeRepository,
) {
    suspend operator fun invoke(
        result: ApiSearchResult
    ): Result<AddFromApiOutcome> = runCatching {
        // Проверка дубликата — до всякой работы. Раньше её не было вовсе, и каждый вызов начинался
        // с генерации нового UUID: N нажатий давали N записей. Проверять надо именно здесь, а не в
        // UI: между нажатием и сохранением проходит скачивание постера, и за это время успевает
        // прилететь второй вызов.
        if (findExistingDuplicate(result) != null) {
            return@runCatching AddFromApiOutcome.ALREADY_IN_COLLECTION
        }

        val animeId = idGenerator.generateUuid()
        val imageFileName = result.posterUrl?.let { url ->
            imageStorage.saveImageFromUrl(url, animeId).getOrNull()
        }

        val rating10 = apiRatingTo10(result.rating)
        val selectedTags = mapApiGenresToTagIds(result.genres, genreRepository)

        // Shikimori-результат из поиска несёт только shikimori_id; MAL id дотягиваем detail-запросом,
        // чтобы у записи сразу были ОБА id (для обогащения english по id, а не по названию).
        val shikimoriMalId = if (result.source.equals("Shikimori", ignoreCase = true) && result.malId == null) {
            result.externalId?.toIntOrNull()?.let { sid ->
                repository.shikimoriById(sid, AppLanguage.EN).getOrNull()?.malId
            }
        } else {
            null
        }
        val effectiveMalId = result.malId ?: shikimoriMalId ?: result.externalId?.toIntOrNull().takeIf {
            result.source.equals("MAL", ignoreCase = true) || result.source.equals("Jikan", ignoreCase = true)
        }

        val params = buildSaveAnimeParams(
            result = result,
            effectiveMalId = effectiveMalId,
            imageFileName = imageFileName,
            selectedTags = selectedTags,
            rating10 = rating10,
            dateAdded = idGenerator.currentTimeMillis(),
        )
        saveAnimeUseCase(params)
        AddFromApiOutcome.ADDED
    }

    /**
     * Ищет в коллекции запись, которую этот результат поиска продублировал бы.
     *
     * Сравнение идёт тем же правилом [isDuplicate], что и схлопывание списка, — чтобы «не дал
     * добавить» и «схлопнул при проходе» не разошлись в понимании того, что такое дубликат.
     *
     * Если тип записи из результата вывести не удалось, проверка **пропускается**: правило
     * дубликата опирается на `mediaType`, и сравнивать с неизвестным типом значило бы гадать.
     * Ошибиться в сторону «добавить лишнее» безопаснее, чем молча отказать в добавлении.
     */
    private fun findExistingDuplicate(result: ApiSearchResult): Anime? {
        val probe = result.toDuplicateProbe() ?: return null
        return repository.getAllAnimeSnapshot().firstOrNull { isDuplicate(it, probe) }
    }
}
