package com.example.myapplication.audiobooks.playback

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Замечает «мёртвый» аудиовыход: плеер играет, а AudioTrack перестал забирать звук.
 *
 * Так бывает, когда системный микшер выбросил трек из активных после недогруза (Bluetooth, короткий
 * буфер). ExoPlayer при этом не получает ошибки и продолжает отсчитывать позицию по часам, так что
 * [PlaybackStallDetector] молчит. Зато выход перестаёт принимать данные: если принимал, а потом
 * [starvedLimitMs] не принимает, звука нет, хотя позиция идёт.
 */
class AudioFeedWatchdog(
    private val starvedLimitMs: Long = STARVED_LIMIT_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    // Пишет поток воспроизведения, читает главный: поле одно, порядок записей не важен.
    @Volatile private var lastFedAtMs = IDLE
    private var lastTriggerAtMs = Long.MIN_VALUE / 2

    /** Выход принял очередной кусок звука. */
    fun onFed(nowMs: Long) {
        lastFedAtMs = nowMs
    }

    /** Подача остановлена штатно (пауза, конец потока, перемотка): тишина — не поломка. */
    fun onIdle() {
        lastFedAtMs = IDLE
    }

    /**
     * Сколько мс выход молчит, если пора чинить, иначе -1. Срабатывает не чаще раза в [cooldownMs];
     * после срабатывания ждёт [starvedLimitMs] заново, на случай если новый трек тоже мёртв.
     */
    fun silentForMs(nowMs: Long, wantsPlay: Boolean, ready: Boolean): Long {
        val fedAt = lastFedAtMs
        if (!wantsPlay || !ready || fedAt == IDLE) return -1L
        val silent = nowMs - fedAt
        if (silent < starvedLimitMs || nowMs - lastTriggerAtMs < cooldownMs) return -1L
        lastTriggerAtMs = nowMs
        lastFedAtMs = nowMs
        return silent
    }

    companion object {
        /** Нормально выход принимает данные десятки раз в секунду; пауза в секунды - он не жив. */
        const val STARVED_LIMIT_MS = 2_500L

        /** Не чаще: пересоздание выхода само занимает время. */
        const val COOLDOWN_MS = 15_000L

        private const val IDLE = Long.MIN_VALUE
    }
}

/** Аудиовыход, который сообщает [AudioFeedWatchdog], принимает ли он ещё звук. */
@UnstableApi
class MonitoredAudioSink(
    sink: AudioSink,
    private val watchdog: AudioFeedWatchdog,
) : ForwardingAudioSink(sink) {
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val accepted = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (accepted) watchdog.onFed(SystemClock.elapsedRealtime())
        return accepted
    }

    override fun playToEndOfStream() {
        watchdog.onIdle()
        super.playToEndOfStream()
    }

    override fun pause() {
        watchdog.onIdle()
        super.pause()
    }

    override fun flush() {
        watchdog.onIdle()
        super.flush()
    }

    override fun reset() {
        watchdog.onIdle()
        super.reset()
    }
}
