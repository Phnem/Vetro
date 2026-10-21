package com.example.myapplication.sync.supabase

import android.content.Context
import android.util.Log
import com.example.myapplication.data.local.AnimeDatabase
import com.example.myapplication.manga.data.ChapterReadingProgress
import com.example.myapplication.manga.data.MangaReadingStore
import com.example.myapplication.media.progress.EpisodePlaybackProgress
import com.example.myapplication.media.progress.EpisodePlaybackStore
import com.example.myapplication.media.progress.EpisodeRating
import com.example.myapplication.media.progress.EpisodeRatingStore
import com.example.myapplication.media.progress.PlaybackEpisodeKey
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Синхронизация прогресса просмотра серий, чтения глав и личных оценок серий с Supabase
 * (`episode_progress` / `manga_progress` / `episode_ratings`).
 *
 * Обе стороны — DataStore, не SQLDelight, поэтому паттерн [SyncRepository] переиспользован не
 * дословно:
 * - **pull** идёт по курсору `sync_cursors` (как у коллекции) — облако отдаёт только то, что
 *   изменилось с прошлого раза;
 * - **push** идёт по водяному знаку в `sync_prefs`, потому что у записей DataStore нет
 *   `sync_status`: помечать «грязные» строки негде, зато у каждой есть `updatedAt`, и его
 *   достаточно, чтобы выбрать изменившиеся.
 *
 * Конфликты — LWW по `updatedAt` на каждую запись отдельно (см. `mergeRemote` в обоих сторах):
 * прогресс по разным сериям одного тайтла независим, и целиком снимок перетирать нельзя.
 *
 * Синк выключается сам, если таблиц ещё нет: ошибка логируется и цикл коллекции не валится —
 * ровно как с `anime_tags` до применения миграции.
 */
