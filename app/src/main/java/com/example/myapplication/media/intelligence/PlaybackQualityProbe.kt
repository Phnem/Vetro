package com.example.myapplication.media.intelligence

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

/**
 * Слушатель одной сессии просмотра у одного источника: время старта, суммарная буферизация,
 * реальная высота кадра, был ли отказ. Результат — [PlaybackSample] для [SourceIntelligence].
 *
 * Живёт ровно столько, сколько играет одна ссылка (см. DisposableEffect в StreamPlayerActivity):
 * смена источника, перезапуск и выход из плеера закрывают сессию через [finish].
 */
class PlaybackQualityProbe(
    private val dubKept: Boolean?,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {

    private val startedAt = clock()
    private var startupMs: Long? = null
    private var bufferingSince: Long? = null
    private var bufferingMs = 0L
    private var playingSince: Long? = null
    private var playedMs = 0L
    private var heightPx: Int? = null
    private var failed = false
    private var finished = false

    override fun onPlaybackStateChanged(playbackState: Int) {
        val now = clock()
        when (playbackState) {
            Player.STATE_READY -> {
                if (startupMs == null) startupMs = now - startedAt
                endBuffering(now)
            }
            // Буферизация до первого кадра — это старт, а не подтормаживание: её считает startupMs.
            Player.STATE_BUFFERING -> if (startupMs != null && bufferingSince == null) bufferingSince = now
            else -> endBuffering(now)
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val now = clock()
        if (isPlaying) {
            if (playingSince == null) playingSince = now
        } else {
            flushPlayed(now)
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.height > 0) heightPx = videoSize.height
    }

    override fun onPlayerError(error: PlaybackException) {
        failed = true
    }

    /**
     * Итог сессии; повторный вызов вернёт null. Сессия, в которой ничего не произошло (закрыли до
     * готовности и без ошибки), — тоже null: из неё нечему учиться.
     */
    fun finish(): PlaybackSample? {
        if (finished) return null
        finished = true
        val now = clock()
        endBuffering(now)
        flushPlayed(now)
        if (startupMs == null && !failed) return null
        return PlaybackSample(
            startupMs = startupMs,
            playedMs = playedMs,
            bufferingMs = bufferingMs,
            heightPx = heightPx,
            failed = failed && playedMs < MIN_PLAYED_TO_FORGIVE_MS,
            dubKept = dubKept,
        )
    }

    private fun endBuffering(now: Long) {
        bufferingSince?.let { bufferingMs += now - it }
        bufferingSince = null
    }

    private fun flushPlayed(now: Long) {
        playingSince?.let { playedMs += now - it }
        playingSince = null
    }

    private companion object {
        /** Ошибка после долгого нормального просмотра (обрыв сети в конце) не делает источник негодным. */
        const val MIN_PLAYED_TO_FORGIVE_MS = 120_000L
    }
}
