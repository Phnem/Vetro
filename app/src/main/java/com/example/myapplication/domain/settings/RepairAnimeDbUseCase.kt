package com.example.myapplication.domain.settings

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.data.repository.ImageStorageRepository
import com.example.myapplication.domain.addedit.SaveAnimeParams
import com.example.myapplication.domain.addedit.SaveAnimeUseCase
import com.example.myapplication.domain.search.apiRatingTo10
import com.example.myapplication.domain.search.mapApiGenresToTagIds
import com.example.myapplication.network.AnimeDetails
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.ExternalIds
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.movie.MovieSeriesRepairLookup
import com.example.myapplication.network.movie.MovieSeriesRepository
import com.example.myapplication.sync.TitleMatcher
import kotlinx.coroutines.delay

data class RepairAnimeDbResult(
    val scannedCount: Int,
    val repairedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
)

/**
 * Проходит по коллекции и заполняет отсутствующие поля (обложка, жанры, рейтинг, ID и пр.).
 *
 * Иерархия источников для аниме-записей ЕДИНА для картинок, жанров и оценок и не зависит
 * от языка приложения:
 *   основа — AniList, endpoint 1 — Shikimori, endpoint 2 — MAL, endpoint 3 — Kitsu,
 *   endpoint 4 — AniLibria (постеры/серии/RU-жанры в их озвучке; своей оценки нет).
 * Поля сливаются по-отдельности: если у AniList нет постера, но есть жанры — жанры берём
 * у AniList, постер ищем дальше по иерархии. Жанры любых источников (EN от AniList/MAL,
 * RU от Shikimori) конвертируются в канонические ID через [GenreRepository] и корректно
 * отображаются на обоих языках.
 *
 * Внутри одной записи два раунда: сперва строгий матч по названию у всех источников,
 * затем (если пробелы остались и строгих матчей нет) — relaxed-выбор лучшего кандидата.
 *
 * Не-аниме записи (кино/сериалы) идут прежним путём через общий searchApi.
 */
