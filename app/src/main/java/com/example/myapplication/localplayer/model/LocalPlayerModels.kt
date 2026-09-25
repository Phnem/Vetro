package com.example.myapplication.localplayer.model

import kotlinx.serialization.Serializable

/**
 * Один локальный видеофайл-эпизод для плеера скачанных серий.
 *
 * [documentUri] — URI файла (`file://` скачанной серии). [episodeNumber] `null` = номер неизвестен
 * (файл всё равно играется, просто в конце списка).
 */
@Serializable
data class LocalEpisode(
    val documentUri: String,
    val originalName: String,
    val displayName: String,
    val episodeNumber: Int? = null,
    /** Сезон из имени файла/метаданных (S02E05 и т.п.). null = не определён. */
    val season: Int? = null,
    val sizeBytes: Long = 0L,
)
