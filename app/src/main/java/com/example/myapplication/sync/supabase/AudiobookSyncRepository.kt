package com.example.myapplication.sync.supabase

import android.content.Context
import android.util.Log
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.data.LOCAL_SOURCE_ID
import com.example.myapplication.data.local.AnimeDatabase
import com.example.myapplication.data.local.Audiobook_bookmark
import com.example.myapplication.data.local.Audiobook_narration
import com.example.myapplication.data.local.Audiobook_progress
import com.example.myapplication.data.local.Audiobook_variant
import com.example.myapplication.data.local.Audiobook_work
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Синхронизация аудиокниг с Supabase: `audiobook_work` / `audiobook_progress` / `audiobook_bookmarks`.
 * В облако уходят только данные — книга, озвучки, ссылки на источники, позиция, закладки; аудио
 * и ссылки на локальные папки (`content://`, имеют смысл только на этом телефоне) — никогда.
 *
 * В облако идут только «свои» книги: в библиотеке, в избранном или с прогрессом. Каждая открытая у
 * источника книга тоже ложится в `audiobook_work`, но это кэш просмотра, а не коллекция.
 *
 * У локальных таблиц нет `sync_status`, а избранное/библиотека меняются без `updated_at`, поэтому
 * книги и закладки выгружаются по отпечатку: хэш выгруженной строки хранится в префах, и уходит
 * только то, у чего хэш поменялся. Прогресс — по водяному знаку `updated_at`, как у серий.
 *
 * Pull идёт по курсорам `sync_cursors`. Книгу и закладки с облака не применяем поверх локальной
 * правки, которая ещё не выгружена (хэш не совпадает с выгруженным); прогресс — LWW по `updated_at`.
 * Ошибки только логируются: синк коллекции из-за книг не падает.
 */
