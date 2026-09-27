package com.example.myapplication.domain.enrichment.title

import com.example.myapplication.network.enrichment.ArtworkImage
import com.example.myapplication.network.enrichment.EnrichmentSource
import com.example.myapplication.network.enrichment.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleEnrichmentRulesTest {

    private fun img(lang: String?, score: Double, w: Int = 800, h: Int = 300) =
        ArtworkImage("u/$lang/$score", lang, w, h, score, EnrichmentSource.TMDB)

    @Test
    fun `logo prefers ui language then english then textless`() {
        val list = listOf(img("en", 9.0), img("ru", 3.0), img(null, 10.0))
        assertEquals("ru", TitleEnrichmentRules.pickLogo(list, "ru")!!.language)
        assertEquals("en", TitleEnrichmentRules.pickLogo(list, "de")!!.language)
        // Слишком вытянутый (баннер 12:1) и крошечный — не логотип.
        assertNull(TitleEnrichmentRules.pickLogo(listOf(img("ru", 5.0, w = 1200, h = 100), img("ru", 5.0, w = 120, h = 60)), "ru"))
    }

    @Test
    fun `trailer prefers language, then trailer type, then official`() {
        fun clip(lang: String?, type: String, official: Boolean, key: String) = VideoClip("YouTube", key, key, type, official, lang, EnrichmentSource.TMDB)
        val list = listOf(
            clip("en", "Trailer", true, "en-official"),
            clip("ru", "Teaser", true, "ru-teaser"),
            clip("ru", "Trailer", false, "ru-fan"),
            VideoClip("Vimeo", "v", "v", "Trailer", true, "ru", EnrichmentSource.TMDB),
        )
        assertEquals("ru-fan", TitleEnrichmentRules.pickTrailer(list, "ru")!!.key)
        assertEquals("en-official", TitleEnrichmentRules.pickTrailer(list, "en")!!.key)
    }

    @Test
    fun `youtube id from any link form`() {
        assertEquals("dQw4w9WgXcQ", TitleEnrichmentRepository.youtubeId("https://youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", TitleEnrichmentRepository.youtubeId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", TitleEnrichmentRepository.youtubeId("https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0"))
        assertNull(TitleEnrichmentRepository.youtubeId("https://vk.com/video1"))
    }
}
