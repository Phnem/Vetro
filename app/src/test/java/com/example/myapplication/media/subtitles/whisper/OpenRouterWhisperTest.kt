package com.example.myapplication.media.subtitles.whisper

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouterWhisperTest {

    @Test
    fun `segments become cues in milliseconds, language name becomes a code`() {
        val body = """{"language":"english","duration":6.4,"text":"Hello there. Hi.",
            "segments":[{"id":0,"start":0.0,"end":1.2,"text":" Hello there. "},{"id":1,"start":1.5,"end":2.25,"text":"Hi."}]}"""
        val t = OpenRouterWhisper.parse(body, words = false, lengthMs = 6_400)
        assertEquals("en", t.language)
        assertEquals(listOf(SubtitleCue(0, 1_200, "Hello there."), SubtitleCue(1_500, 2_250, "Hi.")), t.cues)
    }

    @Test
    fun `words are used when asked for, segments when the provider has no words`() {
        val withWords = """{"language":"ru","text":"Глава первая","words":[{"word":"Глава","start":0.1,"end":0.5},{"word":"первая","start":0.6,"end":1.0}],
            "segments":[{"start":0.0,"end":1.0,"text":"Глава первая"}]}"""
        assertEquals(listOf("Глава", "первая"), OpenRouterWhisper.parse(withWords, words = true, lengthMs = 1_000).cues.map { it.text })
        val noWords = """{"language":"ru","text":"Глава первая","segments":[{"start":0.0,"end":1.0,"text":"Глава первая"}]}"""
        assertEquals(listOf("Глава первая"), OpenRouterWhisper.parse(noWords, words = true, lengthMs = 1_000).cues.map { it.text })
    }

    @Test
    fun `plain text without timestamps covers the whole piece`() {
        val t = OpenRouterWhisper.parse("""{"text":"Только текст"}""", words = false, lengthMs = 60_000)
        assertEquals(listOf(SubtitleCue(0, 60_000, "Только текст")), t.cues)
        assertEquals(null, t.language)
    }

    @Test
    fun `wav header describes 16 kHz mono 16-bit`() {
        val wav = OpenRouterWhisper.wav(FloatArray(16_000) { 0.5f }, 0 until 16_000)
        assertEquals(44 + 32_000, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val rate = java.nio.ByteBuffer.wrap(wav, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(16_000, rate)
    }

    @Test
    fun `language codes pass through, unknown names give null`() {
        assertEquals("ru", OpenRouterWhisper.isoLanguage("ru"))
        assertEquals("ru", OpenRouterWhisper.isoLanguage("Russian"))
        assertEquals(null, OpenRouterWhisper.isoLanguage("klingon"))
        assertEquals(null, OpenRouterWhisper.isoLanguage(" "))
    }
}
