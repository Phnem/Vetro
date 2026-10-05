package com.example.myapplication.manga.ja

/**
 * Чистая часть восстановления страниц (без Android), чтобы проверять юнит-тестами.
 * Что именно делает сайт перед показом - см. [com.example.myapplication.manga.domain.PageDecode].
 */
object PageDecoder {

    /** Сетка GigaViewer: 4x4 блока, размер блока кратен 8 px. */
    private const val DIVIDE = 4
    private const val MULTIPLE = 8

    /** Один блок: откуда берётся в перемешанной картинке и куда ложится в итоговой. */
    data class Cell(val srcX: Int, val srcY: Int, val dstX: Int, val dstY: Int, val width: Int, val height: Int)

    /** XOR с повторяющимся ключом. [keyHex] - hex-строка чётной длины; пустой/битый ключ возвращает данные как есть. */
    fun xor(data: ByteArray, keyHex: String): ByteArray {
        val key = hexToBytes(keyHex)
        if (key.isEmpty()) return data
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        return out
    }

    /**
     * Перестановка блоков GigaViewer: блок (e / 4, e % 4) исходной картинки ложится в позицию
     * (e % 4, e / 4) итоговой - транспонирование сетки. Края, не кратные размеру блока, остаются
     * на месте (их копируют из оригинала до перестановки).
     */
    fun gigaCells(width: Int, height: Int): List<Cell> {
        val cellW = (width / (DIVIDE * MULTIPLE)) * MULTIPLE
        val cellH = (height / (DIVIDE * MULTIPLE)) * MULTIPLE
        if (cellW <= 0 || cellH <= 0) return emptyList()
        return (0 until DIVIDE * DIVIDE).map { e ->
            Cell(
                srcX = (e / DIVIDE) * cellW,
                srcY = (e % DIVIDE) * cellH,
                dstX = (e % DIVIDE) * cellW,
                dstY = (e / DIVIDE) * cellH,
                width = cellW,
                height = cellH,
            )
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.isEmpty() || hex.length % 2 != 0) return ByteArray(0)
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return ByteArray(0)
            ((hi shl 4) or lo).toByte()
        }
    }
}
