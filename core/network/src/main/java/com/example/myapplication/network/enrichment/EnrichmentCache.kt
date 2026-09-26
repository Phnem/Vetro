package com.example.myapplication.network.enrichment

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Кэш ответов API обогащения: файл на запись (тело ответа как есть), не общий JSON в памяти —
 * ответы TMDb с картинками весят десятки килобайт. Хранится срок жизни и отметка «ответа нет»
 * (отрицательный кэш: не долбить API по тайтлам, которых там нет). Старое тело отдаётся, если сеть
 * не ответила. Лимит размера — старые записи удаляются первыми.
 */
class EnrichmentCache(
    private val dir: File,
    private val maxBytes: Long = 24L * 1024 * 1024,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val body: String?, val fetchedAt: Long, val expiresAt: Long) {
        /** Ответа нет (404, пустая выдача): известно, что искать бессмысленно до [expiresAt]. */
        val isNegative: Boolean get() = body == null
    }

    private val mutex = Mutex()

    suspend fun get(key: String): Entry? = withContext(Dispatchers.IO) {
        val file = fileOf(key)
        if (!file.isFile) return@withContext null
        runCatching {
            file.bufferedReader().use { r ->
                val header = r.readLine() ?: return@use null
                val parts = header.split(' ')
                if (parts.size < 3 || parts[0] != MAGIC) return@use null
                val negative = parts.getOrNull(3) == "neg"
                Entry(if (negative) null else r.readText(), parts[1].toLong(), parts[2].toLong())
            }
        }.getOrNull()
    }

    suspend fun put(key: String, body: String?, ttlMs: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.mkdirs()
            val now = nowMs()
            val tmp = File(dir, fileOf(key).name + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write("$MAGIC $now ${now + ttlMs}${if (body == null) " neg" else ""}\n")
                if (body != null) w.write(body)
            }
            tmp.renameTo(fileOf(key)) || run { fileOf(key).delete(); tmp.renameTo(fileOf(key)) }
            trim()
        }
    }

    fun isFresh(entry: Entry): Boolean = entry.expiresAt > nowMs()

    private fun trim() {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return
        var total = files.sumOf(File::length)
        if (total <= maxBytes) return
        for (f in files.sortedBy(File::lastModified)) {
            if (total <= maxBytes * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    private fun fileOf(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) })
    }

    private companion object {
        const val MAGIC = "v1"
    }
}

/** Сколько живёт запись: удачный ответ и «ответа нет» — по-разному. */
data class CachePolicy(val key: String, val ttlMs: Long, val negativeTtlMs: Long = ttlMs / 4)

object CacheTtl {
    const val HOUR = 60L * 60 * 1000
    const val DAY = 24 * HOUR
}
