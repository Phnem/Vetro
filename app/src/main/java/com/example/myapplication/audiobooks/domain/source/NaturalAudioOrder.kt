package com.example.myapplication.audiobooks.domain.source

/** File names from a user folder are ordered as parts 2, 10 rather than 10, 2. */
object NaturalAudioOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var left = 0
        var right = 0
        while (left < a.length && right < b.length) {
            val aDigit = a[left].isDigit()
            val bDigit = b[right].isDigit()
            if (aDigit && bDigit) {
                val aEnd = a.runEnd(left, true)
                val bEnd = b.runEnd(right, true)
                val aNumber = a.substring(left, aEnd).trimStart('0').ifEmpty { "0" }
                val bNumber = b.substring(right, bEnd).trimStart('0').ifEmpty { "0" }
                val numberOrder = aNumber.length.compareTo(bNumber.length).takeIf { it != 0 }
                    ?: aNumber.compareTo(bNumber)
                if (numberOrder != 0) return numberOrder
                left = aEnd
                right = bEnd
            } else {
                val charOrder = a[left].lowercaseChar().compareTo(b[right].lowercaseChar())
                if (charOrder != 0) return charOrder
                left++
                right++
            }
        }
        return if (left == a.length && right == b.length) a.compareTo(b) else
            (a.length - left).compareTo(b.length - right)
    }

    private fun String.runEnd(start: Int, digit: Boolean): Int {
        var end = start
        while (end < length && this[end].isDigit() == digit) end++
        return end
    }
}
