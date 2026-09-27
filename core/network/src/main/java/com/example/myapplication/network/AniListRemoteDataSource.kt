package com.example.myapplication.network

import android.util.Log
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Optional
import com.example.myapplication.network.anilist.EnrichTitlesByIdQuery
import com.example.myapplication.network.anilist.EnrichTitlesSearchQuery
import com.example.myapplication.network.anilist.EpisodeCheckByIdsQuery
import com.example.myapplication.network.anilist.EpisodeCheckByMalIdsQuery
import com.example.myapplication.network.anilist.MediaByIdQuery
import com.example.myapplication.network.anilist.TitleEnrichmentQuery
import com.example.myapplication.network.anilist.fragment.EpisodeCheckFields
import com.example.myapplication.network.anilist.MediaListCollectionChunkQuery
import com.example.myapplication.network.anilist.RecommendationsBatchQuery
import com.example.myapplication.network.anilist.TrendingMediaQuery
import com.example.myapplication.network.anilist.SaveMediaListEntryMutation
import com.example.myapplication.network.anilist.SearchMediaPageQuery
import com.example.myapplication.network.anilist.SearchMediaQuery
import com.example.myapplication.network.anilist.ViewerIdQuery
import com.example.myapplication.network.anilist.type.MediaListStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AniListRemote"
private const val EPISODE_CHECK_CHUNK = 50

/**
 * AniList GraphQL API via Apollo Kotlin. Type-safe queries from .graphql → generated models.
 * Публичные запросы без Bearer; список пользователя и мутации — с заголовком на вызове.
 */