class RepairAnimeDbUseCase(
    private val repository: AnimeRepository,
    private val saveAnimeUseCase: SaveAnimeUseCase,
    private val imageStorage: ImageStorageRepository,
    private val genreRepository: GenreRepository,
    private val placeholderPurge: ShikimoriPlaceholderPurge,
    private val movieSeriesRepository: MovieSeriesRepository,
) {
    suspend operator fun invoke(
        language: AppLanguage,
        contentType: AppContentType,
        sessionLog: RepairDbSessionLog,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): RepairAnimeDbResult {
        // Сначала снять чужую привязку (id/обложка от постороннего тайтла), иначе дальнейшие проходы
        // будут «чинить» запись по чужим id.
        runCatching { purgeForeignMatches(repository.getAllAnimeSnapshot(), language, sessionLog) }
            .onFailure { e -> sessionLog.warn("Foreign match audit failed", e) }

        // Отдельный проход: сверить и починить malId по настоящему myanimelist_id из Shikimori
        // (легаси-порча — у части записей malId == shikimoriId, это РАЗНЫЕ id → чужое аниме в MAL/AniList).
        reconcileMalIds(repository.getAllAnimeSnapshot(), sessionLog)

        // Чистка старых постеров-заглушек Shikimori: файл удаляется, запись падает
        // в missingImage-пробел и в этом же проходе получает нормальный постер.
        runCatching { placeholderPurge.purge(repository.getAllAnimeSnapshot(), sessionLog) }
            .onFailure { e -> sessionLog.warn("Placeholder purge failed", e) }

        val all = repository.getAllAnimeSnapshot() // перечитываем: malId мог измениться после реконсиляции
        val gapNeeding = all.filter { detectGaps(it).needsRepair }
        val gapNeedingIds = gapNeeding.mapTo(HashSet()) { it.id }
        val repairTargets = all.filter { it.id in gapNeedingIds || it.hasSavedMovieSeriesId() }

        sessionLog.info(
            "Start repair: ${all.size} entries, language=$language, " +
                "contentType=$contentType, needRepair=${gapNeeding.size}, " +
                "identityValidation=${repairTargets.size - gapNeeding.size} " +
                "(hierarchy: AniList → Shikimori → MAL → Kitsu → AniLibria)",
        )
        onProgress(0, repairTargets.size)

        for ((index, anime) in repairTargets.withIndex()) {
            repairOne(anime, language, contentType, sessionLog)
            onProgress(index + 1, repairTargets.size)
            if (index < repairTargets.lastIndex) delay(ITEM_DELAY_MS)
        }

        val finalNeeding = repository.getAllAnimeSnapshot().count { detectGaps(it).needsRepair }
        val result = RepairAnimeDbResult(
            scannedCount = all.size,
            repairedCount = (gapNeeding.size - finalNeeding).coerceAtLeast(0),
            failedCount = finalNeeding,
            skippedCount = all.size - gapNeeding.size,
        )
        sessionLog.info(
            "Repair done: scanned=${result.scannedCount}, repaired=${result.repairedCount}, " +
                "failed=${result.failedCount}, skipped=${result.skippedCount}",
        )
        return result
    }

    /**
     * Отдельный проход реконсиляции MAL id. У части записей `malId` ошибочно равен `shikimoriId`
     * (это РАЗНЫЕ идентификаторы), из-за чего поиск по MAL id попадает на чужое аниме. Берём
     * настоящий `myanimelist_id` из Shikimori detail и исправляем расхождение; заодно бэкфиллим
     * malId там, где у записи есть shikimoriId, но malId пуст. Кандидаты ограничены `malId == null`
     * или `malId == shikimoriId`, чтобы не дёргать сеть по заведомо корректным записям.
     */
    private suspend fun reconcileMalIds(all: List<Anime>, sessionLog: RepairDbSessionLog) {
        val suspects = all.filter { anime ->
            anime.shikimoriId != null && (anime.malId == null || anime.malId == anime.shikimoriId)
        }
        if (suspects.isEmpty()) return
        sessionLog.info("MAL id reconcile: ${suspects.size} candidates (malId null или == shikimoriId)")

        var fixed = 0
        for ((index, anime) in suspects.withIndex()) {
            val shikiId = anime.shikimoriId ?: continue
            val realMalId = repository.shikimoriById(shikiId, AppLanguage.EN).getOrNull()?.malId
            when {
                realMalId == null -> sessionLog.debug(
                    "No myanimelist_id for \"${anime.title}\" (shikimori=$shikiId); malId=${anime.malId} оставлен",
                )
                realMalId != anime.malId -> runCatching { repository.setMalId(anime.id, realMalId) }
                    .onSuccess {
                        fixed++
                        sessionLog.info(
                            "Fixed malId \"${anime.title}\": ${anime.malId} → $realMalId (shikimori=$shikiId)",
                        )
                    }
                    .onFailure { e -> sessionLog.warn("setMalId failed for \"${anime.title}\"", e) }
                else -> Unit // malId уже верный
            }
            if (index < suspects.lastIndex) delay(ITEM_DELAY_MS)
        }
        sessionLog.info("MAL id reconcile done: fixed=$fixed of ${suspects.size}")
    }

    /**
     * Снимает чужую привязку. Раньше «относительный» матч брал первый ответ AniList/MAL/Kitsu на
     * кириллический запрос — постороннее аниме (так у разных записей появлялся один и тот же
     * «Psyren»): запись получала его id, английское название, обложку, жанры и оценку.
     *
     * Запись считается испорченной, только если у неё русское название, есть внешний id, а название
     * по этому id с ним не имеет ничего общего ([isForeignMatch]). Тогда id и английское название
     * снимаются, а обложка, жанры и оценка — только если они совпадают с данными чужого тайтла
     * (чужие данные от своих не отличить иначе). Дальше обычный проход заполнит пробелы заново
     * уже исправленным подбором.
     */
    /**
     * Разовая проверка для фонового воркера. true — каждая запись проверена до конца (нет ответа
     * сети — не проверена), и повторять проход не нужно.
     */
    suspend fun auditForeignMatches(language: AppLanguage, sessionLog: RepairDbSessionLog): Boolean =
        runCatching { purgeForeignMatches(repository.getAllAnimeSnapshot(), language, sessionLog) }
            .onFailure { e -> sessionLog.warn("Foreign match audit failed", e) }
            .getOrDefault(false)

    /** @return true, если каждый подозрительный тайтл удалось проверить (без сбоев сети и записи). */
    private suspend fun purgeForeignMatches(
        all: List<Anime>,
        language: AppLanguage,
        sessionLog: RepairDbSessionLog,
    ): Boolean {
        val suspects = all.filter { anime ->
            anime.mediaType == MediaType.ANIME &&
                (anime.shikimoriId != null || anime.malId != null) &&
                russianNamesOf(anime).isNotEmpty()
        }
        if (suspects.isEmpty()) return true
        sessionLog.info("Foreign match audit: ${suspects.size} candidates")

        var purged = 0
        var unverified = 0
        for ((index, anime) in suspects.withIndex()) {
            // id Shikimori у аниме совпадает с MAL id, поэтому malId годится, когда shikimoriId нет.
            val lookupId = anime.shikimoriId ?: anime.malId ?: continue
            val remote = repository.russianTitleByShikimoriId(lookupId).getOrNull()
            when {
                remote == null -> unverified++
                isForeignMatch(russianNamesOf(anime), remote) ->
                    runCatching { purgeForeignMatch(anime, lookupId, language, sessionLog) }
                        .onSuccess { purged++ }
                        .onFailure { e ->
                            unverified++
                            sessionLog.warn("Foreign match purge failed for \"${anime.title}\"", e)
                        }
            }
            if (index < suspects.lastIndex) delay(ITEM_DELAY_MS)
        }
        sessionLog.info("Foreign match audit done: purged=$purged, unverified=$unverified of ${suspects.size}")
        return unverified == 0
    }

    private fun russianNamesOf(anime: Anime): List<String> =
        listOfNotNull(anime.titleRu, anime.title).filter(::containsCyrillic).distinct()

    private suspend fun purgeForeignMatch(
        anime: Anime,
        lookupId: Int,
        language: AppLanguage,
        sessionLog: RepairDbSessionLog,
    ) {
        val foreign = repository.shikimoriById(lookupId, language).getOrNull()
        val foreignTags = foreign?.let { mapApiGenresToTagIds(it.genres, genreRepository) }.orEmpty()
        val foreignRating = foreign?.let { apiRatingTo10(it.rating) }
        val ownsForeignTags = foreignTags.isNotEmpty() && anime.tags == foreignTags
        val ownsForeignRating = foreignRating != null && foreignRating > 0f && anime.rating == foreignRating

        sessionLog.info(
            "Foreign match \"${anime.title}\": ids anilist=${anime.anilistId} mal=${anime.malId} " +
                "shikimori=${anime.shikimoriId}, titleEn=\"${anime.titleEn}\" → cleared",
        )
        saveAnimeUseCase(
            SaveAnimeParams(
                animeId = anime.id,
                title = anime.title,
                titleEn = null,
                titleRu = anime.titleRu,
                episodes = anime.episodes,
                rating = if (ownsForeignRating) 0f else anime.rating,
                imageUri = null,
                currentImageFileName = null,
                orderIndex = anime.orderIndex,
                dateAdded = anime.dateAdded,
                isFavorite = anime.isFavorite,
                selectedTags = if (ownsForeignTags) emptyList() else anime.tags,
                categoryType = anime.categoryType,
                mediaType = anime.mediaType,
                comment = anime.comment,
                anilistId = null,
                malId = null,
                shikimoriId = null,
                anilistNotFoundAt = null,
                malNotFoundAt = null,
                shikimoriNotFoundAt = null,
                tmdbId = anime.tmdbId,
                kinopoiskId = anime.kinopoiskId,
                tmdbNotFoundAt = anime.tmdbNotFoundAt,
                kinopoiskNotFoundAt = anime.kinopoiskNotFoundAt,
            ),
        ).getOrThrow()
    }

    /** internal: live-обогащение чинит по одной записи тем же кодом, что и полный проход. */
    internal suspend fun repairOne(
        anime: Anime,
        language: AppLanguage,
        contentType: AppContentType,
        sessionLog: RepairDbSessionLog,
    ) {
        val initialGaps = detectGaps(anime)
        val isMovieSeries = anime.mediaType == MediaType.MOVIE || anime.mediaType == MediaType.SERIES
        if (!initialGaps.needsRepair && !(isMovieSeries && anime.hasSavedMovieSeriesId())) return

        sessionLog.debug(
            "Needs repair \"${anime.title}\": image=${initialGaps.missingImage}, " +
                "tags=${initialGaps.missingTags}, rating=${initialGaps.missingRating}, " +
                "id=${initialGaps.missingAnimeExternalId}, type=${initialGaps.missingCategoryType}, " +
                "episodes=${initialGaps.missingEpisodes}",
        )

        val movieLookup: MovieSeriesRepairLookup? = if (isMovieSeries) {
            val lookup = movieSeriesRepository.lookupForRepair(
                query = anime.title,
                contentType = anime.mediaType.toAppContentType(),
                language = language,
                externalIds = ExternalIds(tmdb = anime.tmdbId, kinopoisk = anime.kinopoiskId),
                includeKinopoisk = initialGaps.hasRetryableOptionalGaps() || anime.kinopoiskId != null,
                searchForMetadata = initialGaps.hasMetadataGaps(),
            )
            lookup
        } else {
            null
        }
        val repairAnime = if (movieLookup != null) {
            if (movieLookup.staleTmdbId) repository.clearTmdbId(anime.id)
            if (movieLookup.staleKinopoiskId) repository.clearKinopoiskId(anime.id)
            anime.copy(
                tmdbId = anime.tmdbId.takeUnless { movieLookup.staleTmdbId },
                kinopoiskId = anime.kinopoiskId.takeUnless { movieLookup.staleKinopoiskId },
            )
        } else {
            anime
        }
        val gaps = if (repairAnime === anime) initialGaps else detectGaps(repairAnime)
        if (!gaps.needsRepair) return
        val candidates: List<ApiSearchResult> = if (movieLookup != null) {
            listOfNotNull(
                pickBestMatch(anime.title, movieLookup.candidates) ?: movieLookup.candidates.firstOrNull()
            )
        } else {
            collectAnimeCandidates(anime, language, sessionLog)
        }

        if (candidates.isEmpty()) {
            if (movieLookup != null) persistProviderNoMatches(repairAnime, gaps, movieLookup)
            sessionLog.warn("No API data for \"${anime.title}\"")
            return
        }

        sessionLog.debug(
            "Candidates for \"${anime.title}\": " +
                candidates.joinToString { "\"${it.title}\" via ${it.source}" },
        )

        val changed = runCatching { applyRepair(repairAnime, candidates, gaps, sessionLog) }
            .onFailure { e -> sessionLog.warn("Repair error for \"${anime.title}\"", e) }
            .getOrDefault(false)
        if (movieLookup != null) persistProviderNoMatches(repairAnime, gaps, movieLookup)
        if (changed) {
            sessionLog.info("Repaired \"${anime.title}\" via ${candidates.joinToString { it.source }}")
        } else {
            sessionLog.warn("Matched \"${anime.title}\" but gaps remain")
        }
    }

    private suspend fun persistProviderNoMatches(
        anime: Anime,
        gaps: FieldGaps,
        lookup: MovieSeriesRepairLookup,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (gaps.missingTmdb && lookup.candidates.none { it.externalIds.tmdb != null }) {
            notFoundTimestampFor(lookup.tmdb, nowMillis)?.let {
                repository.markTmdbNotFound(anime.id, it)
            }
        }
        if (gaps.hasRetryableOptionalGaps() && lookup.candidates.none { it.externalIds.kinopoisk != null }) {
            notFoundTimestampFor(lookup.kinopoisk, nowMillis)?.let {
                repository.markKinopoiskNotFound(anime.id, it)
            }
        }
    }

    private fun MediaType.toAppContentType(): AppContentType = when (this) {
        MediaType.MOVIE -> AppContentType.MOVIE
        MediaType.SERIES -> AppContentType.SERIES
        else -> error("Expected MOVIE/SERIES, got $this")
    }

    /**
     * Кандидаты в порядке иерархии. Источники опрашиваются лениво: следующий дергаем, только
     * если уже собранные не закрывают все пробелы. Второй relaxed-раунд — если строгих матчей
     * не нашлось вовсе.
     */
    private suspend fun collectAnimeCandidates(
        anime: Anime,
        language: AppLanguage,
        sessionLog: RepairDbSessionLog,
    ): List<ApiSearchResult> {
        val gaps = detectGaps(anime)
        val collected = mutableListOf<ApiSearchResult>()

        suspend fun round(strict: Boolean) {
            val fetchers: List<suspend () -> ApiSearchResult?> = listOf(
                { fetchFromAniList(anime, language, sessionLog, strict = strict) },
                { fetchFromShikimori(anime, language, sessionLog, strict = strict) },
                { fetchFromMal(anime, language, sessionLog, strict = strict) },
                { fetchFromKitsu(anime, sessionLog, strict = strict) },
                { fetchFromAnilibria(anime, sessionLog, strict = strict) },
            )
            for (fetch in fetchers) {
                if (gapsCovered(gaps, collected)) return
                val result = fetch() ?: continue
                collected += result
            }
        }

        round(strict = true)
        if (collected.isEmpty()) {
            sessionLog.debug("No strict match for \"${anime.title}\", relaxed round")
            round(strict = false)
        }
        return collected
    }

    /** Могут ли уже собранные кандидаты закрыть все пробелы записи. */
    private fun gapsCovered(gaps: FieldGaps, candidates: List<ApiSearchResult>): Boolean {
        if (candidates.isEmpty()) return false
        val imageOk = !gaps.missingImage || candidates.any { !it.posterUrl.isNullOrBlank() }
        val tagsOk = !gaps.missingTags ||
            candidates.any { mapApiGenresToTagIds(it.genres, genreRepository).isNotEmpty() }
        val ratingOk = !gaps.missingRating || candidates.any { (it.rating ?: 0) > 0 }
        val episodesOk = !gaps.missingEpisodes || candidates.any { it.episodes > 0 }
        val idOk = !gaps.missingAnimeExternalId || candidates.any { it.externalId != null }
        val tmdbOk = !gaps.missingTmdb || candidates.any { it.externalIds.tmdb != null }
        val kinopoiskOk = !gaps.missingKinopoisk || candidates.any { it.externalIds.kinopoisk != null }
        val typeOk = !gaps.missingCategoryType || candidates.any { it.categoryType.isNotBlank() }
        return imageOk && tagsOk && ratingOk && episodesOk && idOk && tmdbOk && kinopoiskOk && typeOk
    }

    /** internal: переиспользуется live-обогащением ([com.example.myapplication.domain.enrichment]). */
    internal data class FieldGaps(
        val missingImage: Boolean,
        val missingTags: Boolean,
        val missingRating: Boolean,
        val missingAnimeExternalId: Boolean,
        val missingTmdb: Boolean,
        val missingKinopoisk: Boolean,
        val kinopoiskRetryable: Boolean,
        val missingCategoryType: Boolean,
        val missingEpisodes: Boolean,
    ) {
        fun hasCriticalGaps(): Boolean =
            missingImage || missingTags || missingRating || missingAnimeExternalId || missingTmdb ||
                missingCategoryType || missingEpisodes

        fun hasRetryableOptionalGaps(): Boolean = missingKinopoisk && kinopoiskRetryable

        fun hasMetadataGaps(): Boolean =
            missingImage || missingTags || missingRating || missingCategoryType || missingEpisodes

        val needsRepair: Boolean = hasCriticalGaps() || hasRetryableOptionalGaps()
    }

    internal fun detectGaps(anime: Anime, nowMillis: Long = System.currentTimeMillis()): FieldGaps {
        val hasImage = !anime.imageFileName.isNullOrBlank() &&
            imageStorage.hasLocalImage(anime.imageFileName)
        return classifyRepairGaps(anime, hasImage, nowMillis)
    }

    private fun Anime.hasSavedMovieSeriesId(): Boolean =
        (mediaType == MediaType.MOVIE || mediaType == MediaType.SERIES) &&
            (tmdbId != null || kinopoiskId != null)

    private suspend fun fetchFromShikimori(
        anime: Anime,
        language: AppLanguage,
        sessionLog: RepairDbSessionLog,
        strict: Boolean,
    ): ApiSearchResult? {
        anime.shikimoriId?.let { id ->
            repository.shikimoriById(id, language).getOrNull()?.let {
                sessionLog.debug("Shikimori byId=$id for \"${anime.title}\"")
                return it
            }
        }

        val results = repository.searchAnimeShikimoriOnly(
            query = anime.title,
            language = language,
            allowZeroEpisodes = true,
        ).getOrNull().orEmpty()

        val match = if (strict) {
            pickBestMatch(anime.title, results)
        } else {
            pickRelaxed(anime.title, results, ruAware = true)?.also {
                sessionLog.debug("Shikimori relaxed match for \"${anime.title}\"")
            }
        }
        return match?.let { withShikimoriMalId(it, language) }
    }

    /**
     * Списковый Shikimori-результат не содержит MAL id — дотягиваем его detail-запросом,
     * чтобы у записи проставились ОБА id (shikimori_id и mal_id).
     */
    private suspend fun withShikimoriMalId(result: ApiSearchResult, language: AppLanguage): ApiSearchResult {
        if (result.malId != null) return result
        val shikiId = result.externalId?.toIntOrNull() ?: return result
        val malId = repository.shikimoriById(shikiId, language).getOrNull()?.malId ?: return result
        return result.copy(malId = malId)
    }

    private suspend fun fetchFromAniList(
        anime: Anime,
        language: AppLanguage,
        sessionLog: RepairDbSessionLog,
        strict: Boolean,
    ): ApiSearchResult? {
        anime.anilistId?.let { id ->
            repository.mediaByAnilistId(id).getOrNull()?.let {
                sessionLog.debug("AniList byId=$id for \"${anime.title}\"")
                return it
            }
        }

        val results = repository.searchAnimeAniListOnly(
            query = anime.title,
            language = language,
            limit = 10,
        ).getOrNull().orEmpty()

        if (strict) {
            pickBestMatch(anime.title, results)?.let { return it }
            repository.fetchDetails(
                com.example.myapplication.network.DetailsLookupRequest(
                    title = anime.title,
                    language = language,
                    isManga = anime.mediaType == com.example.myapplication.data.models.MediaType.MANGA,
                )
            ).getOrNull()?.toApiSearchResult()
                // Запасной поиск по деталям тоже не вправе отдавать постороннее аниме.
                ?.takeIf { pickBestMatch(anime.title, listOf(it)) != null }
                ?.let {
                    sessionLog.debug("fetchDetails fallback for \"${anime.title}\" via ${it.source}")
                    return it
                }
            return null
        }

        return pickRelaxed(anime.title, results).also {
            if (it != null) sessionLog.debug("AniList relaxed match for \"${anime.title}\"")
        }
    }

    private suspend fun fetchFromMal(
        anime: Anime,
        language: AppLanguage,
        sessionLog: RepairDbSessionLog,
        strict: Boolean,
    ): ApiSearchResult? {
        anime.malId?.let { id ->
            repository.malById(id, language).getOrNull()?.let {
                sessionLog.debug("MAL byId=$id for \"${anime.title}\"")
                return it
            }
        }

        val results = repository.searchAnimeMalOnly(
            query = anime.title,
            language = language,
            limit = 10,
        ).getOrNull().orEmpty()

        if (strict) {
            return pickBestMatch(anime.title, results)
        }

        return pickRelaxed(anime.title, results).also {
            if (it != null) sessionLog.debug("MAL relaxed match for \"${anime.title}\"")
        }
    }

    private suspend fun fetchFromKitsu(
        anime: Anime,
        sessionLog: RepairDbSessionLog,
        strict: Boolean,
    ): ApiSearchResult? {
        val results = repository.searchAnimeKitsuOnly(anime.title).getOrNull().orEmpty()
        if (strict) return pickBestMatch(anime.title, results)
        return pickRelaxed(anime.title, results).also {
            if (it != null) sessionLog.debug("Kitsu relaxed match for \"${anime.title}\"")
        }
    }

    private suspend fun fetchFromAnilibria(
        anime: Anime,
        sessionLog: RepairDbSessionLog,
        strict: Boolean,
    ): ApiSearchResult? {
        val results = repository.searchAnimeAnilibriaOnly(anime.title).getOrNull().orEmpty()
        if (strict) return pickBestMatch(anime.title, results)
        return pickRelaxed(anime.title, results, ruAware = true).also {
            if (it != null) sessionLog.debug("AniLibria relaxed match for \"${anime.title}\"")
        }
    }

    /**
     * Слияние по полям: каждое недостающее поле берём у ПЕРВОГО кандидата в иерархии,
     * у которого оно есть. Внешние ID собираем со всех кандидатов сразу.
     * @return true если в БД реально записаны новые данные
     */
    private suspend fun applyRepair(
        anime: Anime,
        candidates: List<ApiSearchResult>,
        gaps: FieldGaps,
        sessionLog: RepairDbSessionLog,
    ): Boolean {
        var changed = false

        val imageFileName = when {
            !gaps.missingImage -> anime.imageFileName
            else -> {
                val url = candidates.firstNotNullOfOrNull { it.posterUrl?.takeIf { u -> u.isNotBlank() } }
                if (url == null) {
                    sessionLog.warn("No poster URL for \"${anime.title}\"")
                    anime.imageFileName
                } else {
                    imageStorage.saveImageFromUrl(url, anime.id).fold(
                        onSuccess = { name ->
                            changed = true
                            sessionLog.debug("Poster saved for \"${anime.title}\": $name")
                            name
                        },
                        onFailure = { e ->
                            sessionLog.warn("Poster download failed for \"${anime.title}\": $url", e)
                            anime.imageFileName
                        },
                    )
                }
            }
        }

        val tags = if (gaps.missingTags) {
            val mapped = candidates.firstNotNullOfOrNull { candidate ->
                mapApiGenresToTagIds(candidate.genres, genreRepository)
                    .takeIf { it.isNotEmpty() }
                    ?.also { sessionLog.debug("Tags for \"${anime.title}\" via ${candidate.source}: $it") }
            }
            if (mapped != null) {
                changed = true
                mapped
            } else {
                if (candidates.any { it.genres.isNotEmpty() }) {
                    sessionLog.warn(
                        "Genres from API not mapped for \"${anime.title}\": " +
                            candidates.flatMap { it.genres }.distinct(),
                    )
                }
                anime.tags
            }
        } else {
            anime.tags
        }

        val rating = if (gaps.missingRating) {
            val rating10 = candidates.firstNotNullOfOrNull { candidate ->
                apiRatingTo10(candidate.rating).takeIf { it > 0f }
            }
            if (rating10 != null) {
                changed = true
                rating10
            } else {
                anime.rating
            }
        } else {
            anime.rating
        }

        val episodes = repairedEpisodeCount(anime, candidates, gaps)
        if (episodes != anime.episodes) changed = true

        val categoryType = if (gaps.missingCategoryType) {
            changed = true
            candidates.firstNotNullOfOrNull { it.categoryType.takeIf { t -> t.isNotBlank() } } ?: "ANIME"
        } else {
            anime.categoryType
        }

        val ids = mergedRepairExternalIds(anime, candidates)
        val newAnilistId = ids.anilist
        val newMalId = ids.mal
        val newShikimoriId = ids.shikimori
        val newTmdbId = ids.tmdb
        val newKinopoiskId = ids.kinopoisk
        if (
            newAnilistId != anime.anilistId || newMalId != anime.malId ||
            newShikimoriId != anime.shikimoriId || newTmdbId != anime.tmdbId ||
            newKinopoiskId != anime.kinopoiskId
        ) {
            changed = true
        }

        val titleEn = anime.titleEn ?: candidates.firstNotNullOfOrNull { it.titleEn }
        val titleRu = anime.titleRu ?: candidates.firstNotNullOfOrNull { it.titleRu }
        if (titleEn != anime.titleEn || titleRu != anime.titleRu) changed = true

        if (!changed) return false

        val params = SaveAnimeParams(
            animeId = anime.id,
            title = anime.title,
            titleEn = titleEn,
            titleRu = titleRu,
            episodes = episodes,
            rating = rating,
            imageUri = null,
            currentImageFileName = imageFileName,
            orderIndex = anime.orderIndex,
            dateAdded = anime.dateAdded,
            isFavorite = anime.isFavorite,
            selectedTags = tags,
            categoryType = categoryType,
            // Тип записи «Исправление БД» не пересматривает: `categoryType` оно берёт у кандидатов
            // из поиска аниме, и без явной передачи манга стала бы аниме на первом же проходе.
            mediaType = anime.mediaType,
            comment = anime.comment,
            anilistId = newAnilistId,
            malId = newMalId,
            shikimoriId = newShikimoriId,
            anilistNotFoundAt = anime.anilistNotFoundAt,
            malNotFoundAt = anime.malNotFoundAt,
            shikimoriNotFoundAt = anime.shikimoriNotFoundAt,
            tmdbId = newTmdbId,
            kinopoiskId = newKinopoiskId,
            tmdbNotFoundAt = anime.tmdbNotFoundAt.takeIf { newTmdbId == null },
            kinopoiskNotFoundAt = anime.kinopoiskNotFoundAt.takeIf { newKinopoiskId == null },
        )
        saveAnimeUseCase(params).getOrThrow()
        return true
    }

    private fun pickBestMatch(localTitle: String, results: List<ApiSearchResult>): ApiSearchResult? =
        pickStrictMatch(localTitle, results)

    /** [ruAware] — каталог понимает русский запрос (Shikimori, AniLibria); AniList, MAL и Kitsu — нет. */
    private fun pickRelaxed(
        localTitle: String,
        results: List<ApiSearchResult>,
        ruAware: Boolean = false,
    ): ApiSearchResult? = pickRelaxedMatch(localTitle, results, ruAware)

    private fun AnimeDetails.toApiSearchResult(): ApiSearchResult = ApiSearchResult(
        title = title,
        altTitle = altTitle,
        posterUrl = posterUrl,
        episodes = episodesAired.coerceAtLeast(1),
        description = description,
        type = type,
        genres = genres,
        rating = rating,
        source = source,
        categoryType = "ANIME",
        externalId = null,
    )

    private companion object {
        private const val ITEM_DELAY_MS = 450L
    }
}
