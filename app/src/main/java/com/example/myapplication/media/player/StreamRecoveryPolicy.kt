package com.example.myapplication.media.player

import androidx.media3.datasource.HttpDataSource
import com.example.myapplication.media.source.VetroVideo
import kotlin.math.abs

internal enum class StreamRecoveryAction {
    RETRY_CURRENT,
    SWITCH_CANDIDATE,
    RERESOLVE,
}

internal enum class StreamRecoveryTrigger {
    WATCHDOG,
    PLAYER_ERROR,
}

internal data class StreamRecoveryInput(
    val trigger: StreamRecoveryTrigger,
    val bufferedDurationMs: Long,
    val retryAlreadyUsed: Boolean,
    val httpStatus: Int?,
    val hasFallback: Boolean,
)

/** Pure policy. The Activity only performs the selected action and preserves playback position. */
internal class StreamRecoveryPolicy(
    private val minimumRetryBufferMs: Long = 8_000L,
) {
    fun decide(input: StreamRecoveryInput): StreamRecoveryAction = with(input) {
        if (trigger == StreamRecoveryTrigger.WATCHDOG) return fallbackAction(hasFallback)
        when (httpStatus) {
            403 -> StreamRecoveryAction.RERESOLVE
            404, 410 -> fallbackAction(hasFallback)
            429, 503 -> if (bufferedDurationMs >= minimumRetryBufferMs && !retryAlreadyUsed) {
                StreamRecoveryAction.RETRY_CURRENT
            } else {
                fallbackAction(hasFallback)
            }
            else -> if (bufferedDurationMs >= minimumRetryBufferMs && !retryAlreadyUsed) {
                StreamRecoveryAction.RETRY_CURRENT
            } else {
                fallbackAction(hasFallback)
            }
        }
    }

    private fun fallbackAction(hasFallback: Boolean): StreamRecoveryAction = if (hasFallback) {
        StreamRecoveryAction.SWITCH_CANDIDATE
    } else {
        StreamRecoveryAction.RERESOLVE
    }
}

/** Monotonic one-shot gate for a continuous BUFFERING interval. */
internal class ContinuousBufferingWatchdog(
    private val thresholdMs: Long = 8_000L,
) {
    private var bufferingSinceMs: Long? = null
    private var recoveryIssued = false

    fun sample(nowMs: Long, buffering: Boolean): Boolean {
        if (!buffering) {
            bufferingSinceMs = null
            recoveryIssued = false
            return false
        }
        val startedAt = bufferingSinceMs ?: nowMs.also { bufferingSinceMs = it }
        if (recoveryIssued || nowMs - startedAt < thresholdMs) return false
        recoveryIssued = true
        return true
    }
}

/**
 * Recovery order: the closest dub first ([dubSimilarity] — a source dying mid-episode must not
 * silently swap the voice actors), then another host without a quality increase, lower quality on
 * the same host, then another host at any remaining quality. Same-host equal/higher URLs are
 * deliberately omitted.
 */
internal fun rankRecoveryCandidates(
    current: VetroVideo,
    candidates: List<VetroVideo>,
    failedUrls: Set<String>,
): List<VetroVideo> {
    val currentHost = safeMediaHost(current.url)
    val currentResolution = current.resolution?.takeIf { it > 0 }
    return candidates
        .asSequence()
        .filter { it.url != current.url && it.url !in failedUrls }
        .distinctBy { it.url }
        .mapNotNull { candidate ->
            val candidateHost = safeMediaHost(candidate.url)
            val candidateResolution = candidate.resolution?.takeIf { it > 0 }
            val differentHost = candidateHost != currentHost
            val category = when {
                differentHost &&
                    currentResolution != null &&
                    candidateResolution != null &&
                    candidateResolution <= currentResolution -> 0
                !differentHost &&
                    currentResolution != null &&
                    candidateResolution != null &&
                    candidateResolution < currentResolution -> 1
                differentHost -> 2
                else -> return@mapNotNull null
            }
            RankedRecoveryCandidate(
                video = candidate,
                category = category,
                qualityDistance = if (currentResolution != null && candidateResolution != null) {
                    abs(currentResolution - candidateResolution)
                } else {
                    Int.MAX_VALUE
                },
            )
        }
        .sortedWith(
            compareByDescending<RankedRecoveryCandidate> { dubSimilarity(current.sourceName, it.video.sourceName) }
                .thenBy { it.category }
                .thenBy { it.qualityDistance },
        )
        .map { it.video }
        .toList()
}

internal fun playbackHttpStatus(error: Throwable): Int? {
    var current: Throwable? = error
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
        current = current.cause
    }
    return null
}

internal fun selectResumePosition(
    pendingRecoveryPositionMs: Long?,
    savedPositionMs: Long?,
): Long? = pendingRecoveryPositionMs ?: savedPositionMs

/**
 * Серия сменилась — выбирает вариант резолва под ранее выбранную пользователем студию озвучки,
 * а не первый попавшийся: без этого «озвучка» сбрасывалась бы на дефолт резолвера каждую серию.
 * Студии нет среди новых кандидатов → тот же дефолт, что и раньше (первый по рангу резолвера).
 */
internal fun selectPreferredVideo(
    resolved: List<VetroVideo>,
    preferredSourceName: String?,
): VetroVideo? =
    preferredSourceName?.let { name -> resolved.firstOrNull { it.sourceName == name } }
        ?: resolved.firstOrNull()

/**
 * Насколько озвучка кандидата похожа на текущую: 3 — та же студия, 2 — общие слова в названии
 * («AniDUB» и «Kodik · AniDUB»), 1 — тот же алфавит (русская озвучка остаётся русской), 0 — чужая.
 */
internal fun dubSimilarity(current: String?, candidate: String?): Int {
    if (current.isNullOrBlank() || candidate.isNullOrBlank()) return 0
    val a = dubWords(current)
    val b = dubWords(candidate)
    return when {
        current.trim().equals(candidate.trim(), ignoreCase = true) -> 3
        a.isNotEmpty() && b.isNotEmpty() && a.intersect(b).isNotEmpty() -> 2
        current.isCyrillic() == candidate.isCyrillic() -> 1
        else -> 0
    }
}

/** Слова названия озвучки без служебных («озвучка», «dub», качество, провайдеры-плееры). */
private fun dubWords(name: String): Set<String> =
    name.lowercase().replace('ё', 'е')
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 2 && it !in DUB_NOISE && it.toIntOrNull() == null }
        .toSet()

private fun String.isCyrillic(): Boolean = any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }

private val DUB_NOISE = setOf(
    "озвучка", "озвучание", "dub", "voice", "sub", "subs", "субтитры", "многоголосый", "одноголосый",
    "hls", "mp4", "1080p", "720p", "480p", "360p", "kodik", "player", "плеер", "cdn",
)

private data class RankedRecoveryCandidate(
    val video: VetroVideo,
    val category: Int,
    val qualityDistance: Int,
)
