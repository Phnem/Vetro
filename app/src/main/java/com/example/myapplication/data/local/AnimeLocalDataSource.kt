package com.example.myapplication.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.example.myapplication.data.local.AnimeDatabase
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.models.RatingScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AnimeLocalDataSource(
    private val factory: SQLDelightDatabaseFactory,
    private val mirrorCoordinator: DeveloperMirrorCoordinator
) {

    private fun db(): AnimeDatabase = factory.getDatabase()

    /** Реактивный поток коллекции (опционально — одного типа медиа). */
    fun observeAllAnime(filterType: MediaType? = null): Flow<List<Anime>> {
        val queries = db().animeQueries
        val query = if (filterType == null) {
            queries.getAllAnimeWithTagsConcat(::concatRowToAnime)
        } else {
            queries.getAnimeWithTagsConcatByType(filterType.name, ::concatRowToAnime)
        }
        return query.asFlow().mapToList(Dispatchers.IO)
    }

    fun getAnimeCount(): Int {
        return db().animeQueries.getAnimeCount().executeAsOne().toInt()
    }

    fun getMaxOrderIndex(): Int {
        return db().animeQueries.getMaxOrderIndex().executeAsOne().toInt()
    }

    fun getAllAnimeList(): List<Anime> =
        db().animeQueries.getAllAnimeWithTagsConcat(::concatRowToAnime).executeAsList()

    fun getAnimeById(id: String): Anime? =
        db().animeQueries.getAnimeWithTagsConcatById(id, ::concatRowToAnime).executeAsOneOrNull()

    suspend fun updateAnimeComment(id: String, comment: String) {
        db().animeQueries.updateAnimeComment(comment, System.currentTimeMillis(), id)
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun insertAnime(anime: Anime) {
        db().insertNewAnime(anime)
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun updateAnime(anime: Anime) {
        db().animeQueries.transaction {
            db().animeQueries.updateAnime(
                title = anime.title,
                imagePath = anime.imageFileName,
                episodes = anime.episodes.toLong(),
                rating = RatingScale.displayToStored(anime.rating).toLong(),
                status = "watching",
                isFavorite = if (anime.isFavorite) 1L else 0L,
                updatedAt = System.currentTimeMillis(),
                orderIndex = anime.orderIndex.toLong(),
                categoryType = anime.categoryType,
                comment = anime.comment,
                isAiRecommendation = 0L,
                anilist_id = anime.anilistId?.toLong(),
                mal_id = anime.malId?.toLong(),
                shikimori_id = anime.shikimoriId?.toLong(),
                anilist_not_found_at = anime.anilistNotFoundAt,
                mal_not_found_at = anime.malNotFoundAt,
                shikimori_not_found_at = anime.shikimoriNotFoundAt,
                isPrivate = 0L,
                encryptionIv = null,
                deletedAt = null,
                mediaType = anime.mediaType.name,
                title_en = anime.titleEn,
                title_ru = anime.titleRu,
                tmdb_id = anime.tmdbId?.toLong(),
                kinopoisk_id = anime.kinopoiskId?.toLong(),
                tmdb_not_found_at = anime.tmdbNotFoundAt,
                kinopoisk_not_found_at = anime.kinopoiskNotFoundAt,
                imdb_id = anime.imdbId,
                id = anime.id
            )

            // Update tags
            db().animeQueries.deleteAnimeTags(anime.id)
            anime.tags.forEach { tag ->
                db().animeQueries.insertAnimeTag(
                    anime_id = anime.id,
                    tag = tag
                )
            }
        }
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun deleteAnime(id: String) {
        db().animeQueries.transaction {
            db().animeQueries.deleteAnimeTags(id)
            db().animeQueries.deleteAnime(id)
        }
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun insertAllAnime(list: List<Anime>) {
        val database = db()
        val now = System.currentTimeMillis()
        database.animeQueries.transaction {
            list.forEach { anime -> database.insertAnimeRow(anime, updatedAt = now) }
        }
        mirrorCoordinator.requestExportIfEnabled()
    }

    fun observeUpdates(): Flow<List<AnimeUpdate>> =
        db().animeQueries.getAllUpdates()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows ->
                rows.map { row ->
                    AnimeUpdate(
                        animeId = row.anime_id,
                        title = row.title,
                        currentEpisodes = row.current_episodes.toInt(),
                        newEpisodes = row.new_episodes.toInt(),
                        source = row.source,
                    )
                }
            }

    fun getUpdates(): List<AnimeUpdate> {
        return db().animeQueries.getAllUpdates()
            .executeAsList()
            .map { row ->
                AnimeUpdate(
                    animeId = row.anime_id,
                    title = row.title,
                    currentEpisodes = row.current_episodes.toInt(),
                    newEpisodes = row.new_episodes.toInt(),
                    source = row.source
                )
            }
    }

    fun getIgnoredMap(): Map<String, Int> {
        return db().animeQueries.getIgnoredMap()
            .executeAsList()
            .associate { row -> row.anime_id to row.new_episodes.toInt() }
    }

    suspend fun setUpdates(updates: List<AnimeUpdate>) {
        db().animeQueries.transaction {
            db().animeQueries.deleteAllUpdates()
            updates.forEach { u ->
                db().animeQueries.insertUpdate(
                    anime_id = u.animeId,
                    title = u.title,
                    current_episodes = u.currentEpisodes.toLong(),
                    new_episodes = u.newEpisodes.toLong(),
                    source = u.source
                )
            }
        }
        mirrorCoordinator.requestExportIfEnabled()
    }

    /** Прогресс выходящих сезонов (карточки «в процессе»): anime_id → снимок. */
    fun observeAiringProgress(): Flow<Map<String, com.example.myapplication.data.models.AiringProgress>> =
        db().airingProgressQueries.getAllAiringProgress()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows ->
                rows.associate { row ->
                    row.anime_id to com.example.myapplication.data.models.AiringProgress(
                        animeId = row.anime_id,
                        seasonNumber = row.season_number?.toInt(),
                        airedEpisodes = row.aired_episodes.toInt(),
                        totalEpisodes = row.total_episodes?.toInt(),
                        updatedAt = row.updated_at,
                    )
                }
            }

    /** Разовый снимок прогресса сезонов (прошлый проход) — для закрытия завершённых. */
    fun getAiringProgressSnapshot(): Map<String, com.example.myapplication.data.models.AiringProgress> =
        db().airingProgressQueries.getAllAiringProgress()
            .executeAsList()
            .associate { row ->
                row.anime_id to com.example.myapplication.data.models.AiringProgress(
                    animeId = row.anime_id,
                    seasonNumber = row.season_number?.toInt(),
                    airedEpisodes = row.aired_episodes.toInt(),
                    totalEpisodes = row.total_episodes?.toInt(),
                    updatedAt = row.updated_at,
                )
            }

    /** Полная перезапись снимка прогресса сезонов (каждый проход проверки авторитетен). */
    suspend fun setAiringProgress(items: List<com.example.myapplication.data.models.AiringProgress>) {
        db().airingProgressQueries.transaction {
            db().airingProgressQueries.deleteAllAiringProgress()
            items.forEach { p ->
                db().airingProgressQueries.upsertAiringProgress(
                    anime_id = p.animeId,
                    season_number = p.seasonNumber?.toLong(),
                    aired_episodes = p.airedEpisodes.toLong(),
                    total_episodes = p.totalEpisodes?.toLong(),
                    updated_at = p.updatedAt,
                )
            }
        }
    }

    suspend fun addIgnored(animeId: String, newEpisodes: Int) {
        db().animeQueries.setIgnored(
            anime_id = animeId,
            new_episodes = newEpisodes.toLong()
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    /**
     * «Прочитано» для пачки обновлений: каждое уходит из списка и запоминается как отклонённое на
     * своём числе серий, чтобы следующая проверка не вернула его снова. Одной транзакцией.
     */
    suspend fun markUpdatesRead(updates: List<AnimeUpdate>) {
        val queries = db().animeQueries
        queries.transaction {
            updates.forEach { update ->
                queries.setIgnored(anime_id = update.animeId, new_episodes = update.newEpisodes.toLong())
                queries.deleteUpdateByAnimeId(update.animeId)
            }
        }
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun removeUpdate(animeId: String) {
        db().animeQueries.deleteUpdateByAnimeId(animeId)
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun setAnilistId(animeId: String, anilistId: Int) {
        db().animeQueries.setAnilistId(
            anilist_id = anilistId.toLong(),
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun setMalId(animeId: String, malId: Int) {
        db().animeQueries.setMalId(
            mal_id = malId.toLong(),
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun setShikimoriId(animeId: String, shikimoriId: Int) {
        db().animeQueries.setShikimoriId(
            shikimori_id = shikimoriId.toLong(),
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    /** Строки без EN-названия и без отметки попытки — кандидаты для обогащения (Stage 7/8). */
    fun getAnimeNeedingTitleEn(limit: Int): List<Anime> =
        db().animeQueries.selectNeedingTitleEn(limit.toLong(), ::concatRowToAnime).executeAsList()

    /** Сколько записей ещё ждут EN-названия (для прогресса дубляжа). */
    fun countAnimeNeedingTitleEn(): Int {
        return db().animeQueries.countNeedingTitleEn().executeAsOne().toInt()
    }

    /** Сброс отметок «проверено» у ненайденных — полный перескан дубляжа (dev-кнопка, Stage 9). */
    suspend fun resetTitleEnChecks() {
        db().animeQueries.resetTitleEnChecks(
            updatedAt = System.currentTimeMillis()
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    /** Записи без RU-названия и без отметки попытки — кандидаты обратного обогащения (Stage 10). */
    fun getAnimeNeedingTitleRu(limit: Int): List<Anime> =
        db().animeQueries.selectNeedingTitleRu(limit.toLong(), ::concatRowToAnime).executeAsList()

    /** Сколько записей ещё ждут RU-названия. */
    fun countAnimeNeedingTitleRu(): Int {
        return db().animeQueries.countNeedingTitleRu().executeAsOne().toInt()
    }

    suspend fun resetTitleRuChecks() {
        db().animeQueries.resetTitleRuChecks(
            updatedAt = System.currentTimeMillis()
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun setTitleRu(animeId: String, titleRu: String, atMillis: Long = System.currentTimeMillis()) {
        db().animeQueries.setTitleRu(
            title_ru = titleRu,
            title_ru_checked_at = atMillis,
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun markTitleRuChecked(animeId: String, atMillis: Long = System.currentTimeMillis()) {
        db().animeQueries.markTitleRuChecked(
            title_ru_checked_at = atMillis,
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun setTitleEn(animeId: String, titleEn: String, atMillis: Long = System.currentTimeMillis()) {
        db().animeQueries.setTitleEn(
            title_en = titleEn,
            title_en_checked_at = atMillis,
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun markTitleEnChecked(animeId: String, atMillis: Long = System.currentTimeMillis()) {
        db().animeQueries.markTitleEnChecked(
            title_en_checked_at = atMillis,
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun markAnilistNotFound(animeId: String, atMillis: Long) {
        db().animeQueries.markAnilistNotFound(
            anilist_not_found_at = atMillis,
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun markMalNotFound(animeId: String, atMillis: Long) {
        db().animeQueries.markMalNotFound(
            mal_not_found_at = atMillis,
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun markShikimoriNotFound(animeId: String, atMillis: Long) {
        db().animeQueries.markShikimoriNotFound(
            shikimori_not_found_at = atMillis,
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun setTmdbId(animeId: String, tmdbId: Int) {
        db().animeQueries.setTmdbId(
            tmdb_id = tmdbId.toLong(),
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    fun isSeriesEpisodesNormalized(animeId: String): Boolean =
        db().animeQueries.countSeriesEpisodeNormalization(animeId).executeAsOne() > 0

    suspend fun markSeriesEpisodesNormalized(animeId: String) {
        db().animeQueries.markSeriesEpisodesNormalized(anime_id = animeId)
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun clearTmdbId(animeId: String) {
        db().animeQueries.clearTmdbId(updatedAt = System.currentTimeMillis(), id = animeId)
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun setKinopoiskId(animeId: String, kinopoiskId: Int) {
        db().animeQueries.setKinopoiskId(
            kinopoisk_id = kinopoiskId.toLong(),
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    /**
     * Stores the canonical IMDb id (`tt0412142`).
     *
     * Blank input is rejected rather than written: an empty string would look like a resolved id and
     * stop later enrichment from ever retrying.
     */
    suspend fun setImdbId(animeId: String, imdbId: String) {
        val canonical = imdbId.trim()
        if (canonical.isEmpty()) return
        db().animeQueries.setImdbId(
            imdb_id = canonical,
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun clearKinopoiskId(animeId: String) {
        db().animeQueries.clearKinopoiskId(updatedAt = System.currentTimeMillis(), id = animeId)
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun markTmdbNotFound(animeId: String, atMillis: Long) {
        db().animeQueries.markTmdbNotFound(
            tmdb_not_found_at = atMillis,
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }

    suspend fun markKinopoiskNotFound(animeId: String, atMillis: Long) {
        db().animeQueries.markKinopoiskNotFound(
            kinopoisk_not_found_at = atMillis,
            updatedAt = System.currentTimeMillis(),
            id = animeId
        )
        mirrorCoordinator.requestExportIfEnabled()
    }
}
