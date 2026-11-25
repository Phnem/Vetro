package com.example.myapplication.utils

import java.text.BreakIterator

/**
 * Не больше [limit] знаков UTF-16 без разрыва видимого символа: простой `take` резал эмодзи пополам.
 */
fun String.takeWholeChars(limit: Int): String {
    if (length <= limit) return this
    if (limit <= 0) return ""
    val boundaries = BreakIterator.getCharacterInstance().also { it.setText(this) }
    var end = if (boundaries.isBoundary(limit)) limit else boundaries.preceding(limit)
    if (end > 0 && this[end - 1].isHighSurrogate()) end--
    return substring(0, end.coerceAtLeast(0))
}
