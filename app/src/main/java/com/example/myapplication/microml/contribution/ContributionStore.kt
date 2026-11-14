package com.example.myapplication.microml.contribution

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

@Serializable
private data class QueuedPair(val pack: String, val source: String, val label: Int, val kinds: List<Int>, val features: List<Float>)

@Serializable
private data class Queue(val pairs: List<QueuedPair> = emptyList(), val genres: List<String> = emptyList())

/**
 * Согласие, случайный идентификатор установки и очередь на отправку.
 *  - По умолчанию согласия нет: пока пользователь не включил переключатель, ничего не копится и не уходит.
 *  - Идентификатор — 16 случайных байт, не связанных ни с аккаунтом, ни с устройством; через 30 дней заменяется.
 *  - Очередь ограничена; при выключении согласия идентификатор и очередь удаляются сразу.
 */
class ContributionStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("microml_contribution", Context.MODE_PRIVATE)
    private val file = AtomicFile(File(File(app.filesDir, "microml").apply { mkdirs() }, "contribution-queue.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean): Unit = synchronized(lock) {
        if (enabled) {
            prefs.edit().putBoolean(KEY_ENABLED, true).apply()
            installId()
        } else {
            prefs.edit().clear().apply()
            file.delete()
        }
    }

    /** Случайный идентификатор установки; создаётся только после согласия и обновляется раз в [ROTATE_DAYS] дней. */
    fun installId(): String? = synchronized(lock) {
        if (!isEnabled()) return null
        val created = prefs.getLong(KEY_CREATED, 0L)
        val current = prefs.getString(KEY_INSTALL, null)
        if (current != null && now() - created < ROTATE_DAYS * DAY_MS) return current
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val fresh = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        prefs.edit().putString(KEY_INSTALL, fresh).putLong(KEY_CREATED, now()).apply()
        fresh
    }

    fun lastUploadDay(): Long = prefs.getLong(KEY_LAST_UPLOAD_DAY, -1L)
    fun markUploaded() { prefs.edit().putLong(KEY_LAST_UPLOAD_DAY, now() / DAY_MS).apply() }

    private fun read(): Queue = synchronized(lock) {
        runCatching { json.decodeFromString(Queue.serializer(), file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }) }
            .getOrDefault(Queue())
    }

    private fun write(queue: Queue) = synchronized(lock) {
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(Queue.serializer(), queue).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
        }
    }

    fun addPair(entry: PairEntry) {
        if (!isEnabled() || !entry.isValid()) return
        synchronized(lock) {
            val queue = read()
            val pairs = (queue.pairs + QueuedPair(entry.pack, entry.source, entry.label, entry.kinds, entry.features)).takeLast(MAX_QUEUED_PAIRS)
            write(queue.copy(pairs = pairs))
        }
    }

    fun addGenre(raw: String) {
        if (!isEnabled()) return
        val clean = ContributionSchema.genre(raw) ?: return
        synchronized(lock) {
            val queue = read()
            if (clean in queue.genres || queue.genres.size >= MAX_QUEUED_GENRES) return
            write(queue.copy(genres = queue.genres + clean))
        }
    }

    fun pairs(): List<PairEntry> = read().pairs.map { PairEntry(it.pack, it.source, it.label, it.kinds, it.features) }
    fun genres(): List<String> = read().genres

    /** Убирает из очереди то, что только что ушло: если за время отправки добавилось новое, оно остаётся. */
    fun removeSent(pairs: Int, genres: Set<String>): Unit = synchronized(lock) {
        val queue = read()
        write(Queue(queue.pairs.drop(pairs), queue.genres.filterNot { it in genres }))
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INSTALL = "install"
        private const val KEY_CREATED = "created"
        private const val KEY_LAST_UPLOAD_DAY = "last_upload_day"
        const val DAY_MS = 86_400_000L
        const val ROTATE_DAYS = 30L
        const val MAX_QUEUED_PAIRS = 200
        const val MAX_QUEUED_GENRES = 200
    }
}
