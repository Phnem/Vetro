package com.example.myapplication.network.ids

import com.example.myapplication.network.enrichment.TmdbKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsParsersTest {

    // Реальный ответ Wikidata на «Бриллиантовая рука»@ru (поля сокращены до используемых).
    @Test
    fun `wikidata bindings become cross ids with labels`() {
        val body = """
            {"head":{"vars":["i"]},"results":{"bindings":[
              {"i":{"type":"uri","value":"http://www.wikidata.org/entity/Q1999930"},
               "kpV":{"type":"literal","value":"46225"},
               "imdbV":{"type":"literal","value":"tt0062759"},
               "tmdbMV":{"type":"literal","value":"20873"},
               "ruL":{"type":"literal","value":"Бриллиантовая рука","xml:lang":"ru"},
               "enL":{"type":"literal","value":"The Diamond Arm","xml:lang":"en"}}
            ]}}
        """.trimIndent()
        val e = WikidataParser.entities(body).single()
        assertEquals("Q1999930", e.ids.wikidata)
        assertEquals(46225, e.ids.kinopoisk)
        assertEquals("tt0062759", e.ids.imdb)
        assertEquals(20873, e.ids.tmdbMovie)
        assertEquals("Бриллиантовая рука", e.labelRu)
        assertEquals("The Diamond Arm", e.labelEn)
        // В вес входят только каталоги: kp, imdb, tmdb.
        assertEquals(3, e.ids.weight)
    }

    @Test
    fun `wikidata ru wikipedia title is read from sitelinks`() {
        val body = """{"entities":{"Q1999930":{"sitelinks":{"ruwiki":{"site":"ruwiki","title":"Бриллиантовая рука"}}}}}"""
        assertEquals("Бриллиантовая рука", WikidataParser.ruWikiTitle(body, "Q1999930"))
        assertNull(WikidataParser.ruWikiTitle("""{"entities":{"Q1":{"sitelinks":{}}}}""", "Q1"))
    }

    @Test
    fun `anime map keeps tv and movie ids and imdb lists`() {
        val body = """
            [{"type":"TV","anidb_id":6107,"anilist_id":5114,"imdb_id":["tt1355642"],"kitsu_id":3936,"mal_id":5114,
              "simkl_id":41586,"themoviedb_id":{"tv":31911},"tvdb_id":85249,"season":{"tvdb":1,"tmdb":1}},
             {"type":"MOVIE","anilist_id":164,"mal_id":164,"imdb_id":["tt0119698"],"themoviedb_id":{"movie":[128]}},
             {"type":"TV"}]
        """.trimIndent()
        val entries = AnimeIdMapParser.parse(body)
        assertEquals(2, entries.size)
        val tv = entries[0]
        assertEquals(31911, tv.ids.tmdbTv)
        assertEquals(85249, tv.ids.tvdb)
        assertEquals(listOf("tt1355642"), tv.imdb)
        assertEquals(1, tv.tmdbSeason)
        assertEquals(128, entries[1].ids.tmdbMovie)

        val index = AnimeIdMap.Index(entries)
        assertEquals(5114, index.find(ExternalIdKind.KITSU, "3936")?.ids?.anilist)
        assertEquals(164, index.find(ExternalIdKind.TMDB_MOVIE, "128")?.ids?.mal)
        assertEquals(5114, index.find(ExternalIdKind.IMDB, "tt1355642")?.ids?.mal)
        assertNull(index.find(ExternalIdKind.MAL, "999999"))
    }

    @Test
    fun `arm response maps to cross ids`() {
        val body = """{"anidb":6107,"anilist":5114,"imdb":"tt1355642","kitsu":3936,"myanimelist":5114,"themoviedb":31911,"thetvdb":85249,"simkl":41586}"""
        val ids = ArmParser.parse(body)!!
        assertEquals(5114, ids.anilist)
        assertEquals(31911, ids.tmdbTv)
        assertEquals("tt1355642", ids.imdb)
        assertNull(ArmParser.parse("{}"))
    }

    @Test
    fun `poiskkino document gives russian names description and external ids`() {
        val body = """
            {"id":46225,"name":"Бриллиантовая рука","alternativeName":"","enName":null,
             "names":[{"name":"The Diamond Arm","language":"EN"},{"name":"Бриллиантовая рука","language":"RU"}],
             "type":"movie","year":1968,"description":"Комедия Леонида Гайдая.",
             "rating":{"kp":8.7,"imdb":8.3},"poster":{"url":"https://img/p.jpg"},
             "externalId":{"imdb":"tt0062759","tmdb":20873},
             "genres":[{"name":"комедия"}],"countries":[{"name":"СССР"}]}
        """.trimIndent()
        val t = PoiskKinoParser.title(body)!!
        assertEquals("Бриллиантовая рука", t.nameRu)
        assertEquals("The Diamond Arm", t.nameEn)
        assertEquals("tt0062759", t.imdbId)
        assertEquals(20873, t.tmdbId)
        assertEquals(8.7, t.ratingKp!!, 0.001)
        assertTrue(!t.isSeries)
        assertEquals(20873, t.crossIds().tmdbMovie)
        assertNull(t.crossIds().tmdbTv)
    }

    @Test
    fun `poiskkino series routes tmdb id to tv`() {
        val body = """{"docs":[{"id":1,"name":"Сериал","type":"tv-series","year":2020,"externalId":{"tmdb":55}}]}"""
        val t = PoiskKinoParser.search(body).single()
        assertTrue(t.isSeries)
        assertEquals(55, t.crossIds().tmdbTv)
        assertNull(t.crossIds().tmdbMovie)
    }

    @Test
    fun `wikipedia summary skips disambiguation pages`() {
        val ok = """{"type":"standard","title":"Бриллиантовая рука","extract":"«Бриллиантовая рука» — советский фильм.","description":"фильм 1968 года","wikibase_item":"Q1999930","thumbnail":{"source":"https://upload/x.jpg"}}"""
        val s = WikipediaParser.summary(ok, "ru")!!
        assertEquals("Q1999930", s.wikidataId)
        assertEquals("фильм 1968 года", s.description)
        assertNull(WikipediaParser.summary("""{"type":"disambiguation","title":"Х","extract":"Х — значения."}""", "ru"))
    }

    @Test
    fun `tmdb titles use real translations and ignore the original fallback`() {
        val body = """
            {"id":20873,"title":"The Diamond Arm","original_title":"Бриллиантовая рука","original_language":"ru",
             "release_date":"1968-04-24","imdb_id":"tt0062759","overview":"Русское описание",
             "translations":{"translations":[
               {"iso_639_1":"en","data":{"title":"The Diamond Arm","overview":"English overview"}},
               {"iso_639_1":"ru","data":{"title":"Бриллиантовая рука","overview":"Русское описание"}}]},
             "alternative_titles":{"titles":[{"iso_3166_1":"US","title":"Diamond Hand"},{"iso_3166_1":"DE","title":"Der Diamantenarm"}]}}
        """.trimIndent()
        val t = TmdbTitlesParser.titles(body, TmdbKind.MOVIE)
        assertEquals("Бриллиантовая рука", t.titleRu)
        assertEquals("The Diamond Arm", t.titleEn)
        assertEquals(1968, t.year)
        assertEquals("tt0062759", t.imdbId)
        assertEquals(listOf("Diamond Hand", "Der Diamantenarm"), t.altTitles)
        assertEquals("English overview", t.overviewEn)
    }

    @Test
    fun `tmdb find prefers movie results and reads tv`() {
        val movie = """{"movie_results":[{"id":603,"title":"Матрица","original_title":"The Matrix"}],"tv_results":[]}"""
        val m = TmdbTitlesParser.found(movie)!!
        assertEquals(TmdbKind.MOVIE, m.kind)
        assertEquals(603, m.id)
        val tv = """{"movie_results":[],"tv_results":[{"id":1396,"name":"Во все тяжкие","original_name":"Breaking Bad"}]}"""
        assertEquals(TmdbKind.TV, TmdbTitlesParser.found(tv)!!.kind)
        assertNull(TmdbTitlesParser.found("""{"movie_results":[],"tv_results":[]}"""))
    }

    // Реальный ответ api.poiskkino.dev/v1.4/movie/46225 (2026-10-19): alternativeName дублирует русское,
    // names содержит только RU, tmdb — число.
    @Test
    fun `poiskkino real document without english name`() {
        val body = """
            {"id": 46225, "name": "Бриллиантовая рука", "alternativeName": "Бриллиантовая рука", "enName": null,
             "names": [{"language": "RU", "name": "Бриллиантовая рука", "type": "Russian title on kinopoisk"}],
             "type": "movie", "year": 1968, "description": "В южном городке орудует шайка валютчиков",
             "shortDescription": "Контрабандисты гоняются за примерным семьянином.",
             "rating": {"imdb": 8.2, "kp": 8.572}, "poster": {"url": "https://img/p.jpg"},
             "externalId": {"imdb": "tt0062759", "tmdb": 20873}}
        """.trimIndent()
        val t = PoiskKinoParser.title(body)!!
        assertEquals("Бриллиантовая рука", t.nameRu)
        assertNull(t.nameEn)
        assertEquals("tt0062759", t.imdbId)
        assertEquals(20873, t.crossIds().tmdbMovie)
        assertEquals(8.572, t.ratingKp!!, 0.001)
    }

    // VETRO-TAIL[title-id-source]: вклад нового источника только дополняет запись.
    @Test
    fun `contributor fills gaps and never overwrites`() {
        val base = ResolvedTitle(
            ids = CrossIds(kinopoisk = 1),
            titleRu = "Русское",
            altTitles = listOf("Alt"),
            sources = setOf("Wikidata"),
        )
        val merged = base.with(
            TitleContribution(
                ids = CrossIds(kinopoisk = 2, imdb = "tt1"),
                titleRu = "Другое",
                titleEn = "English",
                altTitles = listOf("Alt", "Русское", "Second"),
                descriptionRu = "Описание",
                posterUrl = "https://p",
            ),
            "Index",
        )
        assertEquals(1, merged.ids.kinopoisk)
        assertEquals("tt1", merged.ids.imdb)
        assertEquals("Русское", merged.titleRu)
        assertEquals("English", merged.titleEn)
        assertEquals(listOf("Alt", "Second"), merged.altTitles)
        assertEquals("Описание", merged.descriptionRu)
        assertEquals(setOf("Wikidata", "Index"), merged.sources)
    }

    @Test
    fun `cross ids merge keeps own values and fills gaps`() {
        val mine = CrossIds(kinopoisk = 1, imdb = "tt0000001")
        val other = CrossIds(kinopoisk = 2, tmdbMovie = 7, anilist = 9)
        val merged = mine.merge(other)
        assertEquals(1, merged.kinopoisk)
        assertEquals(7, merged.tmdbMovie)
        assertEquals(9, merged.anilist)
        assertTrue(CrossIds().isEmpty)
    }
}
