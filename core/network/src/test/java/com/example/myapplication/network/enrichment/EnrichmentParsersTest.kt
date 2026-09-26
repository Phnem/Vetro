package com.example.myapplication.network.enrichment

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор ответов API обогащения. Фикстуры — живые ответы от 2026-09-26 (`resources/enrichment`). */
class EnrichmentParsersTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("enrichment/$name")) { name }.readText()

    @Test
    fun `tvmaze show with previous episode airstamp`() {
        val show = TvMazeParser.show(fixture("tvmaze_show_frieren.json"))
        assertEquals(69956, show.id)
        assertEquals("tt22248376", show.imdbId)
        assertEquals(424536, show.tvdbId)
        assertEquals("Asia/Tokyo", show.timezone)
        val prev = show.previous!!
        assertEquals(2, prev.season)
        assertEquals(10, prev.number)
        assertEquals(Instant.parse("2026-03-27T16:00:00Z"), prev.airstamp)
        assertNull(show.next)
    }

    @Test
    fun `tvmaze episodes and lookup id`() {
        assertEquals(169, TvMazeParser.showId(fixture("tvmaze_lookup_breaking_bad.json")))
        val eps = TvMazeParser.episodes(fixture("tvmaze_episodes_breaking_bad.json"))
        assertEquals(62, eps.size)
        assertEquals(Instant.parse("2008-01-21T03:00:00Z"), eps.first().airstamp)
    }

    @Test
    fun `introdb segments and empty movie`() {
        val segs = IntroDbParser.segments(fixture("introdb_got_s1e1.json"))
        val intro = segs.first { it.kind == ExternalSkipKind.OPENING }
        assertEquals(437_000L, intro.startMs)
        assertEquals(531_000L, intro.endMs)
        assertEquals(2, intro.votes)
        assertTrue(segs.any { it.kind == ExternalSkipKind.ENDING })
        assertTrue(IntroDbParser.segments(fixture("introdb_inception_movie_empty.json")).isEmpty())
    }

    @Test
    fun `anime-skip markers become intervals per version`() {
        assertEquals(listOf("ead8bdbc-a814-484d-89ba-892045fe54b4"), AnimeSkipParser.showIds(fixture("animeskip_shows_frieren.json")))
        val eps = AnimeSkipParser.episodes(fixture("animeskip_episodes_frieren.json"))
        assertTrue(eps.size >= 28)
        val first = eps.first { it.season == 1 && it.number == 1 }
        val opening = first.segments.first { it.kind == ExternalSkipKind.OPENING }
        assertEquals(0L, opening.startMs)
        assertTrue(opening.endMs in 89_000L..92_000L)
        // Серия 1 — с превью после титров: отрезки титров и превью различаются.
        assertTrue(first.segments.any { it.kind == ExternalSkipKind.ENDING })
        assertTrue(first.segments.any { it.kind == ExternalSkipKind.PREVIEW })
        // Canon/Title Card не становятся пропускаемыми.
        assertEquals(setOf(ExternalSkipKind.OPENING, ExternalSkipKind.ENDING, ExternalSkipKind.PREVIEW), first.segments.map { it.kind }.toSet())
        assertNotNull(first.baseDurationMs)
    }

    @Test
    fun `tmdb bundle gives logos, videos and external ids`() {
        val tv = TmdbEnrichmentParser.parse(fixture("tmdb_bundle_frieren_ru.json"))
        assertTrue(tv.logos.isNotEmpty())
        assertTrue(tv.logos.all { it.url.startsWith("https://image.tmdb.org/t/p/original/") })
        assertTrue(tv.logos.any { it.language == "ru" } || tv.logos.any { it.language == "en" })
        assertEquals("tt22248376", tv.imdbId)
        assertEquals(424536, tv.tvdbId)
        assertTrue(tv.backdrops.isNotEmpty())
        val movie = TmdbEnrichmentParser.parse(fixture("tmdb_bundle_inception_ru.json"))
        assertEquals("tt1375666", movie.imdbId)
        assertTrue(movie.videos.all { it.site.isNotBlank() && it.key.isNotBlank() })
    }

    @Test
    fun `shikimori enrichment lists russian dub studios`() {
        val e = ShikimoriEnrichmentParser.parse(fixture("shikimori_frieren.json"))!!
        assertTrue("AniLibria" in e.fandubbers)
        assertTrue(e.fansubbers.isNotEmpty())
        assertTrue(e.videos.isNotEmpty())
    }

    @Test
    fun `open library separates work and editions`() {
        val works = OpenLibraryParser.works(fixture("openlibrary_search_three_body.json"))
        val work = works.first()
        assertEquals("/works/OL17267881W", work.key)
        assertTrue(work.editionCount >= 40)
        val editions = OpenLibraryParser.editions(fixture("openlibrary_editions_three_body.json"))
        val russian = editions.first { "rus" in it.languages }
        assertEquals("Задача трёх тел", russian.title)
        assertEquals(listOf("9785041619015"), russian.isbn13)
    }

    @Test
    fun `open library q search returns the work with the matched edition`() {
        val ru = OpenLibraryParser.works(fixture("openlibrary_q_three_body_ru.json")).first()
        assertEquals("/works/OL17267881W", ru.key)
        assertEquals("三体", ru.title)
        val edition = ru.matchedEdition!!
        assertEquals("Задача трёх тел", edition.title)
        assertEquals(listOf("9785041619015"), edition.isbn13)
        assertEquals(listOf("rus"), edition.languages)
        assertTrue(edition.coverIds.isNotEmpty())
        val en = OpenLibraryParser.works(fixture("openlibrary_q_three_body_en.json")).first()
        assertEquals("The Three-Body Problem", en.matchedEdition!!.title)
        val details = OpenLibraryParser.work("""{"description":{"type":"/type/text","value":"Line one.\r\nLine two."},"subjects":["Hugo Award Winner"]}""")
        assertEquals("Line one.\nLine two.", details.description)
        assertEquals(null, OpenLibraryParser.work("""{"title":"x"}""").description)
    }

    @Test
    fun `bookbrainz and itunes`() {
        assertTrue(BookBrainzParser.search(fixture("bookbrainz_search_three_body.json")).any { it.name.contains("Three") })
        val books = ITunesParser.results(fixture("itunes_audiobook_three_body.json"))
        val first = books.first()
        assertEquals("Cixin Liu", first.author)
        assertTrue(first.artworkUrl!!.contains("600x600"))
    }

    @Test
    fun `opensubtitles login and download examples from the official spec`() {
        val session = OpenSubtitlesParser.session(fixture("opensubtitles_login_example.json"))
        assertTrue(session.token.isNotBlank())
        assertEquals(100, session.allowedDownloads)
        val link = OpenSubtitlesParser.download(fixture("opensubtitles_download_example.json"))
        assertTrue(link.url.startsWith("https://"))
    }

    @Test
    fun `opensubtitles search by schema`() {
        // Ответ собран по схеме OpenAPI OpenSubtitles (готового примера поиска в спецификации нет).
        val body = """{"total_pages":1,"total_count":2,"page":1,"data":[
            {"id":"1","type":"subtitle","attributes":{"language":"ru","release":"Breaking.Bad.S01E01.720p","fps":23.976,
             "download_count":5120,"ratings":8.5,"hearing_impaired":false,"machine_translated":false,"ai_translated":false,
             "from_trusted":true,"files":[{"file_id":1954677,"file_name":"bb.srt"}]}},
            {"id":"2","type":"subtitle","attributes":{"language":"en","hearing_impaired":true,"files":[]}}]}"""
        val c = OpenSubtitlesParser.candidates(body)
        assertEquals(1, c.size) // запись без файла отброшена
        assertEquals(1954677L, c.first().fileId)
        assertEquals(23.976, c.first().fps!!, 0.001)
        assertTrue(c.first().fromTrusted)
    }

    @Test
    fun `omdb ratings and not-found`() {
        val body = """{"Title":"Breaking Bad","imdbRating":"9.5","imdbVotes":"2,300,000","Metascore":"N/A",
            "Ratings":[{"Source":"Internet Movie Database","Value":"9.5/10"},{"Source":"Rotten Tomatoes","Value":"96%"},
            {"Source":"Metacritic","Value":"87/100"}],"Awards":"Won 16 Primetime Emmys","Response":"True"}"""
        val r = OmdbParser.parse(body)!!
        assertEquals(9.5, r.imdbRating!!, 0.001)
        assertEquals(2_300_000, r.imdbVotes)
        assertEquals(96, r.rottenTomatoes)
        assertEquals(87, r.metacritic)
        assertNull(OmdbParser.parse("""{"Response":"False","Error":"Incorrect IMDb ID."}"""))
    }

    @Test
    fun `fanart logos sorted by likes are parsed with language`() {
        val body = """{"name":"Breaking Bad","hdtvlogo":[{"id":"1","url":"https://assets.fanart.tv/fanart/tv/81189/hdtvlogo/a.png","lang":"en","likes":"12"},
            {"id":"2","url":"https://assets.fanart.tv/fanart/tv/81189/hdtvlogo/b.png","lang":"ru","likes":"3"}],
            "showbackground":[{"id":"3","url":"https://assets.fanart.tv/x.jpg","lang":"","likes":"7"}]}"""
        val art = FanartParser.parse(body, movie = false)
        assertEquals(2, art.logos.size)
        assertEquals("ru", art.logos[1].language)
        assertEquals(12.0, art.logos[0].score, 0.0)
        assertEquals(1, art.backgrounds.size)
    }

    @Test
    fun `youtube videos status and nyt overview and tastedive`() {
        val yt = YouTubeParser.videos("""{"items":[{"id":"HhesaQXLuRY","snippet":{"title":"Breaking Bad Trailer","channelTitle":"AMC"},
            "status":{"embeddable":true,"privacyStatus":"public"}}]}""")
        assertEquals("AMC", yt.single().channelTitle)
        assertEquals(true, yt.single().embeddable)
        val nyt = NytParser.overview("""{"results":{"lists":[{"list_name_encoded":"hardcover-fiction","display_name":"Hardcover Fiction",
            "books":[{"rank":1,"title":"A BOOK","author":"Someone","primary_isbn13":"9780000000001","weeks_on_list":3}]}]}}""")
        assertEquals("A BOOK", nyt.single().books.single().title)
        val taste = TasteDiveParser.results("""{"similar":{"info":[],"results":[{"name":"Arrival","type":"movie","description":"…","yID":"abc"}]}}""")
        assertEquals("Arrival", taste.single().name)
        assertFalse(TasteDiveParser.results("""{"Similar":{"Results":[]}}""").isNotEmpty())
    }
}
