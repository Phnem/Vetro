package com.example.myapplication.media.download

import com.example.myapplication.network.AppStoreJson
import android.util.Log
import com.example.myapplication.media.source.VetroSkipReference
import com.example.myapplication.media.source.VetroTimestamp
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Opening/ending timings for one downloaded episode, in the shape the players already consume
 * (exact timestamps first, episode reference second — see `SkipSegmentResolver`).
 */
@Serializable
data class DownloadedEpisodeSkip(
    /** Timings measured on this very episode, e.g. AniLiberty's per-episode opening/ending. */
    val timestamps: List<VetroTimestamp> = emptyList(),
    /** Who measured [timestamps] — surfaced in the autoskip diagnostics line. */
    val origin: String? = null,
    /** Timings from another release of the same episode, applied only if durations match. */
    val reference: VetroSkipReference? = null,
) {
    val isEmpty: Boolean get() = timestamps.isEmpty() && reference == null
}

/**
 * Skip timings written next to the episode file (`E019.mp4` → `E019.skip.json`).
 *
 * Resolving them costs a jut.su/AniLiberty roundtrip that only the online path can pay. The
 * download already resolves them — it is the same `SourceEngine` call — so they are stored with
 * the file and autoskip keeps working offline, without the AniSkip request the streaming player
 * falls back to.
 *
 * A sidecar rather than a database row: downloads live purely as files, and a deleted episode must
 * not leave a schema row behind.
 */
object DownloadedSkipStore {

    private const val TAG = "DownloadedSkip"
    private const val SUFFIX = ".skip.json"
    private val json = AppStoreJson

    fun sidecarFor(episodeFile: File): File =
        File(episodeFile.parentFile, episodeFile.nameWithoutExtension + SUFFIX)

    fun save(episodeFile: File, skip: DownloadedEpisodeSkip) {
        val sidecar = sidecarFor(episodeFile)
        if (skip.isEmpty) {
            delete(episodeFile)
            return
        }
        runCatching {
            sidecar.writeText(json.encodeToString(DownloadedEpisodeSkip.serializer(), skip))
        }.onFailure { Log.w(TAG, "Could not write ${sidecar.name}: ${it.message}") }
    }

    fun delete(episodeFile: File) {
        runCatching { sidecarFor(episodeFile).delete() }
    }

    fun load(episodeFile: File): DownloadedEpisodeSkip? {
        val sidecar = sidecarFor(episodeFile)
        if (!sidecar.isFile) return null
        return runCatching {
            json.decodeFromString(DownloadedEpisodeSkip.serializer(), sidecar.readText())
        }.onFailure { Log.w(TAG, "Could not read ${sidecar.name}: ${it.message}") }
            .getOrNull()
            ?.takeUnless { it.isEmpty }
    }

    /**
     * Same lookup for a player that only knows the media id. SAF `content://` episodes come from a
     * user folder we never wrote to, so they have no sidecar and resolve to `null`.
     */
    fun loadFor(mediaUri: String): DownloadedEpisodeSkip? {
        val file = when {
            mediaUri.startsWith("file:") -> runCatching { File(java.net.URI(mediaUri)) }.getOrNull()
            mediaUri.startsWith("/") -> File(mediaUri)
            else -> null
        } ?: return null
        return load(file)
    }
}
