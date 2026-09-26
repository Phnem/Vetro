package com.example.myapplication.audiobooks.data.remote.web

/**
 * Битрейт MP3 по первому кадру (после ID3v2): нужен, чтобы оценить длины треков по размерам файлов,
 * когда сайт не сообщает ни длин, ни общей длины книги. Аудиокниги почти всегда пишутся с
 * постоянным битрейтом, так что размер × 8 / битрейт даёт длину с точностью до секунд.
 */
object Mp3Probe {

    /** Сколько байт начала файла нужно, если в нём нет тега; с тегом — [tagSize] + это. */
    const val HEAD_BYTES = 4096

    /** Размер тега ID3v2 в начале файла (с заголовком), 0 — тега нет. */
    fun tagSize(head: ByteArray): Int {
        if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
        val size = (head[6].toInt() and 0x7F shl 21) or (head[7].toInt() and 0x7F shl 14) or
            (head[8].toInt() and 0x7F shl 7) or (head[9].toInt() and 0x7F)
        val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
        return 10 + size + footer
    }

    /** Битрейт первого валидного кадра MPEG Layer III в [bytes], кбит/с; null — кадр не найден. */
    fun bitrateKbps(bytes: ByteArray): Int? {
        var i = 0
        while (i + 4 <= bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            if (b0 == 0xFF && b1 and 0xE0 == 0xE0) {
                val version = (b1 shr 3) and 0x03 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
                val layer = (b1 shr 1) and 0x03 // 1 = Layer III
                val index = ((bytes[i + 2].toInt() and 0xFF) shr 4) and 0x0F
                if (version != 1 && layer == 1 && index in 1..14) {
                    return if (version == 3) MPEG1_L3[index] else MPEG2_L3[index]
                }
            }
            i++
        }
        return null
    }

    private val MPEG1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
}
