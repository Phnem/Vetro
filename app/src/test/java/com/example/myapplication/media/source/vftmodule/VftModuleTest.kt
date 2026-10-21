package com.example.myapplication.media.source.vftmodule

import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.vftmodule.clients.TorrServerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VftModuleTest {

    @Test
    fun testParseMovieRelease() {
        val title = "Интерстеллар / Interstellar (2014) BDRip 1080p | D, P, A, Original Eng"
        val info = VftReleaseParser.parse(title)

        assertEquals("Интерстеллар", info.titleRu)
        assertEquals("Interstellar", info.titleEn)
        assertEquals(2014, info.year)
        assertEquals("1080p", info.quality)
        assertTrue(info.hasOriginalAudio)
        assertTrue(info.hasRussianAudio)
    }

    @Test
    fun testParseSeriesReleaseWithSeasonsAndEpisodes() {
        val title = "Доктор Хаус / House, M.D. [01-08х01-24 из 24] (2004-2012) WEB-DL 1080p | LostFilm, Original"
        val info = VftReleaseParser.parse(title)

        assertEquals("Доктор Хаус", info.titleRu)
        assertEquals(2004, info.year)
        assertEquals("1080p", info.quality)
        assertTrue(info.seasons.contains(1))
        assertTrue(info.seasons.contains(8))
        assertTrue(info.audioTracks.contains("LostFilm"))
        assertTrue(info.hasOriginalAudio)
    }

    @Test
    fun testParseSingleEpisodeRelease() {
        val title = "Игра Престолов / Game of Thrones S01E09 (2011) 2160p UHD 4K | Red Head Sound"
        val info = VftReleaseParser.parse(title)

        assertEquals("Игра Престолов", info.titleRu)
        assertEquals("Game of Thrones", info.titleEn)
        assertEquals("2160p", info.quality)
        assertEquals(listOf(1), info.seasons)
        assertEquals(listOf(9), info.episodes)
        assertTrue(info.audioTracks.contains("Red Head Sound"))
    }

    @Test
    fun testScoringPrefersOriginalAudioWhenRequested() {
        val queryEn = VftSearchQuery(
            query = "Interstellar",
            titleRu = "Интерстеллар",
            titleEn = "Interstellar",
            year = 2014,
            mediaType = MediaType.MOVIE,
            preferOriginalAudio = true,
        )

        val itemDubOnly = VftTorrentItem(
            title = "Интерстеллар (2014) 1080p Дубляж",
            magnetUrl = "magnet:?xt=urn:btih:1111111111111111111111111111111111111111",
            infoHash = "1111111111111111111111111111111111111111",
            seeders = 100,
            tracker = "Rutor",
            parsedInfo = VftReleaseParser.parse("Интерстеллар (2014) 1080p Дубляж"),
        )

        val itemOriginal = VftTorrentItem(
            title = "Интерстеллар / Interstellar (2014) 1080p | Original Eng, Дубляж",
            magnetUrl = "magnet:?xt=urn:btih:2222222222222222222222222222222222222222",
            infoHash = "2222222222222222222222222222222222222222",
            seeders = 100,
            tracker = "Rutor",
            parsedInfo = VftReleaseParser.parse("Интерстеллар / Interstellar (2014) 1080p | Original Eng, Дубляж"),
        )

        val scoreDub = VftReleaseParser.score(queryEn, itemDubOnly)
        val scoreOrig = VftReleaseParser.score(queryEn, itemOriginal)

        assertTrue("Release with original audio should score higher when preferred", scoreOrig > scoreDub)
    }

    @Test
    fun testTorrServerClientStreamUrlBuilder() {
        val prefs = VftPreferences()
        prefs.torrServerUrl = "http://192.168.1.50:8090"

        val client = TorrServerClient(okhttp3.OkHttpClient(), prefs)
        val url = client.buildStreamUrl("abcdef1234567890", 2, "Episode 1.mkv")

        assertTrue(url.startsWith("http://192.168.1.50:8090/stream/"))
        assertTrue(url.contains("link=abcdef1234567890"))
        assertTrue(url.contains("index=2"))
        assertTrue(url.contains("play"))
    }

    @Test
    fun testVftTorrentEngineFindTargetFile() {
        val files = listOf(
            VftTorrentFile(0, "Doctor House/readme.txt", "readme.txt", 1024, false),
            VftTorrentFile(1, "Doctor House/Season 1/S01E01.mkv", "S01E01.mkv", 1000000000, true, season = 1, episode = 1),
            VftTorrentFile(2, "Doctor House/Season 1/S01E02.mkv", "S01E02.mkv", 1000000000, true, season = 1, episode = 2),
            VftTorrentFile(3, "Doctor House/Season 2/S02E01.mkv", "S02E01.mkv", 1000000000, true, season = 2, episode = 1),
        )

        val target = VftTorrentEngine.extractSeason("Doctor House/Season 1/S01E02.mkv")
        assertEquals(1, target)

        val targetEp = VftTorrentEngine.extractEpisode("Doctor House/Season 1/S01E02.mkv")
        assertEquals(2, targetEp)
    }

    @Test
    fun testVftTorrentSearcherDeduplicationAndScoring() {
        val query = VftSearchQuery(
            query = "House",
            titleRu = "Доктор Хаус",
            titleEn = "House, M.D.",
            season = 1,
            episode = 2,
            mediaType = MediaType.SERIES,
        )

        val item1 = VftTorrentItem(
            title = "Доктор Хаус / House, M.D. S01E02 (2004) 1080p | LostFilm",
            magnetUrl = "magnet:?xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            infoHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            seeders = 50,
            tracker = "Rutor",
            parsedInfo = VftReleaseParser.parse("Доктор Хаус / House, M.D. S01E02 (2004) 1080p | LostFilm"),
        )

        // Та же раздача (тот же hash), но с другим числом сидов
        val item1Duplicate = item1.copy(seeders = 75, tracker = "RuTracker")

        val itemWrongSeason = VftTorrentItem(
            title = "Доктор Хаус / House, M.D. S02E02 (2005) 1080p | LostFilm",
            magnetUrl = "magnet:?xt=urn:btih:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            infoHash = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            seeders = 100,
            tracker = "Rutor",
            parsedInfo = VftReleaseParser.parse("Доктор Хаус / House, M.D. S02E02 (2005) 1080p | LostFilm"),
        )

        val score1 = VftReleaseParser.score(query, item1)
        val scoreWrong = VftReleaseParser.score(query, itemWrongSeason)

        assertTrue("Matching season and episode must score much higher than wrong season", score1 > scoreWrong)
        assertEquals("Wrong season should be penalized or scored 0", 0, scoreWrong)
    }
}
