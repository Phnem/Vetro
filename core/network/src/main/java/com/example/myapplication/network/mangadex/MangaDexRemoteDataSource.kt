package com.example.myapplication.network.mangadex

import com.example.myapplication.utils.runSuspendCatching
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.TokenBucketRateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

/**
 * MangaDex как источник ПОИСКА для коллекции.
 *
 * Отдельный класс от `manga.source.MangaDexSource` намеренно: тот живёт в `:app`, говорит на
 * языке ридера (`MangaItem`/`MangaChapter`) и умеет главы со страницами, а поиску коллекции нужен
 * только `ApiSearchResult` из `:core:network`, куда `:app` не виден. Общий у них ровно один
 * публичный REST API и ничего больше — вытаскивать общий слой ради двух запросов дороже, чем
 * держать их врозь.
 */
class MangaDexRemoteDataSource(
    private val client: HttpClient,
    private val rateLimiter: TokenBucketRateLimiter,
) {

    /**
     * Поиск манги по названию. Ошибки глушим списком: источник — один из нескольких в выдаче
     * манги, и упавший MangaDex не должен уносить с собой Remanga и Shikimori.
     */
    suspend fun searchManga(
        query: String,
        limit: Int = SEARCH_LIMIT,
        language: AppLanguage = AppLanguage.EN,
    ): List<ApiSearchResult> = runSuspendCatching {
        rateLimiter.acquire()
        val response: MangaListResponse = client.get("$API/manga") {
            parameter("title", query.trim())
            parameter("limit", limit.coerceIn(1, SEARCH_LIMIT))
            parameter("order[relevance]", "desc")
            parameter("includes[]", "cover_art")
            // Порнографию не запрашиваем вовсе — не «фильтруем после», а не тянем с сервера.
            CONTENT_RATINGS.forEach { parameter("contentRating[]", it) }
        }.body()
        response.data.mapNotNull { it.toSearchResult(language) }
    }.getOrElse { emptyList() }

    private fun MangaDto.toSearchResult(language: AppLanguage): ApiSearchResult? {
        val attributes = attributes
        val title = attributes.title.preferred(language)
            ?: attributes.altTitles.firstNotNullOfOrNull { it.preferred(language) }
            ?: return null
        // «Вариация» — название на ДРУГОМ языке, чем основное: иначе в карточке дублируется строка.
        val altTitle = attributes.altTitles
            .firstNotNullOfOrNull { it.preferred(language.other()) }
            ?.takeIf { !it.equals(title, ignoreCase = true) }
        val cover = relationships.firstOrNull { it.type == "cover_art" }?.attributes?.fileName
        return ApiSearchResult(
            title = title,
            altTitle = altTitle,
            posterUrl = cover?.let { "$UPLOADS/covers/$id/$it.512.jpg" },
            // Число глав MangaDex в карточке тайтла не отдаёт — только номер последней. Для
            // «сколько прочитано из скольких» этого достаточно, а лишний запрос на фид глав
            // ради каждой строки выдачи поиска стоил бы секунд.
            episodes = attributes.lastChapter?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: 0,
            description = attributes.description.preferred(language).orEmpty(),
            type = "Manga",
            genres = attributes.tags.mapNotNull { it.attributes.name.preferred(language) }.distinct(),
            // Рейтинг живёт в отдельном /statistics — на выдачу поиска это ещё один запрос на тайтл.
            rating = null,
            source = SOURCE,
            categoryType = "MANGA",
            externalId = id,
            statusRaw = attributes.status,
            isOngoing = attributes.status?.equals("ongoing", ignoreCase = true),
            format = "MANGA",
            seasonYear = attributes.year,
        )
    }

    /** Язык интерфейса важнее прочих, но пустой строкой лучше не отдавать ничего. */
    private fun Map<String, String>.preferred(language: AppLanguage): String? {
        val ordered = when (language) {
            AppLanguage.RU -> listOf("ru", "en", "ja-ro")
            AppLanguage.EN -> listOf("en", "ja-ro", "ru")
        }
        return ordered.firstNotNullOfOrNull { this[it]?.takeIf(String::isNotBlank) }
            ?: values.firstOrNull { it.isNotBlank() }
    }

    private fun AppLanguage.other(): AppLanguage =
        if (this == AppLanguage.RU) AppLanguage.EN else AppLanguage.RU

    private companion object {
        const val API = "https://api.mangadex.org"
        const val UPLOADS = "https://uploads.mangadex.org"
        const val SOURCE = "MangaDex"
        const val SEARCH_LIMIT = 20
        val CONTENT_RATINGS = listOf("safe", "suggestive", "erotica")
    }
}

// ---- DTO ----------------------------------------------------------------------------------

@Serializable
private data class MangaListResponse(
    val data: List<MangaDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
private data class MangaDto(
    val id: String,
    val attributes: MangaAttributesDto,
    val relationships: List<RelationshipDto> = emptyList(),
)

@Serializable
private data class MangaAttributesDto(
    val title: Map<String, String> = emptyMap(),
    val altTitles: List<Map<String, String>> = emptyList(),
    val description: Map<String, String> = emptyMap(),
    val lastChapter: String? = null,
    val year: Int? = null,
    val status: String? = null,
    val tags: List<TagDto> = emptyList(),
)

@Serializable
private data class TagDto(val attributes: TagAttributesDto)

@Serializable
private data class TagAttributesDto(val name: Map<String, String> = emptyMap())

@Serializable
private data class RelationshipDto(
    val id: String,
    val type: String,
    val attributes: RelationshipAttributesDto? = null,
)

@Serializable
private data class RelationshipAttributesDto(val fileName: String? = null)
