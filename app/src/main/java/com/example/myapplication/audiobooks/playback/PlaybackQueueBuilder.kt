package com.example.myapplication.audiobooks.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.audiobooks.domain.model.WorkId
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline

/** Builds one item per file; chapters within M4B remain markers on the same item. */
object PlaybackQueueBuilder {
    fun build(
        manifest: MediaManifest,
        workId: WorkId,
        narrationId: NarrationId,
        workTitle: String,
        author: String,
        narrator: String,
        artworkUri: String? = null,
        estimatedBitrateKbps: Int? = null,
    ): List<MediaItem> {
        val timeline = BookTimeline(manifest.tracks, manifest.chapters, estimatedBitrateKbps)
        return manifest.tracks.map { track ->
            val chapter = timeline.toGlobal(track.index, 0L)?.let(timeline::chapterAt)
            val extras = Bundle().apply {
                putString("workId", workId.value)
                putString("narrationId", narrationId.value)
                putInt("chapterIndex", chapter?.index ?: -1)
                putString(AudiobookMediaText.AUTHOR, author)
            }
            val chapterTitle = chapter?.title ?: "Part ${track.index + 1}"
            // Строки системной карточки — см. AudiobookMediaText.
            val metadata = MediaMetadata.Builder()
                .setTitle(chapterTitle)
                .setDisplayTitle(workTitle.ifBlank { chapterTitle })
                .setAlbumTitle(workTitle)
                .setArtist(AudiobookMediaText.systemLine(chapterTitle, author, narrator))
                .setAlbumArtist(narrator)
                .setExtras(extras)
                .apply { artworkUri?.let { setArtworkUri(Uri.parse(it)) } }
                .build()
            MediaItem.Builder()
                .setMediaId("${narrationId.value}#${track.index}")
                .setUri(TrackUriCodec.encode(manifest.variant, track.index))
                .setMediaMetadata(metadata)
                .apply { track.mimeType?.let(::setMimeType) }
                .build()
        }
    }
}
