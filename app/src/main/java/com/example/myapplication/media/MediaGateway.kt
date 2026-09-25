package com.example.myapplication.media

import com.example.myapplication.data.models.Anime
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.media.source.VetroHoster
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.source.PlaybackResolution
import com.example.myapplication.media.source.isSafeForBackgroundPersistence
import com.example.myapplication.media.download.DownloadQuality
import java.io.File

@JvmInline
value class JobId(val value: String)

/** Streams from a resolution, or none. Keeps the reason available to callers that want it. */
val PlaybackResolution.hostersOrEmptyPublic: List<VetroHoster>
    get() = (this as? PlaybackResolution.Found)?.hosters.orEmpty()

/**
 * Single facade for resolve → play | download. UI depends only on this interface.
 */
interface MediaGateway {
    /**
     * The primary resolution seam: it keeps *why* there is nothing to play.
     *
     * This used to be a default implementation built on [resolveHosters], which meant an empty list
     * was re-labelled `NoMatch` and the difference between "no source is configured", "every source
     * failed" and "nobody carries this title" was destroyed before any caller could see it. The
     * source picker needs that difference to tell the user something true.
     */
    suspend fun resolvePlayback(
        anime: Anime,
        episodeNumber: Int,
        seasonInfo: SeasonInfo? = null,
    ): PlaybackResolution

    /** Convenience for callers that only need the streams and have already handled the reason. */
    suspend fun resolveHosters(
        anime: Anime,
        episodeNumber: Int,
        seasonInfo: SeasonInfo? = null,
    ): List<VetroHoster> = resolvePlayback(anime, episodeNumber, seasonInfo).hostersOrEmptyPublic

    suspend fun resolveBestVideo(hosters: List<VetroHoster>): VetroVideo?

    suspend fun enqueueDownload(
        video: VetroVideo,
        fallbackVideos: List<VetroVideo> = emptyList(),
        quality: DownloadQuality,
        outDir: File,
        animeId: String = "",
        episodeNumber: Int = 0,
        durationSec: Int? = null,
    ): JobId
}

/** Можно ли отдать поток фоновой загрузке: источник разрешает и в URL нет секретов. */
internal fun VetroVideo.isDownloadable(): Boolean = downloadAllowed && isSafeForBackgroundPersistence()

/**
 * Запасные потоки, которые влезают во вход задачи WorkManager: у него потолок 10 КБ на весь
 * `Data`, а у Kodik на серию два десятка потоков с заголовками — задача не ставилась вовсе
 * («Data cannot occupy more than 10240 bytes»). Список уже ранжирован, отбрасываем хвост.
 */
internal fun fitCandidatesToBudget(
    candidates: List<VetroVideo>,
    budgetBytes: Int,
    encode: (List<VetroVideo>) -> String,
): List<VetroVideo> {
    var fitted = candidates
    while (fitted.size > 1 && encode(fitted).toByteArray(Charsets.UTF_8).size > budgetBytes) {
        fitted = fitted.dropLast(1)
    }
    return fitted
}

internal fun downloadableCandidates(
    video: VetroVideo,
    fallbackVideos: List<VetroVideo>,
): List<VetroVideo> {
    require(video.downloadAllowed) { "Источник разрешает только просмотр / Source is stream-only" }
    require(video.isSafeForBackgroundPersistence()) {
        "Секретный URL нельзя передать фоновой загрузке / Sensitive stream cannot be persisted"
    }
    return (listOf(video) + fallbackVideos)
        .filter { it.isDownloadable() }
        .distinctBy(VetroVideo::url)
}
