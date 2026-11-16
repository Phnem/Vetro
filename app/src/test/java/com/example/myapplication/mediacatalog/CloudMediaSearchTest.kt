package com.example.myapplication.mediacatalog

import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudMediaSearchTest {
    private val search = CloudMediaSearch(OkHttpClient(), "https://api.test/v1", "https://api.test/covers") { true }

    private val body = """{"q":"x","kind":"anime","items":[
        {"kind":"anime","id":"A00000050","t":"anime","ru":"Атака титанов","en":"Attack on Titan","ro":"Shingeki no Kyojin","y":2013,"st":"tv","f":3,"cv":7597630,
         "ext":{"mal":16498,"shikimori":16498,"anilist":16498}},
        {"kind":"anime","id":"A00000099","t":"anime","en":"Only Shikimori","y":2020,"st":"ova","f":0,"cv":0,"ext":{"shikimori":777}},
        {"kind":"anime","id":"A00000100","t":"anime","en":"No ids at all","f":0,"cv":0}
    ]}"""

    @Test fun `anime hits open through the source they carry an id of, and ones without an id are dropped`() {
        val out = search.parse(body, AppContentType.ANIME, AppLanguage.RU)
        assertEquals(2, out.size)
        val first = out[0]
        assertEquals("Атака титанов", first.title)
        assertEquals("Attack on Titan", first.altTitle)
        assertEquals("AniList", first.source)
        assertEquals("16498", first.externalId)
        assertEquals("ANIME", first.categoryType)
        assertEquals("TV", first.format)
        assertEquals(2013, first.seasonYear)
        assertEquals("https://api.test/covers/media/large/A00000050.avif?v=7597630", first.posterUrl)
        assertEquals("Shikimori", out[1].source)
        assertEquals("777", out[1].externalId)
        assertNull(out[1].posterUrl)
    }

    @Test fun `the title follows the app language`() {
        assertEquals("Attack on Titan", search.parse(body, AppContentType.ANIME, AppLanguage.EN)[0].title)
    }

    @Test fun `films and series open through TMDB and need its id`() {
        val movies = """{"items":[{"kind":"movie","id":"M00000001","t":"movie","en":"Naruto","y":2002,"st":"movie","f":0,"cv":0,"ext":{"tmdb":46260,"imdb":"tt0409591"}},
            {"kind":"movie","id":"M00000002","t":"movie","en":"No tmdb","f":0,"cv":0,"ext":{"imdb":"tt1"}}]}"""
        val out = search.parse(movies, AppContentType.MOVIE, AppLanguage.EN)
        assertEquals(1, out.size)
        assertEquals("TMDB", out[0].source)
        assertEquals("46260", out[0].externalId)
        assertEquals(46260, out[0].externalIds.tmdb)
        assertEquals("tt0409591", out[0].externalIds.imdb)
        assertEquals("MOVIE", out[0].categoryType)
        assertEquals(1, out[0].episodes)
    }

    @Test fun `manga has no cloud database and covers are skipped where AVIF is unreadable`() {
        assertNull(search.kindOf(AppContentType.MANGA))
        assertTrue(search.parse(body, AppContentType.MANGA, AppLanguage.EN).isEmpty())
        val old = CloudMediaSearch(OkHttpClient(), "https://api.test/v1", "https://api.test/covers") { false }
        assertNull(old.parse(body, AppContentType.ANIME, AppLanguage.EN)[0].posterUrl)
        assertEquals("anime", search.kindOf(AppContentType.ANIME))
        assertEquals("tv", search.kindOf(AppContentType.SERIES))
    }

    private fun hit(source: String, id: String, title: String, poster: String? = null) = ApiSearchResult(
        title = title, altTitle = null, posterUrl = poster, episodes = 0, description = "", type = "ANIME", genres = emptyList(), rating = null,
        source = source, categoryType = "ANIME", externalId = id,
    )

    @Test fun `cloud results come first, the same title from an older source replaces the thin one, the rest follows`() {
        val cloud = listOf(hit("AniList", "1", "Cloud A"), hit("Shikimori", "2", "Cloud B"))
        val existing = listOf(hit("Jikan", "9", "Other"), hit("anilist", "1", "Rich A", "https://poster/a.jpg"))
        val merged = CloudMediaSearch.mergeCloudFirst(cloud, existing)
        assertEquals(listOf("Rich A", "Cloud B", "Other"), merged.map { it.title })
        assertEquals(existing, CloudMediaSearch.mergeCloudFirst(emptyList(), existing))
    }
}
