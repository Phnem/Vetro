package com.example.myapplication.domain.seasons

import com.example.myapplication.network.seasonNeighbors
import com.example.myapplication.network.orderByRelations
import com.example.myapplication.network.retryOn429
import android.util.Log
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.EpisodeCheckMedia
import com.example.myapplication.sync.TitleMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Резолвер «серии по сезонам»: для каждого тайтла строит упорядоченную цепочку сезонов
 * франшизы и гарантированно достаёт число серий каждого сезона.
 *
 * Каскад (от дешёвого к дорогому, язык не важен):
 *  1. Сид-узел AniList: по anilistId → по malId/shikimoriId (idMal_in) → поиском по названиям
 *     (AniList → Shikimori → MAL, сопоставление TitleMatcher ≥ 0.91). Найденные id пишутся в БД.
 *  2. Цепочка: BFS по PREQUEL/SEQUEL (батчи episodeCheckByAnilistIds) СКВОЗЬ все форматы, порядок
 *     восстанавливается по рёбрам, и только потом остаются сезонные форматы (TV/TV_SHORT/ONA).
 *     Идти сквозь фильмы/OVA обязательно: цепочки вида «S2 → фильм → S3» встречаются постоянно,
 *     и обход, останавливающийся на фильме, теряет все последующие сезоны.
 *  3. Число серий узла: завершённый — AniList totalEpisodes, онгоинг — только вышедшие
 *     (nextAiringEpisode−1), чтобы UI не предлагал невышедшие серии; дырки дозаполняются
 *     точечными Shikimori byId → MAL byId (Shikimori id ≡ MAL id).
 *  4. Совсем без AniList-узла (нет ни id, ни совпадений) — одиночный «сезон 1» из данных
 *     Shikimori/MAL самой записи, чтобы хоть что-то показать.
 *
 * Неполные записи (сезон без счётчика или онгоинг) хранятся с коротким TTL — следующий фоновый
 * проход дорезолвит. Вызывается из AnimeUpdateWorker после проверки новых серий.
 */
