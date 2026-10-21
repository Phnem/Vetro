package com.example.myapplication.sync.supabase

import kotlinx.serialization.Serializable

/**
 * Строка таблицы `episode_progress` — позиция воспроизведения одной серии.
 *
 * Поля обязаны 1-в-1 совпадать со схемой Supabase: лишнее поле в upsert валит запрос целиком
 * («Could not find the column …») — тот же грабль, что был с `anime_tags`.
 */
@Serializable
data class EpisodeProgressDto(
    val user_id: String,
    val anime_id: String,
    val season: Int,
    val episode: Int,
    val position_ms: Long,
    val duration_ms: Long,
    val updated_at: String,
)

/**
 * Строка таблицы `episode_ratings` — личная оценка одной серии.
 *
 * [rating] — как и у тайтла, целое ×10 (0..100); 0 — оценка снята (строку не удаляем, чтобы
 * снятие доехало до других устройств).
 */
@Serializable
data class EpisodeRatingDto(
    val user_id: String,
    val anime_id: String,
    val season: Int,
    val episode: Int,
    val rating: Int,
    val updated_at: String,
)

/** Строка таблицы `manga_progress` — прогресс чтения одной главы. */
@Serializable
data class MangaProgressDto(
    val user_id: String,
    val anime_id: String,
    val chapter_key: String,
    val page_index: Int,
    val page_count: Int,
    val is_read: Boolean,
    val scroll_offset_fraction: Float? = null,
    val updated_at: String,
)
