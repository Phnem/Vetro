package com.example.myapplication.updates

import com.example.myapplication.network.retryOn429
import android.util.Log
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.models.AiringProgress
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.EpisodeCheckMedia
import com.example.myapplication.sync.TitleMatcher
import kotlinx.coroutines.delay

/**
 * Проверка новых серий по всей коллекции.
 *
 * Порядок резолва каждой записи (от дешёвого и надёжного к дорогому):
 *  1. AniList батч по anilistId (id_in, до 50 тайтлов на HTTP-запрос);
 *  2. AniList батч по malId/shikimoriId (idMal_in) — Shikimori использует MAL id;
 *  3. Точечный byId у «родного» для языка источника (RU → Shikimori, EN → Jikan);
 *  4. Поиск по названиям: сначала основное, затем второе (titleEn/titleRu),
 *     источники в порядке, зависящем от языка (RU: Shikimori → AniList; EN: AniList → MAL).
 *
 * Счётчик серий:
 *  - у онгоингов AniList отдаёт episodes=null → берём nextAiringEpisode-1 (вышедшие);
 *  - если локальный счётчик БОЛЬШЕ, чем у найденной записи — пользователь суммирует
 *    сезоны: обходим цепочку PREQUEL/SEQUEL (франшизу) и сравниваем с суммой по сезонам.
 *
 * Найденные внешние id сохраняются в БД — следующая проверка идёт по быстрой батч-ветке.
 */