class ProgressSyncRepository(
    context: Context,
    private val db: AnimeDatabase,
    private val supabase: SupabaseClient,
    private val authRepository: AuthRepository,
    private val episodeStore: EpisodePlaybackStore,
    private val mangaStore: MangaReadingStore,
    private val ratingStore: EpisodeRatingStore,
) {
    private val syncPrefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    /** Полный цикл: сначала выложить локальные изменения, затем подтянуть чужие. */
    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val userId = authRepository.currentUserId ?: return@runCatching
            if (authRepository.isGuest) return@runCatching
            // Список тайтлов нужен обеим половинам: ключ прогресса — хэш от animeId, и по снимку
            // DataStore нельзя перечислить тайтлы, только спросить про известный id.
            val animeIds = db.animeQueries.getAllAnime().executeAsList().map { it.id }

            runCatching { pushEpisodes(userId, animeIds) }
                .onFailure { Log.w(TAG, "Push episode progress failed: ${safeSyncError(it)}") }
            runCatching { pushChapters(userId, animeIds) }
                .onFailure { Log.w(TAG, "Push manga progress failed: ${safeSyncError(it)}") }
            runCatching { pushRatings(userId, animeIds) }
                .onFailure { Log.w(TAG, "Push episode ratings failed: ${safeSyncError(it)}") }
            runCatching { pullEpisodes(userId) }
                .onFailure { Log.w(TAG, "Pull episode progress failed: ${safeSyncError(it)}") }
            runCatching { pullChapters(userId) }
                .onFailure { Log.w(TAG, "Pull manga progress failed: ${safeSyncError(it)}") }
            runCatching { pullRatings(userId) }
                .onFailure { Log.w(TAG, "Pull episode ratings failed: ${safeSyncError(it)}") }
        }.onFailure { Log.e(TAG, "Progress sync failed: ${safeSyncError(it)}") }
    }

    private suspend fun pushEpisodes(userId: String, animeIds: List<String>) {
        val watermark = syncPrefs.getLong(pushKey(EPISODE_ENTITY, userId), 0L)
        var maxUpdatedAt = watermark
        val dtos = episodeStore.snapshotAll(animeIds).flatMap { (animeId, progress) ->
            progress.mapNotNull { (episodeKey, value) ->
                if (value.updatedAt <= watermark) return@mapNotNull null
                if (value.updatedAt > maxUpdatedAt) maxUpdatedAt = value.updatedAt
                EpisodeProgressDto(
                    user_id = userId,
                    anime_id = animeId,
                    season = episodeKey.season,
                    episode = episodeKey.episode,
                    position_ms = value.positionMs,
                    duration_ms = value.durationMs,
                    updated_at = Instant.ofEpochMilli(value.updatedAt).toString(),
                )
            }
        }
        if (dtos.isEmpty()) return
        dtos.chunked(CHUNK_SIZE).forEach { supabase.postgrest[EPISODE_ENTITY].upsert(it) }
        syncPrefs.edit().putLong(pushKey(EPISODE_ENTITY, userId), maxUpdatedAt).apply()
        Log.i(TAG, "Pushed ${dtos.size} episode progress row(s)")
    }

    private suspend fun pushChapters(userId: String, animeIds: List<String>) {
        val watermark = syncPrefs.getLong(pushKey(MANGA_ENTITY, userId), 0L)
        var maxUpdatedAt = watermark
        val dtos = mangaStore.snapshotAll(animeIds).flatMap { (animeId, progress) ->
            progress.mapNotNull { (chapterKey, value) ->
                if (value.updatedAt <= watermark) return@mapNotNull null
                if (value.updatedAt > maxUpdatedAt) maxUpdatedAt = value.updatedAt
                MangaProgressDto(
                    user_id = userId,
                    anime_id = animeId,
                    chapter_key = chapterKey,
                    page_index = value.pageIndex,
                    page_count = value.pageCount,
                    is_read = value.read,
                    scroll_offset_fraction = value.scrollOffsetFraction,
                    updated_at = Instant.ofEpochMilli(value.updatedAt).toString(),
                )
            }
        }
        if (dtos.isEmpty()) return
        dtos.chunked(CHUNK_SIZE).forEach { supabase.postgrest[MANGA_ENTITY].upsert(it) }
        syncPrefs.edit().putLong(pushKey(MANGA_ENTITY, userId), maxUpdatedAt).apply()
        Log.i(TAG, "Pushed ${dtos.size} manga progress row(s)")
    }

    private suspend fun pullEpisodes(userId: String) {
        val cursorMs = db.syncQueries.getCursor(EPISODE_ENTITY).executeAsOneOrNull() ?: 0L
        val rows = supabase.postgrest[EPISODE_ENTITY]
            .select {
                filter {
                    eq("user_id", userId)
                    gt("updated_at", Instant.ofEpochMilli(cursorMs).toString())
                }
            }
            .decodeList<EpisodeProgressDto>()
        if (rows.isEmpty()) return

        var maxUpdatedAt = cursorMs
        val byAnime = rows.groupBy { it.anime_id }.mapValues { (_, group) ->
            group.associate { row ->
                val updatedMs = parseInstant(row.updated_at)
                if (updatedMs > maxUpdatedAt) maxUpdatedAt = updatedMs
                PlaybackEpisodeKey(row.season, row.episode) to EpisodePlaybackProgress(
                    positionMs = row.position_ms,
                    durationMs = row.duration_ms,
                    updatedAt = updatedMs,
                )
            }
        }
        val applied = episodeStore.mergeRemote(byAnime)
        db.syncQueries.setCursor(EPISODE_ENTITY, maxUpdatedAt)
        Log.i(TAG, "Pulled ${rows.size} episode progress row(s), applied $applied")
    }

    /**
     * Оценки серий едут отдельной таблицей и отдельным водяным знаком: у них свой `updatedAt`,
     * не связанный с позицией воспроизведения (см. [EpisodeRatingStore]).
     */
    private suspend fun pushRatings(userId: String, animeIds: List<String>) {
        val watermark = syncPrefs.getLong(pushKey(RATING_ENTITY, userId), 0L)
        var maxUpdatedAt = watermark
        val dtos = ratingStore.snapshotAll(animeIds).flatMap { (animeId, ratings) ->
            ratings.mapNotNull { (episodeKey, value) ->
                if (value.updatedAt <= watermark) return@mapNotNull null
                if (value.updatedAt > maxUpdatedAt) maxUpdatedAt = value.updatedAt
                EpisodeRatingDto(
                    user_id = userId,
                    anime_id = animeId,
                    season = episodeKey.season,
                    episode = episodeKey.episode,
                    rating = value.stored,
                    updated_at = Instant.ofEpochMilli(value.updatedAt).toString(),
                )
            }
        }
        if (dtos.isEmpty()) return
        dtos.chunked(CHUNK_SIZE).forEach { supabase.postgrest[RATING_ENTITY].upsert(it) }
        syncPrefs.edit().putLong(pushKey(RATING_ENTITY, userId), maxUpdatedAt).apply()
        Log.i(TAG, "Pushed ${dtos.size} episode rating(s)")
    }

    private suspend fun pullRatings(userId: String) {
        val cursorMs = db.syncQueries.getCursor(RATING_ENTITY).executeAsOneOrNull() ?: 0L
        val rows = supabase.postgrest[RATING_ENTITY]
            .select {
                filter {
                    eq("user_id", userId)
                    gt("updated_at", Instant.ofEpochMilli(cursorMs).toString())
                }
            }
            .decodeList<EpisodeRatingDto>()
        if (rows.isEmpty()) return

        var maxUpdatedAt = cursorMs
        val byAnime = rows.groupBy { it.anime_id }.mapValues { (_, group) ->
            group.associate { row ->
                val updatedMs = parseInstant(row.updated_at)
                if (updatedMs > maxUpdatedAt) maxUpdatedAt = updatedMs
                PlaybackEpisodeKey(row.season, row.episode) to EpisodeRating(
                    stored = row.rating,
                    updatedAt = updatedMs,
                )
            }
        }
        val applied = ratingStore.mergeRemote(byAnime)
        db.syncQueries.setCursor(RATING_ENTITY, maxUpdatedAt)
        Log.i(TAG, "Pulled ${rows.size} episode rating(s), applied $applied")
    }

    private suspend fun pullChapters(userId: String) {
        val cursorMs = db.syncQueries.getCursor(MANGA_ENTITY).executeAsOneOrNull() ?: 0L
        val rows = supabase.postgrest[MANGA_ENTITY]
            .select {
                filter {
                    eq("user_id", userId)
                    gt("updated_at", Instant.ofEpochMilli(cursorMs).toString())
                }
            }
            .decodeList<MangaProgressDto>()
        if (rows.isEmpty()) return

        var maxUpdatedAt = cursorMs
        val byAnime = rows.groupBy { it.anime_id }.mapValues { (_, group) ->
            group.associate { row ->
                val updatedMs = parseInstant(row.updated_at)
                if (updatedMs > maxUpdatedAt) maxUpdatedAt = updatedMs
                row.chapter_key to ChapterReadingProgress(
                    pageIndex = row.page_index,
                    pageCount = row.page_count,
                    read = row.is_read,
                    scrollOffsetFraction = row.scroll_offset_fraction,
                    updatedAt = updatedMs,
                )
            }
        }
        val applied = mangaStore.mergeRemote(byAnime)
        db.syncQueries.setCursor(MANGA_ENTITY, maxUpdatedAt)
        Log.i(TAG, "Pulled ${rows.size} manga progress row(s), applied $applied")
    }

    private companion object {
        const val TAG = "ProgressSync"
        const val EPISODE_ENTITY = "episode_progress"
        const val MANGA_ENTITY = "manga_progress"
        const val RATING_ENTITY = "episode_ratings"

        /** Прогресс копится сотнями строк — upsert режется, чтобы не упереться в лимит тела. */
        const val CHUNK_SIZE = 200

        fun pushKey(entity: String, userId: String) = "progress_push_v1_${entity}_$userId"

        /**
         * PostgREST отдаёт `timestamptz` со смещением (`+00:00`), а не всегда с `Z`. Старые
         * реализации [Instant.parse] такой формат не принимают, поэтому фолбэк через
         * [OffsetDateTime] — иначе одна строка роняла бы весь pull.
         */
        fun parseInstant(raw: String): Long =
            runCatching { Instant.parse(raw) }
                .getOrElse { OffsetDateTime.parse(raw).toInstant() }
                .toEpochMilli()
    }
}
