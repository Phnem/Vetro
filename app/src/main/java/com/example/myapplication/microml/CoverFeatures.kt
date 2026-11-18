package com.example.myapplication.microml

/**
 * Признаки обложки для детектора заглушек (контракт `cover-features-v1`, тот же расчёт в tools/microml/cover/features.py).
 *
 * Вход — пиксели ARGB (row-major), обе стороны не меньше 16. Выход — [COUNT] чисел:
 *  - [0, 256)   яркость сетки 16×16: среднее по клетке, клетки — целочисленные разбиения (строки `i*H/16 … (i+1)*H/16`),
 *               яркость = (299 R + 587 G + 114 B) / 255000;
 *  - [256, 304) средние R, G, B сетки 4×4 (0..1): клетка за клеткой, внутри клетки по каналам;
 *  - 304 средняя яркость всей картинки; 305 доля клеток 16×16 со средней яркостью выше 0,9; 306 соотношение сторон W/H.
 * Суммы целочисленные (Long), поэтому результат совпадает с Python бит в бит до приведения к float.
 */
object CoverFeatures {
    const val SPEC = "cover-features-v1"
    private const val GRID = 16
    private const val COLOR_GRID = 4
    const val COUNT = GRID * GRID + COLOR_GRID * COLOR_GRID * 3 + 3
    const val MIN_SIDE = 16
    private const val WHITE_LEVEL = 0.9

    fun compute(argb: IntArray, width: Int, height: Int): FloatArray? {
        if (width < MIN_SIDE || height < MIN_SIDE || argb.size < width * height) return null
        val out = FloatArray(COUNT)

        val rows = IntArray(GRID + 1) { it * height / GRID }
        val cols = IntArray(GRID + 1) { it * width / GRID }
        val grid = DoubleArray(GRID * GRID)
        var total = 0L
        for (gy in 0 until GRID) for (gx in 0 until GRID) {
            var sum = 0L
            for (y in rows[gy] until rows[gy + 1]) {
                var i = y * width + cols[gx]
                for (x in cols[gx] until cols[gx + 1]) {
                    val p = argb[i++]
                    sum += 299L * ((p shr 16) and 255) + 587L * ((p shr 8) and 255) + 114L * (p and 255)
                }
            }
            val count = (rows[gy + 1] - rows[gy]).toLong() * (cols[gx + 1] - cols[gx])
            grid[gy * GRID + gx] = sum / (count * 255000.0)
            total += sum
        }
        for (i in grid.indices) out[i] = grid[i].toFloat()

        val crow = IntArray(COLOR_GRID + 1) { it * height / COLOR_GRID }
        val ccol = IntArray(COLOR_GRID + 1) { it * width / COLOR_GRID }
        var pos = GRID * GRID
        for (gy in 0 until COLOR_GRID) for (gx in 0 until COLOR_GRID) {
            var r = 0L; var g = 0L; var b = 0L
            for (y in crow[gy] until crow[gy + 1]) {
                var i = y * width + ccol[gx]
                for (x in ccol[gx] until ccol[gx + 1]) {
                    val p = argb[i++]
                    r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
                }
            }
            val count = (crow[gy + 1] - crow[gy]).toLong() * (ccol[gx + 1] - ccol[gx])
            out[pos++] = (r / (count * 255.0)).toFloat()
            out[pos++] = (g / (count * 255.0)).toFloat()
            out[pos++] = (b / (count * 255.0)).toFloat()
        }
        out[pos] = (total / (height.toLong() * width * 255000.0)).toFloat()
        out[pos + 1] = (grid.count { it > WHITE_LEVEL } / (GRID * GRID).toDouble()).toFloat()
        out[pos + 2] = (width.toDouble() / height).toFloat()
        return out
    }
}
