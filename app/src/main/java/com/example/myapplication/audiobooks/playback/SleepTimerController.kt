package com.example.myapplication.audiobooks.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer

/** Counts listening time in the service, pauses at zero, and fades the final ten seconds. */
internal class SleepTimerController(
    private val player: ExoPlayer,
    private val onRemainingChanged: (Long?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var countdown: ListeningTimeCountdown? = null
    private var lastTickMs = SystemClock.elapsedRealtime()
    private var lastPublishedSecond = Long.MIN_VALUE
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val timer = countdown ?: return
            val next = timer.advance(now - lastTickMs, player.isPlaying)
            lastTickMs = now
            if (next == 0L) {
                player.pause()
                cancel()
                return
            }
            player.volume = timer.volume
            publish()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start(minutes: Int) {
        require(minutes in 1..240)
        countdown = ListeningTimeCountdown(minutes * 60_000L)
        lastTickMs = SystemClock.elapsedRealtime()
        lastPublishedSecond = Long.MIN_VALUE
        player.volume = 1f
        publish()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    fun onPlaybackChanged() {
        lastTickMs = SystemClock.elapsedRealtime()
        if (!player.isPlaying) player.volume = 1f
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        countdown = null
        player.volume = 1f
        publish()
    }

    private fun publish() {
        val second = countdown?.remainingMs?.div(1000L) ?: -1L
        if (second != lastPublishedSecond) {
            lastPublishedSecond = second
            onRemainingChanged(countdown?.remainingMs)
        }
    }

    private companion object {
        const val TICK_MS = 250L
    }
}

/** Pure countdown rules, shared by service ticks and JVM verification. */
internal class ListeningTimeCountdown(durationMs: Long) {
    var remainingMs = durationMs.coerceAtLeast(0L)
        private set

    fun advance(elapsedMs: Long, playing: Boolean): Long {
        if (playing) remainingMs = (remainingMs - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(0L)
        return remainingMs
    }

    val volume: Float
        get() = if (remainingMs < 10_000L) (remainingMs.toFloat() / 10_000f).let { it * it }
        else 1f
}
