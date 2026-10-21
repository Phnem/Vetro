package com.example.myapplication.media.source.vftmodule

import com.example.myapplication.data.models.MediaType
import kotlinx.serialization.Serializable

/**
 * Описание найденной торрент-раздачи.
 */
@Serializable
data class VftTorrentItem(
    val title: String,
    val magnetUrl: String,
    val torrentUrl: String? = null,
    val infoHash: String,
    val sizeBytes: Long = 0L,
    val seeders: Int = 0,
    val leechers: Int = 0,
    val tracker: String,
    val parsedInfo: VftReleaseInfo = VftReleaseInfo(rawTitle = title),
)

/**
 * Разобранные метаданные названия релиза (качество, озвучка, сезон/серия, оригинал).
 */
@Serializable
data class VftReleaseInfo(
    val rawTitle: String,
    val titleRu: String? = null,
    val titleEn: String? = null,
    val year: Int? = null,
    val seasons: List<Int> = emptyList(),
    val episodes: List<Int> = emptyList(),
    val quality: String? = null,
    val audioTracks: List<String> = emptyList(),
    /** Содержит ли раздача оригинальную / английскую дорожку (часто на Rutor/Rutracker есть оригинал). */
    val hasOriginalAudio: Boolean = false,
    /** Содержит ли русскую озвучку или дубляж. */
    val hasRussianAudio: Boolean = false,
    val codec: String? = null,
    val ripType: String? = null,
)

/**
 * Параметры запроса на поиск торрентов для фильма, сериала или аниме.
 */
data class VftSearchQuery(
    val query: String,
    val titleRu: String? = null,
    val titleEn: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val mediaType: MediaType = MediaType.MOVIE,
    val imdbId: String? = null,
    val kinopoiskId: Int? = null,
    val preferOriginalAudio: Boolean = false,
)

/**
 * Отдельный видеофайл внутри торрент-раздачи.
 */
data class VftTorrentFile(
    val index: Int,
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val isVideo: Boolean,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * Текущее состояние скачивания торрента через TorrentEngine.
 */
enum class VftDownloadState {
    QUEUED,
    DOWNLOADING_METADATA,
    CHECKING,
    DOWNLOADING,
    FINISHED,
    SEEDING,
    PAUSED,
    ERROR,
}

/**
 * Статистика скачивания раздачи.
 */
data class VftDownloadStatus(
    val infoHash: String,
    val name: String,
    val state: VftDownloadState,
    val progress: Float,
    val downloadRate: Long,
    val uploadRate: Long,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val seeders: Int,
    val peers: Int,
    val isComplete: Boolean,
    val savePath: String,
)
