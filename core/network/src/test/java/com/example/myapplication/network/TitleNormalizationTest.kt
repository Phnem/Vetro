package com.example.myapplication.network

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleNormalizationTest {

    @Test
    fun `words collapse punctuation and whitespace runs`() {
        assertEquals("shingeki no kyojin 2", normalizeTitleWords("  Shingeki  no Kyojin: 2!! "))
        assertEquals("атака титанов", normalizeTitleWords("Атака\tТитанов…"))
        assertEquals("", normalizeTitleWords(" — "))
    }

    @Test
    fun `key keeps only letters and digits`() {
        assertEquals("shingekinokyojin2", titleKey("Shingeki no Kyojin: 2"))
        assertEquals("rezero", titleKey("Re:Zero"))
        assertEquals("атакатитанов", titleKey("Атака титанов"))
    }

    @Test
    fun `yo and ye are the same letter for matching`() {
        assertEquals(normalizeTitleWords("Чёрный клевер"), normalizeTitleWords("Черный клевер"))
        assertEquals(titleKey("Задача трёх тел"), titleKey("Задача трех тел"))
        assertEquals(true, isTitleSimilar("Черный клевер", "Чёрный клевер"))
    }
}
