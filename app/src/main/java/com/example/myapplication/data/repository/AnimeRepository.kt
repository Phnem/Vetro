package com.example.myapplication.data.repository

import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.LibraryFilter
import com.example.myapplication.data.models.SortOption
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.network.ApiService
import com.example.myapplication.network.AnimeDetails
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.EnrichedTitles
import com.example.myapplication.network.GithubReleaseInfo
import com.example.myapplication.network.DetailsLookupRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Single source of truth for anime data. Network calls return Result;
 * no try/catch in ViewModel — use result.fold(onSuccess, onFailure).
 *
 * Список аниме — реактивный пайплайн: БД (asFlow) + фильтр/сортировка в памяти (до ~3–5k записей).
 */
class AnimeRepository(
    private val apiService: ApiService,
    private val localDataSource: AnimeLocalDataSource
) {

    fun getAllAnimeSnapshot(): List<Anime> = localDataSource.getAllAnimeList()

    /**
     * Реактивный поток списка: БД + фильтр/сортировка в памяти (до ~3–5k записей).
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun observeAnimeList(
        searchQuery: Flow<String>,
        sortOption: Flow<SortOption>,
        sortAscending: Flow<Boolean>,
        filterTags: Flow<List<String>>,
        mediaTypeFilter: Flow<MediaType?>,
        libraryFilter: Flow<LibraryFilter> = kotlinx.coroutines.flow.flowOf(LibraryFilter()),
    ): Flow<List<Anime>> = mediaTypeFilter.flatMapLatest { filterType ->
        // Плитка "Сериалы" исторически охватывала и фильмы, и сериалы одной строкой
        // (MediaType.TV_SERIES до разделения на MOVIE/SERIES) — сохраняем это поведение: выбор
        // SERIES матчит оба типа. DB-уровня фильтрация тут не годится (один exact match), поэтому
        // при SERIES тянем всё и доотфильтровываем в памяти.
        val dbFilterType = if (filterType == MediaType.SERIES) null else filterType
        combine(
            localDataSource.observeAllAnime(dbFilterType),
            searchQuery.debounce(300L).distinctUntilChanged(),
            sortOption.distinctUntilChanged(),
            sortAscending.distinctUntilChanged(),
            filterTags.distinctUntilChanged(),
        ) { list, q, sort, asc, tags ->
            val scoped = if (filterType == MediaType.SERIES) {
                list.filter { it.mediaType == MediaType.MOVIE || it.mediaType == MediaType.SERIES }
            } else {
                list
            }
            filterAndSortInMemory(scoped, q, sort, asc, tags)
        }.combine(libraryFilter.distinctUntilChanged()) { sorted, filter ->
            // Фильтры шторки (год, рейтинг, серии; статус — во ViewModel, ему нужен прогресс) поверх отсортированного списка:
            // filter сохраняет порядок, а «избранное сверху» остаётся как было.
            if (filter.isActive) sorted.filter(filter::matchesRanges) else sorted
        }
    }.flowOn(Dispatchers.Default)

    private fun filterAndSortInMemory(
        list: List<Anime>,
        searchQuery: String,
        sortOption: SortOption,
        sortAscending: Boolean,
        filterTags: List<String>
    ): List<Anime> {
        val trimmed = searchQuery.trim()
        val filtered = list.asSequence()
            .let { seq ->
                if (trimmed.isEmpty()) seq
                else {
                    val lower = trimmed.lowercase()
                    seq.filter { it.title.lowercase().contains(lower) }
                }
            }
            .let { seq ->
                if (filterTags.isEmpty()) seq
                else seq.filter { it.tags.containsAll(filterTags) }
            }
        val sorted = when (sortOption) {
            SortOption.RATING -> if (sortAscending) filtered.sortedBy { it.rating } else filtered.sortedByDescending { it.rating }
            SortOption.EPISODES -> if (sortAscending) filtered.sortedBy { it.episodes } else filtered.sortedByDescending { it.episodes }
            SortOption.TITLE -> if (sortAscending) filtered.sortedBy { it.title } else filtered.sortedByDescending { it.title }
        }
        // Избранное всегда сверху, поверх любой сортировки. Категорию учитывать не нужно: список
        // сюда приходит уже отфильтрованным по mediaType (`observeAllAnime(filterType)`), поэтому
        // в «Манге» наверх всплывает избранная манга, а в «Все» — всё избранное разом.
        // sortedByDescending стабильна, так что внутри обеих групп порядок сортировки сохраняется,
        // и снятая «звезда» возвращает тайтл ровно на своё место.
        return sorted.sortedByDescending { it.isFavorite }.toList()
    }

    fun getAnimeById(id: String): Anime? = localDataSource.getAnimeById(id)

    suspend fun toggleFavorite(id: String): Anime? {
        val anime = getAnimeById(id) ?: return null
        val updated = anime.copy(isFavorite = !anime.isFavorite)
        localDataSource.updateAnime(updated)
        return updated
    }

    suspend fun fetchDetails(request: DetailsLookupRequest): Result<AnimeDetails?> =
        apiService.fetchDetails(request)

    suspend fun findTotalEpisodes(
        title: String,
        categoryType: String,
        appContentType: AppContentType
    ): Result<Pair<Int, String>?> {
        return apiService.findTotalEpisodes(title, categoryType, appContentType)
    }

    suspend fun checkGithubUpdate(owner: String, repo: String): Result<GithubReleaseInfo?> {
        return apiService.checkGithubUpdate(owner, repo)
    }

    suspend fun searchApi(query: String, contentType: AppContentType, language: AppLanguage): Result<List<ApiSearchResult>> {
        return apiService.searchApi(query, contentType, language)
    }

    suspend fun searchAnimeShikimoriOnly(
        query: String,
        language: AppLanguage,
        allowZeroEpisodes: Boolean = false
    ): Result<List<ApiSearchResult>> {
        return apiService.searchAnimeShikimoriOnly(query, language, allowZeroEpisodes)
    }

    suspend fun mediaByAnilistId(id: Int): Result<ApiSearchResult?> {
        return apiService.mediaByAnilistId(id)
    }

    suspend fun searchAnimeAniListOnly(
        query: String,
        language: AppLanguage,
        limit: Int = 20
    ): Result<List<ApiSearchResult>> {
        return apiService.searchAnimeAniListOnly(query, language, limit)
    }

    suspend fun shikimoriById(id: Int, language: AppLanguage): Result<ApiSearchResult?> {
        return apiService.shikimoriById(id, language)
    }

    suspend fun malById(id: Int, language: AppLanguage): Result<ApiSearchResult?> {
        return apiService.malById(id, language)
    }

    /** Батч-проверка серий: AniList id_in, до 50 тайтлов на запрос. */
    suspend fun episodeCheckByAnilistIds(ids: List<Int>): Result<List<com.example.myapplication.network.EpisodeCheckMedia>> {
        return apiService.episodeCheckByAnilistIds(ids)
    }

    /** Батч-проверка серий по MAL id (idMal_in) — для записей без anilistId. */
    suspend fun episodeCheckByMalIds(malIds: List<Int>): Result<List<com.example.myapplication.network.EpisodeCheckMedia>> {
        return apiService.episodeCheckByMalIds(malIds)
    }

    /** Явно проставить MAL id (напр. реконсиляция malId по Shikimori myanimelist_id в «Исправить БД»). */
    suspend fun setMalId(animeId: String, malId: Int) {
        localDataSource.setMalId(animeId, malId)
    }

    /** Идентификаторы и названия, найденные слоем сопоставления (TitleInfoEnricher). */
    suspend fun setTmdbId(animeId: String, tmdbId: Int) {
        localDataSource.setTmdbId(animeId, tmdbId)
    }

    suspend fun setKinopoiskId(animeId: String, kinopoiskId: Int) {
        localDataSource.setKinopoiskId(animeId, kinopoiskId)
    }

    suspend fun setImdbId(animeId: String, imdbId: String) {
        localDataSource.setImdbId(animeId, imdbId)
    }

    suspend fun setTitleRu(animeId: String, titleRu: String) {
        localDataSource.setTitleRu(animeId, titleRu)
    }

    suspend fun setTitleEn(animeId: String, titleEn: String) {
        localDataSource.setTitleEn(animeId, titleEn)
    }

    suspend fun markTmdbNotFound(animeId: String, atMillis: Long) {
        localDataSource.markTmdbNotFound(animeId, atMillis)
    }

    suspend fun clearTmdbId(animeId: String) {
        localDataSource.clearTmdbId(animeId)
    }

    suspend fun markKinopoiskNotFound(animeId: String, atMillis: Long) {
        localDataSource.markKinopoiskNotFound(animeId, atMillis)
    }

    suspend fun clearKinopoiskId(animeId: String) {
        localDataSource.clearKinopoiskId(animeId)
    }

    suspend fun searchAnimeMalOnly(
        query: String,
        language: AppLanguage,
        limit: Int = 20
    ): Result<List<ApiSearchResult>> {
        return apiService.searchAnimeMalOnly(query, language, limit)
    }

    suspend fun searchAnimeKitsuOnly(query: String, limit: Int = 10): Result<List<ApiSearchResult>> {
        return apiService.searchAnimeKitsuOnly(query, limit)
    }

    suspend fun searchAnimeAnilibriaOnly(query: String, limit: Int = 10): Result<List<ApiSearchResult>> {
        return apiService.searchAnimeAnilibriaOnly(query, limit)
    }

    suspend fun enrichTitlesByIds(anilistId: Int?, malId: Int?): Result<EnrichedTitles?> {
        return apiService.enrichTitlesByIds(anilistId, malId)
    }

    suspend fun enrichTitlesBySearch(query: String, limit: Int = 8): Result<List<EnrichedTitles>> {
        return apiService.enrichTitlesBySearch(query, limit)
    }

    suspend fun malTitlesById(id: Int): Result<EnrichedTitles?> {
        return apiService.malTitlesById(id)
    }

    suspend fun malTitlesBySearch(query: String, limit: Int = 8): Result<List<EnrichedTitles>> {
        return apiService.malTitlesBySearch(query, limit)
    }

    suspend fun russianTitleByShikimoriId(id: Int): Result<EnrichedTitles?> {
        return apiService.russianTitleByShikimoriId(id)
    }

    suspend fun russianTitlesBySearch(query: String, limit: Int = 8): Result<List<EnrichedTitles>> {
        return apiService.russianTitlesBySearch(query, limit)
    }
}
