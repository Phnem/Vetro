package com.example.myapplication.data.local.migrations

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.myapplication.data.local.AnimeDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Прогоняет ровно `15.sqm` (переход version 15 → 16): добавление `imdb_id`.
 *
 * Главный риск этой миграции не в самом ALTER, а в `upsertFromSync`: он написан как
 * `INSERT OR REPLACE`, поэтому колонка, забытая в его списке, молча обнуляется при каждом pull.
 * Именно это здесь и проверяется отдельным тестом.
 */
class Migration15Test {

    private lateinit var driver: JdbcSqliteDriver

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, V14_CREATE_TABLE, 0)
        driver.execute(null, "PRAGMA user_version = 15", 0)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `existing rows survive with a null imdb id`() {
        seed("house")
        migrate()

        val rows = AnimeDatabase(driver).animeQueries.getAllAnime().executeAsList()
        assertEquals(1, rows.size)
        assertNull(rows.single().imdb_id)
    }

    @Test
    fun `imdb id round trips as text preserving the tt prefix and leading zeros`() {
        seed("house")
        migrate()
        val queries = AnimeDatabase(driver).animeQueries

        queries.setImdbId(imdb_id = "tt0412142", updatedAt = 1L, id = "house")

        assertEquals("tt0412142", queries.getAllAnime().executeAsList().single().imdb_id)
    }

    @Test
    fun `a cloud pull does not wipe a locally resolved imdb id`() {
        seed("house")
        migrate()
        val queries = AnimeDatabase(driver).animeQueries
        queries.setImdbId(imdb_id = "tt0412142", updatedAt = 1L, id = "house")

        // upsertFromSync is INSERT OR REPLACE: imdb_id is not part of the cloud schema, so it must
        // be carried over by self-select or every pull would silently erase it.
        queries.upsertFromSync(
            id = "house",
            title = "Doctor House",
            imagePath = null,
            episodes = 1L,
            rating = 0L,
            status = "watching",
            isFavorite = 0L,
            updatedAt = 2L,
            orderIndex = 0L,
            dateAdded = 0L,
            categoryType = "SERIES",
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
            mediaType = "SERIES",
            title_en = null,
            title_en_checked_at = null,
            title_ru = null,
            title_ru_checked_at = null,
        )

        assertEquals("tt0412142", queries.getAllAnime().executeAsList().single().imdb_id)
    }

    private fun seed(id: String) {
        driver.execute(
            null,
            """
            INSERT INTO anime(
                id, title, episodes, rating, status, updatedAt, dateAdded, categoryType, mediaType
            ) VALUES ('$id', 'Doctor House', 1, 0, 'watching', 0, 0, 'SERIES', 'SERIES')
            """.trimIndent(),
            0
        )
    }

    private fun migrate() {
        // 15.sqm срабатывает на переходе 15 → 16 (файл N.sqm — миграция ИЗ версии N). Дальше идём до
        // актуальной схемы: запросы (upsertFromSync и др.) обращаются к колонкам более поздних
        // миграций, и на «застрявшей» на 16-й версии схеме они падают на несуществующей колонке.
        AnimeDatabase.Schema.migrate(driver, 15, AnimeDatabase.Schema.version).value
    }

    private companion object {
        // Форма `anime` после 14.sqm, до 15.sqm — без imdb_id.
        const val V14_CREATE_TABLE = """
            CREATE TABLE anime (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                imagePath TEXT,
                episodes INTEGER NOT NULL,
                rating INTEGER NOT NULL,
                status TEXT NOT NULL,
                isFavorite INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL,
                orderIndex INTEGER NOT NULL DEFAULT 0,
                dateAdded INTEGER NOT NULL,
                categoryType TEXT NOT NULL DEFAULT '',
                sync_status TEXT NOT NULL DEFAULT 'SYNCED',
                comment TEXT NOT NULL DEFAULT '',
                isAiRecommendation INTEGER NOT NULL DEFAULT 0,
                anilist_id INTEGER,
                mal_id INTEGER,
                shikimori_id INTEGER,
                anilist_not_found_at INTEGER,
                mal_not_found_at INTEGER,
                shikimori_not_found_at INTEGER,
                isPrivate INTEGER NOT NULL DEFAULT 0,
                encryptionIv TEXT,
                deletedAt INTEGER,
                mediaType TEXT NOT NULL DEFAULT 'ANIME',
                title_en TEXT,
                title_en_checked_at INTEGER,
                title_ru TEXT,
                title_ru_checked_at INTEGER,
                tmdb_id INTEGER,
                tmdb_not_found_at INTEGER,
                kinopoisk_id INTEGER,
                kinopoisk_not_found_at INTEGER
            )
        """
    }
}