class AniListRemoteDataSource(
    private val apolloClient: ApolloClient,
    private val rateLimiter: TokenBucketRateLimiter
) {
    suspend fun fetchAnimeDetails(query: String): Result<AnimeDetails?> = runCatching {
        withContext(Dispatchers.IO) {
            rateLimiter.acquire()
            val response = apolloClient.query(SearchMediaQuery(q = Optional.presentIfNotNull(query))).execute()
            response.throwIfGraphQlErrors("SearchMedia")
            val media = response.data?.Media ?: return@withContext null
            val title = media.title
            val romaji = title?.romaji?.orEmpty() ?: ""
            val english = title?.english?.orEmpty() ?: ""
            if (!isTitleSimilar(query, romaji, english)) return@withContext null

            val desc = media.description?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
            val type = media.type?.name ?: "ANIME"
            val status = media.status?.name ?: ""
            val episodes = media.episodes ?: 0
            val nextEp = media.nextAiringEpisode?.let { "Episode ${it.episode}" }
            val genres = media.genres?.filterNotNull() ?: emptyList()
            val rating = media.averageScore

            val startDate = media.startDate
            val airedOn = if (startDate?.year != null) {
                buildString {
                    append(startDate.year)
                    if (startDate.month != null) append("-${startDate.month.toString().padStart(2, '0')}")
                    if (startDate.day != null) append("-${startDate.day.toString().padStart(2, '0')}")
                }
            } else null

            AnimeDetails(
                title = romaji.ifEmpty { english }.ifEmpty { query },
                altTitle = if (english.isNotEmpty() && english != romaji) english else null,
                description = desc,
                type = type,
                status = status,
                episodesAired = episodes,
                episodesTotal = media.episodes,
                nextEpisode = nextEp,
                genres = genres,
                rating = rating,
                posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large,
                source = "AniList",
                airedOn = airedOn,
                format = media.format?.rawValue,
                sourceMaterial = media.source?.rawValue,
                studio = media.studios?.nodes?.firstNotNullOfOrNull { it?.name },
                season = media.season?.rawValue,
                seasonYear = media.seasonYear,
            )
        }
    }

    suspend fun searchAnime(query: String, limit: Int = 20, language: AppLanguage = AppLanguage.EN, isManga: Boolean = false): Result<List<ApiSearchResult>> = runCatching {
        withContext(Dispatchers.IO) {
            rateLimiter.acquire()
            val response = apolloClient.query(
                SearchMediaPageQuery(
                    q = Optional.presentIfNotNull(query.takeIf { it.isNotBlank() }),
                    page = Optional.present(1),
                    perPage = Optional.present(limit),
                    type = Optional.present(if (isManga) com.example.myapplication.network.anilist.type.MediaType.MANGA else com.example.myapplication.network.anilist.type.MediaType.ANIME)
                )
            ).execute()
            response.throwIfGraphQlErrors("SearchMediaPage")
            val page = response.data?.Page ?: return@withContext emptyList()
            val mediaList = page.media?.filterNotNull() ?: return@withContext emptyList()
            mediaList.mapNotNull { media ->
                val title = media.title
                val romaji = title?.romaji?.orEmpty() ?: ""
                val english = title?.english?.orEmpty() ?: ""
                val displayTitle = english.ifEmpty { romaji }.ifEmpty { return@mapNotNull null }
                val desc = media.description?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                val posterUrl = media.coverImage?.large
                ApiSearchResult(
                    title = displayTitle,
                    altTitle = if (romaji.isNotEmpty() && romaji != displayTitle) romaji else null,
                    posterUrl = posterUrl,
                    episodes = media.episodes ?: 0,
                    description = desc,
                    type = media.type?.name ?: if (isManga) "MANGA" else "ANIME",
                    genres = media.genres?.filterNotNull() ?: emptyList(),
                    rating = media.averageScore,
                    source = "AniList",
                    categoryType = if (isManga) "MANGA" else "ANIME",
                    externalId = media.id?.toString()
                )
            }
        }
    }

    suspend fun mediaByAnilistId(id: Int): Result<ApiSearchResult?> = runCatching {
        withContext(Dispatchers.IO) {
            rateLimiter.acquire()
            val response = apolloClient.query(MediaByIdQuery(id = Optional.present(id))).execute()
            response.throwIfGraphQlErrors("MediaById")
            val media = response.data?.Media ?: return@withContext null
            val title = media.title
            val romaji = title?.romaji?.orEmpty().orEmpty()
            val english = title?.english?.orEmpty().orEmpty()
            val displayTitle = english.ifEmpty { romaji }.ifEmpty { return@withContext null }
            val desc = media.description?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty()
            val posterUrl = media.coverImage?.large
            ApiSearchResult(
                title = displayTitle,
                altTitle = if (romaji.isNotEmpty() && romaji != displayTitle) romaji else null,
                posterUrl = posterUrl,
                episodes = media.episodes ?: 0,
                description = desc,
                type = media.type?.name ?: "ANIME",
                genres = media.genres?.filterNotNull() ?: emptyList(),
                rating = media.averageScore,
                source = "AniList",
                categoryType = "ANIME",
                externalId = media.id?.toString(),
                statusRaw = media.status?.rawValue,
                format = media.format?.rawValue,
                sourceMaterial = media.source?.rawValue,
                studio = media.studios?.nodes?.firstNotNullOfOrNull { it?.name },
                season = media.season?.rawValue,
                seasonYear = media.seasonYear,
            )
        }
    }

    /**
     * Батч-проверка серий: до 50 тайтлов на HTTP-запрос через id_in.
     * Возвращает все найденные Media (порядок не гарантирован).
     */
    suspend fun episodeCheckByAnilistIds(ids: List<Int>): Result<List<EpisodeCheckMedia>> = runCatching {
        if (ids.isEmpty()) return@runCatching emptyList()
        withContext(Dispatchers.IO) {
            ids.distinct().chunked(EPISODE_CHECK_CHUNK).flatMap { chunk ->
                rateLimiter.acquire()
                val response = apolloClient.query(
                    EpisodeCheckByIdsQuery(
                        ids = Optional.present(chunk),
                        page = Optional.present(1),
                        perPage = Optional.present(EPISODE_CHECK_CHUNK)
                    )
                ).execute()
                response.throwIfGraphQlErrors("EpisodeCheckByIds")
                response.data?.Page?.media?.filterNotNull()
                    ?.mapNotNull { it.episodeCheckFields.toEpisodeCheckMedia() }
                    .orEmpty()
            }
        }
    }

    /** То же, но по MAL id (idMal_in) — для записей без anilistId. */
    suspend fun episodeCheckByMalIds(malIds: List<Int>): Result<List<EpisodeCheckMedia>> = runCatching {
        if (malIds.isEmpty()) return@runCatching emptyList()
        withContext(Dispatchers.IO) {
            malIds.distinct().chunked(EPISODE_CHECK_CHUNK).flatMap { chunk ->
                rateLimiter.acquire()
                val response = apolloClient.query(
                    EpisodeCheckByMalIdsQuery(
                        idsMal = Optional.present(chunk),
                        page = Optional.present(1),
                        perPage = Optional.present(EPISODE_CHECK_CHUNK)
                    )
                ).execute()
                response.throwIfGraphQlErrors("EpisodeCheckByMalIds")
                response.data?.Page?.media?.filterNotNull()
                    ?.mapNotNull { it.episodeCheckFields.toEpisodeCheckMedia() }
                    .orEmpty()
            }
        }
    }

    private fun EpisodeCheckFields.toEpisodeCheckMedia(): EpisodeCheckMedia? {
        val mediaId = id ?: return null
        return EpisodeCheckMedia(
            anilistId = mediaId,
            malId = idMal,
            titleRomaji = title?.romaji?.takeIf { it.isNotBlank() },
            titleEnglish = title?.english?.takeIf { it.isNotBlank() },
            format = format?.name,
            status = status?.name,
            totalEpisodes = episodes,
            airedEpisodes = airedCount(episodes, nextAiringEpisode?.episode),
            relations = relations?.edges?.filterNotNull().orEmpty().mapNotNull { edge ->
                val relType = edge.relationType?.name ?: return@mapNotNull null
                if (relType != "PREQUEL" && relType != "SEQUEL") return@mapNotNull null
                val node = edge.node ?: return@mapNotNull null
                val nodeId = node.id ?: return@mapNotNull null
                if (node.type?.name != "ANIME") return@mapNotNull null
                EpisodeCheckRelation(
                    anilistId = nodeId,
                    relationType = relType,
                    format = node.format?.name,
                    status = node.status?.name,
                    totalEpisodes = node.episodes,
                    airedEpisodes = airedCount(node.episodes, node.nextAiringEpisode?.episode),
                )
            }
        )
    }

    /** Вышедшие серии: у онгоингов episodes=null → берём nextAiring-1. */
    private fun airedCount(episodes: Int?, nextAiringEpisode: Int?): Int {
        val declared = episodes ?: 0
        val airing = nextAiringEpisode?.let { (it - 1).coerceAtLeast(0) } ?: 0
        // Онгоинг: declared=0/итог, airing — фактический прогресс. Берём максимум надёжного:
        // если сериал ещё идёт, вышло airing; если закончился — declared.
        return if (nextAiringEpisode != null) airing else declared
    }

    /**
     * Явные названия по AniList id и/или MAL id (idMal) — для обогащения EN-названий.
     * Передай хотя бы один id; AniList игнорирует null-переменную.
     */
    suspend fun enrichTitlesByIds(anilistId: Int?, malId: Int?): Result<EnrichedTitles?> = runCatching {
        if (anilistId == null && malId == null) return@runCatching null
        withContext(Dispatchers.IO) {
            rateLimiter.acquire()
            val response = apolloClient.query(
                EnrichTitlesByIdQuery(
                    id = Optional.presentIfNotNull(anilistId),
                    idMal = Optional.presentIfNotNull(malId),
                )
            ).execute()
            response.throwIfGraphQlErrors("EnrichTitlesById")
            val media = response.data?.Media ?: return@withContext null
            EnrichedTitles(
                anilistId = media.id,
                malId = media.idMal,
                romaji = media.title?.romaji,
                english = media.title?.english,
                native = media.title?.native,
            )
        }
    }

    /** Явные названия по поиску (для сопоставления, когда id нет). */
    suspend fun enrichTitlesBySearch(query: String, limit: Int = 8): Result<List<EnrichedTitles>> = runCatching {
        if (query.isBlank()) return@runCatching emptyList()
        withContext(Dispatchers.IO) {
            rateLimiter.acquire()
            val response = apolloClient.query(
                EnrichTitlesSearchQuery(
                    q = Optional.present(query),
                    perPage = Optional.present(limit),
                )
            ).execute()
            response.throwIfGraphQlErrors("EnrichTitlesSearch")
            response.data?.Page?.media?.filterNotNull()?.map { media ->
                EnrichedTitles(
                    anilistId = media.id,
                    malId = media.idMal,
                    romaji = media.title?.romaji,
                    english = media.title?.english,
                    native = media.title?.native,
                )
            }.orEmpty()
        }
    }

    suspend fun findTotalEpisodes(query: String): Result<Pair<Int, String>?> = runCatching {
        withContext(Dispatchers.IO) {
            rateLimiter.acquire()
            val response = apolloClient.query(SearchMediaQuery(q = Optional.presentIfNotNull(query))).execute()
            response.throwIfGraphQlErrors("SearchMedia findTotal")
            val media = response.data?.Media ?: return@withContext null
            val title = media.title
            val romaji = title?.romaji?.orEmpty() ?: ""
            val english = title?.english?.orEmpty() ?: ""
            if (!isTitleSimilar(query, romaji, english)) return@withContext null

            var count = media.episodes ?: 0
            media.nextAiringEpisode?.let { count = (it.episode ?: 0) - 1 }
            if (count > 0) count to "AniList" else null
        }
    }

    suspend fun getViewerId(accessToken: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            rateLimiter.acquire()
            val response = apolloClient.query(ViewerIdQuery())
                .addHttpHeader("Authorization", "Bearer $accessToken")
                .execute()
            response.throwIfGraphQlErrors("ViewerId")
            val id = response.data?.Viewer?.id
                ?: error("Viewer.id is null (token or rights?)")
            id
        }
    }

    /**
     * Все записи аниме-списка пользователя: MediaListCollection по chunk, плоский [AniListFlatListEntry].
     */
    suspend fun fetchAllAnimeListEntries(accessToken: String, userId: Int): Result<List<AniListFlatListEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val all = mutableListOf<AniListFlatListEntry>()
                var chunk = 1
                val perChunk = 50
                while (true) {
                    rateLimiter.acquire()
                    val response = apolloClient.query(
                        MediaListCollectionChunkQuery(
                            userId = Optional.present(userId),
                            chunk = Optional.present(chunk),
                            perChunk = Optional.present(perChunk)
                        )
                    )
                        .addHttpHeader("Authorization", "Bearer $accessToken")
                        .execute()
                    response.throwIfGraphQlErrors("MediaListCollection chunk=$chunk")
                    val col = response.data?.MediaListCollection
                        ?: error("MediaListCollection null")
                    val flat = col.lists?.filterNotNull().orEmpty().flatMap { group ->
                        (group.entries ?: emptyList()).filterNotNull().mapNotNull { e ->
                            val media = e.media ?: return@mapNotNull null
                            val mid = e.mediaId ?: media.id ?: return@mapNotNull null
                            val t = media.title
                            val romaji = t?.romaji.orEmpty()
                            val english = t?.english.orEmpty()
                            AniListFlatListEntry(
                                entryId = e.id,
                                mediaId = mid,
                                romaji = romaji,
                                english = english,
                                progress = e.progress ?: 0
                            )
                        }
                    }
                    all += flat
                    if (col.hasNextChunk != true) break
                    chunk++
                }
                all
            }
        }

    suspend fun saveMediaListEntry(
        accessToken: String,
        mediaId: Int,
        status: MediaListStatus,
        progress: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            rateLimiter.acquire()
            val response = apolloClient.mutation(
                SaveMediaListEntryMutation(
                    mediaId = Optional.present(mediaId),
                    status = Optional.present(status),
                    progress = Optional.present(progress)
                )
            )
                .addHttpHeader("Authorization", "Bearer $accessToken")
                .execute()
            response.throwIfGraphQlErrors("SaveMediaListEntry mediaId=$mediaId")
            if (response.data?.SaveMediaListEntry == null) {
                error("SaveMediaListEntry returned null data")
            }
        }
    }

    /**
     * Related-рекомендации для всех сидов ОДНИМ запросом (id_in батч).
     * Возвращает map: anilistId сида → его рекомендации.
     */
    suspend fun recommendationsForIds(ids: List<Int>, perSeed: Int = 12): Result<Map<Int, List<ApiSearchResult>>> = runCatching {
        if (ids.isEmpty()) return@runCatching emptyMap()
        withContext(Dispatchers.IO) {
            rateLimiter.acquire()
            val response = apolloClient.query(
                RecommendationsBatchQuery(
                    ids = Optional.present(ids),
                    perSeed = Optional.present(perSeed)
                )
            ).execute()
            response.throwIfGraphQlErrors("RecommendationsBatch")
            val mediaList = response.data?.Page?.media?.filterNotNull() ?: return@withContext emptyMap()
            mediaList.mapNotNull { seed ->
                val seedId = seed.id ?: return@mapNotNull null
                val recs = seed.recommendations?.nodes
                    ?.mapNotNull { node -> node?.mediaRecommendation?.toApiSearchResult() }
                    .orEmpty()
                seedId to recs
            }.toMap()
        }
    }

    /** Глобальный тренд для cold-start — один запрос. */
    suspend fun trendingAnime(limit: Int = 20): Result<List<ApiSearchResult>> = runCatching {
        withContext(Dispatchers.IO) {
            rateLimiter.acquire()
            val response = apolloClient.query(
                TrendingMediaQuery(
                    page = Optional.present(1),
                    perPage = Optional.present(limit)
                )
            ).execute()
            response.throwIfGraphQlErrors("TrendingMedia")
            response.data?.Page?.media?.filterNotNull()
                ?.mapNotNull { it.toApiSearchResult() }
                .orEmpty()
        }
    }

    private fun RecommendationsBatchQuery.MediaRecommendation.toApiSearchResult(): ApiSearchResult? {
        val romaji = title?.romaji.orEmpty()
        val english = title?.english.orEmpty()
        val displayTitle = english.ifEmpty { romaji }.ifEmpty { return null }
        return ApiSearchResult(
            title = displayTitle,
            altTitle = romaji.takeIf { it.isNotEmpty() && it != displayTitle },
            posterUrl = coverImage?.large,
            episodes = episodes ?: 0,
            description = description?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty(),
            type = type?.name ?: "ANIME",
            genres = genres?.filterNotNull().orEmpty(),
            rating = averageScore,
            source = "AniList",
            categoryType = "ANIME",
            externalId = id?.toString()
        )
    }

    private fun TrendingMediaQuery.Medium.toApiSearchResult(): ApiSearchResult? {
        val romaji = title?.romaji.orEmpty()
        val english = title?.english.orEmpty()
        val displayTitle = english.ifEmpty { romaji }.ifEmpty { return null }
        return ApiSearchResult(
            title = displayTitle,
            altTitle = romaji.takeIf { it.isNotEmpty() && it != displayTitle },
            posterUrl = coverImage?.large,
            episodes = episodes ?: 0,
            description = description?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty(),
            type = type?.name ?: "ANIME",
            genres = genres?.filterNotNull().orEmpty(),
            rating = averageScore,
            source = "AniList",
            categoryType = "ANIME",
            externalId = id?.toString()
        )
    }

    /** Обогащение аниме: точное время следующей серии, официальный трейлер, id MAL. */
    suspend fun titleEnrichment(id: Int): Result<AniListTitleEnrichment?> = runCatching {
        withContext(Dispatchers.IO) {
            rateLimiter.acquire()
            val response = apolloClient.query(TitleEnrichmentQuery(id = Optional.present(id))).execute()
            // Apollo 4 не бросает на сетевой ошибке и 429: ответ приходит с exception и пустыми data.
            // Без этой проверки сбой выглядел как «тайтла нет», и вызывающий запоминал пустоту.
            response.exception?.let { throw it }
            response.throwIfGraphQlErrors("TitleEnrichment")
            val media = response.data?.Media ?: return@withContext null
            AniListTitleEnrichment(
                anilistId = media.id,
                malId = media.idMal,
                status = media.status?.name,
                episodes = media.episodes,
                episodeDurationMin = media.duration,
                nextEpisode = media.nextAiringEpisode?.episode,
                nextAiringAtEpochSec = media.nextAiringEpisode?.airingAt?.toLong(),
                trailerSite = media.trailer?.site,
                trailerId = media.trailer?.id,
                trailerThumbnail = media.trailer?.thumbnail,
            )
        }
    }

    private fun <T : com.apollographql.apollo.api.Operation.Data> ApolloResponse<T>.throwIfGraphQlErrors(operation: String) {
        val errs = errors
        if (!errs.isNullOrEmpty()) {
        val msg = errs.joinToString { it.message ?: "" }
            Log.w(TAG, "GraphQL errors ($operation): $msg")
            error(msg)
        }
    }
}

/** Поля AniList для обогащения; время — секунды эпохи, как отдаёт AniList. */
data class AniListTitleEnrichment(
    val anilistId: Int,
    val malId: Int?,
    val status: String?,
    val episodes: Int?,
    val episodeDurationMin: Int?,
    val nextEpisode: Int?,
    val nextAiringAtEpochSec: Long?,
    val trailerSite: String?,
    val trailerId: String?,
    val trailerThumbnail: String?,
)
