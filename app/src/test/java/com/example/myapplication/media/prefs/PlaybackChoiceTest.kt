package com.example.myapplication.media.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackChoiceTest {

    @Test
    fun `title choice wins, missing fields come from the content type`() {
        val title = PlaybackChoice(sourceName = "AniLibria", subtitlesOff = true)
        val type = PlaybackChoice(sourceName = "AniDUB", audioLanguage = "ja", subtitleLanguage = "ru", subtitlesOff = false)

        val merged = title.orElse(type)

        assertEquals("AniLibria", merged.sourceName)
        assertEquals("ja", merged.audioLanguage)
        assertEquals("ru", merged.subtitleLanguage)
        assertEquals(true, merged.subtitlesOff)
    }

    @Test
    fun `nothing chosen stays nothing`() {
        assertEquals(PlaybackChoice(), PlaybackChoice().orElse(null))
    }
}
