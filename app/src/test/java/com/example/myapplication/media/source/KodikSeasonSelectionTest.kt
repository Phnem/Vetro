package com.example.myapplication.media.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KodikSeasonSelectionTest {

    @Test
    fun `season two episode one never reuses season one episode one`() {
        val seasons = linkedMapOf(
            1 to mapOf(1 to "https://kodik.example/season-1/episode-1"),
            2 to mapOf(1 to "https://kodik.example/season-2/episode-1"),
        )

        val actual = selectKodikEpisodeLink(seasons, season = 2, episode = 1)

        assertEquals(
            "https://kodik.example/season-2/episode-1",
            actual,
        )
    }

    @Test
    fun `standalone ova candidate cannot satisfy a later season it cannot prove`() {
        assertEquals(
            false,
            isKodikStandaloneEligible(season = 2, episode = 1, seasonIdentifiable = false),
        )
        assertEquals(
            true,
            isKodikStandaloneEligible(season = 1, episode = 1, seasonIdentifiable = false),
        )
    }

    /** Фильм, найденный по собственному названию сезона, — это и есть запрошенный сезон. */
    @Test
    fun `standalone candidate satisfies a later season found by its own title`() {
        assertEquals(
            true,
            isKodikStandaloneEligible(season = 2, episode = 1, seasonIdentifiable = true),
        )
        assertEquals(
            false,
            isKodikStandaloneEligible(season = 2, episode = 2, seasonIdentifiable = true),
        )
    }

    @Test
    fun `serial with only season one cannot satisfy an unprovable season two`() {
        val seasons = mapOf(
            1 to mapOf(4 to "https://kodik.example/season-1/episode-4"),
        )

        val actual = selectKodikSerialEpisodeLink(
            baseLink = "https://kodik.example/season-1",
            linksBySeason = seasons,
            lastSeason = 1,
            lastEpisode = 24,
            season = 2,
            episode = 4,
            seasonIdentifiable = false,
        )

        assertNull(actual)
    }

    /**
     * Регрессия на корень отказа Kodik: у сиквела своя запись, и её единственный сезон лежит
     * под ключом «1». Когда релиз найден по названию самого сезона, подмене взяться неоткуда.
     */
    @Test
    fun `single season release found by season title serves the requested season`() {
        val seasons = mapOf(
            1 to mapOf(4 to "https://kodik.example/ni-no-sara/episode-4"),
        )

        val actual = selectKodikSerialEpisodeLink(
            baseLink = "https://kodik.example/ni-no-sara",
            linksBySeason = seasons,
            lastSeason = 1,
            lastEpisode = 13,
            season = 2,
            episode = 4,
            seasonIdentifiable = true,
        )

        assertEquals("https://kodik.example/ni-no-sara/episode-4", actual)
    }

    /** Многосезонная карта без нужного ключа неоднозначна — отказ даже при доказуемом сезоне. */
    @Test
    fun `multi season map without the requested key refuses even when provable`() {
        val seasons = mapOf(
            1 to mapOf(4 to "https://kodik.example/season-1/episode-4"),
            2 to mapOf(4 to "https://kodik.example/season-2/episode-4"),
        )

        val actual = selectKodikSerialEpisodeLink(
            baseLink = "https://kodik.example/release",
            linksBySeason = seasons,
            lastSeason = 3,
            lastEpisode = 13,
            season = 3,
            episode = 4,
            seasonIdentifiable = true,
        )

        assertNull(actual)
    }

    /** Первый сезон у односезонной карты однозначен и без доказательства по названию. */
    @Test
    fun `single season release serves season one without proof`() {
        val seasons = mapOf(
            7 to mapOf(2 to "https://kodik.example/odd-key/episode-2"),
        )

        val actual = selectKodikSerialEpisodeLink(
            baseLink = "https://kodik.example/odd-key",
            linksBySeason = seasons,
            lastSeason = 0,
            lastEpisode = 0,
            season = 1,
            episode = 2,
            seasonIdentifiable = false,
        )

        assertEquals("https://kodik.example/odd-key/episode-2", actual)
    }

    @Test
    fun `serial returns exact episode from requested season`() {
        val seasons = mapOf(
            1 to mapOf(4 to "https://kodik.example/season-1/episode-4"),
            2 to mapOf(4 to "https://kodik.example/season-2/episode-4"),
        )

        val actual = selectKodikSerialEpisodeLink(
            baseLink = "https://kodik.example/release",
            linksBySeason = seasons,
            lastSeason = 2,
            lastEpisode = 13,
            season = 2,
            episode = 4,
            seasonIdentifiable = true,
        )

        assertEquals("https://kodik.example/season-2/episode-4", actual)
    }

    @Test
    fun `serial without episode map only falls back for its own season`() {
        assertEquals(
            "https://kodik.example/release?season=2&episode=4",
            selectKodikSerialEpisodeLink(
                baseLink = "https://kodik.example/release",
                linksBySeason = null,
                lastSeason = 2,
                lastEpisode = 13,
                season = 2,
                episode = 4,
                seasonIdentifiable = false,
            ),
        )
        assertNull(
            selectKodikSerialEpisodeLink(
                baseLink = "https://kodik.example/release",
                linksBySeason = null,
                lastSeason = 1,
                lastEpisode = 24,
                season = 2,
                episode = 4,
                seasonIdentifiable = false,
            )
        )
    }

    /** Регрессия 28.09: «Перерождение в аристократа…» S3E1 играло S2E1 — у Kodik третьего ещё нет. */
    @Test
    fun `a release of another numbered season never stands in for the requested one`() {
        val s2 = listOf("Перерождение в аристократа со способностью анализа 2", "Tensei Kizoku, Kantei Skill de Nariagaru 2nd Season")
        // Названия самого сезона (AniList); русское название франшизы сезон не подтверждает.
        val s3Titles = listOf("Tensei Kizoku, Kantei Skill de Nariagaru 3rd Season")
        assertEquals(false, kodikReleaseServesSeason(s2, season = 3, seasonTitles = s3Titles))
        // Первый сезон без номера тоже не третий.
        assertEquals(false, kodikReleaseServesSeason(listOf("Перерождение в аристократа со способностью анализа"), season = 3, seasonTitles = s3Titles))
        // Сам третий — годится.
        assertEquals(true, kodikReleaseServesSeason(listOf("Tensei Kizoku, Kantei Skill de Nariagaru 3rd Season"), season = 3, seasonTitles = s3Titles))
        assertEquals(true, kodikReleaseServesSeason(listOf("Перерождение в аристократа со способностью анализа 3"), season = 3, seasonTitles = s3Titles))
        // Первый сезон не подменяется релизом второго.
        assertEquals(false, kodikReleaseServesSeason(s2, season = 1, seasonTitles = listOf("Tensei Kizoku, Kantei Skill de Nariagaru")))
    }

    @Test
    fun `single-season release fallback needs the release to confirm the season`() {
        val only = linkedMapOf(1 to mapOf(1 to "https://kodik.example/s2-release/episode-1"))
        assertNull(
            selectKodikSerialEpisodeLink(
                baseLink = "https://kodik.example/s2-release", linksBySeason = only, lastSeason = 1, lastEpisode = 12,
                season = 3, episode = 1, seasonIdentifiable = true, releaseConfirmsSeason = false,
            ),
        )
    }

    @Test
    fun `russian and english season markers are read`() {
        assertEquals(2, explicitReleaseSeason("Tensei Kizoku 2nd Season"))
        assertEquals(3, explicitReleaseSeason("Магическая битва 3 сезон"))
        assertEquals(2, explicitReleaseSeason("Врата Штейна [ТВ-2]"))
        assertEquals(4, explicitReleaseSeason("Re:Zero Season 4"))
        assertNull(explicitReleaseSeason("Перерождение в аристократа со способностью анализа"))
    }
}
