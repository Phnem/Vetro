package com.example.myapplication.domain.enrichment.title

import com.example.myapplication.network.AniListTitleEnrichment
import com.example.myapplication.network.enrichment.ArtworkImage
import com.example.myapplication.network.enrichment.EnrichmentSource
import com.example.myapplication.network.enrichment.ShikimoriEnrichment
import com.example.myapplication.network.enrichment.TmdbEnrichment
import com.example.myapplication.network.enrichment.TmdbEpisodeRef
import com.example.myapplication.network.enrichment.TvMazeEpisode
import com.example.myapplication.network.enrichment.TvMazeShow
import com.example.myapplication.network.enrichment.VideoClip
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleEnrichmentRulesTest {

    private val now = Instant.parse("2026-09-26T12:00:00Z")

    private fun aniList(airingAt: Instant?, episode: Int? = 5, status: String = "RELEASING") = AniListTitleEnrichment(
        anilistId = 1, malId = 2, status = status, episodes = 12, episodeDurationMin = 24,
        nextEpisode = episode, nextAiringAtEpochSec = airingAt?.epochSecond,
        trailerSite = null, trailerId = null, trailerThumbnail = null,
    )

    private fun tvMaze(next: Instant?, previous: Instant?, status: String = "Running") = TvMazeShow(
        id = 1, name = "x", status = status, imdbId = null, tvdbId = null, network = null, timezone = null,
        previous = previous?.let { TvMazeEpisode(1, 4, null, null, it, 24) },
        next = next?.let { TvMazeEpisode(1, 5, null, null, it, 24) },
    )

    private fun tmdb(nextDate: LocalDate?) = TmdbEnrichment(
        logos = emptyList(), backdrops = emptyList(), videos = emptyList(), imdbId = null, tvdbId = null, status = null,
        nextEpisode = nextDate?.let { TmdbEpisodeRef(1, 5, it) }, lastEpisode = null,
    )

    @Test
    fun `anilist exact time wins`() {
        val at = now.plusSeconds(3600)
        val r = TitleEnrichmentRules.nextOriginalRelease(now, aniList(at), null, tvMaze(now.plusSeconds(99_000), null), null)!!
        assertEquals(EnrichmentSource.ANILIST, r.source)
        assertEquals(at, r.at)
        assertEquals(5, r.episode)
        assertEquals(ReleaseConfidence.EXACT, r.confidence)
    }

    @Test
    fun `past anilist time falls through to tvmaze`() {
        val at = now.plusSeconds(7200)
        val r = TitleEnrichmentRules.nextOriginalRelease(now, aniList(now.minusSeconds(10)), null, tvMaze(at, null), null)!!
        assertEquals(EnrichmentSource.TVMAZE, r.source)
        assertEquals(at, r.at)
    }

    @Test
    fun `shikimori then tmdb date only`() {
        val shiki = ShikimoriEnrichment(now.plusSeconds(500), true, emptyList(), emptyList(), null, emptyList())
        assertEquals(EnrichmentSource.SHIKIMORI, TitleEnrichmentRules.nextOriginalRelease(now, null, shiki, null, null)!!.source)
        val r = TitleEnrichmentRules.nextOriginalRelease(now, null, null, null, tmdb(LocalDate.parse("2026-10-03")))!!
        assertEquals(ReleaseConfidence.DATE_ONLY, r.confidence)
        assertNull(r.at)
        assertNull(TitleEnrichmentRules.nextOriginalRelease(now, null, null, null, tmdb(LocalDate.parse("2026-09-20"))))
    }

    @Test
    fun `weekly rhythm estimate only for running show with a recent episode`() {
        val prev = Instant.parse("2026-09-22T16:00:00Z")
        val r = TitleEnrichmentRules.nextOriginalRelease(now, null, null, tvMaze(null, prev), null)!!
        assertEquals(ReleaseConfidence.ESTIMATED, r.confidence)
        assertEquals(Instant.parse("2026-09-29T16:00:00Z"), r.at)
        assertEquals(5, r.episode)
        // Прошлая серия три недели назад — сериал на перерыве, не выдумываем.
        assertNull(TitleEnrichmentRules.nextOriginalRelease(now, null, null, tvMaze(null, Instant.parse("2026-09-01T16:00:00Z")), null))
        assertNull(TitleEnrichmentRules.nextOriginalRelease(now, null, null, tvMaze(null, prev, status = "Ended"), null))
    }

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
