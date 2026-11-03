package com.example.myapplication.audiobooks.playback

import android.os.SystemClock
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
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
 * Конвейер: PCM-блок → куски по ~20 мс → RMS и пик каждого куска → один уровень 0..1 с отметкой
 * времени в кольце → [levelAt] достраивает между отсчётами плавную кривую (монотонный кубический
 * Эрмит, PCHIP: без выбросов выше и ниже соседних отсчётов). Блок декодера может быть и 20 мс, и
 * 200 мс — деление на куски делает частоту отсчётов независимой от этого, иначе кривая превращалась
 * бы в несколько «слайдов» в секунду.
 *
 * Пишет поток воспроизведения, читает главный: общие поля под одним замком, обращений сотни в секунду.
 */
object AudioLevelMeter {
    private const val SIZE = 1024

    /** Целевая длина одного отсчёта; блок длиннее режется на куски такой длины. */
    private const val CHUNK_US = 20_000L
    private const val MAX_CHUNKS = 32

    /** Длительность блока оцениваем по расстоянию до предыдущего; вне этих границ оценке не верим. */
    private const val MIN_BLOCK_US = 5_000L
    private const val MAX_BLOCK_US = 400_000L

    /** Сэмплов на кусок, которых хватает для точного RMS и пика (20 мс стерео 48 кГц — меньше). */
    private const val MAX_SAMPLES_PER_CHUNK = 2048

    /** Шкала уровня в дБ относительно полной шкалы: ниже пола — тишина, потолок — 0 дБ. */
    private const val RMS_FLOOR_DB = -52f
    private const val RMS_RANGE_DB = 46f
    private const val PEAK_FLOOR_DB = -40f
    private const val PEAK_RANGE_DB = 40f

    /** Доля пика в итоговом уровне: RMS даёт «тело» голоса, пик — оживляет согласные. */
    private const val PEAK_WEIGHT = 0.35f

    /**
     * Позицию выход сообщает неровно (раз в секунду и реже, не на каждый кадр), поэтому между сообщениями
     * часы идут сами по времени кадра со скоростью воспроизведения. Дольше этого без сообщений не верим.
     */
    private const val MAX_EXTRAPOLATION_MS = 1500L

    /** Скорость воспроизведения оцениваем по сообщениям на таком окне: короче — шум позиции важнее сдвига. */
    private const val RATE_WINDOW_MS = 4000L

    /**
     * Позиция от выхода шумит на 0.1–0.3 с (она идёт по меткам AudioTrack): каждое сообщение подтягивает
     * наши часы лишь на эту долю расхождения, чтобы волна не дёргалась назад и вперёд.
     */
    private const val POSITION_GAIN = 0.25

    /** Расхождение больше этого — перемотка или возобновление после паузы: часы встают на позицию сразу. */
    private const val SNAP_US = 600_000L

    /** Дальше последнего принятого отсчёта кривую держим не дольше этого (звук ещё не пришёл). */
    private const val MAX_HOLD_US = 250_000L

    private val times = LongArray(SIZE)
    private val levels = FloatArray(SIZE)
    private var written = 0L
    private var size = 0
    private var lastRecordedUs = Long.MIN_VALUE
    private var blockUs = 0L
    private var positionUs = C.TIME_UNSET
    private var positionAtMs = 0L
    private var rate = 1.0
    private var ratePosUs = C.TIME_UNSET
    private var rateAtMs = 0L

    /** Новый блок звука на входе выхода. Буфер не двигаем: его заберёт сам выход. */
    @Synchronized
    fun record(buffer: ByteBuffer, presentationTimeUs: Long) {
        // Выход может предлагать один и тот же кусок несколько раз, пока принимает его частями.
        if (presentationTimeUs == lastRecordedUs) return
        // Декодер отдаёт 16-битный PCM (вывод во float у этого плеера выключен); каналы читаем подряд —
        // для громкости чередование левого и правого неважно.
        val bytes = buffer.remaining() and 1.inv()
        if (bytes <= 0) return
        if (lastRecordedUs != Long.MIN_VALUE) {
            val gap = presentationTimeUs - lastRecordedUs
            if (gap in MIN_BLOCK_US..MAX_BLOCK_US) blockUs = gap
        }
        // Оценка могла ошибиться в большую сторону: убираем отсчёты, которые легли бы на этот блок.
        while (size > 0 && times[indexOf(written - 1)] >= presentationTimeUs) {
            written--
            size--
        }
        val chunks = if (blockUs > 0) (blockUs / CHUNK_US).toInt().coerceIn(1, MAX_CHUNKS) else 1
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        val base = view.position()
        for (k in 0 until chunks) {
            val from = base + ((bytes.toLong() * k / chunks).toInt() and 1.inv())
            val to = base + ((bytes.toLong() * (k + 1) / chunks).toInt() and 1.inv())
            val level = chunkLevel(view, from, to) ?: continue
            // Отсчёт ставим в середину куска: так кривая не запаздывает на полкуска.
            val at = if (blockUs > 0) presentationTimeUs + blockUs * (2L * k + 1) / (2L * chunks)
            else presentationTimeUs + CHUNK_US / 2
            val index = indexOf(written)
            times[index] = at
            levels[index] = level
            written++
            if (size < SIZE) size++
        }
        lastRecordedUs = presentationTimeUs
    }

