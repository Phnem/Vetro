package com.example.myapplication.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.WatchStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Статус просмотра и год релиза — локальные колонки: читаются, пишутся точечно и переживают pull. */
class WatchStatusPersistenceTest {
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

    private fun anime(id: String, status: WatchStatus? = null, year: Int? = null) = Anime(
        id = id,
        title = "Title $id",
        episodes = 12,
        rating = 8f,
        imageFileName = null,
        orderIndex = 0,
        dateAdded = 1,
        watchStatus = status,
        releaseYear = year,
    )

    private fun read(id: String): Anime =
        database.animeQueries.getAnimeWithTagsConcatById(id, ::concatRowToAnime).executeAsOne()

    @Test
    fun `status and year round-trip through insert and read`() {
        database.insertNewAnime(anime("a", WatchStatus.ON_HOLD, 2019), updatedAt = 1)

        val read = read("a")

        assertEquals(WatchStatus.ON_HOLD, read.watchStatus)
        assertEquals(2019, read.releaseYear)
    }

    @Test
    fun `no choice reads back as auto`() {
        database.insertNewAnime(anime("a"), updatedAt = 1)

        assertNull(read("a").watchStatus)
        assertNull(read("a").releaseYear)
    }

    @Test
    fun `setWatchStatus writes a choice and null returns to auto`() {
        database.insertNewAnime(anime("a"), updatedAt = 1)

        database.animeQueries.setWatchStatus(watch_status = WatchStatus.DROPPED.name, updatedAt = 2, id = "a")
        assertEquals(WatchStatus.DROPPED, read("a").watchStatus)

        database.animeQueries.setWatchStatus(watch_status = null, updatedAt = 3, id = "a")
        assertNull(read("a").watchStatus)
    }

    @Test
    fun `unknown persisted status is read as auto instead of crashing`() {
        database.insertNewAnime(anime("a"), updatedAt = 1)
        database.animeQueries.setWatchStatus(watch_status = "FROM_THE_FUTURE", updatedAt = 2, id = "a")

        assertNull(read("a").watchStatus)
    }

    @Test
    fun `year backfill picks only rows never looked up and zero counts as looked up`() {
        database.insertNewAnime(anime("none"), updatedAt = 1)
        database.insertNewAnime(anime("known", year = 2001), updatedAt = 1)
        database.insertNewAnime(anime("searched", year = 0), updatedAt = 1)

        val needing = database.animeQueries.selectNeedingReleaseYear(10, ::concatRowToAnime).executeAsList()

        assertEquals(listOf("none"), needing.map { it.id })
        assertEquals(1L, database.animeQueries.countNeedingReleaseYear().executeAsOne())
    }

    @Test
    fun `setReleaseYear does not touch sync bookkeeping`() {
        database.insertNewAnime(anime("a"), updatedAt = 1)
        database.animeQueries.markAnimeSynced("a")

        database.animeQueries.setReleaseYear(release_year = 1999, id = "a")

        val row = database.animeQueries.getAllAnime().executeAsList().single()
        assertEquals("SYNCED", row.sync_status)
        assertEquals(1L, row.updatedAt)
        assertEquals(1999L, row.release_year)
    }

    @Test
    fun `a cloud pull keeps the local status and year`() {
        database.insertNewAnime(anime("a", WatchStatus.REWATCHING, 2010), updatedAt = 1)

        database.animeQueries.upsertFromSync(
            id = "a",
            title = "Title a",
            imagePath = null,
            episodes = 12L,
            rating = 80L,
            status = "watching",
            isFavorite = 0L,
            updatedAt = 5L,
            orderIndex = 0L,
            dateAdded = 1L,
            categoryType = "",
            comment = "",
            isAiRecommendation = 0L,
            anilist_id = null,
            mal_id = null,
            shikimori_id = null,
            anilist_not_found_at = null,
            mal_not_found_at = null,
            shikimori_not_found_at = null,
            isPrivate = 0L,
            encryptionIv = null,
            deletedAt = null,
            mediaType = "ANIME",
            title_en = null,
            title_en_checked_at = null,
            title_ru = null,
            title_ru_checked_at = null,
        )

        val read = read("a")
        assertEquals(WatchStatus.REWATCHING, read.watchStatus)
        assertEquals(2010, read.releaseYear)
    }
}
