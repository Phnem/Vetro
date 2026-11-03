package com.example.myapplication.audiobooks.playback

import android.os.SystemClock
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Громкость того, что играет плеер аудиокниг, для «живой» волны мини-плеера.
 *
 * Звук снимается с PCM, который аудиовыход получает от декодера ([MonitoredAudioSink]): это звук самой
 * книги, без Visualizer и без разрешения на микрофон. Выход принимает звук с опережением на размер
 * своего буфера (пара секунд), поэтому уровни хранятся по времени и читаются по позиции
 * воспроизведения — иначе волна бежала бы впереди голоса.
 *
 * Пишет поток воспроизведения, читает главный: общие поля под одним замком, обращений десятки в секунду.
 */
object AudioLevelMeter {
    private const val SIZE = 600
    private const val SAMPLES_PER_BUFFER = 128
    /** Ниже этого уровня (дБ) — тишина. */
    private const val FLOOR_DB = -52f
    private const val RANGE_DB = 46f
    /** Позицию выход сообщает чаще, чем кадры экрана; между сообщениями достраиваем, но недолго. */
    private const val MAX_EXTRAPOLATION_MS = 60L

    /** Кусок звука считается «текущим», если начался не раньше этого до запрошенного момента. */
    private const val MAX_BUFFER_US = 250_000L

    private val starts = LongArray(SIZE)
    private val levels = FloatArray(SIZE)
    private var written = 0L
    private var lastRecordedUs = Long.MIN_VALUE
    private var positionUs = C.TIME_UNSET
    private var positionAtMs = 0L

    /** Новый кусок звука на входе выхода. Буфер не двигаем: его заберёт сам выход. */
    @Synchronized
    fun record(buffer: ByteBuffer, presentationTimeUs: Long) {
        // Выход может предлагать один и тот же кусок несколько раз, пока принимает его частями.
        if (presentationTimeUs == lastRecordedUs) return
        // Декодер отдаёт 16-битный PCM (вывод во float у этого плеера выключен); каналы читаем подряд —
        // для громкости чередование левого и правого неважно.
        val samples = buffer.remaining() / 2
        if (samples <= 0) return
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        val start = view.position()
        val stride = max(1, samples / SAMPLES_PER_BUFFER)
        var sum = 0.0
        var count = 0
        var sample = 0
        while (sample < samples) {
            val value = view.getShort(start + sample * 2) / 32768f
            sum += value * value
            count++
            sample += stride
        }
        val rms = sqrt(sum / count).toFloat()
        val db = 20f * log10(max(rms, 1e-5f))
        val index = (written % SIZE).toInt()
        starts[index] = presentationTimeUs
        levels[index] = ((db - FLOOR_DB) / RANGE_DB).coerceIn(0f, 1f)
        written++
        lastRecordedUs = presentationTimeUs
    }

    /** Выход сообщает, докуда дошло воспроизведение (в той же шкале времени, что куски звука). */
    @Synchronized
    fun onPosition(us: Long) {
        positionUs = us
        positionAtMs = SystemClock.elapsedRealtime()
    }

    /** Где сейчас воспроизведение; [C.TIME_UNSET], пока выход ничего не сообщал. */
    @Synchronized
    fun nowUs(): Long {
        if (positionUs == C.TIME_UNSET) return C.TIME_UNSET
        val sinceMs = min(SystemClock.elapsedRealtime() - positionAtMs, MAX_EXTRAPOLATION_MS)
        return positionUs + sinceMs * 1000L
    }

    /** Уровень 0..1 в момент [us]; 0, если этот кусок уже вытеснен или ещё не принят. */
    @Synchronized
    fun levelAt(us: Long): Float {
        var i = written - 1
        val oldest = max(0L, written - SIZE)
        // Свежие записи важнее: после перемотки назад времена старого и нового прохода пересекаются.
        while (i >= oldest) {
            val index = (i % SIZE).toInt()
            val begin = starts[index]
            if (us >= begin && us - begin < MAX_BUFFER_US) return levels[index]
            i--
        }
        return 0f
    }

    /** Перемотка или остановка: принятое ранее больше не относится к тому, что будет звучать. */
    @Synchronized
    fun clear() {
        written = 0L
        lastRecordedUs = Long.MIN_VALUE
        positionUs = C.TIME_UNSET
    }
}
