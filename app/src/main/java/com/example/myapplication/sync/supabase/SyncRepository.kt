package com.example.myapplication.sync.supabase

import android.util.Log
import com.example.myapplication.data.local.AnimeDatabase
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class AnimeRemoteDto(
    val id: String,
    val user_id: String,
    val title: String,
    val image_path: String?,
    val episodes: Int,
    val rating: Int,
    val status: String,
    val is_favorite: Boolean,
    val order_index: Int,
    val date_added: Long,
    val category_type: String,
    val comment: String,
    val is_ai_recommendation: Boolean,
    val anilist_id: Int?,
    val mal_id: Int?,
    val shikimori_id: Int?,
    val anilist_not_found_at: Long?,
    val mal_not_found_at: Long?,
    val shikimori_not_found_at: Long?,
    val is_private: Boolean,
    val encryption_iv: String?,
    val created_at: Long,
    val updated_at: String,
    val deleted_at: Long?,
    // Пишется ВСЕГДА, даже равное умолчанию: клиент Supabase не кодирует значения по умолчанию, а
    // пачка upsert берёт колонки из всех строк сразу — стоит в пачке появиться фильму или манге,
    // и у строк-аниме без поля PostgREST ставил NULL: «null value in column media_type», синк
    // коллекции падал целиком.
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault
    val media_type: String = "ANIME",
    val title_en: String? = null,
    val title_en_checked_at: Long? = null,
    val title_ru: String? = null,
    val title_ru_checked_at: Long? = null
)

/**
 * Строка облачной таблицы anime_tags. ВАЖНО: поля должны 1-в-1 совпадать со схемой Supabase
 * (anime_id, user_id, tag, updated_at) — лишнее поле в upsert валит запрос целиком
 * («Could not find the column …»), из-за чего жанры раньше вообще не пушились.
 */
@Serializable
data class AnimeTagRemoteDto(
    val anime_id: String,
    val user_id: String,
    val tag: String,
    val updated_at: String
)


