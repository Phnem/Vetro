package com.example.myapplication.media.progress

import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Когда сохранять позицию просмотра.
 *
 * Раньше оба плеера писали её КАЖДУЮ секунду и даже на паузе: каждая запись перекодирует JSON
 * тайтла и переписывает общий файл настроек, будя всех подписчиков настроек, — около 1440 записей
 * за 24-минутную серию. Теперь: раз в [intervalMs] только пока идёт воспроизведение, плюс сразу
 * при переходе в паузу (остановку активити плееры сохраняют сами в `onStop`). Это ≤144 записи за
 * серию, а положение на паузе не теряется.
 */
class ProgressSaveGate(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {
    private var wasPlaying = false
    private var lastSaveAt = Long.MIN_VALUE / 2

    fun shouldSave(playing: Boolean, nowMs: Long): Boolean {
        val due = playing && nowMs - lastSaveAt >= intervalMs
        val justPaused = wasPlaying && !playing
        wasPlaying = playing
        if (due || justPaused) {
            lastSaveAt = nowMs
            return true
        }
        return false
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 10_000L
    }
}

/** Цикл сохранения для плеера; живёт в корутине экрана и отменяется вместе с ней. */
suspend fun runPlaybackProgressSaver(
    isPlaying: () -> Boolean,
    gate: ProgressSaveGate = ProgressSaveGate(),
    save: suspend () -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        delay(TICK_MS)
        if (gate.shouldSave(isPlaying(), SystemClock.elapsedRealtime())) save()
    }
}

/** Как часто проверять состояние — не как часто писать: запись решает [ProgressSaveGate]. */
private const val TICK_MS = 1_000L
