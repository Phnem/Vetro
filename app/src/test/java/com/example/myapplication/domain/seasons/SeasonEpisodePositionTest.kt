package com.example.myapplication.domain.seasons

import com.example.myapplication.data.models.AiringProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonEpisodePositionTest {

    private fun season(n: Int, episodes: Int, ongoing: Boolean = false, special: Boolean = false) = SeasonInfo(
        seasonNumber = n, episodes = episodes, ongoing = ongoing, source = "AniList",
        kind = if (special) SeasonKind.Special else SeasonKind.Season,
    )

    private fun layout(vararg seasons: SeasonInfo) = SeasonEpisodesEntry(animeId = "a", seasons = seasons.toList())

    private fun airing(season: Int?, aired: Int, total: Int? = 12) = AiringProgress("a", season, aired, total, 0L)

    @Test
    fun `finished layout gives the last regular season and its episodes`() {
        val l = layout(season(1, 12), season(2, 24), season(SPECIAL_SEASON_BASE, 1, special = true))
        assertEquals(SeasonEpisode(2, 24), latestSeasonEpisode(l, null))
        assertEquals("S2 E24", latestSeasonEpisode(l, null)!!.label())
    }

    @Test
    fun `ongoing season in layout keeps its number and takes fresh episodes from airing`() {
        // JoJo: снимок выхода насчитал «S8», в раскладе выходящий сезон девятый.
        val l = layout(season(8, 1), season(9, 1, ongoing = true))
        assertEquals(SeasonEpisode(9, 3), latestSeasonEpisode(l, airing(8, 3)))
    }

    @Test
    fun `airing ahead of the layout wins completely`() {
        val l = layout(season(1, 12), season(2, 12))
        assertEquals(SeasonEpisode(3, 4), latestSeasonEpisode(l, airing(3, 4)))
    }

    @Test
    fun `airing on the same season as the layout keeps its fresh episode count`() {
        // Re:ZERO: расклад застыл на S4 E18, вышла 19-я — подпись не должна отставать и прыгать.
        val l = layout(season(1, 25), season(2, 25), season(3, 16), season(4, 18))
        assertEquals(SeasonEpisode(4, 19), latestSeasonEpisode(l, airing(4, 19, total = 19)))
    }

    @Test
    fun `nothing known gives null`() {
        assertNull(latestSeasonEpisode(null, null))
        assertNull(latestSeasonEpisode(null, airing(null, 5)))
        assertEquals(SeasonEpisode(2, 5), latestSeasonEpisode(null, airing(2, 5)))
    }

    @Test
    fun `release label shows a range inside one season`() {
        assertEquals("S3 E5", releasedEpisodesLabel(40, 41, SeasonEpisode(3, 5)))
        assertEquals("S1 E5–12", releasedEpisodesLabel(4, 12, SeasonEpisode(1, 12)))
        assertEquals("перешагнули сезон — только последняя", "S2 E2", releasedEpisodesLabel(10, 14, SeasonEpisode(2, 2)))
    }

    @Test
    fun `franchise counter maps onto the season it reaches`() {
        // «Реинкарнация безработного»: 62 серии за сезоны 11 + 12 + 13 + 12 — это S5 E14.
        val seasons = listOf(season(1, 11), season(2, 12), season(3, 13), season(4, 12), season(5, 24, ongoing = true))
        assertEquals(SeasonEpisode(5, 14), watchedPositionFromCount(62, seasons))
        assertEquals("свой сезон первый, счётчик в него не влез — сквозной", SeasonEpisode(5, 14), watchedPositionFromCount(62, seasons, ownSeasonNumber = 1))
        assertEquals("запись «Season 3»: 14 своих серий", SeasonEpisode(5, 14), watchedPositionFromCount(14, seasons, ownSeasonNumber = 5))
        assertEquals("запись первого сезона целиком", SeasonEpisode(1, 11), watchedPositionFromCount(11, seasons, ownSeasonNumber = 1))
        assertEquals("ровно конец сезона", SeasonEpisode(1, 11), watchedPositionFromCount(11, seasons))
        assertEquals("следующая серия — первая нового", SeasonEpisode(2, 1), watchedPositionFromCount(12, seasons))
    }

    @Test
    fun `franchise counter skips specials and clamps past the last episode`() {
        val seasons = listOf(season(2, 10), season(SPECIAL_SEASON_BASE, 3, special = true), season(1, 12))
        assertEquals(SeasonEpisode(2, 3), watchedPositionFromCount(15, seasons))
        assertEquals(SeasonEpisode(2, 10), watchedPositionFromCount(99, seasons))
        assertNull(watchedPositionFromCount(0, seasons))
        assertNull(watchedPositionFromCount(5, emptyList()))
    }
}
