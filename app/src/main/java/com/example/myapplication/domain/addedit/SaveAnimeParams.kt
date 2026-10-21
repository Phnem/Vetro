package com.example.myapplication.domain.addedit

import com.example.myapplication.data.models.MediaType

/**
 * Параметры сохранения аниме. Domain-слой не зависит от UI (AddEditUiState).
 */
data class SaveAnimeParams(
    val animeId: String?,
    val title: String,
    /** Английское название (вариация). null = нет. */
    val titleEn: String? = null,
    /** Русское название (обратное обогащение). Пробрасывается, чтобы редактирование его не затирало. */
    val titleRu: String? = null,
    val episodes: Int,
    /** 10-балльная шкала, одна цифра после запятой (0 = не оценено). */
    val rating: Float,
    val imageUri: String?,
    val currentImageFileName: String?,
    val orderIndex: Int,
    val dateAdded: Long,
    val isFavorite: Boolean,
    val selectedTags: List<String>,
    val categoryType: String,
    /**
     * Тип записи. Пробрасывается явно, чтобы редактирование его не затирало: в AddEdit
     * `categoryType` — это категория ЖАНРОВ (ANIME/MOVIE/SERIES, «MANGA» там не бывает), и вывод
     * типа из неё превратил бы мангу в аниме на первом же сохранении.
     *
     * null = вызывающий типа не знает; тогда он выводится из [categoryType]
     * ([MediaType.fromCategoryType]), а если и оттуда не выводится — [MediaType.ANIME].
     */
    val mediaType: MediaType? = null,
    val comment: String,
    val anilistId: Int? = null,
    val malId: Int? = null,
    val shikimoriId: Int? = null,
    val anilistNotFoundAt: Long? = null,
    val malNotFoundAt: Long? = null,
    val shikimoriNotFoundAt: Long? = null,
    val tmdbId: Int? = null,
    val kinopoiskId: Int? = null,
    val tmdbNotFoundAt: Long? = null,
    val kinopoiskNotFoundAt: Long? = null,
    /** Год релиза из результата поиска; null при правке — тогда сохраняется уже записанный год. */
    val releaseYear: Int? = null,
)
