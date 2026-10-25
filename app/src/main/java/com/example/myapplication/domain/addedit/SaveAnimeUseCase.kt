package com.example.myapplication.domain.addedit

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.DevPreferencesKeys
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.repository.ImageStorageRepository
import com.example.myapplication.domain.IdGenerator
import com.example.myapplication.domain.enrichment.CollectionEnrichmentCoordinator
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.first

class SaveAnimeUseCase(
    private val localDataSource: AnimeLocalDataSource,
    private val imageStorage: ImageStorageRepository,
    private val idGenerator: IdGenerator,
    private val enrichmentCoordinator: CollectionEnrichmentCoordinator,
) {
    suspend operator fun invoke(params: SaveAnimeParams): Result<Unit> = runCatching {
        val animeId = params.animeId ?: idGenerator.generateUuid()
        val imageFileName = params.imageUri?.let { uri ->
            imageStorage.saveImage(uri, animeId).getOrThrow()
        } ?: params.currentImageFileName

        val isNew = params.animeId == null
        // AddEdit про статус просмотра и год релиза ничего не знает: при правке они остаются как были,
        // иначе сохранение формы молча сбрасывало бы выбранный в Details статус в «авто».
        val existing = if (isNew) null else localDataSource.getAnimeById(animeId)
        val anime = Anime(
            id = animeId,
            title = params.title,
            titleEn = params.titleEn,
            titleRu = params.titleRu,
            episodes = params.episodes,
            rating = params.rating,
            imageFileName = imageFileName,
            orderIndex = if (isNew) localDataSource.getMaxOrderIndex() + 1 else params.orderIndex,
            dateAdded = if (isNew) idGenerator.currentTimeMillis() else params.dateAdded,
            isFavorite = params.isFavorite,
            tags = params.selectedTags.toImmutableList(),
            categoryType = params.categoryType,
            // Тип записи решает, что откроется в Details — главы или серии. Раньше он тут не
            // проставлялся вовсе и всегда оставался ANIME по умолчанию Anime(), поэтому манга из
            // поиска попадала в коллекцию как аниме.
            mediaType = params.mediaType
                ?: MediaType.fromCategoryType(params.categoryType)
                ?: MediaType.ANIME,
            comment = params.comment,
            anilistId = params.anilistId,
            malId = params.malId,
            shikimoriId = params.shikimoriId,
            anilistNotFoundAt = params.anilistNotFoundAt,
            malNotFoundAt = params.malNotFoundAt,
            shikimoriNotFoundAt = params.shikimoriNotFoundAt,
            tmdbId = params.tmdbId,
            kinopoiskId = params.kinopoiskId,
            tmdbNotFoundAt = params.tmdbNotFoundAt,
            kinopoiskNotFoundAt = params.kinopoiskNotFoundAt,
            watchStatus = existing?.watchStatus,
            releaseYear = params.releaseYear ?: existing?.releaseYear,
        )

        if (isNew) {
            localDataSource.insertAnime(anime)
            // Мгновенный догон фонового обогащения: воркер подтянет недостающие поля и названия
            // новой записи (идемпотентно — KEEP).
            enrichmentCoordinator.enqueueImmediateScan()
        } else {
            localDataSource.updateAnime(anime)
        }
    }
}
