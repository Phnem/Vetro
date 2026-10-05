package com.example.myapplication.manga.translate

import java.util.Locale
import kotlin.math.roundToInt

/** Подписи размера для шторки загрузки моделей. Мегабайты десятичные: так же считает "≈128 МБ" в тексте. */
object DownloadFormat {

    private const val BYTES_IN_MEGABYTE = 1_000_000.0

    /** "42" для крупных значений и "2,4" / "2.4" для мелких: знак после запятой нужен, пока число однозначное. */
    fun megabytes(bytes: Long, decimalComma: Boolean): String {
        val mb = bytes.coerceAtLeast(0L) / BYTES_IN_MEGABYTE
        val text = if (mb >= 10.0) mb.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", mb)
        return if (decimalComma) text.replace('.', ',') else text
    }
}