class SeasonEpisodesResolver(
    private val repository: AnimeRepository,
    private val localDataSource: AnimeLocalDataSource,
    private val store: SeasonEpisodesStore,
) {

    /**
     * Фоновый проход: перерезолвить протухшие записи коллекции (бюджет [budget] тайтлов
     * за проход, чтобы не душить API). Заодно чистит записи удалённых тайтлов.
     */
    /** Когда резолв тайтла последний раз сорвался (в пределах процесса) — см. refreshStale. */
    private val recentFailures = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun refreshStale(budget: Int = DEFAULT_BUDGET) {
        store.ensureLoaded()
        val animeOnly = localDataSource.getAllAnimeList().filter { it.mediaType == MediaType.ANIME }
        runCatching { store.retainOnly(animeOnly.map { it.id }.toSet()) }

        // Бюджет — это ПОПЫТКИ, а не успехи. Раньше неудачный резолв бюджет не тратил: тайтл,
        // которого нет в каталогах, стоил полный каскад запросов на каждом прогоне, и прогон мог
        // пройти по сети всю коллекцию. Недавно сорвавшиеся тайтлы пропускаются, чтобы бюджет
        // доставался остальным.
        val now = System.currentTimeMillis()
        var attempts = 0
        for (anime in animeOnly) {
            if (attempts >= budget) break
            if (store.isFresh(anime.id)) continue
            val failedAt = recentFailures[anime.id]
            if (failedAt != null && now - failedAt < FAILURE_COOLDOWN_MILLIS) continue
            attempts++
            val entry = runCatching { resolve(anime) }
                .onFailure { Log.w(TAG, "resolve failed for \"${anime.title}\": ${it.message}") }
                .getOrNull()
            if (entry == null) {
                recentFailures[anime.id] = now
            } else {
                recentFailures.remove(anime.id)
                store.put(entry)
                // Провенанс строки виден в логе: источник и наличие сезонного названия решают,
                // сможет ли SourceEngine сузить поиск до сезона.
                val rows = entry.seasons.map {
                    val titled = if (it.title.isNullOrBlank()) "untitled" else "titled"
                    "S${it.seasonNumber}=${it.episodes}(${it.source},$titled)"
                }
                Log.i(TAG, "Seasons for \"${anime.title}\": $rows complete=${entry.complete}")
            }
            delay(ITEM_DELAY_MS)
        }
    }

    /**
     * Точечный резолв одного тайтла вне очереди (открытие Details): если запись протухла
     * или отсутствует — резолвим сразу, UI обновится реактивно через стор.
     */
    suspend fun ensureResolved(animeId: String) = withContext(Dispatchers.IO) {
        store.ensureLoaded()
        if (store.isFresh(animeId)) return@withContext
        val anime = localDataSource.getAnimeById(animeId) ?: return@withContext
        if (anime.mediaType != MediaType.ANIME) return@withContext
        runCatching { resolve(anime) }
            .onFailure { Log.w(TAG, "on-demand resolve failed for \"${anime.title}\": ${it.message}") }
            .getOrNull()
            ?.let { store.put(it) }
    }

    /**
     * Полный резолв одного тайтла. null = сид не найден и фолбэки пусты (перепробуем позже).
     * [includeStoredStreaming] выключается только для ручного full refresh, чтобы старые найденные
     * сезоны не примешались обратно до записи свежего результата.
     */
    suspend fun resolve(
        anime: Anime,
        includeStoredStreaming: Boolean = true,
    ): SeasonEpisodesEntry? {
        val self = findSeedNode(anime)
            ?: return fallbackSingleSeason(anime)

        val graph = gatherFranchise(self)
        // Порядок строим по ПОЛНОМУ графу (фильмы/OVA внутри цепочки — тоже рёбра), а разбираем
        // уже потом: иначе «S2 → фильм → S3» разваливается на два куска.
        val chain = orderByRelations(graph.nodes)
        val orderedSeasons = chain.filter { it.isSeasonFormat() }.ifEmpty { listOf(self) }
        // Спецвыпуски идут ПОСЛЕ сезонов и своей нумерацией: вклинить OVA между сезонами значит
        // сдвинуть номера всех последующих, а по ним источники просмотра ищут релиз.
        val orderedSpecials = chain.filter { it.isSpecialFormat() && it !in orderedSeasons }
        val ordered = orderedSeasons + orderedSpecials

        val seasons = ArrayList<SeasonInfo>(ordered.size)
        var allResolved = true
        for ((index, node) in ordered.withIndex()) {
            val special = index >= orderedSeasons.size
            val ongoing = node.status == "RELEASING"
            val declared = node.totalEpisodes
            val aired = node.airedEpisodes.takeIf { it > 0 }
            // Онгоинг: доступны только вышедшие серии, сколько бы ни было анонсировано.
            var episodes = if (ongoing) aired ?: declared else declared ?: aired
            var total = declared
            var source = "AniList"

            // Дырка у AniList: нет числа серий вовсе, либо сезон идёт без объявленного расписания
            // (nextAiringEpisode пуст → airedEpisodes вырождается в заявленное число, и мы бы
            // показали невышедшие серии). И то, и другое лечится точечным Shikimori/MAL.
            val airedUnknown = ongoing && aired != null && aired == declared
            if ((episodes == null || airedUnknown) && node.malId != null) {
                fillFromShikimoriOrMal(node.malId!!)?.let { filled ->
                    val filledEpisodes =
                        if (ongoing) filled.aired ?: filled.total else filled.total ?: filled.aired
                    if (filledEpisodes != null) {
                        episodes = filledEpisodes
                        total = filled.total ?: total
                        source = filled.source
                    }
                }
            }

            if (episodes == null) {
                // Незакрытый счётчик спецвыпуска записи не портит: `complete` описывает цепочку
                // СЕЗОНОВ, а анонсированная-но-невышедшая OVA есть у половины франшиз. Иначе одна
                // такая строка навсегда сажала бы тайтл на суточный TTL и съедала слот прохода.
                if (!special) allResolved = false
                continue
            }
            seasons += SeasonInfo(
                seasonNumber = index + 1,
                episodes = episodes!!,
                totalEpisodes = total,
                ongoing = ongoing,
                anilistId = node.anilistId,
                malId = node.malId,
                source = source,
                title = node.titleEnglish ?: node.titleRomaji,
                kind = if (special) SeasonKind.Special else SeasonKind.Season,
                format = node.format.takeIf { special },
            )
        }
        if (seasons.isEmpty()) return fallbackSingleSeason(anime)

        // Пропуск в середине цепочки съедает нумерацию — перенумеруем по порядку, но раздельно:
        // сезоны продолжают сквозной ряд, спецвыпуски встают за ними и на него не влияют.
        val renumbered = renumberChain(seasons)
        val withStreaming = if (includeStoredStreaming) {
            mergeStreamingSeasons(anime.id, renumbered)
        } else {
            renumbered
        }
        return SeasonEpisodesEntry(
            animeId = anime.id,
            seasons = withStreaming,
            // graph.complete обязателен: без него ОДИН сорванный батч AniList давал «полную»
            // запись из одного сезона, и она залипала в кэше на месяц (см. gatherFranchise).
            complete = allResolved && graph.complete && withStreaming.none { it.ongoing },
            resolvedAt = System.currentTimeMillis(),
            schema = SeasonEpisodesEntry.CURRENT_SCHEMA,
        )
    }

    /**
     * Вернуть в расклад сезоны, добытые кнопкой «Найти ещё» у источников просмотра.
     *
     * Резолвер знает только граф франшизы AniList и, перерезолвив тайтл, собрал бы список заново —
     * без них. Пользователь при этом нажал кнопку один раз и вправе рассчитывать, что найденное
     * никуда не денется. Опознаём их по [SeasonInfo.source]: каталожные источники пишут туда
     * «AniList»/«Shikimori»/«MAL», источники просмотра — своё имя (см.
     * [StreamingSeasonDiscovery.STREAMING_SOURCES]).
     */
    private fun mergeStreamingSeasons(animeId: String, resolved: List<SeasonInfo>): List<SeasonInfo> {
        val discovered = store.entryFor(animeId)?.seasons.orEmpty()
            .filter { it.source in StreamingSeasonDiscovery.STREAMING_SOURCES && !it.isSpecial }
        if (discovered.isEmpty()) return resolved

        // Источники просмотра нумеруют только сезоны; спецвыпуски в их шкале не участвуют и
        // сливать их по номеру нельзя — номер у них свой, продолжающий сезонный ряд.
        val specials = resolved.filter { it.isSpecial }
        val byNumber = resolved.filterNot { it.isSpecial }
            .associateByTo(LinkedHashMap()) { it.seasonNumber }
        for (season in discovered) {
            val existing = byNumber[season.seasonNumber]
            byNumber[season.seasonNumber] = when {
                // Сезон, которого каталог не знает вовсе, — ровно то, ради чего кнопка и есть.
                existing == null -> season
                // Каталожная запись богаче (id, ongoing, заявленный итог), но серий у источника
                // просмотра может быть больше — берём максимум, остальное каталожное.
                season.episodes > existing.episodes ->
                    existing.copy(episodes = season.episodes, source = season.source)
                else -> existing
            }
        }
        // Номера сезонов НЕ пересчитываем: строка от источника просмотра несёт его собственный
        // номер (у него бывают дыры), и по нему же мы потом просим у него серии. Спецвыпуски
        // живут в своём диапазоне и столкнуться с сезонами не могут.
        return byNumber.values.sortedBy { it.seasonNumber } + specials
    }

    /**
     * Нумерация цепочки: сезоны получают 1..N, спецвыпуски — свой диапазон от
     * [SPECIAL_SEASON_BASE].
     *
     * Номера должны быть уникальны — это ключ строки и в UI (раскрытие карточки, обложки), и в
     * ключах серий [com.example.myapplication.ui.details.EpisodeKey]; совпавший номер у сезона и
     * OVA склеил бы их прогресс в один, а LazyColumn на дубликате ключа падает.
     */
    private fun renumberChain(rows: List<SeasonInfo>): List<SeasonInfo> {
        val seasons = rows.filterNot { it.isSpecial }.mapIndexed { i, row ->
            row.copy(seasonNumber = i + 1)
        }
        val specials = rows.filter { it.isSpecial }.mapIndexed { i, row ->
            row.copy(seasonNumber = SPECIAL_SEASON_BASE + i)
        }
        return seasons + specials
    }

    // ==========================================================
    // Сид: AniList-узел записи
    // ==========================================================

    private suspend fun findSeedNode(anime: Anime): EpisodeCheckMedia? {
        anime.anilistId?.let { id ->
            retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.episodeCheckByAnilistIds(listOf(id)) }
                .getOrNull()?.firstOrNull()?.let { return it }
        }
        (anime.malId ?: anime.shikimoriId)?.let { mal ->
            retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.episodeCheckByMalIds(listOf(mal)) }
                .getOrNull()?.firstOrNull()?.let {
                    persistIds(anime, it)
                    return it
                }
        }
        return seedByTitleSearch(anime)
    }

    /** Поиск сида по названиям: AniList → Shikimori → MAL; совпадение ≥ [MATCH_SCORE]. */
    private suspend fun seedByTitleSearch(anime: Anime): EpisodeCheckMedia? {
        val queries = listOfNotNull(
            anime.title.takeIf { it.isNotBlank() },
            anime.titleEn?.takeIf { it.isNotBlank() },
            anime.titleRu?.takeIf { it.isNotBlank() },
        ).distinctBy { it.lowercase() }
        if (queries.isEmpty()) return null

        // AniList: латиница.
        for (q in queries.filter { it.hasLatin() }) {
            val found = retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.searchAnimeAniListOnly(q, AppLanguage.EN, limit = 5) }
                .getOrNull()?.let { pickMatch(anime, it) } ?: continue
            val anilistId = found.externalId?.toIntOrNull() ?: continue
            delay(ITEM_DELAY_MS)
            return retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.episodeCheckByAnilistIds(listOf(anilistId)) }
                .getOrNull()?.firstOrNull()?.also { persistIds(anime, it) }
        }
        // Shikimori: понимает кириллицу; отдаёт malId.
        for (q in queries) {
            val found = retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.searchAnimeShikimoriOnly(q, AppLanguage.RU, allowZeroEpisodes = true) }
                .getOrNull()?.let { pickMatch(anime, it) } ?: continue
            val mal = found.malId ?: found.externalId?.toIntOrNull() ?: continue
            delay(ITEM_DELAY_MS)
            retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.episodeCheckByMalIds(listOf(mal)) }
                .getOrNull()?.firstOrNull()?.let {
                    persistIds(anime, it)
                    return it
                }
        }
        // MAL (Jikan): латиница.
        for (q in queries.filter { it.hasLatin() }) {
            val found = retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.searchAnimeMalOnly(q, AppLanguage.EN, limit = 5) }
                .getOrNull()?.let { pickMatch(anime, it) } ?: continue
            val mal = found.externalId?.toIntOrNull() ?: continue
            delay(ITEM_DELAY_MS)
            retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.episodeCheckByMalIds(listOf(mal)) }
                .getOrNull()?.firstOrNull()?.let {
                    persistIds(anime, it)
                    return it
                }
        }
        return null
    }

    /**
     * AniList недоступен для тайтла вовсе — одиночный «сезон 1» из родных данных
     * Shikimori/MAL (byId, потом поиск), чтобы запись не оставалась пустой.
     */
    private suspend fun fallbackSingleSeason(anime: Anime): SeasonEpisodesEntry? {
        val mal = anime.malId ?: anime.shikimoriId
        if (mal != null) {
            fillFromShikimoriOrMal(mal)?.let { filled ->
                // Источник знает про вышедшие серии — значит тайтл ещё идёт, показываем только их.
                val ongoing = filled.aired != null && filled.total != null && filled.aired < filled.total
                val episodes = if (ongoing) filled.aired else filled.total ?: filled.aired
                if (episodes != null) {
                    return singleSeasonEntry(anime, episodes, filled.total, filled.source, ongoing)
                }
            }
        }
        // Последний рубеж: Shikimori-поиск (кириллица дружелюбна) — счётчик из выдачи.
        val queries = listOfNotNull(
            anime.title.takeIf { it.isNotBlank() },
            anime.titleRu?.takeIf { it.isNotBlank() },
            anime.titleEn?.takeIf { it.isNotBlank() },
        ).distinctBy { it.lowercase() }
        for (q in queries) {
            val found = retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.searchAnimeShikimoriOnly(q, AppLanguage.RU, allowZeroEpisodes = true) }
                .getOrNull()?.let { pickMatch(anime, it) } ?: continue
            val ongoing = found.isOngoing == true
            val total = found.totalEpisodes?.takeIf { it > 0 }
            val aired = found.airedEpisodes?.takeIf { it > 0 } ?: found.episodes.takeIf { it > 0 }
            val eps = (if (ongoing) aired ?: total else total ?: aired) ?: continue
            return singleSeasonEntry(anime, eps, total, "Shikimori", ongoing)
        }
        return null
    }

    private fun singleSeasonEntry(
        anime: Anime,
        episodes: Int,
        totalEpisodes: Int?,
        source: String,
        ongoing: Boolean = false,
    ): SeasonEpisodesEntry = SeasonEpisodesEntry(
        animeId = anime.id,
        seasons = listOf(
            SeasonInfo(
                seasonNumber = 1,
                episodes = episodes,
                totalEpisodes = totalEpisodes,
                ongoing = ongoing,
                anilistId = anime.anilistId,
                malId = anime.malId ?: anime.shikimoriId,
                source = source,
                title = null,
            ),
        ),
        // Одиночный сезон без графа франшизы полным не считаем: пусть следующий проход
        // попробует построить цепочку (id могли дозаполниться другими фичами).
        complete = false,
        resolvedAt = System.currentTimeMillis(),
        schema = SeasonEpisodesEntry.CURRENT_SCHEMA,
    )

    // ==========================================================
    // Дозаполнение серий сезона: Shikimori → MAL (по malId)
    // ==========================================================

    /** Счётчики одного сезона из «неанилистовых» источников: вышедшие и заявленные раздельно. */
    private data class EpisodeCounts(val aired: Int?, val total: Int?, val source: String)

    private suspend fun fillFromShikimoriOrMal(malId: Int): EpisodeCounts? {
        delay(ITEM_DELAY_MS)
        retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.shikimoriById(malId, AppLanguage.RU) }.getOrNull()?.let { r ->
            counts(r.airedEpisodes, r.totalEpisodes ?: r.episodes, "Shikimori")?.let { return it }
        }
        delay(ITEM_DELAY_MS)
        retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.malById(malId, AppLanguage.EN) }.getOrNull()?.let { r ->
            counts(r.airedEpisodes, r.totalEpisodes ?: r.episodes, "MAL")?.let { return it }
        }
        return null
    }

    private fun counts(aired: Int?, total: Int?, source: String): EpisodeCounts? {
        val a = aired?.takeIf { it > 0 }
        val t = total?.takeIf { it > 0 }
        return if (a == null && t == null) null else EpisodeCounts(a, t, source)
    }

    // ==========================================================
    // Франшиза: BFS + порядок (по образцу FranchiseEpisodeMapper)
    // ==========================================================

    /**
     * Граф франшизы. [complete] = обход дошёл до конца сам, а не упёрся в сорванный запрос или
     * в лимиты: только тогда запись имеет право считаться полной и жить месяц.
     */
    private class FranchiseGraph(val nodes: Map<Int, EpisodeCheckMedia>, val complete: Boolean)

    /**
     * BFS по PREQUEL/SEQUEL. Обходим ВСЕ узлы, включая фильмы и OVA: цепочки сплошь и рядом идут
     * через них («KonoSuba 2 → фильм Kurenai Densetsu → KonoSuba 3»), и если на таком узле
     * остановиться, все последующие сезоны просто не находятся. Отсев по формату — на выходе.
     *
     * Обрыв обхода НЕ маскируем: раньше упавший батч превращался в `emptyList()`, обход тихо
     * заканчивался на сид-узле, и тайтл с пятью сезонами сохранялся как «S1, complete=true» —
     * то есть залипал в кэше на месяц с одним сезоном.
     */
    private suspend fun gatherFranchise(self: EpisodeCheckMedia): FranchiseGraph {
        val result = LinkedHashMap<Int, EpisodeCheckMedia>()
        result[self.anilistId] = self
        var frontier = self.seasonNeighbors() - result.keys
        var level = 0
        var complete = true
        while (frontier.isNotEmpty()) {
            if (result.size >= MAX_NODES || level >= MAX_LEVELS) {
                Log.w(TAG, "franchise traversal truncated at ${result.size} nodes / level $level")
                complete = false
                break
            }
            level++
            val fetched = retryOn429(MAX_ATTEMPTS, RETRY_BASE_DELAY_MS) { repository.episodeCheckByAnilistIds(frontier.toList()) }
                .getOrNull()
            if (fetched == null) {
                Log.w(TAG, "franchise batch failed (${frontier.size} ids) — entry stays incomplete")
                complete = false
                break
            }
            val next = HashSet<Int>()
            for (m in fetched) {
                result[m.anilistId] = m
                next += m.seasonNeighbors()
            }
            frontier = next - result.keys
        }
        return FranchiseGraph(result, complete)
    }



    private fun EpisodeCheckMedia.isSeasonFormat(): Boolean =
        format == null || format in SEASON_FORMATS

    /**
     * Смотрибельное, но не сезон: OVA, спецвыпуски, полнометражки франшизы.
     *
     * Обход и раньше шёл через эти узлы ради связности цепочки — просто выбрасывал их на выходе,
     * и половина франшизы оставалась недоступной из списка серий.
     */
    private fun EpisodeCheckMedia.isSpecialFormat(): Boolean = format in SPECIAL_FORMATS

    // ==========================================================
    // Сопоставление и сетевые мелочи
    // ==========================================================

    private fun localTitles(anime: Anime): List<String> =
        listOfNotNull(anime.title, anime.titleEn, anime.titleRu).filter { it.isNotBlank() }

    private fun pickMatch(anime: Anime, results: List<ApiSearchResult>?): ApiSearchResult? =
        results.orEmpty()
            .map { candidate ->
                val remotes = listOfNotNull(candidate.title, candidate.altTitle)
                candidate to (localTitles(anime).maxOfOrNull { TitleMatcher.bestScore(it, remotes) } ?: 0.0)
            }
            .filter { (_, score) -> score >= MATCH_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first

    /** Найденные id — в БД: остальные фичи (проверка серий, AniSkip) пойдут быстрой веткой. */
    private suspend fun persistIds(anime: Anime, media: EpisodeCheckMedia) {
        runCatching {
            if (anime.anilistId == null) localDataSource.setAnilistId(anime.id, media.anilistId)
            val mal = media.malId
            if (anime.malId == null && mal != null) localDataSource.setMalId(anime.id, mal)
        }
    }


    private fun String.hasLatin(): Boolean = any { it in 'a'..'z' || it in 'A'..'Z' }

    private companion object {
        const val TAG = "SeasonEpisodes"
        const val DEFAULT_BUDGET = 8
        const val ITEM_DELAY_MS = 350L
        /** Сорвавшийся тайтл не пробуем снова раньше — его место в бюджете получают другие. */
        const val FAILURE_COOLDOWN_MILLIS = 6L * 60 * 60 * 1000
        const val MATCH_SCORE = 0.91
        const val MAX_ATTEMPTS = 3
        const val RETRY_BASE_DELAY_MS = 800L
        // Обход идёт сквозь фильмы/OVA, поэтому узлов на франшизу заметно больше, чем сезонов.
        const val MAX_NODES = 40
        const val MAX_LEVELS = 12
        val SEASON_FORMATS = setOf("TV", "TV_SHORT", "ONA")

        /**
         * ONA сюда не попадает намеренно: у стримингов это полноценные сезоны, а не бонус.
         * MUSIC пропущен — клипы смотреть как серии никто не просил.
         */
        val SPECIAL_FORMATS = setOf("OVA", "SPECIAL", "MOVIE")
    }
}
