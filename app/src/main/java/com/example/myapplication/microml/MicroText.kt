package com.example.myapplication.microml

import java.text.Normalizer
import java.util.Locale

/**
 * text-spec-v1 — единый текстовый контракт всех моделей MicroML.
 * Зеркало tools/microml/vml/text.py, функция в функцию: любое расхождение ломает паритет с обучением,
 * поэтому правки здесь возможны только вместе с новой версией спецификации и переобучением.
 */
object MicroText {
    const val SPEC = "text-spec-v1"

    data class Markers(val sequence: Set<Int>, val variants: Set<String>, val years: Set<Int>)

    fun normalize(text: String): String {
        val value = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val out = StringBuilder(value.length)
        var space = false
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isLetter(cp) || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()) {
                if (space && out.isNotEmpty()) out.append(' ')
                if (cp == 'ё'.code) out.append('е') else out.appendCodePoint(cp)
                space = false
            } else space = true
        }
        return out.toString()
    }

    fun key(text: String): String = normalize(text).replace(" ", "")

    /** 0 латиница, 1 кириллица, 2 кана/иероглифы, 3 хангыль, 4 прочее. */
    fun scriptOf(cp: Int): Int = when {
        cp < 0x250 || cp in 0x1E00 until 0x1F00 -> 0
        cp in 0x400 until 0x530 -> 1
        cp in 0x3040 until 0x3100 || cp in 0x3400 until 0x4DC0 || cp in 0x4E00 until 0xA000 ||
            cp in 0xF900 until 0xFB00 || cp in 0x31F0 until 0x3200 -> 2
        cp in 0xAC00 until 0xD7B0 || cp in 0x1100 until 0x1200 || cp in 0x3130 until 0x3190 -> 3
        else -> 4
    }

    /** Преобладающая письменность нормализованной строки; -1, если букв нет. */
    fun script(norm: String): Int {
        val counts = IntArray(5)
        var i = 0
        while (i < norm.length) {
            val cp = norm.codePointAt(i)
            i += Character.charCount(cp)
            if (cp != ' '.code && cp !in '0'.code..'9'.code) counts[scriptOf(cp)]++
        }
        var best = 0
        for (k in 1 until 5) if (counts[k] > counts[best]) best = k
        return if (counts[best] > 0) best else -1
    }

    private val cyrillic: Map<Int, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s",
        'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g", 'ў' to "u", 'ј' to "j", 'љ' to "lj", 'њ' to "nj",
        'ћ' to "c", 'ђ' to "dj", 'џ' to "dz", 'ѕ' to "dz", 'қ' to "k", 'ғ' to "g", 'ү' to "u", 'ұ' to "u",
        'ә' to "a", 'ө' to "o", 'һ' to "h", 'ң' to "n",
    ).mapKeys { it.key.code }

    private val kana: Map<Int, String> = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'ゐ' to "i", 'ゑ' to "e", 'を' to "o", 'ん' to "n",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        'ゔ' to "vu",
        'ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o",
    ).mapKeys { it.key.code }
    private val smallY: Map<Int, String> = mapOf('ゃ' to "a", 'ゅ' to "u", 'ょ' to "o").mapKeys { it.key.code }
    private val smallVowel: Map<Int, String> =
        mapOf('ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o").mapKeys { it.key.code }
    private val hangulLead = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val hangulVowel = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val hangulTail = arrayOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
    private val latinExtra: Map<Int, String> = mapOf(
        'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'đ' to "d", 'ð' to "d", 'þ' to "th", 'ł' to "l", 'ı' to "i", 'ħ' to "h",
    ).mapKeys { it.key.code }

    private const val SOKUON = 0x3063 // っ
    private const val CHOON = 0x30FC // ー

    /** Свёртка нормализованной строки в латиницу; иероглифы проходят как есть. */
    fun latin(norm: String): String {
        val chars = norm.codePoints().map { if (it in 0x30A1..0x30F6) it - 0x60 else it }.toArray()
        val out = StringBuilder(norm.length + 8)
        var geminate = false
        var i = 0
        while (i < chars.size) {
            val cp = chars[i]
            var piece: String? = null
            val cyr = cyrillic[cp]
            val row = kana[cp]
            if (cyr != null) {
                piece = cyr
            } else if (cp == SOKUON) {
                geminate = true; i++; continue
            } else if (cp == CHOON) {
                i++; continue
            } else if (row != null) {
                piece = row
                val next = if (i + 1 < chars.size) chars[i + 1] else -1
                val y = smallY[next]
                val v = smallVowel[next]
                if (y != null && row.endsWith("i") && row.length >= 2) {
                    val stem = row.dropLast(1)
                    piece = (if (stem == "sh" || stem == "ch" || stem == "j") stem else stem + "y") + y
                    i++
                } else if (v != null && (row.last() == 'u' || row.last() == 'e') && row.length >= 2) {
                    piece = row.dropLast(1) + v
                    i++
                }
            } else if (smallY[cp] != null) {
                piece = "y" + smallY[cp]
            } else if (cp in 0xAC00..0xD7A3) {
                val s = cp - 0xAC00
                piece = hangulLead[s / 588] + hangulVowel[(s % 588) / 28] + hangulTail[s % 28]
            } else if (latinExtra[cp] != null) {
                piece = latinExtra[cp]
            } else if (cp >= 0x80 && scriptOf(cp) == 0) {
                val base = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
                piece = base.filter { it in 'a'..'z' || it in '0'..'9' }
            }
            val text = piece ?: String(Character.toChars(cp))
            if (geminate && text.isNotEmpty() && text[0] !in "aeiou") out.append(text[0])
            geminate = false
            out.append(text)
            i++
        }
        return out.toString()
    }

    private val softC = Regex("c(?=[eiy])")
    private val skeletonRules = listOf(
        "shch" to "s", "sch" to "s", "dzh" to "z", "tch" to "t", "sh" to "s", "zh" to "z", "kh" to "h",
        "ch" to "t", "ts" to "t", "tz" to "t", "dz" to "z", "dj" to "z", "ph" to "f", "th" to "t",
        "ck" to "k", "qu" to "kv", "gh" to "g", "x" to "ks", "q" to "k", "w" to "v", "j" to "z",
        "ou" to "o", "oo" to "u", "ee" to "i", "ya" to "ia", "yu" to "iu", "yo" to "io", "ye" to "ie", "y" to "i",
    )

    /** Грубый фонетический ключ: Хепбёрн, Поливанов и обычная латиница сходятся в одну запись. */
    fun skeleton(norm: String): String {
        var s = softC.replace(latin(norm), "s")
        for ((from, to) in skeletonRules) s = s.replace(from, to)
        s = s.replace("c", "k")
        val out = StringBuilder(s.length)
        var i = 0
        var previous = -1
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (previous != cp || cp == ' '.code || cp in '0'.code..'9'.code) out.appendCodePoint(cp)
            previous = cp
        }
        return out.toString()
    }

    private fun mix(hash: Int): Int {
        var h = hash
        h = h xor (h ushr 15)
        h *= 0x2C1B3C6D
        h = h xor (h ushr 12)
        return h
    }

    private fun feedByte(h: Int, b: Int): Int = (h xor (b and 0xFF)) * 0x01000193

    private fun feed(hash: Int, cp: Int): Int {
        var h = hash
        when {
            cp < 0x80 -> h = feedByte(h, cp)
            cp < 0x800 -> { h = feedByte(h, 0xC0 or (cp shr 6)); h = feedByte(h, 0x80 or (cp and 0x3F)) }
            cp < 0x10000 -> {
                h = feedByte(h, 0xE0 or (cp shr 12)); h = feedByte(h, 0x80 or ((cp shr 6) and 0x3F)); h = feedByte(h, 0x80 or (cp and 0x3F))
            }
            else -> {
                h = feedByte(h, 0xF0 or (cp shr 18)); h = feedByte(h, 0x80 or ((cp shr 12) and 0x3F))
                h = feedByte(h, 0x80 or ((cp shr 6) and 0x3F)); h = feedByte(h, 0x80 or (cp and 0x3F))
            }
        }
        return h
    }

    private const val FNV_SEED = 0x811C9DC5.toInt()
    private const val OPEN = '<'.code
    private const val CLOSE = '>'.code

    /**
     * Отсортированные уникальные номера корзин n-грамм строки. [buckets] — степень двойки.
     * Пространства: 'o' — исходная письменность, 's' — фонетический скелет, 'u'/'b' — знаки и пары CJK.
     */
    fun bucketIds(norm: String, buckets: Int): IntArray {
        val mask = buckets - 1
        val seen = HashSet<Int>(128)
        fun grams(space: Int, form: String, original: Boolean) {
            for (word in form.split(' ')) {
                if (word.isEmpty()) continue
                val cps = word.codePoints().toArray()
                val padded = IntArray(cps.size + 2)
                padded[0] = OPEN; System.arraycopy(cps, 0, padded, 1, cps.size); padded[padded.size - 1] = CLOSE
                var whole = feedByte(FNV_SEED, space)
                for (cp in padded) whole = feed(whole, cp)
                seen.add(mix(whole) and mask)
                for (n in 3..4) for (start in 0..padded.size - n) {
                    var h = feedByte(FNV_SEED, space)
                    for (k in start until start + n) h = feed(h, padded[k])
                    seen.add(mix(h) and mask)
                }
                if (original) for (index in cps.indices) if (scriptOf(cps[index]) >= 2) {
                    seen.add(mix(feed(feedByte(FNV_SEED, 'u'.code), cps[index])) and mask)
                    if (index + 1 < cps.size) {
                        seen.add(mix(feed(feed(feedByte(FNV_SEED, 'b'.code), cps[index]), cps[index + 1])) and mask)
                    }
                }
            }
        }
        grams('o'.code, norm, true)
        grams('s'.code, skeleton(norm), false)
        return seen.toIntArray().also { it.sort() }
    }

    private val roman = mapOf("ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9,
        "x" to 10, "xi" to 11, "xii" to 12, "xiii" to 13)
    private val ordinal = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10, "1st" to 1, "2nd" to 2, "3rd" to 3, "4th" to 4,
        "5th" to 5, "6th" to 6, "7th" to 7, "8th" to 8, "9th" to 9, "10th" to 10,
        "первый" to 1, "второй" to 2, "третий" to 3, "четвертый" to 4, "пятый" to 5, "шестой" to 6,
        "первая" to 1, "вторая" to 2, "третья" to 3, "четвертая" to 4, "пятая" to 5, "шестая" to 6,
        "二" to 2, "三" to 3, "四" to 4, "五" to 5, "弐" to 2)
    private val variant = mapOf("movie" to "movie", "film" to "movie", "фильм" to "movie", "gekijouban" to "movie",
        "gekijoban" to "movie", "劇場版" to "movie", "ova" to "ova", "oav" to "ova", "oad" to "ova", "ona" to "ona",
        "special" to "special", "specials" to "special", "спешл" to "special", "спецвыпуск" to "special",
        "recap" to "recap", "рекап" to "recap", "summary" to "recap", "総集編" to "recap",
        "remake" to "remake", "ремейк" to "remake", "reboot" to "remake", "shippuden" to "shippuden",
        "brotherhood" to "brotherhood", "kai" to "kai", "final" to "final", "финал" to "final",
        "zero" to "zero", "prequel" to "prequel", "sequel" to "sequel", "gaiden" to "gaiden",
        "spinoff" to "gaiden", "drama" to "drama", "manga" to "manga", "manhwa" to "manga",
        "novel" to "novel", "ранобэ" to "novel", "манга" to "manga", "audiobook" to "audio",
        "аудиокнига" to "audio", "uncut" to "cut", "extended" to "cut", "us" to "region_us", "uk" to "region_uk")
    private val sequenceWord = setOf("season", "сезон", "part", "часть", "cour", "vol", "volume", "том", "book", "книга",
        "chapter", "глава", "episode", "серия", "эпизод", "act", "phase", "series", "期", "第", "部",
        "章", "巻", "s", "ep", "pt", "кн", "ч", "т")
    // Только [0-9]: у ICU на Android и у JVM разное понимание \d.
    private val glued = Regex("(?:s|season|сезон|part|pt|vol|v|том|ч|кн|ep|e|第)([0-9]{1,3})(?:期|部|章|巻|話)?")
    private val suffixed = Regex("([0-9]{1,3})(?:期|部|章|巻|話|nd|rd|th|st|й|я|е|ой|ая)")

    /** Номера частей/сезонов, метки варианта (movie, ova, remake…) и годы, найденные в названии. */
    fun markers(text: String): Markers {
        val norm = normalize(text)
        val words = if (norm.isEmpty()) emptyList() else norm.split(' ')
        val sequence = HashSet<Int>(); val variants = HashSet<String>(); val years = HashSet<Int>()
        for ((i, w) in words.withIndex()) {
            val previous = if (i > 0) words[i - 1] else ""
            val next = if (i + 1 < words.size) words[i + 1] else ""
            variant[w]?.let(variants::add)
            if (w.isNotEmpty() && w.all { it in '0'..'9' }) {
                val n = w.take(9).toInt()
                if (w.length == 4 && n in 1900..2099) years.add(n) else if (n < 1000) sequence.add(n)
            } else if (roman[w] != null && i > 0) {
                sequence.add(roman.getValue(w))
            } else if (ordinal[w] != null && (previous in sequenceWord || next in sequenceWord)) {
                sequence.add(ordinal.getValue(w))
            } else {
                (glued.matchEntire(w) ?: suffixed.matchEntire(w))?.let { sequence.add(it.groupValues[1].toInt()) }
            }
        }
        return Markers(sequence, variants, years)
    }
}
