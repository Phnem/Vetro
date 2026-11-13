package com.example.myapplication.audiobooks.catalog

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CatalogDetailsTest {
    private fun parse(json: String) = parseCatalogDetails("000011639", Json.parseToJsonElement(json).jsonObject)

    /** Ответ сервера в том виде, как его отдаёт `/v1/books/{id}` (сокращён). */
    private val full = """
        {"id":"000011639","ru":"Волк","y":2013,"f":5,"cv":1,"detail":{
          "orig":{"t":"Волк","l":"ru"},"ol":"ru","coll":1,"ch":24,"dur":860,
          "desc":{"t":"Марк Тумидус пропал без вести за краем Ойкумены.","l":"ru"},
          "gen":["scifi","adventure","no_such_genre"],
          "series":[{"ru":"Ойкумена","en":null,"pos":2}],
          "rate":{"v":8.3,"n":1065}}}
    """.trimIndent()

    @Test
    fun parsesEveryFieldOfTheCard() {
        val d = parse(full)!!
        assertEquals(2013, d.year)
        assertEquals("Волк", d.originalTitle)
        assertEquals("ru", d.originalLanguage)
        assertEquals("ru", d.descriptionLang)
        assertEquals(listOf("scifi", "adventure", "no_such_genre"), d.genres)
        assertEquals("Ойкумена", d.seriesRu)
        assertNull(d.seriesEn) // "en": null — это «нет значения», а не строка "null"
        assertEquals(2.0, d.seriesIndex!!, 0.0)
        assertTrue(d.isCollection)
        assertEquals(24, d.chapterCount)
        assertEquals(860, d.durationMin)
        assertEquals(8.3, d.rating!!, 0.0)
        assertEquals(1065, d.ratingVotes)
    }

    @Test
    fun unknownGenreKeysAreNotShown() {
        // Сервер новее приложения: незнакомый ключ молча пропускается, известные остаются по порядку.
        assertEquals(listOf("Фантастика", "Приключения"), parse(full)!!.genreLabels(english = false))
        assertEquals(listOf("Science fiction", "Adventure"), parse(full)!!.genreLabels(english = true))
    }

    @Test
    fun bookWithoutDetailsGivesNoCard() {
        assertNull(parse("""{"id":"000000006","ru":"Ёлка","f":0,"cv":0}"""))
        val bare = parse("""{"id":"000000006","detail":{"ol":"de"}}""")!!
        assertEquals("de", bare.originalLanguage)
        assertTrue(bare.genres.isEmpty())
        assertNull(bare.description)
        assertFalse(bare.isCollection)
    }

    @Test
    fun descriptionIsShownOnlyInTheInterfaceLanguage() {
        val ru = CatalogDetails(vid = "1", description = "Русская аннотация.", descriptionLang = "ru")
        val en = CatalogDetails(vid = "1", description = "English blurb.", descriptionLang = "en")
        val unknown = CatalogDetails(vid = "1", description = "Без языка.")
        assertEquals("Русская аннотация.", ru.descriptionFor(english = false))
        assertNull(ru.descriptionFor(english = true))
        assertNull(en.descriptionFor(english = false))
        assertEquals("English blurb.", en.descriptionFor(english = true))
        assertEquals("Без языка.", unknown.descriptionFor(english = false))
        assertNull(CatalogDetails(vid = "1", description = "  ").descriptionFor(english = false))
    }

    @Test
    fun seriesLineFollowsTheLanguageAndFormatsTheNumber() {
        val d = CatalogDetails(vid = "1", seriesRu = "Ойкумена", seriesEn = "Oikumene", seriesIndex = 2.0)
        assertEquals("Ойкумена · 2", d.seriesLine(english = false))
        assertEquals("Oikumene · 2", d.seriesLine(english = true))
        assertEquals("Ойкумена · 2.5", d.copy(seriesIndex = 2.5, seriesEn = null).seriesLine(english = true))
        assertEquals("Ойкумена", d.copy(seriesIndex = null).seriesLine(english = false))
        assertNull(CatalogDetails(vid = "1").seriesLine(english = false))
    }

    @Test
    fun genreTableMatchesTheGoldenFileSharedWithTheBuilder() {
        val golden = File("../book-dictionary/v2/tests/golden_genres.json")
        assumeTrue("golden file is not in this checkout", golden.exists())
        val names = JSONObject(golden.readText()).getJSONObject("names")
        assertEquals(names.length(), BookGenres.names.size)
        for (key in names.keys()) {
            val pair = names.getJSONArray(key)
            assertEquals(key, pair.getString(0), BookGenres.label(key, english = false))
            assertEquals(key, pair.getString(1), BookGenres.label(key, english = true))
        }
    }
}