class BatchEpisodeCheckUseCase(
    private val repository: AnimeRepository,
    private val localDataSource: AnimeLocalDataSource
) {

    /**
     * Полный проход: детект → авто-применение → merge с текущими плашками → персист.
     *
     * Новые серии проставляются в коллекцию САМИ (подтверждение пользователя больше
     * не спрашивается). Строки `anime_update` остаются как лента уведомлений «вышла
     * новая серия»: они живут, пока пользователь не смахнёт карточку, и показывают
     * «было → стало».
     *
     * @return только НОВЫЕ события (их нужно показать в системных уведомлениях).
     */
    suspend fun detectAndStore(language: AppLanguage): List<AnimeUpdate> {
        val animeOnly = localDataSource.getAllAnimeList()
            .filter { it.mediaType == MediaType.ANIME }
        if (animeOnly.isEmpty()) return emptyList()

        val detected = detect(animeOnly, language)
        // Снимок «выходящих сезонов» авторитетен для всего прохода: полная перезапись.
        runCatching { localDataSource.setAiringProgress(detected.airing) }
            .onFailure { Log.w(TAG, "setAiringProgress failed: ${it.message}") }

        return publishEpisodeUpdates(
            detected = detected.updates,
            getExisting = localDataSource::getUpdates,
            applyEpisodes = apply@{ update ->
                val anime = localDataSource.getAnimeById(update.animeId) ?: return@apply
                if (anime.episodes >= update.newEpisodes) return@apply
                localDataSource.updateAnime(anime.copy(episodes = update.newEpisodes))
                Log.d(TAG, "Auto-applied: \"${anime.title}\" ${anime.episodes} -> ${update.newEpisodes}")
            },
            setUpdates = localDataSource::setUpdates,
        )
    }

    // ==========================================================
    // Детект
    // ==========================================================

    private suspend fun detect(animeList: List<Anime>, language: AppLanguage): DetectResult {
        val resolutions = mutableMapOf<String, Resolution>()
        /** Кэш AniList-снимков для обхода франшизы (anilistId → media). */
        val mediaCache = mutableMapOf<Int, EpisodeCheckMedia>()
        /** Плашки прошлого прохода: нужны, чтобы закрыть прогресс завершившихся сезонов. */
        val prevAiring = runCatching { localDataSource.getAiringProgressSnapshot() }
            .getOrElse { emptyMap() }

        resolveByAnilistBatch(animeList, resolutions, mediaCache)
        resolveByMalBatch(animeList, resolutions, mediaCache)
        resolveByNativeSourceById(animeList, resolutions, language)
        resolveByTitleSearch(animeList, resolutions, language)

        // Записи, отрезолвленные поиском, имеют anilistId без снимка в кэше —
        // дотягиваем одним батчем, чтобы видеть их статус и связи франшизы.
        backfillMediaCache(resolutions.values.mapNotNull { it.anilistId }, mediaCache)

        val updates = mutableListOf<AnimeUpdate>()
        val franchiseSeeds = mutableMapOf<String, Int>() // animeId → anilistId сида
        val airingSeeds = mutableMapOf<String, Int>()    // animeId → anilistId (виден RELEASING-сезон)

        for (anime in animeList) {
            val res = resolutions[anime.id] ?: continue
            when {
                res.aired > anime.episodes -> {
                    Log.d(TAG, "Update: \"${anime.title}\" ${anime.episodes} -> ${res.aired} via ${res.source}")
                    updates += AnimeUpdate(
                        animeId = anime.id,
                        title = anime.title,
                        currentEpisodes = anime.episodes,
                        newEpisodes = res.aired,
                        source = res.source
                    )
                }
                anime.episodes > (res.total ?: res.aired) && res.anilistId != null -> {
                    // Локально серий больше, чем у одного сезона — пользователь
                    // суммирует сезоны: сравниваем с суммой по франшизе.
                    franchiseSeeds[anime.id] = res.anilistId
                }
            }
            // Онгоинг в поле зрения (сам тайтл или прямой сосед по франшизе) —
            // разворачиваем компоненту, чтобы посчитать номер сезона и прогресс.
            // Записи с плашкой прошлого прохода тоже: их сезон мог завершиться,
            // и компонента нужна, чтобы закрыть прогресс финальным счётом.
            val anilistId = res.anilistId ?: continue
            val media = mediaCache[anilistId] ?: continue
            val releasingInSight = isReleasingSeason(media.status, media.format) ||
                media.relations.any { isReleasingSeason(it.status, it.format) }
            if (releasingInSight || anime.id in prevAiring) airingSeeds[anime.id] = anilistId
        }

        // Одна волна расширения на объединение сидов: франшизным нужна сумма серий,
        // airing-сидам — полная цепочка для номера сезона.
        val expansion = expandFranchiseComponents(franchiseSeeds + airingSeeds, mediaCache)
        val components = expansion.components

        for (anime in animeList) {
            if (anime.id !in franchiseSeeds) continue
            val component = components[anime.id] ?: continue
            val total = component.sumOf { id -> mediaCache[id]?.airedEpisodes ?: 0 }
            if (total > anime.episodes) {
                Log.d(TAG, "Franchise update: \"${anime.title}\" ${anime.episodes} -> $total")
                updates += AnimeUpdate(
                    animeId = anime.id,
                    title = anime.title,
                    currentEpisodes = anime.episodes,
                    newEpisodes = total,
                    source = "AniList"
                )
            }
        }

        val now = System.currentTimeMillis()
        val airingById = components.mapNotNull { (animeId, component) ->
            // Обход франшизы не дошёл до конца (сорванный батч AniList, упор в лимит) — оставляем
            // прошлую плашку как есть. Иначе на «урезанной» компоненте не находится RELEASING-узел,
            // срабатывает ветка «сезон завершился», и корректное «S5 4 / 14» затирается
            // на «S1 11 / 11» — снимок перезаписывается целиком (см. setAiringProgress).
            if (animeId in expansion.degraded) {
                prevAiring[animeId]
            } else {
                airingProgressOf(animeId, component, mediaCache, prevAiring[animeId], now)
            }
        }.associateBy { it.animeId }.toMutableMap()

        // Фолбэк без AniList: Shikimori (и поисковые совпадения) сами знают
        // «вышло/всего» онгоинга — номер сезона неизвестен (нет графа франшизы).
        for (anime in animeList) {
            if (anime.id in airingById) continue
            val res = resolutions[anime.id] ?: continue
            val prev = prevAiring[anime.id]
            when {
                res.ongoing == true -> {
                    val aired = res.airedNow ?: continue
                    airingById[anime.id] = AiringProgress(
                        animeId = anime.id,
                        // Номер сезона этот источник не знает, но прошлый проход (через AniList) знал:
                        // без него карточка прыгала между «S5» и номером из расклада.
                        seasonNumber = prev?.seasonNumber,
                        airedEpisodes = aired,
                        totalEpisodes = res.totalPlanned,
                        updatedAt = now,
                    )
                }
                // Сезон, который мы вели, завершился (Shikimori/MAL сказали «не онгоинг») —
                // закрываем прогресс финальным счётом. AniLibria тут не участвует:
                // её статус завершённости регулярно врёт.
                res.ongoing == false && prev != null -> {
                    val finalCount = res.airedNow ?: res.aired
                    closedRow(prev, anime.id, prev.seasonNumber, finalCount, now)
                        ?.let { airingById[anime.id] = it }
                }
            }
        }

        fillAiringFromAnilibria(animeList, resolutions, airingById, prevAiring, now)

        // Тайтл в этот проход не разрешился вовсе (AniList ответил 429, поиск не нашёл) — это не
        // «сезон закончился». Снимок перезаписывается целиком, и без переноса тайтл выпадал из
        // «выходящих сейчас» до следующей удачной проверки: карточки на главной переставлялись.
        for (anime in animeList) {
            if (anime.id in airingById || resolutions[anime.id] != null) continue
            val prev = prevAiring[anime.id] ?: continue
            val closed = prev.totalEpisodes != null && prev.airedEpisodes >= prev.totalEpisodes
            if (!closed || now - prev.updatedAt <= FINISHED_ROW_TTL_MS) airingById[anime.id] = prev
        }

        return DetectResult(updates, airingById.values.toList())
    }

    /**
     * AniLibria-дозаполнение (бюджет [ANILIBRIA_MAX_LOOKUPS] запросов на проход):
     *  - записи совсем без резолва: их онгоинг-статус и «озвучено/заявлено» берём у AniLibria;
     *  - найденные онгоинги без анонса total: дотягиваем заявленное число серий
     *    (только когда номер сезона неизвестен — франшизным AniList-веткам чужой
     *    total подмешивать нельзя: релиз AniLibria может оказаться другим сезоном).
     */
    private suspend fun fillAiringFromAnilibria(
        animeList: List<Anime>,
        resolutions: Map<String, Resolution>,
        airingById: MutableMap<String, AiringProgress>,
        prevAiring: Map<String, AiringProgress>,
        now: Long,
    ) {
        var lookups = 0
        for (anime in animeList) {
            if (lookups >= ANILIBRIA_MAX_LOOKUPS) break
            val existing = airingById[anime.id]
            val unresolved = existing == null && resolutions[anime.id] == null
            val needsTotal = existing != null && existing.totalEpisodes == null && existing.seasonNumber == null
            if (!unresolved && !needsTotal) continue

            val query = anime.titleRu?.takeIf { it.isNotBlank() } ?: anime.title
            lookups++
            val results = retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.searchAnimeAnilibriaOnly(query, 5) }.getOrNull().orEmpty()
            val match = pickMatchedResult(anime, results)?.result
            if (match == null) {
                delay(FALLBACK_ITEM_DELAY_MS)
                continue
            }
            when {
                needsTotal -> match.totalEpisodes?.let { total ->
                    airingById[anime.id] = existing!!.copy(totalEpisodes = total)
                    Log.d(TAG, "AniLibria total for \"${anime.title}\": $total")
                }
                match.isOngoing == true -> {
                    val aired = match.airedEpisodes
                    if (aired != null && aired > 0) {
                        airingById[anime.id] = AiringProgress(
                            animeId = anime.id,
                            seasonNumber = prevAiring[anime.id]?.seasonNumber,
                            airedEpisodes = aired,
                            totalEpisodes = match.totalEpisodes,
                            updatedAt = now,
                        )
                        Log.d(TAG, "AniLibria airing for \"${anime.title}\": $aired/${match.totalEpisodes}")
                    }
                }
            }
            delay(FALLBACK_ITEM_DELAY_MS)
        }
    }

    /** Батч-дозагрузка снимков для anilistId, которых ещё нет в кэше. */
    private suspend fun backfillMediaCache(ids: List<Int>, mediaCache: MutableMap<Int, EpisodeCheckMedia>) {
        val missing = ids.toSet() - mediaCache.keys
        if (missing.isEmpty()) return
        repository.episodeCheckByAnilistIds(missing.toList())
            .onSuccess { fetched -> fetched.forEach { mediaCache[it.anilistId] = it } }
            .onFailure { Log.w(TAG, "Media cache backfill failed: ${it.message}") }
    }

    // ==========================================================
    // Этап 1–2: AniList батчи (id_in / idMal_in)
    // ==========================================================

    private suspend fun resolveByAnilistBatch(
        animeList: List<Anime>,
        resolutions: MutableMap<String, Resolution>,
        mediaCache: MutableMap<Int, EpisodeCheckMedia>
    ) {
        val withId = animeList.filter { it.anilistId != null }
        if (withId.isEmpty()) return
        val fetched = repository.episodeCheckByAnilistIds(withId.mapNotNull { it.anilistId })
            .getOrElse { e ->
                Log.w(TAG, "AniList id batch failed: ${e.message}")
                return
            }
        fetched.forEach { mediaCache[it.anilistId] = it }
        for (anime in withId) {
            val media = mediaCache[anime.anilistId] ?: continue
            if (!trustIdMatch(anime, media)) {
                Log.d(TAG, "byId distrusted: \"${anime.title}\" vs \"${media.titleRomaji}\"")
                continue
            }
            resolutions[anime.id] = media.toResolution()
            backfillIds(anime, media)
        }
    }

    private suspend fun resolveByMalBatch(
        animeList: List<Anime>,
        resolutions: MutableMap<String, Resolution>,
        mediaCache: MutableMap<Int, EpisodeCheckMedia>
    ) {
        // Shikimori id ≈ MAL id: у Shikimori аниме нумеруются идентификаторами MAL.
        val pending = animeList.filter { it.id !in resolutions && effectiveMalId(it) != null }
        if (pending.isEmpty()) return
        val fetched = repository.episodeCheckByMalIds(pending.mapNotNull(::effectiveMalId))
            .getOrElse { e ->
                Log.w(TAG, "AniList idMal batch failed: ${e.message}")
                return
            }
        val byMal = fetched.filter { it.malId != null }.associateBy { it.malId!! }
        fetched.forEach { mediaCache[it.anilistId] = it }
        for (anime in pending) {
            val media = byMal[effectiveMalId(anime)] ?: continue
            if (!trustIdMatch(anime, media)) continue
            resolutions[anime.id] = media.toResolution()
            backfillIds(anime, media)
        }
    }

    private fun effectiveMalId(anime: Anime): Int? = anime.malId ?: anime.shikimoriId

    /** Дозаписываем найденные внешние id — следующий прогон пойдёт быстрой веткой. */
    private suspend fun backfillIds(anime: Anime, media: EpisodeCheckMedia) {
        runCatching {
            if (anime.anilistId == null) localDataSource.setAnilistId(anime.id, media.anilistId)
            val mal = media.malId
            if (anime.malId == null && mal != null) localDataSource.setMalId(anime.id, mal)
        }
    }

    // ==========================================================
    // Этап 3: точечный byId у «родного» источника языка
    // ==========================================================

    private suspend fun resolveByNativeSourceById(
        animeList: List<Anime>,
        resolutions: MutableMap<String, Resolution>,
        language: AppLanguage
    ) {
        val pending = animeList.filter { it.id !in resolutions }
        for (anime in pending) {
            when (language) {
                AppLanguage.RU -> {
                    val id = anime.shikimoriId ?: anime.malId ?: continue
                    val remote = retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.shikimoriById(id, AppLanguage.RU) }
                        .getOrNull() ?: continue
                    if (remote.episodes <= 0) continue
                    if (!isTitleMatch(anime, remote)) continue
                    resolutions[anime.id] = Resolution(
                        source = "Shikimori",
                        aired = remote.episodes,
                        total = null,
                        anilistId = anime.anilistId,
                        ongoing = remote.isOngoing,
                        airedNow = remote.airedEpisodes,
                        totalPlanned = remote.totalEpisodes,
                    )
                }
                AppLanguage.EN -> {
                    val id = anime.malId ?: continue
                    val remote = retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.malById(id, AppLanguage.EN) }
                        .getOrNull() ?: continue
                    if (remote.episodes <= 0) continue
                    if (!isTitleMatch(anime, remote)) continue
                    resolutions[anime.id] = Resolution(
                        source = "MAL",
                        aired = remote.episodes,
                        total = null,
                        anilistId = anime.anilistId,
                        ongoing = remote.isOngoing,
                        totalPlanned = remote.totalEpisodes,
                    )
                }
            }
            delay(FALLBACK_ITEM_DELAY_MS)
        }
    }

    // ==========================================================
    // Этап 4: поиск по названиям (основное → второе), источники по языку
    // ==========================================================

    private suspend fun resolveByTitleSearch(
        animeList: List<Anime>,
        resolutions: MutableMap<String, Resolution>,
        language: AppLanguage
    ) {
        val pending = animeList.filter { it.id !in resolutions }
        for (anime in pending) {
            // Основное название первым, затем альтернативы на других языках.
            val queries = listOfNotNull(
                anime.title.takeIf { it.isNotBlank() },
                anime.titleEn?.takeIf { it.isNotBlank() },
                anime.titleRu?.takeIf { it.isNotBlank() }
            ).distinctBy { it.lowercase() }
            if (queries.isEmpty()) continue

            val sources = when (language) {
                AppLanguage.RU -> listOf(SearchSource.SHIKIMORI, SearchSource.ANILIST)
                AppLanguage.EN -> listOf(SearchSource.ANILIST, SearchSource.MAL)
            }

            outer@ for (source in sources) {
                if (!shouldRetryNotFound(notFoundAt(anime, source))) continue
                var sawTransientFailure = false
                var searched = false
                for (query in queries) {
                    // Кириллический запрос имеет смысл только на Shikimori.
                    if (source != SearchSource.SHIKIMORI && !query.hasLatin()) continue
                    searched = true
                    val result = retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { search(source, query) }
                    if (result.isFailure) {
                        if (isTransientFailure(result.exceptionOrNull())) sawTransientFailure = true
                        continue
                    }
                    val found = pickMatchedResult(anime, result.getOrNull())
                        ?.takeIf { it.result.episodes > 0 && it.score >= DIRECT_NOTIFY_FROM_SEARCH_SCORE }
                        ?: continue

                    persistDiscoveredId(anime, source, found.result)
                    resolutions[anime.id] = Resolution(
                        source = source.label,
                        aired = found.result.episodes,
                        total = null,
                        anilistId = if (source == SearchSource.ANILIST) {
                            found.result.externalId?.toIntOrNull()
                        } else anime.anilistId,
                        ongoing = found.result.isOngoing,
                        airedNow = found.result.airedEpisodes,
                        totalPlanned = found.result.totalEpisodes,
                    )
                    break@outer
                }
                // Помечаем not-found только при завершённом поиске без сетевых сбоев.
                if (searched && !sawTransientFailure) markNotFound(anime, source)
                delay(FALLBACK_ITEM_DELAY_MS)
            }
        }
    }

    private enum class SearchSource(val label: String) {
        ANILIST("AniList"),
        SHIKIMORI("Shikimori"),
        MAL("MAL")
    }

    private suspend fun search(source: SearchSource, query: String): Result<List<ApiSearchResult>> =
        when (source) {
            SearchSource.ANILIST -> repository.searchAnimeAniListOnly(query, AppLanguage.EN, limit = 5)
            SearchSource.SHIKIMORI -> repository.searchAnimeShikimoriOnly(query, AppLanguage.RU, allowZeroEpisodes = true)
            SearchSource.MAL -> repository.searchAnimeMalOnly(query, AppLanguage.EN, limit = 5)
        }

    private fun notFoundAt(anime: Anime, source: SearchSource): Long? = when (source) {
        SearchSource.ANILIST -> anime.anilistNotFoundAt
        SearchSource.SHIKIMORI -> anime.shikimoriNotFoundAt
        SearchSource.MAL -> anime.malNotFoundAt
    }

    private suspend fun markNotFound(anime: Anime, source: SearchSource) {
        val now = System.currentTimeMillis()
        runCatching {
            when (source) {
                SearchSource.ANILIST -> localDataSource.markAnilistNotFound(anime.id, now)
                SearchSource.SHIKIMORI -> localDataSource.markShikimoriNotFound(anime.id, now)
                SearchSource.MAL -> localDataSource.markMalNotFound(anime.id, now)
            }
        }
    }

    private suspend fun persistDiscoveredId(anime: Anime, source: SearchSource, result: ApiSearchResult) {
        val extId = result.externalId?.toIntOrNull() ?: return
        runCatching {
            when (source) {
                SearchSource.ANILIST -> if (anime.anilistId == null) localDataSource.setAnilistId(anime.id, extId)
                SearchSource.SHIKIMORI -> {
                    if (anime.shikimoriId == null) localDataSource.setShikimoriId(anime.id, extId)
                    val mal = result.malId
                    if (anime.malId == null && mal != null) localDataSource.setMalId(anime.id, mal)
                }
                SearchSource.MAL -> if (anime.malId == null) localDataSource.setMalId(anime.id, extId)
            }
        }
    }

    // ==========================================================
    // Франшиза: сумма вышедших серий по цепочке PREQUEL/SEQUEL
    // ==========================================================

    /** Компоненты франшиз + записи, чей обход оборвался и чьему результату нельзя доверять. */
    private class FranchiseExpansion(
        val components: Map<String, Set<Int>>,
        val degraded: Set<String>,
    )

    private suspend fun expandFranchiseComponents(
        seeds: Map<String, Int>,
        mediaCache: MutableMap<Int, EpisodeCheckMedia>
    ): FranchiseExpansion {
        if (seeds.isEmpty()) return FranchiseExpansion(emptyMap(), emptySet())
        // Компонента связности каждой записи; фронтиры всех сидов расширяем
        // синхронно — недостающие узлы дотягиваем ОДНИМ батчем на уровень.
        val components = seeds.mapValues { (_, seedId) -> linkedSetOf(seedId) }
        var frontiers: Map<String, Set<Int>> = seeds.mapValues { (_, seedId) -> setOf(seedId) }

        val degraded = mutableSetOf<String>()
        var depth = 0
        while (frontiers.isNotEmpty() && depth < FRANCHISE_MAX_DEPTH) {
            depth++
            val needed = frontiers.values.flatten().toSet() - mediaCache.keys
            if (needed.isNotEmpty()) {
                val fetched = repository.episodeCheckByAnilistIds(needed.toList()).getOrElse { e ->
                    // Сбой батча = все ещё расширявшиеся записи получили неполную франшизу.
                    Log.w(TAG, "Franchise batch failed: ${e.message}")
                    degraded += frontiers.keys
                    null
                }
                if (fetched == null) break
                fetched.forEach { mediaCache[it.anilistId] = it }
            }
            val nextFrontiers = mutableMapOf<String, Set<Int>>()
            for ((animeId, frontier) in frontiers) {
                val component = components.getValue(animeId)
                val next = mutableSetOf<Int>()
                for (nodeId in frontier) {
                    val media = mediaCache[nodeId] ?: continue
                    for (rel in media.relations) {
                        // Идём СКВОЗЬ фильмы/OVA/спешлы: цепочки вида «S2 → фильм → S3»
                        // встречаются постоянно, и обход, останавливающийся на фильме, терял
                        // все последующие сезоны. По формату отсеиваем потребители компоненты
                        // (сумма серий, поиск RELEASING/FINISHED) — не сам обход.
                        if (rel.anilistId in component) continue
                        if (component.size >= FRANCHISE_MAX_NODES) {
                            degraded += animeId
                            break
                        }
                        component += rel.anilistId
                        next += rel.anilistId
                    }
                }
                if (next.isNotEmpty()) nextFrontiers[animeId] = next
            }
            frontiers = nextFrontiers
        }
        // Не доразвернули за отведённые уровни — остаток тоже недостоверен.
        degraded += frontiers.keys
        return FranchiseExpansion(components, degraded)
    }

    // ==========================================================
    // Выходящий сезон: RELEASING-узел компоненты + номер сезона
    // ==========================================================

    private fun isReleasingSeason(status: String?, format: String?): Boolean =
        status == "RELEASING" && isSeasonFormat(format)

    /**
     * Прогресс выходящего сезона внутри компоненты франшизы: находим RELEASING-узел
     * (при нескольких берём самый поздний по цепочке), номер сезона — длина
     * PREQUEL-цепочки от него до корня.
     *
     * Если RELEASING-узла больше нет, а плашка с прошлого прохода была — сезон
     * завершился: закрываем прогресс финальным счётом (FINISHED-узел, aired == total).
     */
    private fun airingProgressOf(
        animeId: String,
        component: Set<Int>,
        mediaCache: Map<Int, EpisodeCheckMedia>,
        prev: AiringProgress?,
        now: Long,
    ): AiringProgress? {
        val releasing = component
            .mapNotNull { mediaCache[it] }
            .filter { isReleasingSeason(it.status, it.format) }
        val node = releasing.maxByOrNull { seasonNumberOf(it, mediaCache) }
        if (node != null) {
            val aired = node.airedEpisodes
            if (aired <= 0 && node.totalEpisodes == null) return null
            return AiringProgress(
                animeId = animeId,
                seasonNumber = seasonNumberOf(node, mediaCache),
                airedEpisodes = aired.coerceAtLeast(0),
                totalEpisodes = node.totalEpisodes?.takeIf { it > 0 },
                updatedAt = now,
            )
        }

        if (prev == null) return null
        val finished = component
            .mapNotNull { mediaCache[it] }
            .filter { it.status == "FINISHED" && isSeasonFormat(it.format) }
            .maxByOrNull { seasonNumberOf(it, mediaCache) }
            ?: return null
        val finalCount = finished.totalEpisodes ?: finished.airedEpisodes
        return closedRow(prev, animeId, seasonNumberOf(finished, mediaCache), finalCount, now)
    }

    /**
     * Закрытая плашка «сезон вышел полностью»: aired == total, бар 100%.
     * Живёт [FINISHED_ROW_TTL_MS] от момента закрытия (updatedAt наследуется,
     * а не обновляется каждым проходом), потом строка не переписывается и исчезает.
     */
    private fun closedRow(
        prev: AiringProgress,
        animeId: String,
        seasonNumber: Int?,
        finalCount: Int,
        now: Long,
    ): AiringProgress? {
        if (finalCount <= 0) return null
        val wasClosed = prev.totalEpisodes != null && prev.airedEpisodes >= prev.totalEpisodes
        val closedAt = if (wasClosed) prev.updatedAt else now
        if (now - closedAt > FINISHED_ROW_TTL_MS) return null
        return AiringProgress(
            animeId = animeId,
            seasonNumber = seasonNumber,
            airedEpisodes = finalCount,
            totalEpisodes = finalCount,
            updatedAt = closedAt,
        )
    }

    /**
     * Номер сезона — официальный, а не длина цепочки узлов (см. [SeasonNumbering]):
     *  • номер в названии («Season 4», «4th Season») берётся как есть — у самого узла или у
     *    ближайшего приквела, от которого досчитываем;
     *  • иначе 1 + число предшествующих сезонных узлов, где вторая половина сезона («Part 2»,
     *    «2nd Cour», «2nd STAGE») новым сезоном не считается.
     *
     * Идём по любому приквелу, а увеличиваем счётчик только на сезонных форматах: раньше обход
     * обрывался на первом же фильме/спешле, и у франшизы с фильмом в середине номер сезона
     * схлопывался к 1. Приквел вне кэша засчитывается (нижняя оценка), дальше цепочка не идёт.
     */
    private fun seasonNumberOf(node: EpisodeCheckMedia, mediaCache: Map<Int, EpisodeCheckMedia>): Int {
        SeasonNumbering.explicitSeason(node.titleEnglish, node.titleRomaji)?.let { return it }
        var seasonsBefore = 0
        var current: EpisodeCheckMedia? = node
        val visited = mutableSetOf(node.anilistId)
        while (current != null) {
            val prequel = current.relations.firstOrNull { it.relationType == "PREQUEL" } ?: break
            if (!visited.add(prequel.anilistId)) break
            // Английское название надёжнее: в ромадзи «Part 5» у JoJo — часть манги, а не половина сезона.
            val continuation = SeasonNumbering.isContinuation(current.titleEnglish ?: current.titleRomaji)
            if (isSeasonFormat(prequel.format) && !continuation) seasonsBefore++
            val prequelNode = mediaCache[prequel.anilistId]
            prequelNode?.let { SeasonNumbering.explicitSeason(it.titleEnglish, it.titleRomaji) }
                ?.let { return it + seasonsBefore }
            current = prequelNode
        }
        return 1 + seasonsBefore
    }

    /** «Сезонные» форматы: полнометражки/спешлы в сумму серий не входят. */
    private fun isSeasonFormat(format: String?): Boolean =
        format == "TV" || format == "TV_SHORT" || format == "ONA"

    // ==========================================================
    // Сопоставление названий
    // ==========================================================

    private fun localTitles(anime: Anime): List<String> =
        listOfNotNull(anime.title, anime.titleEn, anime.titleRu).filter { it.isNotBlank() }

    /**
     * Сохранённому id верим почти всегда (его ставили наши же флоу сопоставления).
     * Отклоняем только явный рассинхрон, и только когда названия вообще сравнимы:
     * кириллическое «Атака титанов» против romaji честно сравнить нельзя.
     */
    private fun trustIdMatch(anime: Anime, media: EpisodeCheckMedia): Boolean {
        val remotes = listOfNotNull(media.titleRomaji, media.titleEnglish)
        if (remotes.isEmpty()) return true
        val comparable = localTitles(anime).filter { it.hasLatin() }
        if (comparable.isEmpty()) return true
        val score = comparable.maxOf { TitleMatcher.bestScore(it, remotes) }
        return score >= ID_TRUST_SCORE
    }

    private fun isTitleMatch(anime: Anime, remote: ApiSearchResult): Boolean {
        val candidates = listOfNotNull(remote.title, remote.altTitle)
        return localTitles(anime).any { local ->
            TitleMatcher.bestScore(local, candidates) >= STRICT_MATCH_SCORE
        }
    }

    private fun pickMatchedResult(anime: Anime, results: List<ApiSearchResult>?): MatchedResult? {
        val best = results.orEmpty()
            .map { candidate ->
                val remotes = listOfNotNull(candidate.title, candidate.altTitle)
                val score = localTitles(anime).maxOfOrNull { TitleMatcher.bestScore(it, remotes) } ?: 0.0
                MatchedResult(candidate, score)
            }
            .maxByOrNull { it.score } ?: return null
        Log.d(TAG, "Search best: local=\"${anime.title}\" remote=\"${best.result.title}\" score=${best.score}")
        return best.takeIf { it.score >= STRICT_MATCH_SCORE }
    }

    private fun String.hasLatin(): Boolean = any { it in 'a'..'z' || it in 'A'..'Z' }

    // ==========================================================
    // Сетевые мелочи
    // ==========================================================


    private fun shouldRetryNotFound(notFoundAt: Long?): Boolean {
        val ts = notFoundAt ?: return true
        return (System.currentTimeMillis() - ts) >= NOT_FOUND_TTL_MS
    }

    private fun isTransientFailure(throwable: Throwable?): Boolean {
        val msg = throwable?.message?.lowercase().orEmpty()
        return msg.contains("429")
            || msg.contains("too many requests")
            || msg.contains("timeout")
            || msg.contains("tempor")
            || msg.contains("unavailable")
            || msg.contains("network")
            || msg.contains("ioexception")
    }

    private fun EpisodeCheckMedia.toResolution() = Resolution(
        source = "AniList",
        aired = airedEpisodes,
        total = totalEpisodes,
        anilistId = anilistId
    )

    /** Итог полного детекта: предложения обновлений + снимок выходящих сезонов. */
    private data class DetectResult(
        val updates: List<AnimeUpdate>,
        val airing: List<AiringProgress>,
    )

    /** Итог резолва записи: сколько серий вышло и где искать франшизу. */
    private data class Resolution(
        val source: String,
        val aired: Int,
        val total: Int?,
        val anilistId: Int?,
        /** Онгоинг по данным источника (Shikimori/AniLibria/MAL); null = статус неизвестен. */
        val ongoing: Boolean? = null,
        /** Вышло серий сейчас — для прогресс-бара, когда AniList-путь недоступен. */
        val airedNow: Int? = null,
        /** Заявлено серий всего (аналогично). */
        val totalPlanned: Int? = null,
    )

    private data class MatchedResult(
        val result: ApiSearchResult,
        val score: Double
    )

    private companion object {
        private const val TAG = "BatchEpisodeCheck"
        private const val FALLBACK_ITEM_DELAY_MS = 350L
        private const val STRICT_MATCH_SCORE = 0.91
        private const val DIRECT_NOTIFY_FROM_SEARCH_SCORE = 0.96
        private const val ID_TRUST_SCORE = 0.30
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 800L
        private const val NOT_FOUND_TTL_MS = 14L * 24L * 60L * 60L * 1000L
        // Обход идёт сквозь фильмы/OVA, поэтому и уровней, и узлов на франшизу нужно больше:
        // промежуточные не-сезонные узлы теперь тоже съедают бюджет.
        private const val FRANCHISE_MAX_DEPTH = 9
        private const val FRANCHISE_MAX_NODES = 48
        /** Бюджет AniLibria-запросов на один проход проверки. */
        private const val ANILIBRIA_MAX_LOOKUPS = 10
        /** Сколько держим закрытую плашку «сезон вышел полностью» после завершения. */
        private const val FINISHED_ROW_TTL_MS = 14L * 24L * 60L * 60L * 1000L
        /** Потолок ленты «вышли новые серии»: старые события вытесняются свежими. */
    }
}
