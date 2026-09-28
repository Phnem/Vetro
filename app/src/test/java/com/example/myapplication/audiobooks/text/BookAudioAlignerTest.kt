package com.example.myapplication.audiobooks.text

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookAudioAlignerTest {

    // ---------- Синтетическая книга ----------

    private val syllables = listOf("ка", "ло", "ми", "ра", "то", "не", "су", "ве", "да", "по", "ри", "ша", "зо", "ле", "ну", "бо", "ти", "га", "ме", "хо")

    private fun vocabulary(seed: Int, size: Int): List<String> {
        val r = Random(seed)
        return List(size) { (0 until r.nextInt(2, 5)).joinToString("") { syllables[r.nextInt(syllables.size)] } }.distinct()
    }

    private fun bookText(seed: Int, sentences: Int, vocab: List<String>): String {
        val r = Random(seed)
        val sb = StringBuilder()
        repeat(sentences) { i ->
            val words = List(r.nextInt(6, 16)) { vocab[r.nextInt(vocab.size)] }
            sb.append(words.joinToString(" ").replaceFirstChar { it.uppercase() }).append(". ")
            if (i % 5 == 4) sb.append("\n\n")
        }
        return sb.toString()
    }

    /** «Распознавание» куска книги: слова по 400 мс, начиная с [startMs]. */
    private fun speak(text: String, startMs: Long, errors: Double = 0.0, seed: Int = 1): List<AsrWord> {
        val r = Random(seed)
        return MatchText.tokens(text, asr = true).mapIndexed { i, t ->
            val key = if (r.nextDouble() < errors) "шум${r.nextInt(1000)}" else t.key
            AsrWord(key, startMs + i * 400L, startMs + i * 400L + 350)
        }
    }

    private val vocab = vocabulary(7, 2500)
    private val display = bookText(11, 600, vocab)
    private val book = BookText(display)
    private val aligner = BookAudioAligner(book)

    private fun sentenceText(i: Int) = display.substring(book.sentences[i].start, book.sentences[i].end)

    private fun speakSentences(range: IntRange, startMs: Long, errors: Double = 0.0): List<AsrWord> =
        speak(range.joinToString(" ") { sentenceText(it) }, startMs, errors)

    // ---------- Кейсы ----------

    @Test
    fun `case 1 - same edition, cues start where the sentence is spoken`() {
        val words = speakSentences(0..40, 0)
        val result = aligner.align(words, expectedToken = 0, blockEndMs = words.last().endMs)
        assertTrue(result.matchRatio > 0.9f)
        val shown = result.cues.filter { it.confidence >= BookAudioAligner.SHOW_THRESHOLD }
        assertTrue(shown.size >= 39)
        // Начало реплики — время первого слова предложения.
        var t = 0L
        for (s in 0..40) {
            val cue = result.cues.firstOrNull { it.sentence == s }
            if (cue != null) assertEquals("sentence $s", t, cue.startMs)
            t += MatchText.tokens(sentenceText(s)).size * 400L
        }
        assertEquals(sentenceText(5), result.cues.first { it.sentence == 5 }.text(book))
    }

    @Test
    fun `case 2 - skipped sentences are not shown, the rest stays in sync`() {
        val spoken = (100..140).filter { it != 110 && it != 111 && it != 125 }
        val words = speak(spoken.joinToString(" ") { sentenceText(it) }, 3_600_000)
        val expected = book.tokens.starts.indexOfFirst { it >= book.sentences[100].start }
        val result = aligner.align(words, expected, words.last().endMs)
        val shown = result.cues.filter { it.confidence >= BookAudioAligner.SHOW_THRESHOLD }.map { it.sentence }.toSet()
        assertFalse(110 in shown)
        assertFalse(125 in shown)
        assertTrue(shown.containsAll((126..139).toList()))
    }

    @Test
    fun `case 3 - numbers as digits in the book and as words in the speech`() {
        val text = BookText("Глава III. В 1895 году он купил 21 книгу и дом номер 6. Потом прошло ещё 3 года.")
        val speech = MatchText.tokens("глава третья в тысяча восемьсот девяносто пятом году он купил двадцать одну книгу и дом номер шесть потом прошло ещё три года", asr = true)
        val bookKeys = text.tokens.keys.toList()
        // Большая часть слов совпадает по ключам.
        val common = speech.map { it.key }.count { it in bookKeys }
        assertTrue("common=$common of ${speech.size}", common >= speech.size - 4)
        assertEquals(listOf("глава", "три"), bookKeys.take(2))
    }

    @Test
    fun `case 4 - a narrator intro before the text is skipped`() {
        val intro = speak(bookText(99, 4, vocabulary(99, 300)), 0)
        val body = speakSentences(0..30, intro.last().endMs + 1000)
        val result = aligner.align(intro + body, expectedToken = 0, blockEndMs = body.last().endMs)
        val first = result.cues.filter { it.confidence >= BookAudioAligner.SHOW_THRESHOLD }.minByOrNull { it.startMs }!!
        assertEquals(0, first.sentence)
        assertTrue(first.startMs >= body.first().startMs)
    }

    @Test
    fun `case 5 - another translation does not align`() {
        val other = BookText(bookText(12345, 60, vocab))
        val words = speak(other.display.take(3000), 0)
        val result = aligner.align(words, expectedToken = 0, blockEndMs = words.last().endMs)
        assertTrue("ratio=${result.matchRatio}", result.matchRatio < BookAudioAligner.MIN_RATIO)
        assertTrue(result.cues.isEmpty())
    }

    @Test
    fun `recognition errors on every tenth word keep the alignment`() {
        val words = speakSentences(200..240, 0, errors = 0.1)
        val result = aligner.align(words, book.tokens.starts.indexOfFirst { it >= book.sentences[200].start }, words.last().endMs)
        val shown = result.cues.filter { it.confidence >= BookAudioAligner.SHOW_THRESHOLD }.map { it.sentence }
        assertTrue(shown.size >= 35)
        assertTrue(shown.all { it in 199..241 })
    }

    @Test
    fun `case 7 - a far seek resyncs from a wrong expected position`() {
        val words = speakSentences(450..470, 30_000_000)
        val result = aligner.align(words, expectedToken = 100, blockEndMs = words.last().endMs)
        assertTrue(result.resynced)
        assertTrue(result.cues.filter { it.confidence >= BookAudioAligner.SHOW_THRESHOLD }.map { it.sentence }.containsAll((452..468).toList()))
    }

    @Test
    fun `expected position follows known anchors, not an average reading rate`() {
        val anchors = listOf(TextAnchor(0, 0), TextAnchor(100_000, 500))
        assertEquals(250, aligner.expectedToken(50_000, anchors, null))
        // Дальше последнего якоря — по темпу этой записи: 5 слов в секунду.
        assertEquals(750, aligner.expectedToken(150_000, anchors, null))
        assertNull(aligner.expectedToken(1000, emptyList(), null))
    }

    // ---------- Сегментация ----------

    @Test
    fun `sentences respect initials, abbreviations and short replies`() {
        val text = "А. П. Чехов написал рассказ в 1899 г. и т. д. Это было давно! — Да. — Нет, не так… Потом он ушёл."
        val parts = BookText(text).sentences.map { text.substring(it.start, it.end) }
        assertEquals("А. П. Чехов написал рассказ в 1899 г. и т. д. Это было давно!", parts[0])
        assertTrue(parts.last().endsWith("Потом он ушёл."))
    }

    @Test
    fun `long sentences are cut at natural pauses`() {
        val long = List(40) { "слово$it" }.joinToString(" ") + "; " + List(40) { "другое$it" }.joinToString(" ") + "."
        val parts = BookText(long).sentences
        assertTrue(parts.size >= 2)
        assertTrue(parts.all { it.end - it.start <= 240 })
    }

    @Test
    fun `display text is never changed`() {
        val text = "Глава III. Сол №6 — «ёлка»."
        val book = BookText(text)
        assertEquals(text, book.display)
        assertEquals(listOf("глава", "три", "сол", "номер", "шесть", "елка"), book.tokens.keys.toList())
    }

    @Test
    fun `sentences between two shown cues are filled in at a plausible pace, not across a long gap`() {
        val text = BookText("Первое предложение здесь. Второе предложение тоже. Третье предложение есть. Четвёртое предложение.")
        fun cue(s: Int, start: Long, end: Long, c: Float = 0.9f) =
            AlignedCue(start, end, text.sentences[s].start, text.sentences[s].end, s, c)
        val filled = BookAudioAligner.fillGaps(listOf(cue(0, 0, 2_000), cue(3, 6_000, 8_000)), text)
        assertEquals(listOf(0, 1, 2, 3), filled.map { it.sentence })
        assertEquals(2_000L, filled[1].startMs)
        assertEquals(6_000L, filled[2].endMs, 5)
        // Минута тишины на две короткие фразы — это не чтение, не заполняем.
        val gap = BookAudioAligner.fillGaps(listOf(cue(0, 0, 2_000), cue(3, 62_000, 64_000)), text)
        assertEquals(listOf(0, 3), gap.map { it.sentence })
    }

    private fun assertEquals(expected: Long, actual: Long, delta: Long) = assertTrue("$expected vs $actual", kotlin.math.abs(expected - actual) <= delta)
}
