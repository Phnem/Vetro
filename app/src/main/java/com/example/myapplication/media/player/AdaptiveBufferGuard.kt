package com.example.myapplication.media.player

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Страховка буфера стрима по памяти.
 *
 * Буфер стрима держится по ВРЕМЕНИ (60–90 с, приоритет времени над размером) — это запас на
 * проседания сети. Его байты живут в куче приложения, поэтому 90 с тяжёлого потока (8 Мбит/с =
 * 90 МБ, 20 Мбит/с = 225 МБ) на телефоне с небольшой кучей могли бы уронить плеер по памяти.
 *
 * Ограничитель включается ТОЛЬКО когда верхняя граница буфера по времени не влезает в бюджет: тогда
 * граница снижается до того, что влезает, но не ниже нижней границы. На медленной сети
 * (0,5–2 Мбит/с: 90 с = 5,6–22 МБ) он не включается никогда — буфер работает ровно как раньше.
 */
internal object AdaptiveBufferGuard {

    /**
     * Верхняя граница буфера в мс или `null`, если ограничитель не нужен.
     *
     * @param bytesPerSecond сколько байт буфера занимает секунда потока; ≤0 — неизвестно.
     */
    fun capMs(bytesPerSecond: Long, budgetBytes: Long, minBufferMs: Int, maxBufferMs: Int): Int? {
        if (bytesPerSecond <= 0L || budgetBytes <= 0L) return null
        val fitsMs = budgetBytes * 1_000L / bytesPerSecond
        if (fitsMs >= maxBufferMs) return null
        return fitsMs.coerceAtLeast(minBufferMs.toLong()).toInt()
    }

    /**
     * Сколько байт можно отдать буферу: доля кучи и не больше половины того, что в ней реально
     * свободно без учёта самого буфера.
     */
    fun budgetBytes(
        heapMaxBytes: Long,
        heapUsedBytes: Long,
        ownBufferBytes: Long,
        lowRamDevice: Boolean,
    ): Long {
        val share = (heapMaxBytes * if (lowRamDevice) LOW_RAM_HEAP_SHARE else HEAP_SHARE).toLong()
        val freeWithoutBuffer = heapMaxBytes - (heapUsedBytes - ownBufferBytes).coerceAtLeast(0L)
        return minOf(share, freeWithoutBuffer / 2).coerceAtLeast(0L)
    }

    /** Байт в секунду по заявленным битрейтам выбранных дорожек; 0 — битрейт не объявлен. */
    fun declaredBytesPerSecond(bitrates: List<Int>): Long =
        bitrates.filter { it != Format.NO_VALUE && it > 0 }.sumOf { it.toLong() } / 8L

    /** Байт в секунду по факту: сколько занимает уже набранный буфер. Нужен, когда битрейт не объявлен. */
    fun measuredBytesPerSecond(allocatedBytes: Long, bufferedUs: Long): Long =
        if (bufferedUs < MIN_MEASURE_US || allocatedBytes <= 0L) 0L
        else allocatedBytes * 1_000_000L / bufferedUs

    const val HEAP_SHARE = 0.30
    const val LOW_RAM_HEAP_SHARE = 0.20
    /** Меньше 5 с буфера — слишком мало, чтобы оценивать поток по факту. */
    const val MIN_MEASURE_US = 5_000_000L
    /** Пересчёт не чаще — между сменами дорожки. */
    const val RECALC_INTERVAL_MS = 10_000L
}

/**
 * Удержание загрузки у потолка с тем же гистерезисом, что у DefaultLoadControl: упёрлись в
 * [capUs] — стоим, пока буфер не опустится ниже нижней границы, а не дёргаем сеть на каждом
 * проигранном кадре.
 */
internal class BufferHold {
    var holding = false
        private set

    /** `true` — загрузку можно продолжать. */
    fun allowLoading(bufferedUs: Long, capUs: Long?, minBufferUs: Long): Boolean {
        if (capUs == null) {
            holding = false
            return true
        }
        if (bufferedUs >= capUs) {
            holding = true
        } else if (bufferedUs < minBufferUs) {
            holding = false
        }
        return !holding
    }
}

/**
 * [DefaultLoadControl] с потолком от [AdaptiveBufferGuard]. Все решения, кроме «продолжать ли
 * загрузку», отдаются исходному контролу без изменений. Вызывается на потоке воспроизведения.
 */
internal class MemoryGuardedLoadControl(
    private val delegate: DefaultLoadControl,
    private val allocator: Allocator,
    private val minBufferMs: Int,
    private val maxBufferMs: Int,
    private val memory: () -> MemorySnapshot,
    private val nowMs: () -> Long,
) : LoadControl {

    data class MemorySnapshot(val heapMaxBytes: Long, val heapUsedBytes: Long, val lowRamDevice: Boolean)

    private val hold = BufferHold()
    private var selections: List<ExoTrackSelection> = emptyList()
    private var capUs: Long? = null
    private var lastRecalcAtMs = Long.MIN_VALUE / 2

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) {
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
        selections = trackSelections.filterNotNull()
        // Новая дорожка — новый поток: пересчитать при ближайшей загрузке, не дожидаясь интервала.
        lastRecalcAtMs = Long.MIN_VALUE / 2
    }

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (!delegate.shouldContinueLoading(parameters)) return false
        maybeRecalculate(parameters.bufferedDurationUs)
        return hold.allowLoading(parameters.bufferedDurationUs, capUs, minBufferMs * 1_000L)
    }

    private fun maybeRecalculate(bufferedUs: Long) {
        val now = nowMs()
        if (now - lastRecalcAtMs < AdaptiveBufferGuard.RECALC_INTERVAL_MS) return
        lastRecalcAtMs = now
        val allocated = allocator.totalBytesAllocated.toLong()
        val bytesPerSecond = AdaptiveBufferGuard.declaredBytesPerSecond(
            selections.map { it.selectedFormat.bitrate },
        ).takeIf { it > 0L }
            ?: AdaptiveBufferGuard.measuredBytesPerSecond(allocated, bufferedUs)
        val snapshot = memory()
        val budget = AdaptiveBufferGuard.budgetBytes(
            heapMaxBytes = snapshot.heapMaxBytes,
            heapUsedBytes = snapshot.heapUsedBytes,
            ownBufferBytes = allocated,
            lowRamDevice = snapshot.lowRamDevice,
        )
        val capMs = AdaptiveBufferGuard.capMs(bytesPerSecond, budget, minBufferMs, maxBufferMs)
        val newCapUs = capMs?.let { it * 1_000L }
        if (newCapUs != capUs) {
            Log.i(
                TAG,
                if (capMs == null) {
                    "off: ${bytesPerSecond / 1024} KiB/s fits ${maxBufferMs / 1000}s in ${budget shr 20} MiB"
                } else {
                    "on: cap ${capMs / 1000}s (${bytesPerSecond / 1024} KiB/s, budget ${budget shr 20} MiB)"
                },
            )
            capUs = newCapUs
        }
    }

    companion object {
        private const val TAG = "AdaptiveBufferGuard"

        fun memorySnapshot(context: Context): () -> MemorySnapshot {
            val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.isLowRamDevice == true
            return {
                val runtime = Runtime.getRuntime()
                MemorySnapshot(
                    heapMaxBytes = runtime.maxMemory(),
                    heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
                    lowRamDevice = lowRam,
                )
            }
        }
    }
}
