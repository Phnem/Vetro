package com.example.myapplication.network

/**
 * Network API contract. All methods return Result for idiomatic Kotlin error handling.
 */
interface ApiService {
    suspend fun fetchDetails(request: DetailsLookupRequest): Result<AnimeDetails?>
    suspend fun findTotalEpisodes(
        title: String,
        categoryType: String,
        appContentType: AppContentType
    ): Result<Pair<Int, String>?>
    suspend fun checkGithubUpdate(owner: String, repo: String): Result<GithubReleaseInfo?>

    suspend fun searchApi(query: String, contentType: AppContentType, language: AppLanguage): Result<List<ApiSearchResult>>

    /**
     * Тот же поиск, но выдача приходит по мере ответа источников: каждый элемент потока — вся
     * накопленная к этому моменту выдача (не прирост). По умолчанию — один элемент, итог [searchApi].
     */
    fun searchApiProgressive(
        query: String,
        contentType: AppContentType,
        language: AppLanguage,
    ): kotlinx.coroutines.flow.Flow<List<ApiSearchResult>> =
        kotlinx.coroutines.flow.flow { emit(searchApi(query, contentType, language).getOrThrow()) }

    /**
     * Только Shikimori (для RU inspect: trace → Gemini → Shikimori).
     * [allowZeroEpisodes]: для AI-рекомендаций, иначе Shikimori даёт 0 эпизодов у не вышедших/фильмов — список становится пустым.
     */
    suspend fun searchAnimeShikimoriOnly(
        query: String,
        language: AppLanguage,
        allowZeroEpisodes: Boolean = false
    ): Result<List<ApiSearchResult>>

    /** AniList [Media] по числовому id (для trace.moe → AniList). */
    suspend fun mediaByAnilistId(id: Int): Result<ApiSearchResult?>

    /** AniList-only search (used by batch update checker). */
    suspend fun searchAnimeAniListOnly(
        query: String,
        language: AppLanguage,
        limit: Int = 20
    ): Result<List<ApiSearchResult>>

    /** Shikimori title by numeric id. */
    suspend fun shikimoriById(id: Int, language: AppLanguage): Result<ApiSearchResult?>

    /** MAL/Jikan title by numeric id. */
    suspend fun malById(id: Int, language: AppLanguage): Result<ApiSearchResult?>

    /** Kitsu-only search (EN endpoint: названия/описания/постеры/оценки). */
    suspend fun searchAnimeKitsuOnly(query: String, limit: Int = 10): Result<List<ApiSearchResult>>

    /** AniLibria-only search (RU endpoint: русские описания/постеры/серии в озвучке). */
    suspend fun searchAnimeAnilibriaOnly(query: String, limit: Int = 10): Result<List<ApiSearchResult>>

    // ---- Батч-проверка новых серий (AniList id_in / idMal_in, до 50 тайтлов на запрос) ----

    /** AniList: снимки Media со связями франшизы по списку AniList id. */
    suspend fun episodeCheckByAnilistIds(ids: List<Int>): Result<List<EpisodeCheckMedia>>

    /** AniList: то же по списку MAL id — для записей без anilistId. */
    suspend fun episodeCheckByMalIds(malIds: List<Int>): Result<List<EpisodeCheckMedia>>

    /** AniList: связи франшизы по AniList id — для отсева рекомендаций из уже имеющихся франшиз. */
    suspend fun franchiseLinksByAnilistIds(ids: List<Int>): Result<List<FranchiseLinks>>

    /** То же по MAL id (он же id Shikimori). */
    suspend fun franchiseLinksByMalIds(malIds: List<Int>): Result<List<FranchiseLinks>>

    /** MAL-only search via Jikan (used as EN fallback). */
    suspend fun searchAnimeMalOnly(
        query: String,
        language: AppLanguage,
        limit: Int = 20
    ): Result<List<ApiSearchResult>>

    // ---- Обогащение EN-названий (явные romaji/english/native + внешние id) ----

    /** AniList: явные названия по AniList id и/или MAL id (idMal). */
    suspend fun enrichTitlesByIds(anilistId: Int?, malId: Int?): Result<EnrichedTitles?>

    /** AniList: явные названия по поиску (для сопоставления, когда id нет). */
    suspend fun enrichTitlesBySearch(query: String, limit: Int = 8): Result<List<EnrichedTitles>>

    /** MAL/Jikan: явные названия по MAL id (title / title_english / title_japanese). */
    suspend fun malTitlesById(id: Int): Result<EnrichedTitles?>

    /** MAL/Jikan: явные названия по поиску (fallback). */
    suspend fun malTitlesBySearch(query: String, limit: Int = 8): Result<List<EnrichedTitles>>

    /** Shikimori: явное русское название по shikimori_id (обратное обогащение, Stage 10). */
    suspend fun russianTitleByShikimoriId(id: Int): Result<EnrichedTitles?>

    /** Shikimori: явные русские названия по поиску (fallback, когда shikimori_id нет). */
    suspend fun russianTitlesBySearch(query: String, limit: Int = 8): Result<List<EnrichedTitles>>

    // ---- Recommendations (related-графы источников) ----

    /** AniList: related для ВСЕХ сидов одним батч-запросом. Map: anilistId сида → кандидаты. */
    suspend fun anilistRecommendationsBatch(
        anilistIds: List<Int>,
        perSeed: Int = 12
    ): Result<Map<Int, List<ApiSearchResult>>>

    /** Shikimori: похожие тайтлы по id (fallback для сидов без anilistId). */
    suspend fun shikimoriSimilar(id: Int, language: AppLanguage): Result<List<ApiSearchResult>>

    /** MAL/Jikan: рекомендации по id (fallback для сидов только с malId). */
    suspend fun malRecommendations(id: Int): Result<List<ApiSearchResult>>

    /** AniList: глобальный тренд — cold-start, когда сидов нет. */
    suspend fun anilistTrending(limit: Int = 20): Result<List<ApiSearchResult>>
}
