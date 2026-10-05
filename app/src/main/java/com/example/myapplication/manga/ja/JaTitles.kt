package com.example.myapplication.manga.ja

import java.text.Normalizer

/**
 * Сопоставление названий для японских источников. Автоматически привязываем только при ТОЧНОМ
 * совпадении: подсунутая не та манга хуже, чем отсутствие японского продолжения.
 */
object JaTitles {

    private val NOISE = Regex("[\\p{P}\\p{S}\\p{Z}\\s\\u3000]+")
    private val TRAILING_BRACKETS = Regex("[\\(（\\[【][^\\)）\\]】]*[\\)）\\]】]\\s*$")

    /**
     * NFKC (полуширинные/полноширинные формы, ｶﾅ → カナ), нижний регистр, без знаков и пробелов,
     * хирагана приведена к катакане: «ばくまん» и «バクマン» - одно название.
     */
    fun normalize(title: String): String {
        val nfkc = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase()
        val noise = NOISE.replace(nfkc, "")
        return buildString(noise.length) {
            for (c in noise) append(if (c in 'ぁ'..'ゖ') (c.code + 0x60).toChar() else c)
        }
    }

    /** Название без хвостовой пометки вроде «（原作）» или «【単行本】». */
    fun stripTrailingNote(title: String): String =
        TRAILING_BRACKETS.replace(title.trim(), "").trim()

    /** Одно и то же название; допускаем хвост в скобках у любой из сторон. */
    fun same(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        return normalize(stripTrailingNote(a)) == normalize(stripTrailingNote(b))
    }

    /** Совпадение хотя бы по одному из названий-кандидатов. */
    fun matchesAny(title: String, candidates: Collection<String>): Boolean =
        candidates.any { same(title, it) }

    /** Автор в выдаче источника ("原泰久", "原作／あーもんど 漫画／かしい葵") содержит имя автора из метаданных. */
    fun authorMatches(sourceAuthors: String?, knownAuthors: Collection<String>): Boolean {
        if (sourceAuthors.isNullOrBlank() || knownAuthors.isEmpty()) return true // нечем возразить
        val haystack = normalize(sourceAuthors)
        return knownAuthors.any { name ->
            val n = normalize(name)
            n.isNotEmpty() && haystack.contains(n)
        }
    }

    /** Главы публикуются как "第35話", "#12", "Chapter 7", "12.5話"; возвращает номер или null. */
    fun chapterNumber(title: String): Double? {
        val t = Normalizer.normalize(title, Normalizer.Form.NFKC)
        NUMBER_PATTERNS.forEach { pattern ->
            pattern.find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private val NUMBER_PATTERNS = listOf(
        Regex("第\\s*(\\d+(?:\\.\\d+)?)\\s*[話话回章]"),
        Regex("#\\s*(\\d+(?:\\.\\d+)?)"),
        Regex("(?i)(?:chapter|ch\\.?|episode|ep\\.?)\\s*(\\d+(?:\\.\\d+)?)"),
        // "[160話]название", "12話": число прямо перед 話, без «第».
        Regex("(\\d+(?:\\.\\d+)?)\\s*話"),
        Regex("\\[\\s*(\\d+(?:\\.\\d+)?)\\s*\\]"),
    )
}
