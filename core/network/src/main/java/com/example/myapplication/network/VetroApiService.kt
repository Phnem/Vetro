package com.example.myapplication.network

import com.example.myapplication.network.movie.MovieSeriesRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLProtocol
import io.ktor.http.appendPathSegments
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ktor 3.x–based API facade. Uses Shikimori + AniList + Kitsu + AniLibria remote data sources,
 * returns Result everywhere.
 */
class VetroApiService(
    private val httpClient: HttpClient,
    private val shikimori: ShikimoriRemoteDataSource,
    private val aniList: AniListRemoteDataSource,
    private val kitsu: KitsuRemoteDataSource,
    private val anilibria: AnilibriaRemoteDataSource,
    private val remanga: com.example.myapplication.network.remanga.RemangaRemoteDataSource,
    private val mangaDex: com.example.myapplication.network.mangadex.MangaDexRemoteDataSource,
    private val movieSeriesRepository: MovieSeriesRepository,
    private val heavyRate: TokenBucketRateLimiter,
    private val searchRate: TokenBucketRateLimiter,
    private val burstRate: TokenBucketRateLimiter,
    /** Jikan — только через выключатель: лежащий MAL не должен тормозить каскады. */
    private val jikan: JikanGateway = JikanGateway(httpClient),
) : ApiService {

    private val json = AppJson

    override suspend fun fetchDetails(request: DetailsLookupRequest): Result<AnimeDetails?> {
        return runCatching {
            heavyRate.acquire()
            if (request.appContentType == AppContentType.MOVIE || request.appContentType == AppContentType.SERIES) {
                return@runCatching movieSeriesRepository.fetchDetails(
                    contentType = request.appContentType,
                    language = request.language,
                    externalIds = request.externalIds,
                    title = request.title.trim(),
                ).valueOrNullOrThrow()
            }
            if (request.isManga) {
                if (request.remangaId != null) {
                    val remangaDetails = remanga.getMangaDetails(request.remangaId)
                    if (remangaDetails != null) return@runCatching remangaDetails
                }
                val query = request.title.trim()
                when (request.language) {
                    AppLanguage.EN -> fetchMangaDetailsEn(query)
                    AppLanguage.RU -> shikimori.fetchAnimeDetails(query).getOrNull()
                        ?: aniList.fetchAnimeDetails(query).getOrNull()
                }
            } else {
                when (request.language) {
                    AppLanguage.EN -> fetchAnimeDetailsEn(
                        request.anilistId,
                        request.malId,
                        request.shikimoriId,
                        request.titleEn,
                    )
                    AppLanguage.RU -> fetchAnimeDetailsRu(request.title.trim())
                }
            }
        }
    }

    /**
     * EN-режим (аниме): AniList id → MAL id (AniList/Jikan) → английское название (AniList/Jikan).
     * Shikimori не используется как источник описания; shikimoriId — только для резолва MAL id.
     */
    private suspend fun fetchAnimeDetailsEn(
        anilistId: Int?,
        malId: Int?,
        shikimoriId: Int?,
        titleEn: String?,
    ): AnimeDetails? {
        anilistId?.let { id ->
            acceptEnDescription(aniList.mediaByAnilistId(id).getOrNull())?.let { return it }
        }

        resolveMalId(malId, shikimoriId)?.let { resolvedMalId ->
            enrichTitlesByIds(null, resolvedMalId).getOrNull()?.anilistId?.let { aid ->
                acceptEnDescription(aniList.mediaByAnilistId(aid).getOrNull())?.let { return it }
            }
            acceptEnDescription(malById(resolvedMalId, AppLanguage.EN).getOrNull())?.let { return it }
        }

        titleEn?.trim()?.takeIf { it.isNotEmpty() }?.let { en ->
            fetchAnimeDetailsEnByTitle(en)?.let { return it }
        }
        return null
    }

    private suspend fun fetchMangaDetailsEn(query: String): AnimeDetails? {
        acceptEnDescription(aniList.fetchAnimeDetails(query).getOrNull())?.let { return it }
        searchJikanManga(query, AppLanguage.EN).firstOrNull()?.let { hit ->
            acceptEnDescription(hit.toAnimeDetails())?.let { return it }
        }
        return null
    }

    private suspend fun fetchAnimeDetailsEnByTitle(title: String): AnimeDetails? {
        acceptEnDescription(aniList.fetchAnimeDetails(title).getOrNull())?.let { return it }
        searchJikan(title, AppLanguage.EN).firstOrNull()?.let { hit ->
            acceptEnDescription(hit.toAnimeDetails())?.let { return it }
        }
        // Kitsu — EN endpoint 3: стабильный JSON:API, закрывает пробелы AniList/Jikan.
        acceptEnDescription(kitsu.fetchAnimeDetails(title).getOrNull())?.let { return it }
        return null
    }

    /** Shikimori — только метаданные (myanimelist_id), не synopsis. */
    private suspend fun resolveMalId(malId: Int?, shikimoriId: Int?): Int? {
        malId?.takeIf { it > 0 }?.let { return it }
        shikimoriId?.takeIf { it > 0 }?.let { id ->
            shikimori.getAnimeById(id, AppLanguage.EN).getOrNull()?.malId?.takeIf { it > 0 }?.let { return it }
        }
        return null
    }

    /**
     * RU-режим: Shikimori (русский synopsis) → AniLibria (русское описание) → AniList
     * (EN-описание как последний шанс). Если у Shikimori карточка есть, но описание пустое,
     * берём текст AniLibria, а метаданные (постер/эпизоды/жанры) оставляем шикиморские.
     */
    private suspend fun fetchAnimeDetailsRu(query: String): AnimeDetails? {
        val shiki = shikimori.fetchAnimeDetails(query).getOrNull()
        if (shiki != null && shiki.description.isNotBlank()) return shiki

        anilibria.fetchAnimeDetails(query).getOrNull()
            ?.takeIf { it.description.isNotBlank() }
            ?.let { libria ->
                return if (shiki != null) shiki.copy(description = libria.description) else libria
            }

        return shiki ?: aniList.fetchAnimeDetails(query).getOrNull()
    }

    override suspend fun findTotalEpisodes(
        title: String,
        categoryType: String,
        appContentType: AppContentType
    ): Result<Pair<Int, String>?> {
        return runCatching {
            heavyRate.acquire()
            when (appContentType) {
                AppContentType.ANIME -> {
                    aniList.findTotalEpisodes(title.trim()).getOrNull()
                        ?: shikimori.findTotalEpisodes(title.trim()).getOrNull()
                        ?: checkJikan(title.trim())
                }
                AppContentType.MANGA -> {
                    val res = searchJikanManga(title.trim(), AppLanguage.EN).firstOrNull()
                    res?.episodes?.takeIf { it > 0 }?.let { it to "JIKAN" }
                }
                AppContentType.MOVIE, AppContentType.SERIES ->
                    movieSeriesRepository.findTotalEpisodes(title.trim(), appContentType).valueOrNullOrThrow()
            }
        }
    }

    override suspend fun searchApi(query: String, contentType: AppContentType, language: AppLanguage): Result<List<ApiSearchResult>> {
        return runCatching { searchApiProgressive(query, contentType, language).lastOrNull().orEmpty() }
    }

    /**
     * Источники опрашиваются параллельно и каждый со своим таймаутом ([SOURCE_TIMEOUT_MS]): время
     * поиска — время самого медленного из них, а не сумма, и один подвисший источник не держит
     * выдачу. Первые ответы уходят в поток сразу, итог (с русским мостом) — последним элементом.
     * Готовый итог кладётся в короткий кэш: повторный и обратный ввод отвечают мгновенно.
     */
    override fun searchApiProgressive(
        query: String,
        contentType: AppContentType,
        language: AppLanguage,
    ): Flow<List<ApiSearchResult>> = channelFlow {
        val q = query.trim()
        if (q.isEmpty()) {
            send(emptyList())
            return@channelFlow
        }
        val cacheKey = SearchCacheKey(contentType, language, q.lowercase())
        searchCache.get(cacheKey)?.let {
            send(it)
            return@channelFlow
        }
        searchRate.acquire()
        // Что найдено в разделе «Манга» — манга, и так по каждому разделу. Тип берём из
        // раздела, а не из ответа источника: Shikimori проставляет "ANIME" и результатам
        // /api/mangas, из-за чего добавленная манга попадала в коллекцию как аниме и
        // открывала меню серий вместо глав. См. [AppContentType.categoryTypeName].
        val categoryType = contentType.categoryTypeName()
        fun stamp(list: List<ApiSearchResult>) =
            list.map { if (it.categoryType == categoryType) it else it.copy(categoryType = categoryType) }
        val staged = when (contentType) {
            AppContentType.ANIME -> searchAnimeStaged(q, language) { send(stamp(it)) }
            AppContentType.MANGA -> searchMangaStaged(q, language) { send(stamp(it)) }
            AppContentType.MOVIE, AppContentType.SERIES ->
                StagedSearch(movieSeriesRepository.search(q, contentType, language).valueOrEmptyOrThrow(), complete = true)
        }
        val results = stamp(staged.results)
        send(results)
        // Пустую или неполную (источник не уложился в таймаут) выдачу не кэшируем: повтор может дать больше.
        if (results.isNotEmpty() && staged.complete) searchCache.put(cacheKey, results)
    }

    override suspend fun searchAnimeShikimoriOnly(
        query: String,
        language: AppLanguage,
        allowZeroEpisodes: Boolean
    ): Result<List<ApiSearchResult>> {
        return runCatching {
            searchRate.acquire()
            val q = query.trim()
            if (q.isEmpty()) return@runCatching emptyList()
            val raw = shikimori.searchAnime(q, 20, language).getOrThrow()
            // Inspect: обычно убираем 0 эпизодов; AI — оставляем, иначе пусто у анонсов/некоторых записей
            val pool = if (allowZeroEpisodes) raw else raw.filter { it.episodes > 0 }
            if (pool.isEmpty()) return@runCatching emptyList()

            // Для Inspect RU-аниме нам критично "не получить пусто" из-за слишком строгого fuzzy:
            // Gemini часто даёт близкое, но не 1:1 совпадение. Поэтому делаем мягкую фильтрацию.
            val normQuery = normalizeForSearch(q)
            if (normQuery.isEmpty()) return@runCatching pool

            fun searchableKey(r: ApiSearchResult): String {
                val both = (r.title + " " + (r.altTitle ?: "")).trim()
                return normalizeForSearch(both)
            }

            val exactOrPartial = pool.filter { r ->
                val key = searchableKey(r)
                key.isNotEmpty() && (key.contains(normQuery) || normQuery.contains(key))
            }

            val ranked = (exactOrPartial.ifEmpty { pool })
                .sortedWith(compareBy<ApiSearchResult> { r ->
                    val key = searchableKey(r)
                    when {
                        key == normQuery -> 0
                        key.contains(normQuery) || normQuery.contains(key) -> 1
                        else -> 2
                    }
                }.thenByDescending { it.rating ?: Int.MIN_VALUE })
                .distinctBy { it.source + "_" + (it.externalId ?: it.title) }

            ranked.take(20)
        }
    }

    override suspend fun mediaByAnilistId(id: Int): Result<ApiSearchResult?> {
        return runCatching {
            searchRate.acquire()
            aniList.mediaByAnilistId(id).getOrThrow()
        }
    }

    override suspend fun searchAnimeAniListOnly(
        query: String,
        language: AppLanguage,
        limit: Int
    ): Result<List<ApiSearchResult>> = runCatching {
        searchRate.acquire()
        aniList.searchAnime(query.trim(), limit, language).getOrThrow()
    }

    override suspend fun shikimoriById(id: Int, language: AppLanguage): Result<ApiSearchResult?> = runCatching {
        searchRate.acquire()
        shikimori.getAnimeById(id, language).getOrThrow()
    }

    override suspend fun malById(id: Int, language: AppLanguage): Result<ApiSearchResult?> = runCatching {
        searchRate.acquire()
        fetchJikanById(id, language)
    }

    override suspend fun episodeCheckByAnilistIds(ids: List<Int>): Result<List<EpisodeCheckMedia>> = runCatching {
        searchRate.acquire()
        aniList.episodeCheckByAnilistIds(ids).getOrThrow()
    }

    override suspend fun episodeCheckByMalIds(malIds: List<Int>): Result<List<EpisodeCheckMedia>> = runCatching {
        searchRate.acquire()
        aniList.episodeCheckByMalIds(malIds).getOrThrow()
    }

    override suspend fun searchAnimeMalOnly(
        query: String,
        language: AppLanguage,
        limit: Int
    ): Result<List<ApiSearchResult>> = runCatching {
        searchRate.acquire()
        searchJikan(query.trim(), language).take(limit)
    }

    override suspend fun searchAnimeKitsuOnly(query: String, limit: Int): Result<List<ApiSearchResult>> = runCatching {
        searchRate.acquire()
        kitsu.searchAnime(query.trim(), limit).getOrThrow()
    }

    override suspend fun searchAnimeAnilibriaOnly(query: String, limit: Int): Result<List<ApiSearchResult>> = runCatching {
        searchRate.acquire()
        anilibria.searchAnime(query.trim(), limit).getOrThrow()
    }

    override suspend fun enrichTitlesByIds(anilistId: Int?, malId: Int?): Result<EnrichedTitles?> = runCatching {
        searchRate.acquire()
        aniList.enrichTitlesByIds(anilistId, malId).getOrThrow()
    }

    override suspend fun enrichTitlesBySearch(query: String, limit: Int): Result<List<EnrichedTitles>> = runCatching {
        searchRate.acquire()
        aniList.enrichTitlesBySearch(query.trim(), limit).getOrThrow()
    }

    override suspend fun malTitlesById(id: Int): Result<EnrichedTitles?> = runCatching {
        searchRate.acquire()
        fetchJikanTitlesById(id)
    }

    override suspend fun malTitlesBySearch(query: String, limit: Int): Result<List<EnrichedTitles>> = runCatching {
        searchRate.acquire()
        searchJikanTitles(query.trim(), limit)
    }

    override suspend fun russianTitleByShikimoriId(id: Int): Result<EnrichedTitles?> = runCatching {
        searchRate.acquire()
        shikimori.enrichRussianById(id).getOrThrow()
    }

    override suspend fun russianTitlesBySearch(query: String, limit: Int): Result<List<EnrichedTitles>> = runCatching {
        searchRate.acquire()
        shikimori.enrichRussianBySearch(query.trim(), limit).getOrThrow()
    }

    override suspend fun anilistRecommendationsBatch(
        anilistIds: List<Int>,
        perSeed: Int
    ): Result<Map<Int, List<ApiSearchResult>>> = runCatching {
        if (anilistIds.isEmpty()) return@runCatching emptyMap()
        searchRate.acquire()
        aniList.recommendationsForIds(anilistIds, perSeed).getOrThrow()
    }

    override suspend fun shikimoriSimilar(id: Int, language: AppLanguage): Result<List<ApiSearchResult>> = runCatching {
        searchRate.acquire()
        shikimori.getSimilarAnime(id, language).getOrThrow()
    }

    override suspend fun malRecommendations(id: Int): Result<List<ApiSearchResult>>  = runCatching {
        searchRate.acquire()
        val root = jikan.get(listOf("anime", id.toString(), "recommendations")) ?: error("Jikan unavailable")
        val data = root["data"]?.jsonArray ?: return@runCatching emptyList()
        data.mapNotNull { el ->
            val entry = el.jsonObject["entry"]?.jsonObject ?: return@mapNotNull null
            val title = entry["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val jpg = entry["images"]?.jsonObject?.get("jpg")?.jsonObject
            val image = jpg?.get("large_image_url")?.jsonPrimitive?.content
                ?: jpg?.get("image_url")?.jsonPrimitive?.content
            ApiSearchResult(
                title = title,
                altTitle = null,
                posterUrl = image,
                // У Jikan recommendations усечённая entry: без жанров/оценки/эпизодов —
                // скоринг у таких кандидатов опирается на co-occurrence.
                episodes = 0,
                description = "",
                type = "",
                genres = emptyList(),
                rating = null,
                source = "MAL",
                categoryType = "ANIME",
                externalId = entry["mal_id"]?.jsonPrimitive?.intOrNull?.toString()
            )
        }
    }

    override suspend fun anilistTrending(limit: Int): Result<List<ApiSearchResult>> = runCatching {
        searchRate.acquire()
        aniList.trendingAnime(limit).getOrThrow()
    }

    private enum class SearchMatch { EXACT, PARTIAL, FUZZY, NONE }

    private fun normalizeForSearch(s: String): String = titleKey(s)

    private fun resultDedupKey(r: ApiSearchResult): String {
        val titleKey = normalizeForSearch(r.title)
        if (titleKey.isEmpty()) return ""
        return r.externalId?.let { "$titleKey::$it" } ?: titleKey
    }

    private fun searchMatchType(result: ApiSearchResult, query: String): SearchMatch {
        val q = query.trim()
        if (q.isEmpty()) return SearchMatch.NONE
        val normQuery = normalizeForSearch(q)
        val searchable = (result.title + " " + (result.altTitle ?: "")).trim()
        val normTitle = normalizeForSearch(searchable)
        if (normTitle == normQuery) return SearchMatch.EXACT
        if (normTitle.contains(normQuery) || normQuery.contains(normTitle)) return SearchMatch.PARTIAL
        val queryWords = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        if (queryWords.isEmpty()) return SearchMatch.PARTIAL
        val titleWords = searchable.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val fuzzyOk = queryWords.all { qw -> titleWords.any { tw -> levenshtein(qw, tw) <= 2 } }
        return if (fuzzyOk) SearchMatch.FUZZY else SearchMatch.NONE
    }

    /** Фильтрация (exact/partial/fuzzy) + ранжирование: exact > partial > fuzzy. */
    private fun filterAndRankByQuery(query: String, results: List<ApiSearchResult>): List<ApiSearchResult> {
        val seen = mutableSetOf<String>()
        val order = listOf(SearchMatch.EXACT, SearchMatch.PARTIAL, SearchMatch.FUZZY)
        return results
            .mapNotNull { r ->
                val match = searchMatchType(r, query)
                if (match == SearchMatch.NONE) return@mapNotNull null
                val key = resultDedupKey(r)
                if (key.isEmpty() || !seen.add(key)) return@mapNotNull null
                r to match
            }
            .sortedBy { (_, m) -> order.indexOf(m) }
            .map { it.first }
    }

    private class StagedSearch(val results: List<ApiSearchResult>, val complete: Boolean)

    private class Gathered(val results: List<List<ApiSearchResult>>, val complete: Boolean)

    private data class SearchCacheKey(val type: AppContentType, val language: AppLanguage, val query: String)

    /** Короткий кэш готовых выдач: ключ — раздел, язык и запрос, запись живёт [ttlMs]. */
    private class SearchCache(private val ttlMs: Long = 10 * 60_000L, private val maxEntries: Int = 40) {
        private class Entry(val at: Long, val value: List<ApiSearchResult>)

        private val map = object : LinkedHashMap<SearchCacheKey, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SearchCacheKey, Entry>?) = size > maxEntries
        }

        @Synchronized
        fun get(key: SearchCacheKey): List<ApiSearchResult>? {
            val entry = map[key] ?: return null
            if (System.currentTimeMillis() - entry.at > ttlMs) {
                map.remove(key)
                return null
            }
            return entry.value
        }

        @Synchronized
        fun put(key: SearchCacheKey, value: List<ApiSearchResult>) {
            map[key] = Entry(System.currentTimeMillis(), value)
        }
    }

    private val searchCache = SearchCache()

    /**
     * Запускает [sources] одновременно, каждый с таймаутом. После каждого ответа зовёт [onArrival]
     * со слотами по порядку источников (null — ещё не ответил): порядок слияния не зависит от того,
     * кто успел первым, и выдача получается такой же, как при последовательном опросе.
     */
    private suspend fun gather(
        sources: List<suspend () -> List<ApiSearchResult>>,
        onArrival: suspend (List<List<ApiSearchResult>?>) -> Unit,
    ): Gathered = coroutineScope {
        val slots = arrayOfNulls<List<ApiSearchResult>>(sources.size)
        var complete = true
        val lock = Mutex()
        sources.mapIndexed { index, source ->
            async {
                val found: List<ApiSearchResult>? = try {
                    withTimeoutOrNull(SOURCE_TIMEOUT_MS) { source() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                lock.withLock {
                    if (found == null) complete = false
                    slots[index] = found.orEmpty()
                    onArrival(slots.toList())
                }
            }
        }.awaitAll()
        Gathered(slots.map { it.orEmpty() }, complete)
    }

    /** Слияние ответов аниме-источников по порядку: дубликаты по названию отбрасываются, затем ранжирование. */
    private fun mergeAnime(query: String, arrived: List<List<ApiSearchResult>?>): List<ApiSearchResult> {
        val raw = mutableListOf<ApiSearchResult>()
        val seenKeys = mutableSetOf<String>()
        arrived.forEach { list ->
            list.orEmpty().forEach { r ->
                val key = normalizeForSearch(r.title)
                if (key.isNotEmpty() && seenKeys.add(key)) raw.add(r)
            }
        }
        val ranked = filterAndRankByQuery(query, raw)
        if (ranked.isNotEmpty() || raw.isEmpty()) return ranked
        return raw.sortedWith(compareByDescending<ApiSearchResult> { it.rating ?: Int.MIN_VALUE }).take(20)
    }

    private suspend fun searchAnimeStaged(
        query: String,
        language: AppLanguage,
        onPartial: suspend (List<ApiSearchResult>) -> Unit,
    ): StagedSearch {
        val sources: List<suspend () -> List<ApiSearchResult>> = listOf(
            { shikimori.searchAnime(query, 20, language).getOrNull().orEmpty() },
            { aniList.searchAnime(query, 20, language).getOrNull().orEmpty() },
            { searchJikan(query, language) },
            // Языковые endpoint'ы: AniLibria — русские описания/постеры, Kitsu — стабильный EN JSON:API.
            {
                when (language) {
                    AppLanguage.RU -> anilibria.searchAnime(query, 10).getOrNull().orEmpty()
                    AppLanguage.EN -> kitsu.searchAnime(query, 10).getOrNull().orEmpty()
                }
            },
        )
        val gathered = gather(sources) { arrived ->
            val merged = mergeAnime(query, arrived)
            if (merged.isNotEmpty()) onPartial(merged)
        }
        val raw = mutableListOf<ApiSearchResult>()
        val seenKeys = mutableSetOf<String>()
        gathered.results.forEach { list ->
            list.forEach { r ->
                val key = normalizeForSearch(r.title)
                if (key.isNotEmpty() && seenKeys.add(key)) raw.add(r)
            }
        }
        val ranked = filterAndRankByQuery(query, raw)
        // Stage 12: при RU-запросе без сильного Shikimori — мост через EN-название → Shikimori.
        val withBridge = if (language == AppLanguage.RU) augmentRuSearchViaEnglish(query, raw, ranked) else ranked
        val final = when {
            withBridge.isNotEmpty() -> withBridge
            raw.isEmpty() -> emptyList()
            else -> raw.sortedWith(compareByDescending<ApiSearchResult> { it.rating ?: Int.MIN_VALUE }).take(20)
        }
        return StagedSearch(final, gathered.complete)
    }

    /**
     * RU miss → EN → Shikimori: если у Shikimori нет exact/partial по запросу, берём
     * английские/ромадзи-названия из AniList/Jikan (или EnrichTitles-поиска) и ищем
     * по ним на Shikimori уже на русском. Найденные карточки ставятся в начало выдачи.
     */
    private suspend fun augmentRuSearchViaEnglish(
        query: String,
        raw: List<ApiSearchResult>,
        ranked: List<ApiSearchResult>,
    ): List<ApiSearchResult> {
        val hasStrongShiki = ranked.any { r ->
            r.source.equals("shikimori", ignoreCase = true) &&
                searchMatchType(r, query).let { it == SearchMatch.EXACT || it == SearchMatch.PARTIAL }
        }
        if (hasStrongShiki) return ranked

        val enQueries = linkedSetOf<String>()
        for (r in raw) {
            when (r.source.lowercase()) {
                "anilist", "mal", "jikan" -> {
                    r.title.takeIf { it.isNotBlank() }?.let(enQueries::add)
                    r.altTitle?.takeIf { it.isNotBlank() }?.let(enQueries::add)
                }
            }
        }
        if (enQueries.isEmpty()) {
            enrichTitlesBySearch(query, limit = 5).getOrNull().orEmpty()
                .mapNotNull { it.englishOrNull ?: it.romaji }
                .forEach(enQueries::add)
        }
        if (enQueries.isEmpty()) return ranked

        val seen = ranked.map { resultDedupKey(it) }.filter { it.isNotEmpty() }.toMutableSet()
        val shikiExtra = mutableListOf<ApiSearchResult>()
        // Три запроса к Shikimori — одновременно; разбор по-прежнему по порядку запросов.
        val bridgeQueries = enQueries.take(3)
        val bridged = gather(
            bridgeQueries.map { en -> suspend { shikimori.searchAnime(en, 8, AppLanguage.RU).getOrNull().orEmpty() } },
        ) { }
        for ((position, en) in bridgeQueries.withIndex()) {
            val hits = bridged.results[position]
            for (hit in hits) {
                val key = resultDedupKey(hit)
                if (key.isEmpty() || !seen.add(key)) continue
                val matchEn = searchMatchType(hit, en)
                val matchRu = searchMatchType(hit, query)
                if (matchEn != SearchMatch.NONE || matchRu != SearchMatch.NONE) {
                    shikiExtra.add(hit)
                }
            }
            if (shikiExtra.size >= 5) break
        }
        if (shikiExtra.isEmpty()) return ranked
        return (shikiExtra + ranked).distinctBy { resultDedupKey(it) }.take(20)
    }

    private suspend fun searchJikan(query: String, language: AppLanguage): List<ApiSearchResult> = runCatching {
        val root = jikan.get(listOf("anime"), mapOf("q" to query, "limit" to "20")) ?: error("Jikan unavailable")
        val data = root["data"]?.jsonArray ?: return@runCatching emptyList()
        data.mapNotNull { el ->
            val obj = el.jsonObject
            val titleJp = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val titleEng = obj["title_english"]?.jsonPrimitive?.content ?: ""
            val (displayTitle, altTitle) = when {
                titleEng.isNotBlank() -> titleEng to titleJp.takeIf { it != titleEng }
                else -> titleJp to null
            }
            val desc = obj["synopsis"]?.jsonPrimitive?.content?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
            val episodes = obj["episodes"]?.jsonPrimitive?.intOrNull ?: 0
            val score = obj["score"]?.jsonPrimitive?.content?.toFloatOrNull()?.toInt()
            val jpg = obj["images"]?.jsonObject?.get("jpg")?.jsonObject
            val image = jpg?.get("large_image_url")?.jsonPrimitive?.content
                ?: jpg?.get("image_url")?.jsonPrimitive?.content
            val genres = obj["genres"]?.jsonArray?.mapNotNull { g ->
                g.jsonObject["name"]?.jsonPrimitive?.content
            } ?: emptyList()
            val type = obj["type"]?.jsonPrimitive?.content ?: ""
            ApiSearchResult(
                title = displayTitle,
                altTitle = altTitle,
                posterUrl = image,
                episodes = episodes,
                description = desc,
                type = type,
                genres = genres,
                rating = score,
                source = "Jikan",
                categoryType = "ANIME",
                externalId = obj["mal_id"]?.jsonPrimitive?.intOrNull?.toString()
                    ?: obj["mal_id"]?.jsonPrimitive?.content,
                // Jikan знает статус и план серий, но не «сколько вышло сейчас».
                isOngoing = obj["airing"]?.jsonPrimitive?.booleanOrNull,
                totalEpisodes = episodes.takeIf { it > 0 },
                statusRaw = obj["status"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                format = type.takeIf { it.isNotBlank() },
                sourceMaterial = obj.jikanSourceMaterial(),
                studio = obj.jikanStudio(),
                season = obj.jikanSeason(),
                seasonYear = obj["year"]?.jsonPrimitive?.intOrNull,
            )
        }
    }.getOrElse { emptyList() }

    /** Слияние ответов манга-источников по порядку: у дубликата недостающие поля берутся у следующего источника. */
    private fun mergeManga(query: String, arrived: List<List<ApiSearchResult>?>): List<ApiSearchResult> {
        val raw = mutableListOf<ApiSearchResult>()
        arrived.forEach { list ->
            list.orEmpty().forEach { r ->
                val key = resultDedupKey(r)
                if (key.isEmpty()) return@forEach
                val existing = raw.find { resultDedupKey(it) == key }
                if (existing != null) {
                    raw[raw.indexOf(existing)] = existing.copy(
                        episodes = if (existing.episodes == 0) r.episodes else existing.episodes,
                        genres = if (existing.genres.isEmpty()) r.genres else existing.genres,
                        posterUrl = if (existing.posterUrl.isNullOrEmpty()) r.posterUrl else existing.posterUrl,
                    )
                } else {
                    raw.add(r)
                }
            }
        }
        val ranked = filterAndRankByQuery(query, raw)
        if (ranked.isNotEmpty() || raw.isEmpty()) return ranked
        return raw.sortedWith(compareByDescending<ApiSearchResult> { it.rating ?: Int.MIN_VALUE }).take(20)
    }

    private suspend fun searchMangaStaged(
        query: String,
        language: AppLanguage,
        onPartial: suspend (List<ApiSearchResult>) -> Unit,
    ): StagedSearch {
        val sources: List<suspend () -> List<ApiSearchResult>> = listOf(
            // 1. Remanga — основной источник манги, особенно русской.
            { remanga.searchManga(query, 6) },
            // 2. MangaDex — второй источник, у которого главы реально читаются в приложении
            //    (Shikimori и AniList ниже дают только карточку тайтла). Свой лимит он держит сам.
            { filterAndRankByQuery(query, mangaDex.searchManga(query, 20, language)).take(6) },
            // 3–4. Shikimori и AniList — запасные.
            { filterAndRankByQuery(query, shikimori.searchManga(query, 30, language).getOrNull().orEmpty()).take(3) },
            { filterAndRankByQuery(query, aniList.searchAnime(query, 30, language, isManga = true).getOrNull().orEmpty()).take(3) },
        )
        val gathered = gather(sources) { arrived ->
            val merged = mergeManga(query, arrived)
            if (merged.isNotEmpty()) onPartial(merged)
        }
        return StagedSearch(mergeManga(query, gathered.results), gathered.complete)
    }

    private suspend fun searchJikanManga(query: String, language: AppLanguage): List<ApiSearchResult> = runCatching {
        val root = jikan.get(listOf("manga"), mapOf("q" to query, "limit" to "20")) ?: error("Jikan unavailable")
        val data = root["data"]?.jsonArray ?: return@runCatching emptyList()
        data.mapNotNull { el ->
            val obj = el.jsonObject
            val titleJp = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val titleEng = obj["title_english"]?.jsonPrimitive?.content ?: ""
            val (displayTitle, altTitle) = when {
                titleEng.isNotBlank() -> titleEng to titleJp.takeIf { it != titleEng }
                else -> titleJp to null
            }
            val desc = obj["synopsis"]?.jsonPrimitive?.content?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
            val episodes = obj["chapters"]?.jsonPrimitive?.intOrNull ?: obj["volumes"]?.jsonPrimitive?.intOrNull ?: 0
            val score = obj["score"]?.jsonPrimitive?.content?.toFloatOrNull()?.toInt()
            val jpg = obj["images"]?.jsonObject?.get("jpg")?.jsonObject
            val image = jpg?.get("large_image_url")?.jsonPrimitive?.content
                ?: jpg?.get("image_url")?.jsonPrimitive?.content
            val genres = obj["genres"]?.jsonArray?.mapNotNull { g ->
                g.jsonObject["name"]?.jsonPrimitive?.content
            } ?: emptyList()
            val type = obj["type"]?.jsonPrimitive?.content ?: ""
            ApiSearchResult(
                title = displayTitle,
                altTitle = altTitle,
                posterUrl = image,
                episodes = episodes,
                description = desc,
                type = type,
                genres = genres,
                rating = score,
                source = "JIKAN",
                categoryType = "MANGA",
                externalId = obj["mal_id"]?.jsonPrimitive?.content
            )
        }
    }.getOrElse { emptyList() }

    private suspend fun fetchJikanById(id: Int, language: AppLanguage): ApiSearchResult? = runCatching {
        val root = jikan.get(listOf("anime", id.toString())) ?: error("Jikan unavailable")
        val obj = root["data"]?.jsonObject ?: return@runCatching null
        val titleJp = obj["title"]?.jsonPrimitive?.content ?: return@runCatching null
        val titleEng = obj["title_english"]?.jsonPrimitive?.content ?: ""
        val (displayTitle, altTitle) = when {
            language == AppLanguage.RU && titleJp.isNotBlank() -> titleJp to titleEng.takeIf { it.isNotBlank() && it != titleJp }
            titleEng.isNotBlank() -> titleEng to titleJp.takeIf { it != titleEng }
            else -> titleJp to null
        }
        val desc = obj["synopsis"]?.jsonPrimitive?.content?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
        val episodes = obj["episodes"]?.jsonPrimitive?.intOrNull ?: 0
        val score = obj["score"]?.jsonPrimitive?.content?.toFloatOrNull()?.toInt()
        val jpg = obj["images"]?.jsonObject?.get("jpg")?.jsonObject
        val image = jpg?.get("large_image_url")?.jsonPrimitive?.content
            ?: jpg?.get("image_url")?.jsonPrimitive?.content
        val genres = obj["genres"]?.jsonArray?.mapNotNull { g ->
            g.jsonObject["name"]?.jsonPrimitive?.content
        } ?: emptyList()
        val type = obj["type"]?.jsonPrimitive?.content ?: ""
        ApiSearchResult(
            title = displayTitle,
            altTitle = altTitle,
            posterUrl = image,
            episodes = episodes,
            description = desc,
            type = type,
            genres = genres,
            rating = score,
            source = "MAL",
            categoryType = "ANIME",
            externalId = obj["mal_id"]?.jsonPrimitive?.intOrNull?.toString()
                ?: obj["mal_id"]?.jsonPrimitive?.content,
            isOngoing = obj["airing"]?.jsonPrimitive?.booleanOrNull,
            totalEpisodes = episodes.takeIf { it > 0 },
            statusRaw = obj["status"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            format = type.takeIf { it.isNotBlank() },
            sourceMaterial = obj.jikanSourceMaterial(),
            studio = obj.jikanStudio(),
            season = obj.jikanSeason(),
            seasonYear = obj["year"]?.jsonPrimitive?.intOrNull,
        )
    }.getOrNull()

    /** Jikan пишет первоисточник человекочитаемо («Light novel») — приводим к кодам AniList. */
    private fun kotlinx.serialization.json.JsonObject.jikanSourceMaterial(): String? =
        this["source"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
            ?.trim()
            ?.uppercase()
            ?.replace(' ', '_')
            ?.replace('-', '_')

    private fun kotlinx.serialization.json.JsonObject.jikanStudio(): String? =
        this["studios"]?.jsonArray
            ?.firstNotNullOfOrNull { it.jsonObject["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) }

    private fun kotlinx.serialization.json.JsonObject.jikanSeason(): String? =
        this["season"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.uppercase()

    private fun parseJikanTitles(obj: kotlinx.serialization.json.JsonObject): EnrichedTitles? {
        val titleJp = obj["title"]?.jsonPrimitive?.content
        val titleEng = obj["title_english"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val titleNative = obj["title_japanese"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val malId = obj["mal_id"]?.jsonPrimitive?.intOrNull
        if (titleJp == null && titleEng == null) return null
        return EnrichedTitles(
            malId = malId,
            romaji = titleJp,
            english = titleEng,
            native = titleNative,
        )
    }

    private suspend fun fetchJikanTitlesById(id: Int): EnrichedTitles? = runCatching {
        val root = jikan.get(listOf("anime", id.toString())) ?: error("Jikan unavailable")
        val obj = root["data"]?.jsonObject ?: return@runCatching null
        parseJikanTitles(obj)
    }.getOrNull()

    private suspend fun searchJikanTitles(query: String, limit: Int): List<EnrichedTitles> = runCatching {
        if (query.isBlank()) return@runCatching emptyList()
        val root = jikan.get(listOf("anime"), mapOf("q" to query, "limit" to limit.toString())) ?: error("Jikan unavailable")
        root["data"]?.jsonArray
            ?.mapNotNull { parseJikanTitles(it.jsonObject) }
            .orEmpty()
    }.getOrElse { emptyList() }

    override suspend fun checkGithubUpdate(owner: String, repo: String): Result<GithubReleaseInfo?> {
        return runCatching {
            val response = httpClient.get("https://api.github.com/repos/$owner/$repo/releases").bodyAsText()
            GithubReleaseParser.parse(response)
        }
    }

    private suspend fun checkJikan(query: String): Pair<Int, String>? {
        return runCatching {
            val root = jikan.get(listOf("anime"), mapOf("q" to query, "limit" to "1")) ?: error("Jikan unavailable")
            val data = root["data"]?.jsonArray ?: return@runCatching null
            val first = data.firstOrNull()?.jsonObject ?: return@runCatching null
            val title = first["title"]?.jsonPrimitive?.content ?: return@runCatching null
            val titleEng = first["title_english"]?.jsonPrimitive?.content ?: ""
            if (!isTitleSimilar(query, title, titleEng)) return@runCatching null
            val episodes = first["episodes"]?.jsonPrimitive?.intOrNull ?: 0
            if (episodes > 0) episodes to "Jikan" else null
        }.getOrNull()
    }

    private fun <T> LookupResult<T>.valueOrNullOrThrow(): T? = when (this) {
        is LookupResult.Found -> value
        is LookupResult.NoMatch, is LookupResult.NotFoundById -> null
        is LookupResult.Failure -> throw cause
    }

    private fun LookupResult<List<ApiSearchResult>>.valueOrEmptyOrThrow(): List<ApiSearchResult> =
        valueOrNullOrThrow().orEmpty()
}

/** Сколько ждём один источник поиска, мс: дальше он выпадает из выдачи, остальные не ждут. */
private const val SOURCE_TIMEOUT_MS = 5_000L
