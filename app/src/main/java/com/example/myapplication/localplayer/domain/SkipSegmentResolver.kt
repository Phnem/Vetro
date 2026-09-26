package com.example.myapplication.localplayer.domain

import com.example.myapplication.media.source.VetroSkipReference
import com.example.myapplication.media.source.VetroTimestamp
import kotlin.math.abs

data class SkipSegmentRequest(
    val anilistId: Int?,
    val malId: Int?,
    val episodeNumber: Int?,
    val durationMs: Long,
    val exactTimestamps: List<VetroTimestamp> = emptyList(),
    val exactOrigin: String? = null,
    val reference: VetroSkipReference? = null,
    /** Для IntroDB (кино и сериалы): канонический IMDb id, сезон и признак фильма. */
    val imdbId: String? = null,
    val seasonNumber: Int? = null,
    val isMovie: Boolean = false,
)

data class SkipSegmentResolution(
    val segments: List<SkipSegment>,
    val origin: String?,
    val referenceDurationMs: Long?,
)

/**
 * Single priority resolver for every player:
 * exact current-video timestamps → compatible episode reference → AniSkip (+ recap/preview from
 * Anime-Skip) → Anime-Skip / IntroDB when AniSkip has nothing.
 */
class SkipSegmentResolver internal constructor(
    private val aniSkip: AniSkipLookup,
    private val external: ExternalSkipLookup? = null,
) {

    suspend fun resolve(request: SkipSegmentRequest): SkipSegmentResolution {
        if (request.durationMs <= 0L) return SkipSegmentResolution(emptyList(), null, null)

        val exact = request.exactTimestamps.toSegments(request.durationMs)
        if (exact.isNotEmpty()) {
            return SkipSegmentResolution(
                segments = exact,
                origin = request.exactOrigin ?: EXACT_ORIGIN,
                referenceDurationMs = request.durationMs,
            )
        }

        val reference = request.reference
        if (reference != null) {
            val referenced = reference.segments
                .filter { timestamp ->
                    areSkipDurationsCompatible(
                        request.durationMs,
                        reference.referenceDurationMs,
                        timestamp.kind,
                        reference.origin,
                    )
                }
                .toSegments(request.durationMs)
            if (referenced.isNotEmpty()) {
                return SkipSegmentResolution(
                    segments = referenced,
                    origin = reference.origin,
                    referenceDurationMs = reference.referenceDurationMs,
                )
            }
        }

        val fallback = if (request.anilistId != null || request.malId != null) {
            aniSkip.fetch(
                request.anilistId,
                request.malId,
                request.episodeNumber,
                request.durationMs,
            )
        } else {
            null
        }
        if (fallback != null && fallback.segments.isNotEmpty()) {
            val extra = externalOrNull { it.supplement(request) }
                ?.segments
                ?.filter { candidate -> fallback.segments.none { it.overlaps(candidate) } }
                .orEmpty()
            return SkipSegmentResolution(
                segments = (fallback.segments + extra).sortedBy(SkipSegment::startMs),
                origin = if (extra.isEmpty()) ANISKIP_ORIGIN else "$ANISKIP_ORIGIN+${ExternalSkipMatching.ANIME_SKIP_ORIGIN}",
                referenceDurationMs = fallback.referenceDurationMs,
            )
        }
        val external = externalOrNull { it.fallback(request) }
        return SkipSegmentResolution(
            segments = external?.segments.orEmpty().sortedBy(SkipSegment::startMs),
            origin = external?.origin,
            referenceDurationMs = external?.referenceDurationMs ?: fallback?.referenceDurationMs,
        )
    }

    /** Внешние базы — дополнение: их отказ не должен ронять уже найденную разметку. */
    private suspend fun externalOrNull(block: suspend (ExternalSkipLookup) -> ExternalSkipSelection?): ExternalSkipSelection? {
        val lookup = external ?: return null
        return try {
            block(lookup)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun SkipSegment.overlaps(other: SkipSegment): Boolean =
        startMs < other.endMs && other.startMs < endMs

    private fun List<VetroTimestamp>.toSegments(durationMs: Long): List<SkipSegment> =
        mapNotNull { timestamp ->
            if (timestamp.startMs < 0L) return@mapNotNull null
            val start = timestamp.startMs
            val end = timestamp.endMs.coerceAtMost(durationMs)
            if (start >= end || start >= durationMs) return@mapNotNull null
            SkipSegment(start, end, timestamp.kind)
        }.sortedBy(SkipSegment::startMs)

    private companion object {
        const val EXACT_ORIGIN = "source"
        const val ANISKIP_ORIGIN = "AniSkip"
    }
}

internal fun areSkipDurationsCompatible(
    currentDurationMs: Long,
    referenceDurationMs: Long,
    kind: SkipKind? = null,
    origin: String? = null,
): Boolean {
    if (currentDurationMs <= 0L || referenceDurationMs <= 0L) return false
    val difference = abs(currentDurationMs - referenceDurationMs)
    val opening = kind == SkipKind.OPENING && origin.equals("jut.su", ignoreCase = true)
    val maxDifferenceMs = if (opening) MAX_OPENING_REFERENCE_DIFFERENCE_MS else MAX_REFERENCE_DIFFERENCE_MS
    val maxDifferenceRatio =
        if (opening) MAX_OPENING_REFERENCE_DIFFERENCE_RATIO else MAX_REFERENCE_DIFFERENCE_RATIO
    return difference <= maxDifferenceMs &&
        difference.toDouble() <= currentDurationMs.toDouble() * maxDifferenceRatio
}

private const val MAX_REFERENCE_DIFFERENCE_RATIO = 0.01
private const val MAX_REFERENCE_DIFFERENCE_MS = 15_000L
private const val MAX_OPENING_REFERENCE_DIFFERENCE_RATIO = 0.02
private const val MAX_OPENING_REFERENCE_DIFFERENCE_MS = 30_000L