class AudiobookSyncRepository(
    private val context: Context,
    private val db: AnimeDatabase,
    private val supabase: SupabaseClient,
    private val authRepository: AuthRepository,
) {
    private val q get() = db.audiobookQueries

    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val userId = authRepository.currentUserId ?: return@runCatching
            if (authRepository.isGuest) return@runCatching
            val pushed = context.getSharedPreferences("audiobook_sync_$userId", Context.MODE_PRIVATE)

            runCatching { pushWorks(userId, pushed) }
                .onFailure { Log.w(TAG, "Push audiobooks failed: ${safeSyncError(it)}") }
            runCatching { pushProgress(userId, pushed) }
                .onFailure { Log.w(TAG, "Push audiobook progress failed: ${safeSyncError(it)}") }
            runCatching { pushBookmarks(userId, pushed) }
                .onFailure { Log.w(TAG, "Push audiobook bookmarks failed: ${safeSyncError(it)}") }
            // Книги — первыми: прогресс и закладки ссылаются на их озвучки.
            runCatching { pullWorks(userId, pushed) }
                .onFailure { Log.w(TAG, "Pull audiobooks failed: ${safeSyncError(it)}") }
            runCatching { pullProgress(userId) }
                .onFailure { Log.w(TAG, "Pull audiobook progress failed: ${safeSyncError(it)}") }
            runCatching { pullBookmarks(userId, pushed) }
                .onFailure { Log.w(TAG, "Pull audiobook bookmarks failed: ${safeSyncError(it)}") }
        }.onFailure { Log.e(TAG, "Audiobook sync failed: ${safeSyncError(it)}") }
    }

    // ---- книги ----

    private suspend fun pushWorks(userId: String, pushed: android.content.SharedPreferences) {
        val now = Instant.now().toString()
        val syncable = q.syncableWorks().executeAsList()
        val syncableIds = syncable.mapTo(HashSet()) { it.work_id }
        // Книга перестала быть «своей» (убрали из библиотеки и избранного) — выгружаем её ещё раз
        // со снятыми флагами, чтобы другие устройства тоже её убрали, и забываем.
        val dropped = pushed.all.keys
            .filter { it.startsWith(WORK_PREFIX) }
            .map { it.removePrefix(WORK_PREFIX) }
            .filter { it !in syncableIds }
            .mapNotNull { q.workById(it).executeAsOneOrNull() }
        val dtos = (syncable + dropped).mapNotNull { work ->
            val dto = workDto(userId, work, now)
            if (pushed.getString(WORK_PREFIX + work.work_id, null) == hash(dto)) null else dto
        }
        if (dtos.isEmpty()) return
        dtos.chunked(CHUNK_SIZE).forEach { supabase.postgrest[WORK_ENTITY].upsert(it) }
        pushed.edit().apply {
            dtos.forEach { dto ->
                if (dto.work_id in syncableIds) putString(WORK_PREFIX + dto.work_id, hash(dto))
                else remove(WORK_PREFIX + dto.work_id)
            }
        }.apply()
        Log.i(TAG, "Pushed ${dtos.size} audiobook(s)")
    }

    private suspend fun pullWorks(userId: String, pushed: android.content.SharedPreferences) {
        val cursorMs = db.syncQueries.getCursor(WORK_ENTITY).executeAsOneOrNull() ?: 0L
        val rows = supabase.postgrest[WORK_ENTITY]
            .select { filter { eq("user_id", userId); gt("updated_at", Instant.ofEpochMilli(cursorMs).toString()) } }
            .decodeList<AudiobookWorkDto>()
        if (rows.isEmpty()) return

        var maxUpdatedAt = cursorMs
        var applied = 0
        val edit = pushed.edit()
        rows.forEach { remote ->
            val updatedMs = parseInstant(remote.updated_at)
            if (updatedMs > maxUpdatedAt) maxUpdatedAt = updatedMs
            val local = q.workById(remote.work_id).executeAsOneOrNull()
            val pushedHash = pushed.getString(WORK_PREFIX + remote.work_id, null)
            // Локальная правка ещё не выгружена — она новее; облако получит её следующим push.
            if (local != null && pushedHash != null && hash(workDto(userId, local, "")) != pushedHash) return@forEach
            applyWork(remote, local, updatedMs)
            applied++
            val stored = q.workById(remote.work_id).executeAsOneOrNull() ?: return@forEach
            val dto = workDto(userId, stored, "")
            if (stored.in_library == 1L || stored.is_favorite == 1L) edit.putString(WORK_PREFIX + remote.work_id, hash(dto))
            else edit.remove(WORK_PREFIX + remote.work_id)
        }
        edit.apply()
        db.syncQueries.setCursor(WORK_ENTITY, maxUpdatedAt)
        Log.i(TAG, "Pulled ${rows.size} audiobook(s), applied $applied")
    }

    private fun applyWork(remote: AudiobookWorkDto, local: Audiobook_work?, updatedMs: Long) = q.transaction {
        q.upsertWork(
            Audiobook_work(
                work_id = remote.work_id,
                cluster_fingerprint = remote.cluster_fingerprint,
                collection_id = local?.collection_id,
                title = remote.title,
                title_original = remote.title_original,
                authors_json = json.encodeToString(remote.authors),
                series_title = remote.series_title,
                series_index = remote.series_index,
                description = remote.description,
                genres_json = json.encodeToString(remote.genres),
                language = remote.language,
                year = remote.year?.toLong(),
                is_collection = if (remote.is_collection) 1 else 0,
                cover_url = remote.cover_url,
                // Палитра и blurhash — производные от обложки, считаются на устройстве.
                cover_palette_json = local?.cover_palette_json,
                cover_blurhash = local?.cover_blurhash,
                selected_narration_id = remote.selected_narration_id,
                updated_at = maxOf(updatedMs, local?.updated_at ?: 0L),
                is_favorite = if (remote.is_favorite) 1 else 0,
                in_library = if (remote.in_library) 1 else 0,
            ),
        )
        remote.narrations.forEach { n ->
            q.upsertNarration(
                Audiobook_narration(
                    narration_id = n.narration_id,
                    work_id = remote.work_id,
                    cluster_fingerprint = AudiobookRepository.fingerprint(n.narrators, "", ""),
                    narrators_json = json.encodeToString(n.narrators),
                    kind = n.kind,
                    duration_ms = n.duration_ms,
                    chapter_count = n.chapter_count,
                ),
            )
            n.variants.forEach { v ->
                val existing = q.variantById(v.variant_id).executeAsOneOrNull()
                q.upsertVariant(
                    Audiobook_variant(
                        variant_id = v.variant_id,
                        narration_id = n.narration_id,
                        source_id = v.source_id,
                        source_url = v.source_url,
                        duration_ms = v.duration_ms,
                        chapter_count = v.chapter_count,
                        // Доступность — наблюдение этого устройства, с облака не берётся.
                        availability = existing?.availability ?: AudiobookRepository.AVAILABLE,
                        infrastructure = v.infrastructure,
                        last_verified_at = existing?.last_verified_at,
                        user_pinned = if (v.user_pinned) 1 else 0,
                    ),
                )
            }
        }
    }

    private fun workDto(userId: String, work: Audiobook_work, updatedAt: String): AudiobookWorkDto =
        AudiobookWorkDto(
            user_id = userId,
            work_id = work.work_id,
            cluster_fingerprint = work.cluster_fingerprint,
            title = work.title,
            title_original = work.title_original,
            authors = decodeList(work.authors_json),
            series_title = work.series_title,
            series_index = work.series_index,
            description = work.description,
            genres = decodeList(work.genres_json),
            language = work.language,
            year = work.year?.toInt(),
            is_collection = work.is_collection != 0L,
            cover_url = work.cover_url,
            selected_narration_id = work.selected_narration_id,
            is_favorite = work.is_favorite != 0L,
            in_library = work.in_library != 0L,
            narrations = q.narrationsByWork(work.work_id).executeAsList().sortedBy { it.narration_id }.map { n ->
                AudiobookNarrationDto(
                    narration_id = n.narration_id,
                    narrators = decodeList(n.narrators_json),
                    kind = n.kind,
                    duration_ms = n.duration_ms,
                    chapter_count = n.chapter_count,
                    variants = q.variantsByNarration(n.narration_id).executeAsList()
                        .filter { it.source_id != LOCAL_SOURCE_ID }
                        .sortedBy { it.variant_id }
                        .map { v ->
                            AudiobookVariantDto(
                                variant_id = v.variant_id,
                                source_id = v.source_id,
                                source_url = v.source_url,
                                duration_ms = v.duration_ms,
                                chapter_count = v.chapter_count,
                                infrastructure = v.infrastructure,
                                user_pinned = v.user_pinned != 0L,
                            )
                        },
                )
            },
            updated_at = updatedAt,
        )

    // ---- прогресс ----

    private suspend fun pushProgress(userId: String, pushed: android.content.SharedPreferences) {
        val watermark = pushed.getLong(PROGRESS_WATERMARK, 0L)
        var maxUpdatedAt = watermark
        val dtos = q.allProgress().executeAsList().filter { it.updated_at > watermark }.map { p ->
            if (p.updated_at > maxUpdatedAt) maxUpdatedAt = p.updated_at
            AudiobookProgressDto(
                user_id = userId,
                narration_id = p.narration_id,
                work_id = p.work_id,
                variant_id = p.variant_id,
                global_ms = p.global_ms,
                chapter_idx = p.chapter_idx.toInt(),
                chapter_offset_ms = p.chapter_offset_ms,
                total_ms = p.total_ms,
                speed = p.speed.toFloat(),
                finished = p.finished != 0L,
                updated_at = Instant.ofEpochMilli(p.updated_at).toString(),
            )
        }
        if (dtos.isEmpty()) return
        dtos.chunked(CHUNK_SIZE).forEach { supabase.postgrest[PROGRESS_ENTITY].upsert(it) }
        pushed.edit().putLong(PROGRESS_WATERMARK, maxUpdatedAt).apply()
        Log.i(TAG, "Pushed ${dtos.size} audiobook progress row(s)")
    }

    private suspend fun pullProgress(userId: String) {
        val cursorMs = db.syncQueries.getCursor(PROGRESS_ENTITY).executeAsOneOrNull() ?: 0L
        val rows = supabase.postgrest[PROGRESS_ENTITY]
            .select { filter { eq("user_id", userId); gt("updated_at", Instant.ofEpochMilli(cursorMs).toString()) } }
            .decodeList<AudiobookProgressDto>()
        if (rows.isEmpty()) return

        var maxUpdatedAt = cursorMs
        var applied = 0
        rows.forEach { remote ->
            val updatedMs = parseInstant(remote.updated_at)
            if (updatedMs > maxUpdatedAt) maxUpdatedAt = updatedMs
            if (q.narrationById(remote.narration_id).executeAsOneOrNull() == null) return@forEach
            val local = q.progressByNarration(remote.narration_id).executeAsOneOrNull()
            if (local != null && local.updated_at >= updatedMs) return@forEach
            // Мимо AudiobookRepository.saveProgress: тот ставит updated_at = сейчас, а LWW нужен облачный.
            q.upsertProgress(
                Audiobook_progress(
                    narration_id = remote.narration_id,
                    work_id = remote.work_id,
                    variant_id = remote.variant_id,
                    global_ms = remote.global_ms,
                    chapter_idx = remote.chapter_idx.toLong(),
                    chapter_offset_ms = remote.chapter_offset_ms,
                    total_ms = remote.total_ms,
                    speed = remote.speed.toDouble(),
                    finished = if (remote.finished) 1 else 0,
                    updated_at = updatedMs,
                ),
            )
            applied++
        }
        db.syncQueries.setCursor(PROGRESS_ENTITY, maxUpdatedAt)
        Log.i(TAG, "Pulled ${rows.size} audiobook progress row(s), applied $applied")
    }

    // ---- закладки ----

    private suspend fun pushBookmarks(userId: String, pushed: android.content.SharedPreferences) {
        val now = Instant.now().toString()
        val byNarration = q.allBookmarks().executeAsList().groupBy { it.narration_id }
        // Озвучки, чьи закладки уже были в облаке, а локально их не осталось, — уходят пустым набором.
        val narrationIds = byNarration.keys + pushed.all.keys
            .filter { it.startsWith(BOOKMARKS_PREFIX) }
            .map { it.removePrefix(BOOKMARKS_PREFIX) }
        val dtos = narrationIds.mapNotNull { narrationId ->
            val workId = q.narrationById(narrationId).executeAsOneOrNull()?.work_id ?: return@mapNotNull null
            val dto = bookmarksDto(userId, narrationId, workId, byNarration[narrationId].orEmpty(), now)
            if (pushed.getString(BOOKMARKS_PREFIX + narrationId, null) == hash(dto)) null else dto
        }
        if (dtos.isEmpty()) return
        dtos.chunked(CHUNK_SIZE).forEach { supabase.postgrest[BOOKMARKS_ENTITY].upsert(it) }
        pushed.edit().apply {
            dtos.forEach { dto ->
                if (dto.bookmarks.isEmpty()) remove(BOOKMARKS_PREFIX + dto.narration_id)
                else putString(BOOKMARKS_PREFIX + dto.narration_id, hash(dto))
            }
        }.apply()
        Log.i(TAG, "Pushed bookmarks of ${dtos.size} narration(s)")
    }

    private suspend fun pullBookmarks(userId: String, pushed: android.content.SharedPreferences) {
        val cursorMs = db.syncQueries.getCursor(BOOKMARKS_ENTITY).executeAsOneOrNull() ?: 0L
        val rows = supabase.postgrest[BOOKMARKS_ENTITY]
            .select { filter { eq("user_id", userId); gt("updated_at", Instant.ofEpochMilli(cursorMs).toString()) } }
            .decodeList<AudiobookBookmarksDto>()
        if (rows.isEmpty()) return

        var maxUpdatedAt = cursorMs
        val edit = pushed.edit()
        rows.forEach { remote ->
            val updatedMs = parseInstant(remote.updated_at)
            if (updatedMs > maxUpdatedAt) maxUpdatedAt = updatedMs
            if (q.narrationById(remote.narration_id).executeAsOneOrNull() == null) return@forEach
            val local = q.bookmarksByNarration(remote.narration_id).executeAsList()
            val pushedHash = pushed.getString(BOOKMARKS_PREFIX + remote.narration_id, null)
            val localHash = hash(bookmarksDto(userId, remote.narration_id, remote.work_id, local, ""))
            if (pushedHash != null && local.isNotEmpty() && localHash != pushedHash) return@forEach
            val remoteIds = remote.bookmarks.mapTo(HashSet()) { it.id }
            val localIds = local.mapTo(HashSet()) { it.id }
            q.transaction {
                local.filter { it.id !in remoteIds }.forEach { q.deleteBookmark(it.id) }
                remote.bookmarks.filter { it.id !in localIds }.forEach { b ->
                    q.insertBookmark(
                        Audiobook_bookmark(
                            id = b.id,
                            narration_id = remote.narration_id,
                            global_ms = b.global_ms,
                            chapter_idx = b.chapter_idx.toLong(),
                            chapter_offset_ms = b.chapter_offset_ms,
                            note = b.note,
                            created_at = b.created_at,
                        ),
                    )
                }
            }
            val stored = q.bookmarksByNarration(remote.narration_id).executeAsList()
            if (stored.isEmpty()) edit.remove(BOOKMARKS_PREFIX + remote.narration_id)
            else edit.putString(BOOKMARKS_PREFIX + remote.narration_id, hash(bookmarksDto(userId, remote.narration_id, remote.work_id, stored, "")))
        }
        edit.apply()
        db.syncQueries.setCursor(BOOKMARKS_ENTITY, maxUpdatedAt)
        Log.i(TAG, "Pulled bookmarks of ${rows.size} narration(s)")
    }

    private fun bookmarksDto(
        userId: String,
        narrationId: String,
        workId: String,
        bookmarks: List<Audiobook_bookmark>,
        updatedAt: String,
    ) = AudiobookBookmarksDto(
        user_id = userId,
        narration_id = narrationId,
        work_id = workId,
        bookmarks = bookmarks.sortedBy { it.id }.map {
            AudiobookBookmarkDto(
                id = it.id,
                global_ms = it.global_ms,
                chapter_idx = it.chapter_idx.toInt(),
                chapter_offset_ms = it.chapter_offset_ms,
                note = it.note,
                created_at = it.created_at,
            )
        },
        updated_at = updatedAt,
    )

    private fun decodeList(value: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "AudiobookSync"
        const val WORK_ENTITY = "audiobook_work"
        const val PROGRESS_ENTITY = "audiobook_progress"
        const val BOOKMARKS_ENTITY = "audiobook_bookmarks"
        const val WORK_PREFIX = "work:"
        const val BOOKMARKS_PREFIX = "bookmarks:"
        const val PROGRESS_WATERMARK = "progress_push_watermark"
        const val CHUNK_SIZE = 200

        val json = Json { ignoreUnknownKeys = true }

        /** Отпечаток содержимого строки без времени выгрузки: поменялось ли что-то с прошлого push. */
        fun hash(dto: AudiobookWorkDto): String = sha1(json.encodeToString(dto.copy(updated_at = "")))
        fun hash(dto: AudiobookBookmarksDto): String = sha1(json.encodeToString(dto.copy(updated_at = "")))

        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** PostgREST отдаёт `timestamptz` со смещением (`+00:00`), не всегда с `Z`. */
        fun parseInstant(raw: String): Long =
            runCatching { Instant.parse(raw) }
                .getOrElse { OffsetDateTime.parse(raw).toInstant() }
                .toEpochMilli()
    }
}
