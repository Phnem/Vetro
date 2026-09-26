package com.example.myapplication.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import kotlinx.collections.immutable.persistentListOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Все чтения коллекции идут через один [concatRowToAnime]. Регрессия: раньше `getAnimeById` и
 * `getAllAnimeList` теряли `mal_not_found_at` (и у части запросов — id TMDB/Кинопоиска), и
 * сохранение из AddEdit записывало обратно NULL.
 */
class AnimeRowMappingTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AnimeDatabase

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AnimeDatabase.Schema.create(driver).value
        database = AnimeDatabase(driver)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private val entry = Anime(
        id = "a1",
        title = "Title",
        titleEn = "Title EN",
        titleRu = "Название",
        episodes = 12,
        rating = 8f,
        imageFileName = "cover.jpg",
        orderIndex = 3,
        dateAdded = 42,
        tags = persistentListOf("Action", "Drama"),
        categoryType = "ANIME",
        comment = "note",
        anilistId = 10,
        malId = null,
        shikimoriId = 30,
        anilistNotFoundAt = null,
        malNotFoundAt = 1_700_000_000_000,
        shikimoriNotFoundAt = null,
        mediaType = MediaType.ANIME,
        tmdbId = 555,
        kinopoiskId = 777,
        tmdbNotFoundAt = null,
        kinopoiskNotFoundAt = 1_600_000_000_000,
        imdbId = "tt0000001",
    )

    @Test
    fun `read by id keeps every persisted field`() {
        database.insertNewAnime(entry, updatedAt = 1)

        val read = database.animeQueries.getAnimeWithTagsConcatById("a1", ::concatRowToAnime).executeAsOne()

        assertEquals(entry, read)
    }

    @Test
    fun `full list and title-candidate queries map the same way`() {
        database.insertNewAnime(entry.copy(titleEn = null, titleRu = null), updatedAt = 1)

        val all = database.animeQueries.getAllAnimeWithTagsConcat(::concatRowToAnime).executeAsList()
        val needEn = database.animeQueries.selectNeedingTitleEn(10, ::concatRowToAnime).executeAsList()

        assertEquals(1_700_000_000_000, all.single().malNotFoundAt)
        assertEquals(listOf("Action", "Drama"), all.single().tags)
        assertEquals(all, needEn)
    }
}
