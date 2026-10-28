package com.example.myapplication.audiobooks.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.torrent.TorrentEngine

/** A speech-only player owned by AudiobookPlaybackService. */
object AudiobookPlayerFactory {
    /** [silence] — цепочка с уровнями пропуска тишины (см. [LeveledSilenceChain]); держит её сервис. */
    fun create(
        context: Context,
        manifestResolver: ManifestResolver,
        torrents: TorrentEngine,
        silence: LeveledSilenceChain,
        feedWatchdog: AudioFeedWatchdog,
    ): ExoPlayer = ExoPlayer.Builder(context, SilenceAwareRenderers(context, silence, feedWatchdog))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(VetroAudioDataSource.factory(context, manifestResolver, torrents)),
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(15_000L)
        .setSeekForwardIncrementMs(30_000L)
        .build()
        .apply { skipSilenceEnabled = false }
}

/** Аудиовыход с нашей цепочкой обработки: уровни пропуска тишины вместо одного встроенного. */
@androidx.media3.common.util.UnstableApi
private class SilenceAwareRenderers(
    context: Context,
    private val silence: LeveledSilenceChain,
    private val feedWatchdog: AudioFeedWatchdog,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        MonitoredAudioSink(
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(silence)
                .setAudioTrackBufferSizeProvider(
                    DefaultAudioTrackBufferSizeProvider.Builder()
                        .setTargetPcmBufferDurationUs(PCM_BUFFER_US)
                        .build(),
                )
                .build(),
            feedWatchdog,
        )

    private companion object {
        /**
         * По умолчанию 0,5 с: по Bluetooth (A2DP) малейшая задержка подачи опустошает такой буфер, микшер
         * выбрасывает трек из активных, и звук пропадает. Книге запас в пару секунд ничего не стоит.
         */
        const val PCM_BUFFER_US = 2_000_000
    }
}
