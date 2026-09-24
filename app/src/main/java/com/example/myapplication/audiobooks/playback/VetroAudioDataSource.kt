package com.example.myapplication.audiobooks.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import java.io.IOException
import kotlinx.coroutines.runBlocking

/** Replaces a stable vetro-audio track URI with a fresh provider URL at each open(). */
@UnstableApi
object VetroAudioDataSource {
    fun factory(context: Context, resolver: ManifestResolver): DataSource.Factory =
        factory(DefaultDataSource.Factory(context), resolver)

    fun factory(upstream: DataSource.Factory, resolver: ManifestResolver): DataSource.Factory =
        ResolvingDataSource.Factory(upstream) { spec: DataSpec ->
            if (spec.uri.scheme != "vetro-audio") return@Factory spec
            val ref = TrackUriCodec.decode(spec.uri.toString())
                ?: throw IOException("Invalid audiobook track URI")
            val track = try {
                runBlocking { resolver.resolve(ref.variant, ref.trackIndex) }
            } catch (cause: Exception) {
                throw IOException("Audiobook track cannot be resolved", cause)
            }
            spec.buildUpon()
                .setUri(track.url)
                .setHttpRequestHeaders(spec.httpRequestHeaders + track.headers)
                .build()
        }
}

