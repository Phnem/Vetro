package com.example.myapplication.data.local

import app.cash.sqldelight.db.QueryResult
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.myapplication.data.local.AnimeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

class SQLDelightDatabaseFactory(private val context: Context) {

    /**
     * Драйвер открывается один раз и на весь процесс. `lazy` синхронизирован: при холодном старте
     * к БД одновременно приходят сплэш, фоновый координатор статистики и воркеры, и без блокировки
     * каждый мог открыть собственный драйвер. Flow, подписанный на один драйвер, не видит записей
     * через другой — список «замерзал».
     */
    private val lazyDriver: SqlDriver by lazy {
        alignLegacyAnimeDbUserVersionOnce()
        AndroidSqliteDriver(
            schema = AnimeDatabase.Schema,
            context = context,
            name = "anime.db"
        )
    }

    private val lazyDatabase: AnimeDatabase by lazy { AnimeDatabase(lazyDriver) }

    private fun getDriver(): SqlDriver = lazyDriver

    /**
     * Выравнивание легаси-установок нужно один раз на версию схемы: дальше колонки и
     * `user_version` уже в порядке, а лишнее открытие файла и три PRAGMA на каждом холодном
     * старте — чистые потери.
     */
    private fun alignLegacyAnimeDbUserVersionOnce() {
        val prefs = context.getSharedPreferences(ALIGN_PREFS, Context.MODE_PRIVATE)
        val key = "aligned_v${AnimeDatabase.Schema.version}"
        if (prefs.getBoolean(key, false)) return
        alignLegacyAnimeDbUserVersion()
        prefs.edit().putBoolean(key, true).apply()
    }

    /**
     * Legacy installs where columns were added outside SQLDelight may have new columns but stale `user_version`.
     * Bump version so SQLDelight does not re-run ALTER (duplicate column).
     */
    private fun alignLegacyAnimeDbUserVersion() {
        val dbFile = context.getDatabasePath("anime.db")
        if (!dbFile.exists()) return
        val targetVersion = AnimeDatabase.Schema.version
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            fun readAnimeColumns(): Set<String> = db.rawQuery("PRAGMA table_info(anime)", null).use { c ->
                buildSet {
                    while (c.moveToNext()) add(c.getString(1))
                }
            }
            fun ensureColumn(name: String, ddl: String) {
                try {
                    db.execSQL(ddl)
                } catch (e: Exception) {
                    Log.w("SQLDelight", "alignLegacy: column=$name", e)
                }
            }

            val colsInitial = readAnimeColumns()
            // Не полагаться только на .sqm: старая логика могла поднять user_version без ALTER.
            if (!colsInitial.contains("isAiRecommendation")) {
                ensureColumn(
                    "isAiRecommendation",
                    "ALTER TABLE anime ADD COLUMN isAiRecommendation INTEGER NOT NULL DEFAULT 0"
                )
            }
            if (!colsInitial.contains("anilist_id")) {
                ensureColumn("anilist_id", "ALTER TABLE anime ADD COLUMN anilist_id INTEGER")
            }
            if (!colsInitial.contains("mal_id")) {
                ensureColumn("mal_id", "ALTER TABLE anime ADD COLUMN mal_id INTEGER")
            }
            if (!colsInitial.contains("shikimori_id")) {
                ensureColumn("shikimori_id", "ALTER TABLE anime ADD COLUMN shikimori_id INTEGER")
            }
            if (!colsInitial.contains("anilist_not_found_at")) {
                ensureColumn("anilist_not_found_at", "ALTER TABLE anime ADD COLUMN anilist_not_found_at INTEGER")
            }
            if (!colsInitial.contains("mal_not_found_at")) {
                ensureColumn("mal_not_found_at", "ALTER TABLE anime ADD COLUMN mal_not_found_at INTEGER")
            }
            if (!colsInitial.contains("shikimori_not_found_at")) {
                ensureColumn("shikimori_not_found_at", "ALTER TABLE anime ADD COLUMN shikimori_not_found_at INTEGER")
            }
            val ver = db.rawQuery("PRAGMA user_version", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
            val cols = readAnimeColumns()
            
            // Fix for users who had their version bumped to 7 by the legacy logic without running 6.sqm
            if (ver >= 7L && !cols.contains("isPrivate")) {
                Log.d("SQLDelight", "Downgrading user_version to 6 because isPrivate is missing")
                db.execSQL("PRAGMA user_version = 6")
            } else if (ver >= targetVersion) {
                return@use
            }

            val hasSync = cols.contains("sync_status")
            val hasComment = cols.contains("comment")
            val hasEpisodeCheckColumns = cols.contains("anilist_id")
                && cols.contains("mal_id")
                && cols.contains("shikimori_id")
                && cols.contains("anilist_not_found_at")
                && cols.contains("mal_not_found_at")
                && cols.contains("shikimori_not_found_at")
            when {
                hasSync && hasComment && hasEpisodeCheckColumns && ver < 6L ->
                    db.execSQL("PRAGMA user_version = 6")
                hasSync && !hasComment && ver < 3L ->
                    db.execSQL("PRAGMA user_version = 3")
            }
        }
    }

    fun getDatabase(): AnimeDatabase = lazyDatabase

    suspend fun checkpoint() {
        withContext(Dispatchers.IO) {
            try {
                // Публичный API драйвера, а не рефлексия в его поле SQLiteDatabase: рефлексию
                // ломала бы обфускация, и ради неё SQLDelight держался в релизе целиком (-keep).
                val result = getDriver().executeQuery(
                    identifier = null,
                    sql = "PRAGMA wal_checkpoint(FULL)",
                    mapper = { cursor ->
                        QueryResult.Value(
                            if (cursor.next().value) {
                                Triple(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
                            } else {
                                null
                            },
                        )
                    },
                    parameters = 0,
                ).value
                result?.let { (busy, log, checkpointed) ->
                    Log.d("SQLDelight", "WAL checkpoint: busy=$busy, log=$log, checkpointed=$checkpointed")
                }
            } catch (e: Exception) {
                // Checkpoint is not critical, log and continue
                Log.w("SQLDelight", "Failed to checkpoint WAL", e)
            }
        }
    }

    private companion object {
        const val ALIGN_PREFS = "anime_db_legacy_align"
    }
}
