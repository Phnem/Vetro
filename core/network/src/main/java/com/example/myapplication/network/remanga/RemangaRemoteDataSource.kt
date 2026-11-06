package com.example.myapplication.network.remanga

import com.example.myapplication.network.AnimeDetails
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.remanga.dto.ImageDto
import com.example.myapplication.network.remanga.dto.RemangaPageV2
import com.example.myapplication.network.remanga.dto.TitleV2Dto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class RemangaRemoteDataSource(
    private val httpClient: HttpClient
) {
    private val baseUrl = "https://api.remanga.org/api"
    private val mediaBaseUrl = "https://remanga.org"

    /**
     * Поиск в каталоге. API v2: v1 (`/search/`, `/titles/{dir}/`) с осени 2026 отвечает 404, и
     * Remanga молча выпадала из поиска манги — а она в нём первый источник.
     */
    suspend fun searchManga(query: String, limit: Int = 30): List<ApiSearchResult> = coroutineScope {
        try {
            val response: RemangaPageV2<TitleV2Dto> = httpClient.get("$baseUrl/v2/search/") {
                parameter("query", query)
                parameter("count", 30)
                remangaHeaders()
            }.body()

            val bestMatches = response.results
                .sortedWith(
                    compareByDescending<TitleV2Dto> { calculateRelevanceScore(it, query) }
                        .thenByDescending { it.avgRating ?: 0.0 }
                )
                .filter { calculateRelevanceScore(it, query) > 0 }
                .take(limit)

            // Описание и жанры есть только в карточке тайтла - догружаем её для первых совпадений.
            bestMatches.mapNotNull { item ->
                val dir = item.dir ?: return@mapNotNull null
                try {
                    mapToResult(titleV2(dir) ?: item)
                } catch (e: Exception) {
                    e.printStackTrace()
                    mapToResult(item)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getMangaDetails(slug: String): AnimeDetails? = withContext(Dispatchers.IO) {
        try {
            val dto = titleV2(slug) ?: return@withContext null
            AnimeDetails(
                title = dto.mainName ?: dto.secondaryName ?: "Unknown",
                altTitle = dto.secondaryName,
                posterUrl = buildPosterUrl(dto.cover, dto.dir),
                episodesAired = dto.countChapters ?: 0,
                episodesTotal = null,
                nextEpisode = null,
                description = dto.description ?: "",
                type = "Manga",
                genres = dto.genres?.mapNotNull { it.name } ?: emptyList(),
                rating = ratingToInt10(dto.avgRating),
                source = "Remanga",
                status = dto.status?.name ?: "Unknown"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun titleV2(dir: String): TitleV2Dto? =
        httpClient.get("$baseUrl/v2/titles/$dir/") { remangaHeaders() }
            .body<TitleV2Dto>()
            .takeIf { it.dir != null }

    private fun mapToResult(dto: TitleV2Dto): ApiSearchResult = ApiSearchResult(
        title = dto.mainName ?: dto.secondaryName ?: "Unknown",
        altTitle = dto.secondaryName,
        posterUrl = buildPosterUrl(dto.cover, dto.dir),
        episodes = dto.countChapters ?: 0,
        description = dto.description ?: "",
        type = "Manga",
        genres = dto.genres?.mapNotNull { it.name } ?: emptyList(),
        rating = ratingToInt10(dto.avgRating),
        source = "Remanga",
        categoryType = "MANGA",
        externalId = dto.dir ?: dto.id?.toString()
    )

    private fun buildPosterUrl(img: ImageDto?, dir: String?): String =
        mediaBaseUrl + (img?.high ?: img?.mid ?: img?.low ?: dir?.let { "/media/titles/$it/cover.jpg" } ?: "")

    private fun ratingToInt10(avgRating: Double?): Int? =
        avgRating?.times(10)?.toInt()

    private fun io.ktor.client.request.HttpRequestBuilder.remangaHeaders() {
        headers {
            append("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            append("Accept", "application/json, text/plain, */*")
            append("Origin", "https://remanga.org")
            append("Referer", "https://remanga.org/")
        }
    }

    private fun calculateRelevanceScore(title: TitleV2Dto, query: String): Int {
        val q = query.lowercase()
        val rus = (title.mainName ?: "").lowercase()
        val en = (title.secondaryName ?: "").lowercase()

        if (rus == q || en == q) return 100
        if (rus.startsWith(q) || en.startsWith(q)) return 75

        val paddedRus = " $rus "
        val paddedEn = " $en "
        val paddedQ = " $q "

        if (paddedRus.contains(paddedQ) || paddedEn.contains(paddedQ)) return 50
        if (rus.contains(q) || en.contains(q)) return 25

        return 0
    }
}
