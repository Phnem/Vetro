package com.example.myapplication.audiobooks.domain.timeline

import com.example.myapplication.audiobooks.domain.model.AudioTrack
import com.example.myapplication.audiobooks.domain.model.Chapter
import kotlin.math.ceil

/** Maps track offsets and chapter markers onto one book clock without inventing unknown lengths. */
class BookTimeline(
    tracks: List<AudioTrack>,
    chapters: List<Chapter>,
    estimatedBitrateKbps: Int? = null,
) {
    val tracks: List<AudioTrack> = tracks.toList()
    val chapters: List<Chapter> = chapters.sortedBy { it.startMs }
    private val durations: List<Long?>

    init {
        require(this.tracks.isNotEmpty()) { "A book needs at least one track" }
        require(this.tracks.indices.all { this.tracks[it].index == it }) { "Track indices must be contiguous and zero-based" }
        require(this.chapters.all { it.startMs >= 0 && (it.durationMs == null || it.durationMs >= 0) })
        require(this.chapters.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
        durations = this.tracks.map { track ->
            track.durationMs?.also { require(it > 0) }
                ?: estimateDuration(track.sizeBytes, estimatedBitrateKbps)
        }
    }

    /** Null means at least one track has no usable duration or estimate. */
    val totalMs: Long? = if (durations.any { it == null }) null else durations.filterNotNull().sum()
    val hasEstimates: Boolean = tracks.indices.any { tracks[it].durationMs == null && durations[it] != null }

    fun trackDurationMs(trackIndex: Int): Long? = durations[trackIndex]

    /** Null if an earlier track still has unknown length. */
    fun toGlobal(trackIndex: Int, offsetMs: Long): Long? {
        require(trackIndex in tracks.indices)
        require(offsetMs >= 0)
        val preceding = durations.take(trackIndex)
        if (preceding.any { it == null }) return null
        val prefix = preceding.filterNotNull().sum()
        return prefix + offsetMs.coerceAtMost(durations[trackIndex] ?: Long.MAX_VALUE)
    }

    /** At a track boundary, returns the following track at offset zero. */
    fun toTrack(globalMs: Long): Pair<Int, Long>? {
        require(globalMs >= 0)
        var start = 0L
        for (index in tracks.indices) {
            val duration = durations[index] ?: return if (globalMs == start) index to 0L else null
            val end = start + duration
            if (globalMs < end || index == tracks.lastIndex) {
                return index to (globalMs - start).coerceIn(0L, duration)
            }
            start = end
        }
        return null
    }

    fun chapterAt(globalMs: Long): Chapter? {
        require(globalMs >= 0)
        return chapters.lastOrNull { it.startMs <= globalMs }
    }

    fun progress(globalMs: Long): Float? {
        val total = totalMs ?: return null
        if (total == 0L) return null
        return (globalMs.coerceIn(0L, total).toDouble() / total).toFloat()
    }

    fun remainingMs(globalMs: Long, speed: Float): Long? {
        require(speed.isFinite() && speed > 0f)
        val total = totalMs ?: return null
        return ceil((total - globalMs.coerceIn(0L, total)).toDouble() / speed).toLong()
    }

    private fun estimateDuration(sizeBytes: Long?, bitrateKbps: Int?): Long? {
        if (sizeBytes == null || sizeBytes <= 0 || bitrateKbps == null || bitrateKbps <= 0) return null
        return (sizeBytes.coerceAtMost(Long.MAX_VALUE / 8) * 8 / bitrateKbps).coerceAtLeast(1L)
    }
}