class SyncRepository(
    private val db: AnimeDatabase,
    private val supabase: SupabaseClient,
    private val authRepository: AuthRepository,
    private val attachmentSyncManager: AttachmentSyncManager
) {
    private val syncPrefs =
        attachmentSyncManager.context.getSharedPreferences("sync_prefs", android.content.Context.MODE_PRIVATE)

    suspend fun pushPendingChanges(): PushResult = withContext(Dispatchers.IO) {
        if (!syncPrefs.getBoolean("retroactive_upload_done_v5", false)) {
            attachmentSyncManager.retroactivelyUploadCachedAttachments()
            syncPrefs.edit().putBoolean("retroactive_upload_done_v5", true).apply()
        }

        val userId = authRepository.currentUserId ?: return@withContext PushResult(0, "Not signed in")
        Log.d(TAG, "Push for user_id=$userId, pending=${db.animeQueries.countPendingSync().executeAsOne()}")
        ensureInitialLocalUpload(userId)

        // Однократный ресинк жанров в облако. Причины: (1) старый push тегов падал молча
        // (лишняя колонка is_deleted), (2) таблица anime_tags могла быть НЕ создана на боевой
        // базе (миграция не применялась). Пушим теги всех тайтлов напрямую (без пометки аниме
        // pending — лишний трафик); флаг ставим ТОЛЬКО при успехе, поэтому после создания
        // таблицы жанры до-синкнутся автоматически на ближайшем синке.
        if (!syncPrefs.getBoolean("tags_resync_done_v2", false)) {
            val taggedIds = db.animeQueries.getAllAnime().executeAsList()
                .map { it.id }
                .filter { db.animeQueries.getAnimeTags(it).executeAsList().isNotEmpty() }
            val ok = runCatching { pushTagsForAnime(userId, taggedIds) }
                .onFailure { Log.w(TAG, "Genre resync deferred: ${safeSyncError(it)}") }
                .isSuccess
            if (ok) syncPrefs.edit().putBoolean("tags_resync_done_v2", true).apply()
        }

        val pendingAnime = db.animeQueries.selectPendingSync().executeAsList()
        if (pendingAnime.isEmpty()) return@withContext PushResult(0, null)

        val syncedIds = mutableListOf<String>()
        val dtos = pendingAnime.map { anime ->
            var finalImagePath = anime.imagePath
            var imageUploadFailed = false

            if (finalImagePath != null &&
                !finalImagePath.startsWith("http") &&
                !finalImagePath.startsWith("collection-attachment://")
            ) {
                val cloudUrl = attachmentSyncManager.uploadLocalImageFile(anime.id, finalImagePath)
                if (cloudUrl != null) {
                    finalImagePath = cloudUrl
                    db.animeQueries.updateAnimeImagePath(imagePath = cloudUrl, id = anime.id)
                } else if (attachmentSyncManager.localImageExists(finalImagePath)) {
                    // Файл есть, но загрузка в R2 не удалась: строку пушим (метаданные не теряем),
                    // но SYNCED не ставим — следующий цикл повторит загрузку обложки.
                    // Раньше запись помечалась SYNCED и обложка не доезжала до облака никогда.
                    imageUploadFailed = true
                }
            }

            if (!imageUploadFailed) syncedIds.add(anime.id)

            AnimeRemoteDto(
                id = anime.id,
                user_id = userId,
                title = anime.title,
                image_path = finalImagePath,
                episodes = anime.episodes.toInt(),
                rating = anime.rating.toInt(),
                status = anime.status,
                is_favorite = anime.isFavorite > 0,
                order_index = anime.orderIndex.toInt(),
                date_added = anime.dateAdded,
                category_type = anime.categoryType,
                comment = anime.comment,
                is_ai_recommendation = anime.isAiRecommendation > 0,
                anilist_id = anime.anilist_id?.toInt(),
                mal_id = anime.mal_id?.toInt(),
                shikimori_id = anime.shikimori_id?.toInt(),
                anilist_not_found_at = anime.anilist_not_found_at,
                mal_not_found_at = anime.mal_not_found_at,
                shikimori_not_found_at = anime.shikimori_not_found_at,
                is_private = anime.isPrivate > 0,
                encryption_iv = anime.encryptionIv,
                created_at = anime.dateAdded,
                updated_at = java.time.Instant.ofEpochMilli(anime.updatedAt).toString(),
                deleted_at = anime.deletedAt,
                media_type = anime.mediaType,
                title_en = anime.title_en,
                title_en_checked_at = anime.title_en_checked_at,
                title_ru = anime.title_ru,
                title_ru_checked_at = anime.title_ru_checked_at
            )
        }

        if (dtos.isEmpty()) return@withContext PushResult(0, null)

        try {
            supabase.postgrest["anime"].upsert(dtos)
        } catch (e: Exception) {
            val safeError = safeSyncError(e)
            Log.e(TAG, "Push anime failed: $safeError")
            return@withContext PushResult(0, safeError)
        }

        // Аниме уже в облаке — помечаем synced. Теги (жанры) пушим отдельно и best-effort:
        // отсутствие таблицы anime_tags (не применённая миграция) НЕ должно валить весь синк —
        // аниме-данные важнее и уже сохранены. Ошибку логируем; после создания таблицы жанры
        // до-синкнутся ресинком выше (tags_resync_done_v2 не выставлен, пока push тегов падает).
        db.transaction {
            syncedIds.forEach { id -> db.animeQueries.markAnimeSynced(id) }
        }
        syncPrefs.edit().putBoolean(initialUploadKey(userId), true).apply()
        runCatching { pushTagsForAnime(userId, syncedIds) }
            .onFailure { Log.w(TAG, "Push tags deferred: ${safeSyncError(it)}") }
        Log.i(TAG, "Pushed ${dtos.size} anime row(s) to cloud")
        return@withContext PushResult(dtos.size, null)
    }

    /**
     * Полная замена облачного набора тегов для изменённых тайтлов: DELETE по anime_id + INSERT
     * текущего набора. Так удаление жанра доезжает до других устройств без tombstone-колонок:
     * та сторона перечитает теги по факту обновления строки anime (см. [pullTagsFor]).
     */
    private suspend fun pushTagsForAnime(userId: String, syncedIds: List<String>) {
        if (syncedIds.isEmpty()) return
        val now = java.time.Instant.now().toString()
        syncedIds.chunked(TAG_CHUNK_SIZE).forEach { chunk ->
            supabase.postgrest["anime_tags"].delete {
                filter {
                    eq("user_id", userId)
                    isIn("anime_id", chunk)
                }
            }
            val dtos = chunk.flatMap { animeId ->
                db.animeQueries.getAnimeTags(animeId).executeAsList().map { tag ->
                    AnimeTagRemoteDto(
                        anime_id = animeId,
                        user_id = userId,
                        tag = tag,
                        updated_at = now
                    )
                }
            }
            if (dtos.isNotEmpty()) {
                supabase.postgrest["anime_tags"].upsert(dtos)
            }
        }
    }

    data class PushResult(val pushedCount: Int, val error: String?)
    data class PullResult(val pulledCount: Int, val error: String?)

    suspend fun pullRemoteChanges(): PullResult = withContext(Dispatchers.IO) {
        val userId = authRepository.currentUserId ?: return@withContext PullResult(0, "Not signed in")
        val cursorMs = db.syncQueries.getCursor("anime").executeAsOneOrNull() ?: 0L
        val cursorIso = java.time.Instant.ofEpochMilli(cursorMs).toString()

        try {
            val remoteChanges = supabase.postgrest["anime"]
                .select {
                    filter {
                        eq("user_id", userId)
                        gt("updated_at", cursorIso)
                    }
                }
                .decodeList<AnimeRemoteDto>()

            if (remoteChanges.isEmpty()) return@withContext PullResult(0, null)

            // Теги (жанры) изменённых тайтлов тянем заранее, ДО локальной транзакции:
            // сетевые вызовы внутри db.transaction недопустимы. Курсорная выборка тегов была
            // с багом: приходили только изменённые строки, а локальный набор заменялся целиком —
            // старые теги терялись. Теперь читаем ПОЛНЫЙ набор тегов по каждому anime_id.
            val remoteTagsByAnime = pullTagsFor(
                userId = userId,
                animeIds = remoteChanges.filter { it.deleted_at == null }.map { it.id },
            )

            var maxUpdatedAt = cursorMs
            val attachmentsToDownload = mutableListOf<Pair<String, String>>()
            db.transaction {
                remoteChanges.forEach { remote ->
                    val remoteUpdatedMs = java.time.Instant.parse(remote.updated_at).toEpochMilli()
                    if (remoteUpdatedMs > maxUpdatedAt) {
                        maxUpdatedAt = remoteUpdatedMs
                    }

                    val local = db.animeQueries.getAnimeById(remote.id).executeAsOneOrNull()

                    // LWW: local dirty wins
                    if (local != null && local.sync_status != "SYNCED") {
                        return@forEach
                    }

                    // Soft delete: only apply if remote is newer than local
                    if (remote.deleted_at != null) {
                        if (local == null || remoteUpdatedMs > local.updatedAt) {
                            // FK CASCADE в SQLite не гарантирован (PRAGMA может быть выключен) —
                            // чистим теги явно.
                            db.animeQueries.deleteAnimeTags(remote.id)
                            db.animeQueries.deleteAnime(remote.id)
                        }
                        return@forEach
                    }

                    db.animeQueries.upsertFromSync(
                        id = remote.id,
                        title = remote.title,
                        imagePath = remote.image_path,
                        episodes = remote.episodes.toLong(),
                        // Рейтинг хранится ×10 (0..100). Легаси-строки в облаке (5-звёзд, 1..5)
                        // нормализуем на лету: новая шкала значений <10 не порождает.
                        rating = normalizeRemoteRating(remote.rating),
                        status = remote.status,
                        isFavorite = if (remote.is_favorite) 1L else 0L,
                        updatedAt = remoteUpdatedMs,
                        orderIndex = remote.order_index.toLong(),
                        dateAdded = remote.date_added,
                        categoryType = remote.category_type,
                        comment = remote.comment,
                        isAiRecommendation = if (remote.is_ai_recommendation) 1L else 0L,
                        anilist_id = remote.anilist_id?.toLong(),
                        mal_id = remote.mal_id?.toLong(),
                        shikimori_id = remote.shikimori_id?.toLong(),
                        anilist_not_found_at = remote.anilist_not_found_at,
                        mal_not_found_at = remote.mal_not_found_at,
                        shikimori_not_found_at = remote.shikimori_not_found_at,
                        isPrivate = if (remote.is_private) 1L else 0L,
                        encryptionIv = remote.encryption_iv,
                        deletedAt = remote.deleted_at,
                        // Облако полно строк, выгруженных до починки типа: `media_type` там 'ANIME'
                        // даже у манги. Чиним на входе тем же правилом, что и миграция 12.sqm,
                        // иначе первый же pull возвращал бы мангу обратно в аниме.
                        mediaType = normalizeRemoteMediaType(remote.media_type, remote.category_type),
                        title_en = remote.title_en,
                        title_en_checked_at = remote.title_en_checked_at,
                        title_ru = remote.title_ru,
                        title_ru_checked_at = remote.title_ru_checked_at
                    )
                    
                    // Локальный набор тегов заменяем облачным целиком (мы только что применили
                    // облачную версию строки anime — теги едут тем же снапшотом). Но ТОЛЬКО если
                    // теги реально прочитаны (remoteTagsByAnime != null); при недоступной таблице
                    // anime_tags локальные жанры НЕ трогаем, иначе затёрли бы их пустотой.
                    if (remoteTagsByAnime != null) {
                        db.animeQueries.deleteAnimeTags(remote.id)
                        remoteTagsByAnime[remote.id].orEmpty().forEach { tag ->
                            db.animeQueries.insertAnimeTag(remote.id, tag)
                        }
                    }

                    val imagePath = remote.image_path
                    if (imagePath?.startsWith("collection-attachment://") == true) {
                        if (!attachmentSyncManager.hasLocalCopy(remote.id, imagePath)) {
                            attachmentsToDownload.add(remote.id to imagePath)
                        }
                    }
                }
                db.syncQueries.setCursor("anime", maxUpdatedAt)
            }
            attachmentsToDownload.forEach { (animeId, cloudUrl) ->
                attachmentSyncManager.downloadAndImportToCollection(animeId, cloudUrl)
            }

            Log.i(TAG, "Pulled ${remoteChanges.size} anime row(s) from cloud")
            return@withContext PullResult(remoteChanges.size, null)
        } catch (e: Exception) {
            val safeError = safeSyncError(e)
            Log.e(TAG, "Pull failed: $safeError")
            return@withContext PullResult(0, safeError)
        }
    }

    /**
     * Guest/local-only rows stay SYNCED until first cloud upload. Queue them once when cloud is empty.
     */
    private suspend fun ensureInitialLocalUpload(userId: String) {
        val flagKey = initialUploadKey(userId)
        if (syncPrefs.getBoolean(flagKey, false)) return

        val localCount = db.animeQueries.getAnimeCount().executeAsOne()
        if (localCount == 0L) {
            syncPrefs.edit().putBoolean(flagKey, true).apply()
            return
        }

        if (remoteHasAnime(userId)) {
            syncPrefs.edit().putBoolean(flagKey, true).apply()
            return
        }

        Log.i(TAG, "Queueing $localCount local anime for first cloud upload")
        db.animeQueries.markAllAnimePendingUpload()
    }

    fun resetInitialUploadFlag(userId: String) {
        syncPrefs.edit().remove(initialUploadKey(userId)).apply()
    }

    suspend fun requeueLocalUploadIfCloudEmpty(userId: String) {
        if (db.animeQueries.getAnimeCount().executeAsOne() == 0L) return
        if (remoteHasAnime(userId)) return
        resetInitialUploadFlag(userId)
        ensureInitialLocalUpload(userId)
    }

    private fun initialUploadKey(userId: String) = "initial_local_upload_v1_$userId"

    /** Полный набор облачных тегов по списку anime_id (чанками, чтобы не упереться в лимит URL). */
    /**
     * @return null — теги прочитать НЕ удалось (таблицы нет и т.п.): вызывающий должен
     * оставить локальные теги нетронутыми, НЕ затирать. Непустая/пустая карта — авторитетный
     * облачный набор (пустая = у тайтла в облаке тегов нет).
     */
    private suspend fun pullTagsFor(
        userId: String,
        animeIds: List<String>,
    ): Map<String, List<String>>? {
        if (animeIds.isEmpty()) return emptyMap()
        return runCatching {
            val result = mutableListOf<AnimeTagRemoteDto>()
            animeIds.chunked(TAG_CHUNK_SIZE).forEach { chunk ->
                result += supabase.postgrest["anime_tags"]
                    .select {
                        filter {
                            eq("user_id", userId)
                            isIn("anime_id", chunk)
                        }
                    }
                    .decodeList<AnimeTagRemoteDto>()
            }
            result.groupBy({ it.anime_id }, { it.tag })
        }.getOrElse {
            Log.w(TAG, "Pull tags skipped: ${safeSyncError(it)}")
            null
        }
    }

    private suspend fun remoteHasAnime(userId: String): Boolean {
        return try {
            supabase.postgrest["anime"]
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                    limit(1)
                }
                .decodeList<AnimeRemoteDto>()
                .isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "remoteHasAnime failed: ${safeSyncError(e)}")
            false
        }
    }

    private companion object {
        private const val TAG = "SyncRepository"
        /** Размер чанка для isIn-фильтров по anime_id (PostgREST кодирует список в URL). */
        private const val TAG_CHUNK_SIZE = 100

        /** Легаси 5-звёздочный рейтинг (1..9) → хранимые единицы 10-балльной шкалы (×20). */
        private fun normalizeRemoteRating(rating: Int): Long =
            if (rating in 1..9) (rating * 20L).coerceAtMost(100L) else rating.toLong()

        /**
         * Тип записи из облака с починкой легаси-строк: до фикса `media_type` выгружался всегда
         * 'ANIME', тогда как `category_type` у манги честно равен 'MANGA'. Правило то же, что в
         * миграции 12.sqm — правим только мангу, признак однозначный.
         */
        private fun normalizeRemoteMediaType(mediaType: String, categoryType: String): String =
            if (mediaType.equals("ANIME", ignoreCase = true) &&
                categoryType.trim().equals("MANGA", ignoreCase = true)
            ) "MANGA" else mediaType
    }
}