    /** RMS и пик куска [from, to), сведённые в один уровень 0..1; null, если в куске нет сэмплов. */
    private fun chunkLevel(view: ByteBuffer, from: Int, to: Int): Float? {
        val samples = (to - from) / 2
        if (samples <= 0) return null
        val stride = max(1, samples / MAX_SAMPLES_PER_CHUNK)
        var sum = 0.0
        var peak = 0f
        var count = 0
        var sample = 0
        while (sample < samples) {
            val value = view.getShort(from + sample * 2) / 32768f
            sum += value * value
            val magnitude = abs(value)
            if (magnitude > peak) peak = magnitude
            count++
            sample += stride
        }
        val rms = sqrt(sum / count).toFloat()
        val rmsLevel = ((20f * log10(max(rms, 1e-5f)) - RMS_FLOOR_DB) / RMS_RANGE_DB).coerceIn(0f, 1f)
        val peakLevel = ((20f * log10(max(peak, 1e-5f)) - PEAK_FLOOR_DB) / PEAK_RANGE_DB).coerceIn(0f, 1f)
        return (1f - PEAK_WEIGHT) * rmsLevel + PEAK_WEIGHT * peakLevel
    }

    /** Выход сообщает, докуда дошло воспроизведение (в той же шкале времени, что куски звука). */
    @Synchronized
    fun onPosition(us: Long) {
        val wall = SystemClock.elapsedRealtime()
        if (positionUs == C.TIME_UNSET) {
            positionUs = us
            positionAtMs = wall
            ratePosUs = us
            rateAtMs = wall
            return
        }
        val error = us - nowUs()
        if (abs(error) > SNAP_US) {
            positionUs = us
            ratePosUs = us
            rateAtMs = wall
        } else {
            positionUs = nowUs() + (error * POSITION_GAIN).toLong()
        }
        positionAtMs = wall
        // Скорость (1x, 1.5x, 2x…) видна по тому, как позиция уходит вперёд относительно часов телефона.
        if (wall - rateAtMs >= RATE_WINDOW_MS) {
            val measured = (us - ratePosUs) / ((wall - rateAtMs) * 1000.0)
            // Перемотка и паузы дают значения вне разумных скоростей: их пропускаем.
            if (measured in 0.2..4.5) rate += (measured - rate) * 0.5
            ratePosUs = us
            rateAtMs = wall
        }
    }

    /** Где сейчас воспроизведение; [C.TIME_UNSET], пока выход ничего не сообщал. */
    @Synchronized
    fun nowUs(): Long {
        if (positionUs == C.TIME_UNSET) return C.TIME_UNSET
        val sinceMs = min(SystemClock.elapsedRealtime() - positionAtMs, MAX_EXTRAPOLATION_MS)
        return positionUs + (sinceMs * 1000L * rate).toLong()
    }

    /**
     * Уровень 0..1 в момент [us] на плавной кривой через отсчёты; 0, если этот момент уже вытеснен
     * из кольца или звук для него ещё не принят.
     */
    @Synchronized
    fun levelAt(us: Long): Float {
        if (size < 2) return 0f
        val first = written - size
        val last = written - 1
        if (us < timeAt(first)) return 0f
        if (us >= timeAt(last)) return if (us - timeAt(last) <= MAX_HOLD_US) levels[indexOf(last)] else 0f

        // Последний отсчёт, не позже us: время в кольце строго растёт (см. record).
        var lo = first
        var hi = last - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (timeAt(mid) <= us) lo = mid else hi = mid - 1
        }
        val i1 = lo
        val i2 = lo + 1
        val i0 = max(first, i1 - 1)
        val i3 = min(last, i2 + 1)

        val x1 = timeAt(i1).toDouble()
        val h = timeAt(i2) - x1
        val y1 = levels[indexOf(i1)].toDouble()
        val y2 = levels[indexOf(i2)].toDouble()
        val secant = (y2 - y1) / h

        val m1 = if (i0 == i1) secant else {
            val h0 = x1 - timeAt(i0)
            pchipSlope(h0, (y1 - levels[indexOf(i0)]) / h0, h, secant)
        }
        val m2 = if (i3 == i2) secant else {
            val h1 = timeAt(i3) - timeAt(i2).toDouble()
            pchipSlope(h, secant, h1, (levels[indexOf(i3)] - y2) / h1)
        }

        // Кубический Эрмит на отрезке [i1, i2].
        val t = (us - x1) / h
        val t2 = t * t
        val t3 = t2 * t
        val value = (2 * t3 - 3 * t2 + 1) * y1 + (t3 - 2 * t2 + t) * h * m1 +
            (-2 * t3 + 3 * t2) * y2 + (t3 - t2) * h * m2
        return value.toFloat().coerceIn(0f, 1f)
    }

    /**
     * Наклон в узле между отрезками шириной [h0] и [h1] с секущими [d0] и [d1] (Фрицш—Карлсон): нуль на
     * экстремуме, иначе взвешенное гармоническое среднее — кривая не вылезает за соседние значения.
     */
    private fun pchipSlope(h0: Double, d0: Double, h1: Double, d1: Double): Double {
        if (d0 * d1 <= 0.0) return 0.0
        val w1 = 2 * h1 + h0
        val w2 = h1 + 2 * h0
        return (w1 + w2) / (w1 / d0 + w2 / d1)
    }

    private fun indexOf(logical: Long): Int = (logical % SIZE).toInt()

    private fun timeAt(logical: Long): Long = times[indexOf(logical)]

    /** Перемотка или остановка: принятое ранее больше не относится к тому, что будет звучать. */
    @Synchronized
    fun clear() {
        written = 0L
        size = 0
        lastRecordedUs = Long.MIN_VALUE
        blockUs = 0L
        positionUs = C.TIME_UNSET
        ratePosUs = C.TIME_UNSET
    }
}
