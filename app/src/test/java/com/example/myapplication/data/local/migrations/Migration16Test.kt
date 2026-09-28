package com.example.myapplication.data.local.migrations

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.myapplication.data.local.AnimeDatabase
import com.example.myapplication.data.local.Audiobook_narration
import com.example.myapplication.data.local.Audiobook_progress
import com.example.myapplication.data.local.Audiobook_work
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `16.sqm` (16 → 17) создаёт таблицы аудиокниг. Главный риск — расхождение миграции с `Audiobook.sq`:
 * тогда обновлённая установка и чистая получили бы разные схемы. Здесь это сравнивается напрямую.
 */
class Migration16Test {

    private lateinit var migrated: JdbcSqliteDriver
    private lateinit var fresh: JdbcSqliteDriver

    @Before
    fun setUp() {
        migrated = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        migrated.execute(null, "CREATE TABLE anime (id TEXT PRIMARY KEY NOT NULL)", 0)
        migrated.execute(null, "PRAGMA user_version = 16", 0)
        // 16.sqm создаёт таблицы, 17.sqm добавляет избранное, 18.sqm — библиотеку; сравниваем с итоговой схемой.
        AnimeDatabase.Schema.migrate(migrated, 16, AnimeDatabase.Schema.version).value
        fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AnimeDatabase.Schema.create(fresh).value
    }

    @After
    fun tearDown() {
        migrated.close()
        fresh.close()
    }

    @Test
    fun `upgraded and fresh installs have identical audiobook schema`() {
        assertEquals(audiobookSchema(fresh), audiobookSchema(migrated))
        assertEquals(7, audiobookSchema(migrated).count { it.startsWith("table ") })
    }

    @Test
    fun `continue listening returns unfinished books newest first`() {
        val q = AnimeDatabase(migrated).audiobookQueries
        listOf("w1" to 10L, "w2" to 20L, "w3" to 30L).forEach { (id, at) ->
            q.upsertWork(work(id))
            q.upsertNarration(narration("n-$id", id))
            q.upsertProgress(progress("n-$id", id, updatedAt = at, finished = if (id == "w3") 1 else 0))
        }

        val rows = q.continueListening(limit = 5).executeAsList()

        assertEquals(listOf("w2", "w1"), rows.map { it.work_id })
        assertEquals("Title w2", rows.first().title)
    }

    private fun audiobookSchema(driver: JdbcSqliteDriver): List<String> = driver.executeQuery(
        identifier = null,
        sql = "SELECT type, name, sql FROM sqlite_master WHERE name LIKE 'audiobook_%' ORDER BY name",
        mapper = { c ->
            QueryResult.Value(buildList {
                while (c.next().value) add("${c.getString(0)} ${c.getString(1)} ${c.getString(2)?.normalize()}")
            })
        },
        parameters = 0,
    ).value

    // ALTER TABLE ADD COLUMN SQLite дописывает в текст CREATE с пробелом перед запятой — пробелы у
    // скобок и запятых смысла не несут.
    private fun String.normalize() = replace(Regex("\\s+"), " ").replace(Regex("\\s*([(),])\\s*"), "$1").trim()

    private fun work(id: String) = Audiobook_work(
        work_id = id, cluster_fingerprint = "fp-$id", collection_id = null, title = "Title $id",
        title_original = null, authors_json = "[]", series_title = null, series_index = null,
        description = null, genres_json = "[]", language = "RU", year = null, is_collection = 0,
        cover_url = null, cover_palette_json = null, cover_blurhash = null, selected_narration_id = null,
        updated_at = 0, is_favorite = 0, in_library = 0,
    )

    private fun narration(id: String, workId: String) = Audiobook_narration(
        narration_id = id, work_id = workId, cluster_fingerprint = "fp-$id", narrators_json = "[]",
        kind = "SOLO", duration_ms = null, chapter_count = null,
    )

    private fun progress(narrationId: String, workId: String, updatedAt: Long, finished: Long) = Audiobook_progress(
        narration_id = narrationId, work_id = workId, variant_id = null, global_ms = 1_000,
        chapter_idx = 0, chapter_offset_ms = 1_000, total_ms = null, speed = 1.0, finished = finished,
        updated_at = updatedAt,
    )
}
