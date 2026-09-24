package com.example.myapplication.localplayer.domain

data class SkipMediaKey(
    val playerIdentity: Int,
    val mediaId: String,
    val episodeNumber: Int?,
)

enum class SkipSeekReason { AUTOMATIC, MANUAL }

data class SkipSeekDecision(
    val segment: SkipSegment,
    val targetMs: Long,
    val reason: SkipSeekReason,
    /**
     * Откуда прыгнули. Нужна, чтобы отмена вернула ровно туда, где пользователь был, а не к
     * началу сегмента: в опенинг можно войти и с середины — перемоткой или возобновлением.
     */
    val fromMs: Long = 0L,
)

/**
 * Pure state machine shared by local and streaming Compose adapters.
 *
 * The key owns all state. Installing another player, URL/media id or episode clears both the
 * resolved segments and automatic-seek deduplication.
 */
class MediaSkipCoordinator {
    private var currentKey: SkipMediaKey? = null
    private var resolution = SkipSegmentResolution(emptyList(), null, null)
    private var lastAutomaticSegment: SegmentIdentity? = null
    private var pendingAutomaticTargetMs: Long? = null

    /**
     * Сегменты, от автопропуска которых пользователь отказался.
     *
     * Отдельно от [lastAutomaticSegment], который всего лишь не даёт прыгнуть дважды подряд и
     * сбрасывается любой перемоткой. Отмена — это перемотка НАЗАД в тот самый сегмент, то есть
     * ровно тот случай, когда дедупликация снимается: без явного отказа автопропуск сработал бы
     * снова через долю секунды, и кнопка отмены не работала бы вовсе.
     *
     * Живёт до конца серии: пользователь отказался смотреть не этот кадр, а этот опенинг.
     */
    private val declined = mutableSetOf<SegmentIdentity>()

    fun install(key: SkipMediaKey, resolved: SkipSegmentResolution) {
        val mediaChanged = currentKey != key
        currentKey = key
        resolution = resolved
        if (mediaChanged) {
            lastAutomaticSegment = null
            pendingAutomaticTargetMs = null
            declined.clear()
        }
    }

    fun activeSegment(key: SkipMediaKey, positionMs: Long): SkipSegment? {
        if (key != currentKey) return null
        return resolution.segments.firstOrNull {
            positionMs >= it.startMs && positionMs < it.endMs
        }
    }

    fun automaticSeek(
        key: SkipMediaKey,
        positionMs: Long,
        enabled: Boolean,
    ): SkipSeekDecision? {
        if (key != currentKey) return null
        val active = activeSegment(key, positionMs)
        if (active == null) {
            lastAutomaticSegment = null
            pendingAutomaticTargetMs = null
            return null
        }
        if (!enabled) return null
        val identity = SegmentIdentity(active.startMs, active.endMs, active.kind)
        if (lastAutomaticSegment == identity) return null
        if (identity in declined) return null
        lastAutomaticSegment = identity
        pendingAutomaticTargetMs = active.endMs
        return SkipSeekDecision(active, active.endMs, SkipSeekReason.AUTOMATIC, positionMs)
    }

    fun manualSeek(key: SkipMediaKey, positionMs: Long): SkipSeekDecision? {
        val active = activeSegment(key, positionMs) ?: return null
        return SkipSeekDecision(active, active.endMs, SkipSeekReason.MANUAL, positionMs)
    }

    /**
     * Пользователь отменил автопропуск этого сегмента: больше его не трогаем до конца серии.
     *
     * Ручной пропуск при этом остаётся доступен — отказ касается только автоматики.
     */
    fun declineAutomatic(key: SkipMediaKey, segment: SkipSegment) {
        if (key != currentKey) return
        declined += SegmentIdentity(segment.startMs, segment.endMs, segment.kind)
        lastAutomaticSegment = null
        pendingAutomaticTargetMs = null
    }

    /** Отказывались ли уже от автопропуска этого сегмента. */
    fun isDeclined(segment: SkipSegment): Boolean =
        SegmentIdentity(segment.startMs, segment.endMs, segment.kind) in declined

    /**
     * A resume/user/source seek is a fresh segment-entry check, even if Compose saw no null gap.
     * The discontinuity caused by our own automatic seek is consumed without re-arming it.
     */
    fun onPositionDiscontinuity(key: SkipMediaKey, newPositionMs: Long) {
        if (key != currentKey) return
        val automaticTarget = pendingAutomaticTargetMs
        pendingAutomaticTargetMs = null
        if (
            automaticTarget != null &&
            kotlin.math.abs(newPositionMs - automaticTarget) <= OWN_SEEK_TOLERANCE_MS
        ) {
            return
        }
        lastAutomaticSegment = null
    }

    private data class SegmentIdentity(
        val startMs: Long,
        val endMs: Long,
        val kind: SkipKind,
    )

    private companion object {
        const val OWN_SEEK_TOLERANCE_MS = 1_000L
    }
}
