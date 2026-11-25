package com.example.myapplication.ui.addedit

import android.net.Uri
import com.example.myapplication.data.models.MediaType

enum class CommentMode { AddButton, Editing, Saved }

/**
 * Immutable UI state для AddEditScreen
 */
data class AddEditUiState(
    val animeId: String? = null,
    val title: String = "",
    /** Английское название (вариация). Пустая строка = не задано. */
    val titleEn: String = "",
    /** Показывать ли поле английского названия (кнопка «добавить вариацию» / уже есть перевод). */
    val showTitleEn: Boolean = false,
    /** Поля, которые не редактируются в UI, но должны пережить сохранение (иначе затрутся). */
    val titleRu: String? = null,
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
    val episodes: String = "",
    /** 10-балльная шкала с шагом 0.1 (0 = не оценено). */
    val rating: Float = 0f,
    val selectedTags: List<String> = emptyList(),
    /** Категория ЖАНРОВ (ANIME/MOVIE/SERIES) — не тип записи, см. [mediaType]. */
    val categoryType: String = "",
    /** Тип записи: в UI не редактируется, но должен пережить сохранение (иначе манга станет аниме). */
    val mediaType: MediaType = MediaType.ANIME,
    val imageUri: Uri? = null,
    val currentImageFileName: String? = null,
    val imageFilePath: String? = null,
    val orderIndex: Int = 0,
    val dateAdded: Long = 0L,
    val isFavorite: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val comment: String = "",
    val commentMode: CommentMode = CommentMode.AddButton,
    /** Набранный, но ещё не свёрнутый комментарий; null — поле не редактируют. */
    val commentDraft: String? = null,
) {
    /**
     * Проверка наличия изменений
     */
    val hasChanges: Boolean
        get() = if (animeId == null) {
            title.isNotBlank()
        } else {
            // Для редактирования проверяем изменения относительно оригинала
            true // Упрощенно, в реальности сравниваем с оригиналом
        }
    
    /**
     * Валидация формы. Пустое episodes трактуется как 0.
     */
    val isValid: Boolean
        get() = title.isNotBlank() &&
                (episodes.isBlank() || (episodes.toIntOrNull()?.let { it >= 0 } ?: false))
}
