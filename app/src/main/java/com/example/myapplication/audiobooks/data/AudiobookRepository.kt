package com.example.myapplication.audiobooks.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.model.WorkId
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.BookLanguage
import com.example.myapplication.audiobooks.domain.source.SourceBookDetails
import com.example.myapplication.data.local.AnimeDatabase
import com.example.myapplication.data.local.Audiobook_narration
import com.example.myapplication.data.local.Audiobook_progress
import com.example.myapplication.data.local.Audiobook_variant
import com.example.myapplication.data.local.Audiobook_work
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Книги раздела в БД (spec/03). Работает только с таблицами `audiobook_*`: строка коллекции Vetro
 * появится в AB-12/AB-33, до тех пор `collection_id` пуст.
 */
class AudiobookRepository(
    private val db: AnimeDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val q get() = db.audiobookQueries

    /**
     * Книга, открытая у источника: вариант → озвучка → произведение. Озвучки одного произведения
     * собираются под одним `WorkId` по отпечатку (авторы + название + язык); озвучка внутри — по чтецам.
     * Полная кластеризация с порогами — AB-24; это её консервативное начало.
     */
    suspend fun saveOpened(source: AudiobookSource, details: SourceBookDetails): OpenedBook = io {
        val book = details.book
        val variantId = source.variantOf(book.ref)
        val language = source.languages.singleOrNull() ?: BookLanguage.RU
        q.transactionWithResult {
            val existing = q.variantById(variantId.value).executeAsOneOrNull()
            val narration = existing?.let { q.narrationById(it.narration_id).executeAsOneOrNull() }
            val workFp = fingerprint(book.authors, book.title, language.name)
            val work = narration?.let { q.workById(it.work_id).executeAsOneOrNull() }
                ?: q.worksByFingerprint(workFp).executeAsList().firstOrNull()
            val workId = work?.work_id ?: WorkId.new().value
            q.upsertWork(
                Audiobook_work(
                    work_id = workId,
                    cluster_fingerprint = workFp,
                    collection_id = work?.collection_id,
                    title = book.title,
                    title_original = work?.title_original,
                    authors_json = json.encodeToString(book.authors),
                    series_title = work?.series_title,
                    series_index = work?.series_index,
                    description = details.description ?: work?.description,
                    genres_json = json.encodeToString(book.genres),
                    language = language.name,
                    year = book.year?.toLong() ?: work?.year,
                    is_collection = work?.is_collection ?: 0,
                    cover_url = work?.cover_url ?: book.coverUrl,
                    cover_palette_json = work?.cover_palette_json,
                    cover_blurhash = work?.cover_blurhash,
                    selected_narration_id = work?.selected_narration_id,
                    updated_at = nowMs(),
                ),
            )
            val narrationFp = fingerprint(book.narrators, "", "")
            val narrationId = narration?.narration_id
                ?: q.narrationsByWork(workId).executeAsList().firstOrNull { it.cluster_fingerprint == narrationFp }?.narration_id
                ?: NarrationId.new().value
            val durationMs = book.durationSec?.times(1000)
            val chapterCount = details.chapterDurationsSec.size.toLong().takeIf { it > 0 }
            q.upsertNarration(
                Audiobook_narration(
                    narration_id = narrationId,
                    work_id = workId,
                    cluster_fingerprint = narrationFp,
                    narrators_json = json.encodeToString(book.narrators),
                    kind = "SOLO",
                    duration_ms = durationMs,
                    chapter_count = chapterCount,
                ),
            )
            q.upsertVariant(
                Audiobook_variant(
                    variant_id = variantId.value,
                    narration_id = narrationId,
                    source_id = source.id.value,
                    source_url = book.ref.key,
                    duration_ms = durationMs,
                    chapter_count = chapterCount,
                    availability = "AVAILABLE",
                    infrastructure = source.infrastructureGroup,
                    last_verified_at = nowMs(),
                    user_pinned = existing?.user_pinned ?: 0,
                ),
            )
            OpenedBook(WorkId(workId), NarrationId(narrationId), variantId)
        }
    }

    /**
     * Плеер запущен с книгой, которой ещё нет в БД (локальная папка, старая очередь): заводим
     * минимальные записи из метаданных очереди, чтобы позиция было к чему привязать.
     */
    suspend fun ensureFromPlayback(meta: PlaybackBookMeta) = io {
        if (q.narrationById(meta.narrationId.value).executeAsOneOrNull() != null) return@io
        q.transaction {
            if (q.workById(meta.workId.value).executeAsOneOrNull() == null) {
                q.upsertWork(
                    Audiobook_work(
                        work_id = meta.workId.value,
                        cluster_fingerprint = fingerprint(listOfNotNull(meta.author), meta.title, ""),
                        collection_id = null, title = meta.title, title_original = null,
                        authors_json = json.encodeToString(listOfNotNull(meta.author)),
                        series_title = null, series_index = null, description = null, genres_json = "[]",
                        language = BookLanguage.RU.name, year = null, is_collection = 0,
                        cover_url = meta.coverUrl, cover_palette_json = null, cover_blurhash = null,
                        selected_narration_id = meta.narrationId.value, updated_at = nowMs(),
                    ),
                )
            }
            q.upsertNarration(
                Audiobook_narration(
                    narration_id = meta.narrationId.value, work_id = meta.workId.value,
                    cluster_fingerprint = fingerprint(listOfNotNull(meta.narrator), "", ""),
                    narrators_json = json.encodeToString(listOfNotNull(meta.narrator)),
                    kind = "SOLO", duration_ms = null, chapter_count = null,
                ),
            )
        }
    }

    /** Запись, которую нельзя терять: доигрывает даже при отмене вызывающей корутины. */
    suspend fun saveProgress(p: ProgressSnapshot) = withContext(NonCancellable + Dispatchers.IO) {
        q.upsertProgress(
            Audiobook_progress(
                narration_id = p.narrationId.value,
                work_id = p.workId.value,
                variant_id = p.variantId?.value,
                global_ms = p.globalMs,
                chapter_idx = p.chapterIndex.toLong(),
                chapter_offset_ms = p.chapterOffsetMs,
                total_ms = p.totalMs,
                speed = p.speed.toDouble(),
                finished = if (p.finished) 1 else 0,
                updated_at = nowMs(),
            ),
        )
    }

    suspend fun progress(narrationId: NarrationId): SavedProgress? = io {
        q.progressByNarration(narrationId.value).executeAsOneOrNull()?.let {
            SavedProgress(globalMs = it.global_ms, finished = it.finished != 0L)
        }
    }

    fun continueListening(limit: Long = 5): Flow<List<ContinueItem>> =
        q.continueListening(limit).asFlow().mapToList(Dispatchers.IO).map { rows ->
            rows.map { r ->
                ContinueItem(
                    workId = WorkId(r.work_id),
                    narrationId = NarrationId(r.narration_id),
                    variantId = r.variant_id?.let(::VariantId),
                    title = r.title,
                    authors = decode(r.authors_json),
                    narrators = decode(r.narrators_json),
                    coverUrl = r.cover_url,
                    globalMs = r.global_ms,
                    totalMs = r.total_ms,
                    chapterIndex = r.chapter_idx.toInt(),
                    speed = r.speed.toFloat(),
                )
            }
        }

    suspend fun listenedSince(sinceMs: Long): Long = io { q.listenedSince(sinceMs).executeAsOne() }

    private fun decode(value: String): List<String> = runCatching { json.decodeFromString<List<String>>(value) }
        .getOrDefault(emptyList())

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Отпечаток для сопоставления, не личность: 16 hex SHA-1 от нормализованных полей. Имена
         * раскладываются на слова и сортируются — «Лем Станислав» и «Станислав Лем» совпадают.
         */
        fun fingerprint(names: List<String>, title: String, language: String): String {
            val people = names.flatMap { normalize(it).split(' ') }.filter { it.isNotBlank() }.sorted().joinToString(" ")
            val raw = "$people|${normalize(title)}|$language"
            return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
        }

        fun normalize(s: String): String = s.lowercase().replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}

data class OpenedBook(val workId: WorkId, val narrationId: NarrationId, val variantId: VariantId)

data class PlaybackBookMeta(
    val workId: WorkId,
    val narrationId: NarrationId,
    val title: String,
    val author: String?,
    val narrator: String?,
    val coverUrl: String?,
)

data class ProgressSnapshot(
    val workId: WorkId,
    val narrationId: NarrationId,
    val variantId: VariantId?,
    val globalMs: Long,
    val chapterIndex: Int,
    val chapterOffsetMs: Long,
    val totalMs: Long?,
    val speed: Float,
    val finished: Boolean,
)

data class SavedProgress(val globalMs: Long, val finished: Boolean)

data class ContinueItem(
    val workId: WorkId,
    val narrationId: NarrationId,
    val variantId: VariantId?,
    val title: String,
    val authors: List<String>,
    val narrators: List<String>,
    val coverUrl: String?,
    val globalMs: Long,
    val totalMs: Long?,
    val chapterIndex: Int,
    val speed: Float,
)
