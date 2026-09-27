package com.example.myapplication.domain.enrichment.title

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.enrichment.YouTubeVideo
import org.junit.Assert.assertEquals
import org.junit.Test

class TrailerQueryTest {

    private fun anime(type: MediaType = MediaType.ANIME) = Anime(
        id = "1", title = "Naruto", titleEn = "Naruto", titleRu = "Наруто", episodes = 220,
        rating = 0f, imageFileName = null, orderIndex = 0, dateAdded = 0L, mediaType = type,
    )

    private fun video(title: String) = YouTubeVideo(title, title, "", null, null)

    @Test
    fun `query follows the ui language, first season and dub language`() {
        assertEquals("Наруто 1 сезон трейлер на русском", TitleEnrichmentRules.trailerQuery(anime(), AppLanguage.RU))
        assertEquals("Naruto season 1 official trailer", TitleEnrichmentRules.trailerQuery(anime(), AppLanguage.EN))
        assertEquals("Наруто трейлер на русском", TitleEnrichmentRules.trailerQuery(anime(MediaType.MOVIE), AppLanguage.RU))
    }

    @Test
    fun `missing localized title falls back to the main one`() {
        assertEquals("Naruto 1 сезон трейлер на русском", TitleEnrichmentRules.trailerQuery(anime().copy(titleRu = null), AppLanguage.RU))
    }

    @Test
    fun `searched trailer prefers a trailer word and cyrillic for russian`() {
        val results = listOf(video("Naruto opening 1"), video("Naruto official trailer"), video("Трейлер НАРУТО"))
        assertEquals("Трейлер НАРУТО", TitleEnrichmentRules.pickSearchedTrailer(results, AppLanguage.RU)!!.title)
        assertEquals("Naruto official trailer", TitleEnrichmentRules.pickSearchedTrailer(results, AppLanguage.EN)!!.title)
        assertEquals("равные — порядок выдачи", "Naruto opening 1",
            TitleEnrichmentRules.pickSearchedTrailer(listOf(video("Naruto opening 1"), video("Naruto ending")), AppLanguage.EN)!!.title)
    }
}
