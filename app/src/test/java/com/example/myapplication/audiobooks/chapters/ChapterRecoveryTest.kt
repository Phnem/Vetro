package com.example.myapplication.audiobooks.chapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterRecoveryTest {

    // ---------- Паузы ----------

    /** Огибающая: речь ~70 (−30 дБ) с короткими паузами, тишина ~10 (−90 дБ) заданной длины. */
    private fun envelope(vararg parts: Pair<Boolean, Long>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var k = 0
        for ((speech, ms) in parts) {
            repeat((ms / SilenceDetector.FRAME_MS).toInt()) { out.write(if (speech) 65 + (k++ % 10) else 8 + (k++ % 3)) }
        }
        return out.toByteArray()
    }

    @Test
    fun `short phrase pauses are ignored, a long pause is found with its bounds`() {
        val env = envelope(true to 10_000, false to 400, true to 10_000, false to 3_000, true to 10_000)
        val gaps = SilenceDetector.gaps(env)
        assertEquals(1, gaps.size)
        assertEquals(20_400L, gaps[0].startMs)
        assertEquals(23_400L, gaps[0].endMs)
    }

    @Test
    fun `offset moves gaps onto the book clock`() {
        val gaps = SilenceDetector.gaps(envelope(true to 5_000, false to 2_000, true to 5_000), offsetMs = 3_600_000)
        assertEquals(3_605_000L, gaps.single().startMs)
    }

    // ---------- Заголовки ----------

    @Test
    fun `russian headings in words, digits and roman numerals`() {
        assertEquals("Глава 3", HeadingParser.parse("Глава третья. Возвращение домой")?.title)
        assertEquals("Глава 23", HeadingParser.parse("глава двадцать третья")?.title)
        assertEquals("Глава 15", HeadingParser.parse("Глава пятнадцатая")?.title)
        assertEquals("Глава 50", HeadingParser.parse("Глава пятидесятая")?.title)
        assertEquals("Глава 7", HeadingParser.parse("Глава 7.")?.title)
        assertEquals("Часть 2. Глава 1", HeadingParser.parse("Часть вторая. Глава первая.")?.title)
        assertEquals("Глава 14", HeadingParser.parse("Глава XIV")?.title)
        assertEquals("Пролог", HeadingParser.parse("Пролог. Тот, кто ждал")?.title)
        assertEquals(3, HeadingParser.parse("Глава третья")?.number)
    }

    @Test
    fun `english headings`() {
        assertEquals("Chapter 12", HeadingParser.parse("Chapter Twelve. The Return")?.title)
        assertEquals("Chapter 21", HeadingParser.parse("Chapter twenty-one")?.title)
        assertEquals("Chapter 3", HeadingParser.parse("Chapter the... no. Chapter 3.")?.title)
        assertEquals("Epilogue", HeadingParser.parse("Epilogue")?.title)
        assertEquals("Part 1", HeadingParser.parse("Part One")?.title)
    }

    @Test
    fun `a chapter word deep inside a sentence is not a heading`() {
        assertNull(HeadingParser.parse("Он открыл книгу и долго искал ту самую главу третью где"))
        assertNull(HeadingParser.parse("Глава семьи сказал"))
        assertNull(HeadingParser.parse("She read the part of the letter"))
    }

    // ---------- Предложение ----------

    private fun gap(atMin: Double, lengthMs: Long): SilenceGap {
        val start = (atMin * 60_000).toLong()
        return SilenceGap(start, start + lengthMs)
    }

    @Test
    fun `without speech the longest pauses become chapters spaced by the minimum length`() {
        val total = 60 * 60_000L
        val gaps = buildList {
            // Паузы между фразами и абзацами — каждые полминуты, до секунды.
            var t = 0.5
            while (t < 60) { add(gap(t, 1_000)); t += 0.5 }
            // Паузы между главами: 3–4 секунды; одна — слишком близко к соседней.
            add(gap(12.2, 3_500)); add(gap(25.1, 4_000)); add(gap(26.3, 3_200)); add(gap(41.7, 3_800))
        }
        val chapters = ChapterProposer.propose(total, gaps, emptyMap())
        assertEquals(listOf(0L, 12.2, 25.1, 41.7).size, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(listOf("Глава 1", "Глава 2", "Глава 3", "Глава 4"), chapters.map { it.title })
        assertTrue(chapters.drop(1).none { it.confirmed })
    }

    @Test
    fun `heard headings win - short chapters allowed, titles from the speech`() {
        val total = 60 * 60_000L
        val g1 = gap(2.0, 1_900)
        val g2 = gap(4.5, 2_000)
        val g3 = gap(30.0, 1_600)
        val headings = mapOf(
            g1 to Heading("Глава 1", 1),
            g2 to Heading("Глава 2", 2),
            g3 to Heading("Глава 3", 3),
        )
        val chapters = ChapterProposer.propose(total, listOf(g1, g2, g3, gap(15.0, 5_000)), headings, opening = Heading("Пролог", null))
        assertEquals(listOf("Пролог", "Глава 1", "Глава 2", "Глава 3"), chapters.map { it.title })
        assertTrue(chapters.all { it.confirmed })
        assertEquals(ChapterProposer.boundaryOf(g2), chapters[2].startMs)
    }

    @Test
    fun `file boundaries are kept as chapter starts`() {
        val chapters = ChapterProposer.propose(40 * 60_000L, emptyList<SilenceGap>() + gap(10.0, 900), emptyMap(), fixedStarts = listOf(20 * 60_000L))
        assertEquals(listOf(0L, 20 * 60_000L), chapters.map { it.startMs })
    }

    @Test
    fun `a short recording gets nothing`() {
        assertTrue(ChapterProposer.propose(5 * 60_000L, listOf(gap(2.0, 5_000)), emptyMap()).isEmpty())
    }

    @Test
    fun `candidates skip the edges and keep the longest`() {
        val total = 30 * 60_000L
        val gaps = listOf(gap(0.5, 5_000), gap(10.0, 3_000), gap(20.0, 900), gap(20.5, 900), gap(29.5, 6_000), gap(15.0, 1_000)) +
            (1..8).map { gap(it * 3.0 + 1, 900) }
        val candidates = ChapterProposer.candidates(gaps, total)
        assertEquals(listOf(gap(10.0, 3_000)), candidates)
    }

    // ---------- CUE и перевод в главы ----------

    @Test
    fun `cue sheet tracks become chapters`() {
        val cue = """
            PERFORMER "Чтец"
            TITLE "Книга"
            FILE "book.mp3" MP3
              TRACK 01 AUDIO
                TITLE "Пролог"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Глава 1"
                INDEX 00 12:29:00
                INDEX 01 12:30:37
        """.trimIndent()
        val chapters = CueSheet.parse(cue)
        assertEquals(listOf("Пролог", "Глава 1"), chapters.map { it.title })
        assertEquals(12 * 60_000L + 30_000L + 37 * 1000L / 75, chapters[1].startMs)
    }

    @Test
    fun `durations run to the next chapter and to the end of the book`() {
        val chapters = RecoveredChapterStore.toChapters(
            listOf(RecoveredChapter(600_000, "Глава 2", false), RecoveredChapter(0, "Глава 1", true)),
            totalMs = 1_000_000,
        )
        assertEquals(listOf(0, 1), chapters.map { it.index })
        assertEquals(listOf(600_000L, 400_000L), chapters.map { it.durationMs })
    }
}
