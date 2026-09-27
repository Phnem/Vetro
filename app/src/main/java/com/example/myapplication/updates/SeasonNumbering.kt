package com.example.myapplication.updates

/**
 * Номер выходящего сезона — одинаковый при каждой проверке и совпадающий с официальным.
 *
 * Раньше номер был длиной PREQUEL-цепочки AniList, а там вторая половина сезона — отдельный узел:
 * у Re:ZERO «Season 2 Part 2» делал из официального четвёртого сезона пятый. Чистые функции —
 * правила проверяются тестами (SeasonNumberingTest).
 */
internal object SeasonNumbering {

    /**
     * Номер сезона, написанный в самом названии: «Season 4», «4th Season», «Season IV». Это то,
     * что видит пользователь на афише, и никакой подсчёт его не перебивает.
     */
    fun explicitSeason(vararg titles: String?): Int? =
        titles.asSequence().filterNotNull().mapNotNull(::explicitIn).firstOrNull()

    private fun explicitIn(title: String): Int? {
        SEASON_N.find(title)?.let { m -> return parseNumber(m.groupValues[1]) }
        NTH_SEASON.find(title)?.let { m -> return m.groupValues[1].toIntOrNull() }
        WORD_SEASON.find(title)?.let { m -> return ORDINAL_WORDS[m.groupValues[1].lowercase()] }
        return null
    }

    /**
     * Узел — продолжение того же сезона, а не новый: «Part 2», «2nd Cour», «Cour 2», «2nd - 3rd
     * STAGE». Первая часть («Part 1», «1st STAGE») — начало сезона, продолжением не считается.
     */
    fun isContinuation(vararg titles: String?): Boolean =
        titles.asSequence().filterNotNull().any { title ->
            val part = PART_N.find(title)?.groupValues?.get(1)?.let(::parseNumber)
                ?: NTH_PART.find(title)?.groupValues?.get(1)?.toIntOrNull()
            part != null && part >= 2
        }

    private fun parseNumber(raw: String): Int? = raw.toIntOrNull() ?: ROMAN[raw.uppercase()]

    private val ROMAN = mapOf("I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10)
    private val ORDINAL_WORDS = mapOf(
        "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10,
    )

    // Фигурные скобки не нужны нигде — на Android литеральная «}» в ICU-регэкспе роняет разбор.
    private val SEASON_N = Regex("""(?i)\bseason\s+(\d+|[ivx]+)\b""")
    private val NTH_SEASON = Regex("""(?i)\b(\d+)(?:st|nd|rd|th)\s+season\b""")
    private val WORD_SEASON = Regex("""(?i)\b(second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\s+season\b""")
    private val PART_N = Regex("""(?i)\b(?:part|cour|stage)\s+(\d+|[ivx]+)\b""")
    private val NTH_PART = Regex("""(?i)\b(\d+)(?:st|nd|rd|th)\b(?:\s*[-–&]\s*\d+(?:st|nd|rd|th))?\s+(?:part|cour|stage)\b""")
}
