package com.example.myapplication.network

import com.example.myapplication.network.dto.ShikimoriImageDto
import com.example.myapplication.network.dto.ShikimoriSearchItemDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
/**
 * Type-safe remote data source for Shikimori API.
 */
class ShikimoriRemoteDataSource(
    private val client: HttpClient,
    private val burstRate: TokenBucketRateLimiter
) {

    suspend fun fetchAnimeDetails(query: String): Result<AnimeDetails> = runCatching {
        val searchResponse = client.get("https://shikimori.one/api/animes") {
            parameter("search", query.trim())
            parameter("limit", "1")
        }.body<List<ShikimoriSearchItemDto>>()

        val first = searchResponse.firstOrNull()
            ?: throw NoSuchElementException("Anime not found: $query")

        if (!isTitleSimilar(query, first.name, first.russian)) {
            throw NoSuchElementException("Title mismatch: $query")
        }

        burstRate.acquire()
        val full = client.get("https://shikimori.one/api/animes/${first.id}").body<ShikimoriSearchItemDto>()
        full.toDomain()
    }

    suspend fun searchAnime(query: String, limit: Int = 20, language: AppLanguage = AppLanguage.EN): Result<List<ApiSearchResult>> = runCatching {
        val searchResponse = client.get("https://shikimori.one/api/animes") {
            parameter("search", query.trim())
            parameter("limit", limit)
        }.body<List<ShikimoriSearchItemDto>>()
        searchResponse.map { it.toApiSearchResult(language) }
    }

    suspend fun searchManga(query: String, limit: Int = 20, language: AppLanguage = AppLanguage.EN): Result<List<ApiSearchResult>> = runCatching {
        val searchResponse = client.get("https://shikimori.one/api/mangas") {
            parameter("search", query.trim())
            parameter("limit", limit)
        }.body<List<ShikimoriSearchItemDto>>()
        searchResponse.map { it.toApiSearchResult(language) }
    }

    suspend fun findTotalEpisodes(query: String): Result<Pair<Int, String>?> = runCatching {
        val searchResponse = client.get("https://shikimori.one/api/animes") {
            parameter("search", query.trim())
            parameter("limit", "1")
        }.body<List<ShikimoriSearchItemDto>>()

        val first = searchResponse.firstOrNull() ?: return@runCatching null
        if (!isTitleSimilar(query, first.name, first.russian)) return@runCatching null
        val episodes = first.episodesAired ?: first.episodes ?: 0
        if (episodes > 0) episodes to "Shikimori" else null
    }

    /** Похожие тайтлы по id — источник related-кандидатов для рекомендаций. */
    suspend fun getSimilarAnime(id: Int, language: AppLanguage = AppLanguage.EN): Result<List<ApiSearchResult>> = runCatching {
        burstRate.acquire()
        val response = client.get("https://shikimori.one/api/animes/$id/similar")
            .body<List<ShikimoriSearchItemDto>>()
        response.map { it.toApiSearchResult(language) }
    }

    suspend fun getAnimeById(id: Int, language: AppLanguage = AppLanguage.EN): Result<ApiSearchResult?> = runCatching {
        burstRate.acquire()
        val full = client.get("https://shikimori.one/api/animes/$id").body<ShikimoriSearchItemDto>()
        full.toApiSearchResult(language)
    }

    /**
     * Обогащение аниме (.scratch/sources-expansion): время следующей серии, какие студии озвучивают
     * и сабят (RU-трек выхода), промо-ролики и русское лицензионное название. Та же ручка, что и
     * карточка, — отдельный разбор полей, которых нет в DTO поиска.
     */
    suspend fun animeEnrichment(id: Int): Result<com.example.myapplication.network.enrichment.ShikimoriEnrichment?> = runCatching {
        burstRate.acquire()
        val body = client.get("https://shikimori.one/api/animes/$id").bodyAsText()
        com.example.myapplication.network.enrichment.ShikimoriEnrichmentParser.parse(body)
    }

    /** Явное русское название по Shikimori id (обратное обогащение, Stage 10). */
    suspend fun enrichRussianById(id: Int): Result<EnrichedTitles?> = runCatching {
        burstRate.acquire()
        val full = client.get("https://shikimori.one/api/animes/$id").body<ShikimoriSearchItemDto>()
        full.toEnrichedTitles()
    }

    /** Явные русские названия по поиску (для сопоставления, когда shikimori_id нет). */
    suspend fun enrichRussianBySearch(query: String, limit: Int = 8): Result<List<EnrichedTitles>> = runCatching {
        if (query.isBlank()) return@runCatching emptyList()
        val response = client.get("https://shikimori.one/api/animes") {
            parameter("search", query.trim())
            parameter("limit", limit)
        }.body<List<ShikimoriSearchItemDto>>()
        response.map { it.toEnrichedTitles() }
    }

    /**
     * Shikimori при отсутствии обложки отдаёт HTTP 200 с картинкой-заглушкой
     * (путь вида /assets/globals/missing_original.jpg). Технически URL валиден,
     * поэтому детектим по подстроке и возвращаем null — тогда постер возьмёт
     * следующий источник в иерархии.
     */
    private fun ShikimoriImageDto?.realPosterUrl(): String? {
        val path = this?.original?.takeIf { it.isNotBlank() } ?: return null
        if (path.contains("missing", ignoreCase = true) ||
            path.contains("no-cover", ignoreCase = true)
        ) return null
        return if (path.startsWith("http")) path else "https://shikimori.one$path"
    }

    private fun ShikimoriSearchItemDto.toEnrichedTitles(): EnrichedTitles = EnrichedTitles(
        shikimoriId = id,
        malId = malId,
        romaji = name?.takeIf { it.isNotBlank() },
        russian = russian?.takeIf { it.isNotBlank() },
    )

    private fun ShikimoriSearchItemDto.toApiSearchResult(language: AppLanguage): ApiSearchResult {
        val desc = description
            ?.replace(Regex("\\[.*?\\]"), "")
            ?.replace(Regex("<[^>]+>"), "")
            ?.trim() ?: ""
        val posterUrl = image.realPosterUrl()
        val nameVal = name ?: "Unknown"
        val russianVal = russian?.takeIf { it.isNotBlank() }
        val (title, altTitle) = when (language) {
            AppLanguage.RU -> (russianVal ?: nameVal) to (if (russianVal != null) nameVal else null)
            AppLanguage.EN -> nameVal to russianVal
        }
        return ApiSearchResult(
            title = title,
            altTitle = altTitle,
            posterUrl = posterUrl,
            episodes = episodesAired ?: episodes ?: 0,
            description = desc,
            type = kind ?: "",
            genres = genres?.mapNotNull { it.russian ?: it.name } ?: emptyList(),
            rating = score?.toFloatOrNull()?.toInt(),
            source = "Shikimori",
            categoryType = "ANIME",
            externalId = id.toString(),
            malId = malId,
            isOngoing = status?.equals("ongoing", ignoreCase = true),
            airedEpisodes = episodesAired?.takeIf { it > 0 },
            totalEpisodes = episodes?.takeIf { it > 0 },
            statusRaw = status?.takeIf { it.isNotBlank() },
            format = kind?.takeIf { it.isNotBlank() },
            studio = mainStudio(),
            season = airedOn?.let { seasonOf(it) },
            seasonYear = airedOn?.let { seasonYearOf(it) },
        )
    }

    /**
     * Первоисточника Shikimori не отдаёт вообще — карточка «Источник» в RU-режиме остаётся
     * пустой, пока тайтл не найден на AniList. Студия есть только в detail-ответе.
     */
    private fun ShikimoriSearchItemDto.mainStudio(): String? =
        studios?.firstNotNullOfOrNull { it.filteredName?.takeIf(String::isNotBlank) ?: it.name?.takeIf(String::isNotBlank) }

    /** `aired_on` в формате `2026-01-09` → сезон в кодах AniList, чтобы UI знал один словарь. */
    private fun seasonOf(airedOn: String): String? = when (monthOf(airedOn)) {
        12, 1, 2 -> "WINTER"
        3, 4, 5 -> "SPRING"
        6, 7, 8 -> "SUMMER"
        9, 10, 11 -> "FALL"
        else -> null
    }

    /** Декабрьский старт — это зима СЛЕДУЮЩЕГО года, как считает и AniList. */
    private fun seasonYearOf(airedOn: String): Int? {
        val year = airedOn.take(4).toIntOrNull() ?: return null
        return if (monthOf(airedOn) == 12) year + 1 else year
    }

    private fun monthOf(airedOn: String): Int? = airedOn.drop(5).take(2).toIntOrNull()

    private fun ShikimoriSearchItemDto.toDomain(): AnimeDetails {
        val desc = description
            ?.replace(Regex("\\[.*?\\]"), "")
            ?.replace(Regex("<[^>]+>"), "")
            ?.trim() ?: ""
        val posterUrl = image.realPosterUrl()
        return AnimeDetails(
            title = name ?: "Unknown",
            altTitle = russian?.takeIf { it.isNotBlank() },
            description = desc,
            type = kind ?: "",
            status = status ?: "",
            episodesAired = episodesAired ?: episodes ?: 0,
            episodesTotal = episodes,
            nextEpisode = null,
            genres = genres?.mapNotNull { it.russian ?: it.name } ?: emptyList(),
            rating = score?.toFloatOrNull()?.toInt(),
            posterUrl = posterUrl,
            source = "Shikimori",
            airedOn = airedOn,
            format = kind?.takeIf { it.isNotBlank() },
            studio = mainStudio(),
            season = airedOn?.let { seasonOf(it) },
            seasonYear = airedOn?.let { seasonYearOf(it) },
        )
    }
}
