package com.example.myapplication.media.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.example.myapplication.media.source.SanitizeHeaders
import com.example.myapplication.media.source.VetroSubtitleTrack
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.source.forPlaybackCandidate
import okhttp3.OkHttpClient

/**
 * Composes one remote playback session: conservative buffering/ABR, transfer diagnostics and a
 * header-aware MediaSource. Local playback deliberately uses a different factory.
 */
object StreamingPlaybackSessionFactory {

    internal fun createSession(
        context: Context,
        client: OkHttpClient,
        video: VetroVideo,
        tuning: StreamingPlayerTuning = StreamingPlayerTuning.DEFAULT,
    ): StreamingPlaybackSession {
        val transferMonitor = StreamingTransferMonitor(SystemClock::elapsedRealtime)
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val mediaSourceFactory = createMediaSourceFactory(context, client, video, transferMonitor)
        val adaptiveFactory = StallAwareAdaptiveTrackSelectionFactory(
            tuning = tuning,
            transferMonitor = transferMonitor,
            nowMs = SystemClock::elapsedRealtime,
        )
        val trackSelector = DefaultTrackSelector(context, adaptiveFactory)
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(
                // Буфер по времени (60/90 с) — как был; потолок по памяти включается, только
                // если 90 с этого потока не влезают в кучу (см. AdaptiveBufferGuard).
                MemoryGuardedLoadControl(
                    delegate = DefaultLoadControl.Builder()
                        .setAllocator(allocator)
                        .setBufferDurationsMs(
                            tuning.minBufferMs,
                            tuning.maxBufferMs,
                            tuning.bufferForPlaybackMs,
                            tuning.bufferForPlaybackAfterRebufferMs,
                        )
                        .setPrioritizeTimeOverSizeThresholds(
                            tuning.prioritizeTimeOverSizeThresholds,
                        )
                        .build(),
                    allocator = allocator,
                    minBufferMs = tuning.minBufferMs,
                    maxBufferMs = tuning.maxBufferMs,
                    memory = MemoryGuardedLoadControl.memorySnapshot(context),
                    nowMs = SystemClock::elapsedRealtime,
                ),
            )
            .build()
        player.setMediaSource(buildMediaSource(mediaSourceFactory, video))
        player.addAnalyticsListener(
            StreamingPlaybackDiagnostics(
                video = video,
                transferMonitor = transferMonitor,
                allocator = allocator,
            ),
        )
        return StreamingPlaybackSession(player, transferMonitor)
    }

    private fun buildMediaSource(
        factory: DefaultMediaSourceFactory,
        video: VetroVideo,
    ): MediaSource {
        val primary = factory.createMediaSource(buildMediaItem(video))
        val externalAudio = video.audioTracks.map { track ->
            factory.createMediaSource(buildTrackMediaItem(track))
        }
        return if (externalAudio.isEmpty()) {
            primary
        } else {
            MergingMediaSource(primary, *externalAudio.toTypedArray())
        }
    }

    fun buildMediaItem(video: VetroVideo): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(video.url))
        when {
            video.url.contains(".m3u8", ignoreCase = true) ->
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            video.url.contains(".mpd", ignoreCase = true) ->
                builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        val subs = video.subtitles.map { it.toSubtitleConfig() }
        if (subs.isNotEmpty()) builder.setSubtitleConfigurations(subs)
        return builder.build()
    }

    /**
     * Источник так же, как у плеера (заголовки источника, OkHttp, file:// для локальных файлов), — для
     * фоновой работы со звуком того же видео (локальные субтитры).
     */
    internal fun mediaSourceFactory(context: Context, client: OkHttpClient, video: VetroVideo): MediaSource.Factory =
        createMediaSourceFactory(context, client, video, StreamingTransferMonitor(SystemClock::elapsedRealtime))

    private fun createMediaSourceFactory(
        context: Context,
        client: OkHttpClient,
        video: VetroVideo,
        transferMonitor: StreamingTransferMonitor,
    ): DefaultMediaSourceFactory {
        val headers = SanitizeHeaders.sanitize(video.headers)
        val okHttpFactory = OkHttpDataSource.Factory(client.forPlaybackCandidate(video))
            .setDefaultRequestProperties(headers)
            .setTransferListener(transferMonitor)
        // http(s) — через OkHttp с заголовками источника; file:// — субтитры, подгруженные
        // пользователем в кэш приложения (OkHttp такие адреса не открывает).
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(context, okHttpFactory))
    }

    private fun buildTrackMediaItem(track: VetroSubtitleTrack): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(Uri.parse(track.url))
            .setMediaId("external-audio:${track.lang}")
        when {
            track.url.contains(".m3u8", ignoreCase = true) ->
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            track.url.contains(".mpd", ignoreCase = true) ->
                builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return builder.build()
    }

    private fun VetroSubtitleTrack.toSubtitleConfig(): MediaItem.SubtitleConfiguration {
        val mime = when {
            mimeType.contains("ssa", ignoreCase = true) ||
                mimeType.contains("ass", ignoreCase = true) -> MimeTypes.TEXT_SSA
            mimeType.contains("vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
            // SRT, разобранный как VTT, не показывается вовсе: у SRT нет заголовка WEBVTT.
            mimeType.contains("subrip", ignoreCase = true) ||
                mimeType.contains("srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
            else -> MimeTypes.TEXT_VTT
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
            .setMimeType(mime)
            .setLanguage(lang)
            .setId(id)
            .setLabel(label)
            // Подгруженные пользователем включает сам выбор в меню, а не флаг «по умолчанию».
            .setSelectionFlags(if (isUserAdded) 0 else C.SELECTION_FLAG_DEFAULT)
            .build()
    }
}

internal data class StreamingPlayerTuning(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val prioritizeTimeOverSizeThresholds: Boolean,
    val minDurationForQualityIncreaseMs: Int,
    val maxDurationForQualityDecreaseMs: Int,
    val minDurationToRetainAfterDiscardMs: Int,
    val maxWidthToDiscard: Int,
    val maxHeightToDiscard: Int,
    val bandwidthFraction: Float,
) {
    companion object {
        val DEFAULT = StreamingPlayerTuning(
            minBufferMs = 60_000,
            maxBufferMs = 90_000,
            bufferForPlaybackMs = 2_000,
            bufferForPlaybackAfterRebufferMs = 6_000,
            prioritizeTimeOverSizeThresholds = true,
            minDurationForQualityIncreaseMs = 25_000,
            maxDurationForQualityDecreaseMs = 10_000,
            minDurationToRetainAfterDiscardMs = 25_000,
            maxWidthToDiscard = 0,
            maxHeightToDiscard = 0,
            bandwidthFraction = 0.60f,
        )
    }
}

internal class StreamingPlaybackSession(
    val player: ExoPlayer,
    internal val transferMonitor: StreamingTransferMonitor,
)
