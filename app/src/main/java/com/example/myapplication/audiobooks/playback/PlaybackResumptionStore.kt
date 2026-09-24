package com.example.myapplication.audiobooks.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import org.json.JSONArray
import org.json.JSONObject

/** Temporary local-only snapshot until audiobook_progress is introduced in AB-13. */
class PlaybackResumptionStore(context: Context) {
    private val prefs = context.getSharedPreferences("audiobook_playback_resumption", Context.MODE_PRIVATE)

    fun save(player: Player) {
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        if (!supported(uri)) return
        val queue = JSONArray()
        for (index in 0 until player.mediaItemCount) {
            val queued = player.getMediaItemAt(index)
            val queuedUri = queued.localConfiguration?.uri ?: return
            if (!supported(queuedUri)) return
            queue.put(JSONObject().apply {
                put("uri", queuedUri.toString())
                put("id", queued.mediaId)
                put("title", queued.mediaMetadata.title?.toString())
                put("album", queued.mediaMetadata.albumTitle?.toString())
                put("artist", queued.mediaMetadata.artist?.toString())
                put("narrator", queued.mediaMetadata.albumArtist?.toString())
                put("artwork", queued.mediaMetadata.artworkUri?.toString())
                put("mime", queued.localConfiguration?.mimeType)
            })
        }
        prefs.edit()
            .putString(KEY_QUEUE, queue.toString())
            .putInt(KEY_INDEX, player.currentMediaItemIndex)
            .putString(KEY_URI, uri.toString())
            .putString(KEY_ID, item.mediaId)
            .putString(KEY_TITLE, item.mediaMetadata.title?.toString())
            .putString(KEY_ALBUM, item.mediaMetadata.albumTitle?.toString())
            .putString(KEY_ARTIST, item.mediaMetadata.artist?.toString())
            .putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0L))
            .apply()
    }

    fun load(): Snapshot? {
        prefs.getString(KEY_QUEUE, null)?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                val items = (0 until array.length()).map { index ->
                    val entry = array.getJSONObject(index)
                    val uri = Uri.parse(entry.getString("uri"))
                    require(supported(uri))
                    val metadata = MediaMetadata.Builder()
                        .setTitle(entry.optString("title"))
                        .setAlbumTitle(entry.optString("album"))
                        .setArtist(entry.optString("artist"))
                        .setAlbumArtist(entry.optString("narrator"))
                        .apply { entry.optString("artwork").takeIf(String::isNotBlank)?.let { setArtworkUri(Uri.parse(it)) } }
                        .build()
                    MediaItem.Builder()
                        .setMediaId(entry.getString("id"))
                        .setUri(uri)
                        .setMediaMetadata(metadata)
                        .apply { entry.optString("mime").takeIf(String::isNotBlank)?.let(::setMimeType) }
                        .build()
                }
                val index = prefs.getInt(KEY_INDEX, 0)
                require(items.isNotEmpty() && index in items.indices)
                Snapshot(items, index, prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L))
            }.getOrNull()?.let { return it }
        }
        val uri = prefs.getString(KEY_URI, null)?.let(Uri::parse) ?: return null
        if (!supported(uri)) return null
        val metadata = MediaMetadata.Builder()
            .setTitle(prefs.getString(KEY_TITLE, null))
            .setAlbumTitle(prefs.getString(KEY_ALBUM, null))
            .setArtist(prefs.getString(KEY_ARTIST, null))
            .build()
        val item = MediaItem.Builder()
            .setMediaId(prefs.getString(KEY_ID, null).orEmpty())
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
        return Snapshot(listOf(item), 0, prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L))
    }

    data class Snapshot(val items: List<MediaItem>, val mediaIndex: Int, val positionMs: Long) {
        val item: MediaItem get() = items[mediaIndex]
    }

    private fun supported(uri: Uri): Boolean = uri.scheme in setOf("content", "file") ||
        TrackUriCodec.decode(uri.toString()) != null

    private companion object {
        const val KEY_URI = "uri"
        const val KEY_QUEUE = "queue"
        const val KEY_INDEX = "index"
        const val KEY_ID = "media_id"
        const val KEY_TITLE = "title"
        const val KEY_ALBUM = "album"
        const val KEY_ARTIST = "artist"
        const val KEY_POSITION = "position_ms"
    }
}
