package com.example.myapplication.audiobooks.catalog

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.squareup.zstd.okio.zstdDecompress
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.HashingSink
import okio.HashingSource
import okio.blackholeSink
import okio.buffer
import okio.sink

/**
 * Телефонный индекс каталога книг (BookIndex V2): один SQLite-файл с FTS4, который обновляется с сервера.
 * Первый запуск — база целиком (zstd, ~10 МБ); дальше — маленькие дельты, а база целиком — только если
 * дельт нет или они стоят дороже половины базы ([BookIndexPlanner]). Всё проверяется по SHA-256 из манифеста,
 * дельта применяется одной транзакцией: при любом сбое остаётся прежний индекс.
 */
class BookIndexStore(
    private val dir: File,
    private val http: OkHttpClient,
    private val apiV1: String = BookCatalogConfig.API_V1,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { UP_TO_DATE, UPDATED, SKIPPED, UNSUPPORTED, FAILED }

    @Serializable
    private data class State(val checkedAtMs: Long = 0, val etag: String? = null)

    private val json = Json { ignoreUnknownKeys = true }
    /** Одно обновление за раз; поиск его не ждёт (скачивание занимает секунды, а поиск должен отвечать сразу). */
    private val refreshLock = Mutex()
    /** Короткая блокировка на открытую базу: поиск берёт её на миг, обновление — на подмену файла/транзакцию дельты. */
    private val dbLock = Any()
    private val file get() = File(dir, "index.sqlite")
    private val stateFile get() = File(dir, "state.json")
    @Volatile private var db: SQLiteDatabase? = null

    /** Запросы к индексу или null, пока индекса на устройстве ещё нет. */
    suspend fun query(): IndexQuery? = withContext(Dispatchers.IO) {
        synchronized(dbLock) { openReadable() } ?: return@withContext null
        IndexQuery { sql, args ->
            // База берётся и читается под одной блокировкой: обновление не закроет её между этими шагами.
            synchronized(dbLock) {
            val open = openReadable() ?: return@synchronized emptyList()
            open.rawQuery(sql, args.toTypedArray()).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            List(c.columnCount) { i ->
                                when (c.getType(i)) {
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                    android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                    android.database.Cursor.FIELD_TYPE_STRING -> c.getString(i)
                                    else -> null
                                }
                            },
                        )
                    }
                }
            }
            }
        }
    }

    /** Версия индекса на устройстве; null — индекса нет или он повреждён. */
    suspend fun localVersion(): Int? = withContext(Dispatchers.IO) { synchronized(dbLock) { readVersion(openReadable()) } }

    /**
     * Свериться с манифестом (не чаще раза в [CHECK_INTERVAL_MS], если индекс уже есть) и догнать его.
     * Сбои сети и битые файлы не бросаются: возвращается [Outcome.FAILED], индекс остаётся прежним.
     */
    suspend fun refresh(force: Boolean = false): Outcome = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            try {
                dir.mkdirs()
                val local = synchronized(dbLock) { readVersion(openReadable()) }
                val state = readState()
                if (!force && local != null && nowMs() - state.checkedAtMs < CHECK_INTERVAL_MS) return@withLock Outcome.SKIPPED
                val manifest = fetchManifest(state.etag)
                    ?: return@withLock Outcome.FAILED
                // ETag запоминаем только вместе с версией, которая действительно встала на диск:
                // иначе после сбоя установки сервер отвечал бы 304, и старый индекс остался бы надолго.
                val m = manifest.first ?: run {
                    if (local == null) return@withLock Outcome.FAILED
                    writeState(State(nowMs(), manifest.second))
                    return@withLock Outcome.UP_TO_DATE
                }
                val outcome = when (val plan = BookIndexPlanner.plan(local, m)) {
                    BookIndexPlan.UpToDate -> Outcome.UP_TO_DATE
                    BookIndexPlan.Unsupported -> Outcome.UNSUPPORTED
                    is BookIndexPlan.FullBase -> { installBase(plan.base); Outcome.UPDATED }
                    is BookIndexPlan.Deltas -> {
                        try {
                            plan.steps.forEach(::applyDelta)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Дельта не легла (битый файл, чужая база): надёжнее скачать базу целиком.
                            Log.w(TAG, "delta failed, installing the full base", e)
                            installBase(m.base)
                        }
                        Outcome.UPDATED
                    }
                }
                writeState(State(nowMs(), manifest.second.takeUnless { outcome == Outcome.UNSUPPORTED }))
                outcome
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "index refresh failed", e)
                Outcome.FAILED
            }
        }
    }

    // --- манифест -------------------------------------------------------------------------------------------

    /** Пара (манифест или null при 304, новый ETag); null — сбой. */
    private fun fetchManifest(etag: String?): Pair<BookIndexManifest?, String?>? {
        val req = Request.Builder().url("$apiV1/manifest").apply { if (etag != null && file.exists()) header("If-None-Match", etag) }.build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 304) return null to etag
            if (!resp.isSuccessful) return null
            val body = resp.body.string()
            return json.decodeFromString(BookIndexManifest.serializer(), body) to resp.header("ETag")
        }
    }

    // --- файлы ----------------------------------------------------------------------------------------------

    private fun fileUrl(rel: String) = "$apiV1/$rel"

    private fun installBase(base: BookIndexManifest.BaseRef) {
        val part = File(dir, "index.sqlite.part").also { it.delete() }
        val compressedHash = download(fileUrl(base.url)) { src ->
            val out = HashingSink.sha256(part.sink())
            out.buffer().use { it.writeAll(src) }
            check(part.length() == base.rawBytes) { "base size ${part.length()} != ${base.rawBytes}" }
            check(out.hash.hex() == base.rawSha256) { "base content hash mismatch" }
        }
        check(compressedHash == base.sha256) { "base download hash mismatch" }
        SQLiteDatabase.openDatabase(part.path, null, SQLiteDatabase.OPEN_READONLY).use { check(readVersion(it) == base.version) { "base version" } }
        synchronized(dbLock) {
            db?.close(); db = null
            // Замена одним переименованием: если оно не удалось, прежний индекс цел.
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun applyDelta(step: BookIndexManifest.DeltaRef) {
        val lines = mutableListOf<String>()
        val hash = download(fileUrl(step.url)) { src -> lines += src.readUtf8().lineSequence().filter { it.isNotEmpty() } }
        check(hash == step.sha256) { "delta download hash mismatch" }
        val header = json.parseToJsonElement(lines.first()).jsonObject
        check(header["from"]!!.jsonPrimitive.int == step.from && header["to"]!!.jsonPrimitive.int == step.to) { "delta header" }
        val parsed = (1 until lines.size).map { json.parseToJsonElement(lines[it]).jsonArray } // разбор вне блокировки
        synchronized(dbLock) {
            db?.close(); db = null
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { w ->
                check(readVersion(w) == step.from) { "delta does not start at the local version" }
                w.beginTransaction()
                try {
                    for (row in parsed) {
                        w.execSQL("DELETE FROM books WHERE docid = ?", arrayOf<Any?>(row[0].jsonPrimitive.long()))
                        if (row.size > 1) {
                            w.execSQL(
                                "INSERT INTO books(docid, ru, en, ra, ea, alt, x, y, f, cv) VALUES (?,?,?,?,?,?,?,?,?,?)",
                                Array<Any?>(10) { k -> value(row[k]) },
                            )
                        }
                    }
                    w.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES ('version', ?)", arrayOf<Any?>(step.to.toString()))
                    w.setTransactionSuccessful()
                } finally {
                    w.endTransaction()
                }
            }
        }
    }

    private fun value(e: kotlinx.serialization.json.JsonElement): Any? = when {
        e is JsonNull -> null
        e is JsonPrimitive && e.isString -> e.content
        e is JsonPrimitive -> e.longOrNull ?: e.content
        else -> null
    }

    private fun JsonPrimitive.long(): Long = longOrNull ?: error("id is not a number")

    /** Качает файл, отдаёт [consume] распакованный поток и возвращает SHA-256 скачанных (сжатых) байт. */
    private fun download(url: String, consume: (okio.BufferedSource) -> Unit): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
            val hashing = HashingSource.sha256(resp.body.source())
            val compressed = hashing.buffer()
            consume(compressed.zstdDecompress().buffer())
            compressed.readAll(blackholeSink()) // дочитать хвост, иначе хэш посчитан не по всему файлу
            return hashing.hash.hex()
        }
    }

    // --- база ------------------------------------------------------------------------------------------------

    private fun openReadable(): SQLiteDatabase? {
        db?.takeIf { it.isOpen }?.let { return it }
        if (!file.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
        } catch (_: Exception) {
            file.delete() // не открывается — как будто индекса нет, следующее обновление поставит новый
            null
        }
    }

    private fun readVersion(d: SQLiteDatabase?): Int? = d?.let {
        try {
            if (it.version != BookIndexPlanner.SUPPORTED_SCHEMA) return null
            it.rawQuery("SELECT v FROM meta WHERE k = 'version'", null).use { c -> if (c.moveToFirst()) c.getString(0).toIntOrNull() else null }
        } catch (_: Exception) {
            null
        }
    }

    private fun readState(): State = runCatching { json.decodeFromString(State.serializer(), stateFile.readText()) }.getOrDefault(State())

    private fun writeState(s: State) {
        runCatching { stateFile.writeText(json.encodeToString(State.serializer(), s)) }
    }

    companion object {
        /** Манифест спрашиваем не чаще, чем раз в трое суток. */
        const val CHECK_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000
        private const val TAG = "BookIndex"
    }
}
