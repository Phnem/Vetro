package com.example.myapplication.audiobooks.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.myapplication.audiobooks.domain.source.ManifestResolver

/** A speech-only player owned by AudiobookPlaybackService. */
object AudiobookPlayerFactory {
    fun create(context: Context, manifestResolver: ManifestResolver): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(VetroAudioDataSource.factory(context, manifestResolver)),
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
