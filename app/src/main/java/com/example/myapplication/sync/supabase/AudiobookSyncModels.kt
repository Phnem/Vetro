package com.example.myapplication.sync.supabase

import kotlinx.serialization.Serializable

// Строки таблиц аудиокниг в Supabase (миграция 20261004010000_audiobooks.sql). Поля обязаны
// 1-в-1 совпадать с колонками: лишнее поле валит upsert («Could not find the column …»).
// Значений по умолчанию нет намеренно — клиент Supabase их не кодирует, и колонка ушла бы NULL.

/** Книга с озвучками и ссылками на источники; только данные — аудио в облако не попадает. */
@Serializable
data class AudiobookWorkDto(
    val user_id: String,
    val work_id: String,
    val cluster_fingerprint: String,
    val title: String,
    val title_original: String?,
    val authors: List<String>,
    val series_title: String?,
    val series_index: Double?,
    val description: String?,
    val genres: List<String>,
    val language: String,
    val year: Int?,
    val is_collection: Boolean,
    val cover_url: String?,
    val selected_narration_id: String?,
    val is_favorite: Boolean,
    val in_library: Boolean,
    val narrations: List<AudiobookNarrationDto>,
    val updated_at: String,
)

@Serializable
data class AudiobookNarrationDto(
    val narration_id: String,
    val narrators: List<String>,
    val kind: String,
    val duration_ms: Long?,
    val chapter_count: Long?,
    val variants: List<AudiobookVariantDto>,
)

/** Где эту озвучку слушать: источник и ключ книги у него. Локальные папки сюда не попадают. */
@Serializable
data class AudiobookVariantDto(
    val variant_id: String,
    val source_id: String,
    val source_url: String,
    val duration_ms: Long?,
    val chapter_count: Long?,
    val infrastructure: String?,
    val user_pinned: Boolean,
)

/** Позиция прослушивания озвучки (`audiobook_progress`). */
@Serializable
data class AudiobookProgressDto(
    val user_id: String,
    val narration_id: String,
    val work_id: String,
    val variant_id: String?,
    val global_ms: Long,
    val chapter_idx: Int,
    val chapter_offset_ms: Long,
    val total_ms: Long?,
    val speed: Float,
    val finished: Boolean,
    val updated_at: String,
)

/** Все закладки озвучки одним набором (`audiobook_bookmarks`): удаление — новый набор без неё. */
@Serializable
data class AudiobookBookmarksDto(
    val user_id: String,
    val narration_id: String,
    val work_id: String,
    val bookmarks: List<AudiobookBookmarkDto>,
    val updated_at: String,
)

@Serializable
data class AudiobookBookmarkDto(
    val id: String,
    val global_ms: Long,
    val chapter_idx: Int,
    val chapter_offset_ms: Long,
    val note: String?,
    val created_at: Long,
)
