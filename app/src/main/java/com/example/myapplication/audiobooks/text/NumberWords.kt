package com.example.myapplication.audiobooks.text

/**
 * Число словами для сравнения текста с речью: в книге «1895», диктор читает «тысяча восемьсот
 * девяносто пятого», распознавание пишет то цифрами, то словами. Именительный падеж — остальное
 * добирает сравнение по основе слова ([MatchText.STEM]).
 */
object NumberWords {
    private val ruUnits = listOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val ruTeens = listOf(
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать",
        "семнадцать", "восемнадцать", "девятнадцать",
    )
    private val ruTens = listOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val ruHundreds = listOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")

    private val enUnits = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val enTeens = listOf(
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val enTens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** Слова числа; длинные коды и числа с ведущим нулём — по цифре. */
    fun spell(digits: String, russian: Boolean): List<String> {
        if (digits.isEmpty()) return emptyList()
        if (digits.length > 12 || (digits.length > 1 && digits[0] == '0')) {
            return digits.map { d -> (if (russian) ruUnits else enUnits)[d - '0'] }
        }
        val n = digits.toLong()
        if (n == 0L) return listOf(if (russian) "ноль" else "zero")
        return if (russian) ru(n) else en(n)
    }

    private fun ru(n: Long): List<String> {
        val out = ArrayList<String>()
        val billions = n / 1_000_000_000
        val millions = n / 1_000_000 % 1000
        val thousands = n / 1000 % 1000
        val rest = n % 1000
        if (billions > 0) { out += ruTriple(billions, false); out += ruPlural(billions, "миллиард", "миллиарда", "миллиардов") }
        if (millions > 0) { out += ruTriple(millions, false); out += ruPlural(millions, "миллион", "миллиона", "миллионов") }
        if (thousands > 0) {
            // «одна тысяча» обычно говорят просто «тысяча».
            if (thousands != 1L) out += ruTriple(thousands, true)
            out += ruPlural(thousands, "тысяча", "тысячи", "тысяч")
        }
        if (rest > 0) out += ruTriple(rest, false)
        return out
    }

    private fun ruTriple(n: Long, feminine: Boolean): List<String> {
        val out = ArrayList<String>()
        val h = (n / 100).toInt()
        val t = (n / 10 % 10).toInt()
        val u = (n % 10).toInt()
        if (h > 0) out += ruHundreds[h]
        when {
            t == 1 -> out += ruTeens[u]
            else -> {
                if (t > 1) out += ruTens[t]
                if (u > 0) out += when {
                    feminine && u == 1 -> "одна"
                    feminine && u == 2 -> "две"
                    else -> ruUnits[u]
                }
            }
        }
        return out
    }

    private fun ruPlural(n: Long, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1L -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    private fun en(n: Long): List<String> {
        // Годы читают парами: «1895» — «eighteen ninety-five».
        if (n in 1100..1999 && n % 100 != 0L) return enBelow100(n / 100) + enBelow100(n % 100)
        val out = ArrayList<String>()
        val scales = listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1000L to "thousand")
        var rest = n
        for ((value, name) in scales) {
            if (rest >= value) { out += enBelow1000(rest / value); out += name; rest %= value }
        }
        if (rest > 0) out += enBelow1000(rest)
        return out
    }

    private fun enBelow1000(n: Long): List<String> {
        val out = ArrayList<String>()
        if (n >= 100) { out += enUnits[(n / 100).toInt()]; out += "hundred" }
        if (n % 100 > 0) out += enBelow100(n % 100)
        return out
    }

    private fun enBelow100(n: Long): List<String> = when {
        n < 10 -> listOf(enUnits[n.toInt()])
        n < 20 -> listOf(enTeens[(n - 10).toInt()])
        n % 10 == 0L -> listOf(enTens[(n / 10).toInt()])
        else -> listOf(enTens[(n / 10).toInt()], enUnits[(n % 10).toInt()])
    }
}
