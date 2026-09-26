package com.example.myapplication.media.download

import java.nio.ByteBuffer

/**
 * AAC в обёртке ADTS — так аудио лежит в MPEG-TS (HLS у Kodik). MediaExtractor на таком потоке
 * отдаёт PES целиком: несколько кадров AAC подряд, каждый со своим 7/9-байтным заголовком. В MP4
 * сэмпл обязан быть ОДНИМ голым кадром AAC, иначе декодер не справляется: скачанная серия стояла
 * на буферизации с первого кадра (5 кадров ADTS в сэмпле, 6 476 сэмплов вместо ~70 000).
 */
internal object AdtsFrames {

    /**
     * Кадр AAC внутри буфера: где начинается полезная нагрузка без заголовка, её длина и
     * частота из заголовка (0 — индекс вне таблицы).
     */
    data class Frame(val payloadOffset: Int, val payloadSize: Int, val sampleRate: Int = 0)

    private const val HEADER_WITHOUT_CRC = 7
    private const val HEADER_WITH_CRC = 9

    /** Похоже ли начало сэмпла на заголовок ADTS (синхрослово 0xFFF, слой 00). */
    fun startsWithAdts(buffer: ByteBuffer, offset: Int, size: Int): Boolean {
        if (size < HEADER_WITHOUT_CRC) return false
        val b0 = buffer.get(offset).toInt() and 0xFF
        val b1 = buffer.get(offset + 1).toInt() and 0xFF
        return b0 == 0xFF && (b1 and 0xF6) == 0xF0
    }

    /**
     * Кадры ADTS подряд в `[offset, offset + size)`. Пустой список — если данные не ADTS или
     * последовательность рвётся (тогда сэмпл пишется как есть, ничего не ломаем).
     */
    fun split(buffer: ByteBuffer, offset: Int, size: Int): List<Frame> {
        val frames = ArrayList<Frame>(8)
        var pos = offset
        val end = offset + size
        while (pos < end) {
            if (!startsWithAdts(buffer, pos, end - pos)) return emptyList()
            val protectionAbsent = (buffer.get(pos + 1).toInt() and 0x01) == 1
            val headerSize = if (protectionAbsent) HEADER_WITHOUT_CRC else HEADER_WITH_CRC
            val frameLength = ((buffer.get(pos + 3).toInt() and 0x03) shl 11) or
                ((buffer.get(pos + 4).toInt() and 0xFF) shl 3) or
                ((buffer.get(pos + 5).toInt() and 0xE0) ushr 5)
            if (frameLength <= headerSize || pos + frameLength > end) return emptyList()
            val rateIndex = (buffer.get(pos + 2).toInt() and 0x3C) ushr 2
            frames += Frame(pos + headerSize, frameLength - headerSize, SAMPLE_RATES.getOrElse(rateIndex) { 0 })
            pos += frameLength
        }
        return frames
    }

    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
    )

    /**
     * Время кадров AAC в MP4. Время PES из TS с числом кадров в нём не сходится (MediaExtractor
     * отдаёт PES первого кадра на 22 мс, а в нём пять кадров по 21 мс), и если раскладывать кадры
     * от времени каждого PES, они налезают на следующий — MPEG4Writer останавливает дорожку.
     * Поэтому время идёт непрерывным счётом кадров от первого PES; разрыв больше [resyncUs]
     * (настоящая дыра в потоке) — новая точка отсчёта. Назад время не идёт никогда.
     */
    class Timeline(private val resyncUs: Long = 500_000L) {
        private var nextUs: Long? = null

        /** Время первого кадра PES с временем [pesTimeUs] и [frames] кадрами по [frameUs]. */
        fun place(pesTimeUs: Long, frames: Int, frameUs: Long): Long {
            val expected = nextUs
            val start = if (expected == null || pesTimeUs > expected + resyncUs) pesTimeUs else expected
            nextUs = start + frames * frameUs
            return start
        }
    }

    /**
     * AudioSpecificConfig (csd-0) для MP4: 5 бит тип объекта, 4 бита индекс частоты, 4 бита
     * конфигурация каналов. Нужен, если экстрактор не положил его в формат трека.
     */
    fun audioSpecificConfig(sampleRate: Int, channelCount: Int, objectType: Int = 2): ByteArray? {
        val index = SAMPLE_RATES.indexOf(sampleRate)
        if (index < 0 || channelCount !in 1..7) return null
        val bits = (objectType shl 11) or (index shl 7) or (channelCount shl 3)
        return byteArrayOf((bits ushr 8).toByte(), bits.toByte())
    }
}
