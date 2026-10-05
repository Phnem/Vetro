package com.example.myapplication.manga.source

import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.manga.ja.AniListLookup
import com.example.myapplication.manga.domain.MangaDetails
import com.example.myapplication.manga.domain.MangaItem
import com.example.myapplication.manga.domain.MangaPage
import com.example.myapplication.manga.domain.MangaSearchPage
import com.example.myapplication.manga.domain.MangaSourceId
import com.example.myapplication.manga.domain.MangaStatus
import com.example.myapplication.manga.domain.VetroMangaSource
import com.example.myapplication.network.TokenBucketRateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * MangaDex: метаданные, список глав И сами страницы через официальный API — без скрейпинга и без
 * стороннего исполняемого кода.
 *
 * Ограничения API, которые видны наружу как отфильтрованные главы: записи с `externalUrl` лежат
 * не на MangaDex (официальные издатели) и внутри приложения нечитаемы, главы с `pages == 0`
 * отдают пустой `at-home` ответ. И то, и другое отсекаем здесь, чтобы ридер никогда не открывал
 * заведомо пустую главу.
 */
class MangaDexSource(
    private val client: HttpClient,
    private val rateLimiter: TokenBucketRateLimiter,
) : VetroMangaSource, AniListLookup {

    override val id = MangaSourceId("mangadex")
    override val name = "MangaDex"
    override val language = "multi"

    override suspend fun search(query: String, page: Int): MangaSearchPage {
        val offset = (page - 1).coerceAtLeast(0) * SEARCH_LIMIT
        val response: MangaListResponse = request("$API/manga") {
            parameter("title", query)
            parameter("limit", SEARCH_LIMIT)
            parameter("offset", offset)
            parameter("order[relevance]", "desc")
            parameter("includes[]", "cover_art")
            contentRatings()
        }
        val items = response.data.map { it.toItem() }
        return MangaSearchPage(
            items = items,
            hasNextPage = offset + items.size < response.total,
        )
    }

    /**
     * Тайтл MangaDex с этим номером AniList. Названия не сравниваются: у MangaDex в карточке лежит
     * ссылка на AniList (`links.al`), и совпасть по ней может только та же самая манга, а не тёзка.
     * Ищем по каждому из [titles], пока не попадём; тайтл без такой ссылки так не находится.
     */
    override suspend fun findByAniList(aniListId: Int, titles: List<String>): MangaItem? {
        val wanted = aniListId.toString()
        for (title in titles.filter { it.isNotBlank() }.distinct()) {
            val response: MangaListResponse = request("$API/manga") {
                parameter("title", title)
                parameter("limit", SEARCH_LIMIT)
                parameter("order[relevance]", "desc")
                parameter("includes[]", "cover_art")
                contentRatings()
            }
            response.data.firstOrNull { it.attributes.links["al"] == wanted }?.let { return it.toItem() }
        }
        return null
    }

    override suspend fun details(manga: MangaItem): MangaDetails {
        val response: MangaEntityResponse = request("$API/manga/${manga.key}") {
            parameter("includes[]", "cover_art")
            parameter("includes[]", "author")
            parameter("includes[]", "artist")
        }
        val dto = response.data
        val attributes = dto.attributes
        return MangaDetails(
            item = dto.toItem(),
            description = attributes.description.preferred(),
            tags = attributes.tags.mapNotNull { it.attributes.name.preferred() }.distinct(),
            authors = dto.relationships
                .filter { it.type == "author" || it.type == "artist" }
                .mapNotNull { it.attributes?.name }
                .distinct(),
        )
    }

    /**
     * Фид отдаёт максимум 500 глав за запрос — забираем страницами, пока не выберем `total`.
     * Верхняя граница [MAX_CHAPTERS] защищает от тайтлов-монстров (Kingdom, One Piece + все языки),
     * на которых иначе уходит десяток запросов ради списка, который никто не прокрутит.
     */
    override suspend fun chapters(manga: MangaItem): List<MangaChapter> {
        val collected = mutableListOf<MangaChapter>()
        var offset = 0
        while (offset < MAX_CHAPTERS) {
            val response: ChapterListResponse = request("$API/manga/${manga.key}/feed") {
                parameter("limit", FEED_LIMIT)
                parameter("offset", offset)
                parameter("order[volume]", "asc")
                parameter("order[chapter]", "asc")
                parameter("includes[]", "scanlation_group")
                LANGUAGES.forEach { parameter("translatedLanguage[]", it) }
                contentRatings()
            }
            collected += response.data.mapNotNull { it.toChapter(manga) }
            offset += FEED_LIMIT
            if (offset >= response.total || response.data.isEmpty()) break
        }
        return collected
    }

    override suspend fun pages(chapter: MangaChapter): List<MangaPage> {
        val response: AtHomeResponse = request("$API/at-home/server/${chapter.key}")
        val base = response.baseUrl.trimEnd('/')
        val hash = response.chapter.hash
        return response.chapter.data.mapIndexed { index, file ->
            MangaPage(index = index, url = "$base/data/$hash/$file")
        }
    }

    private suspend inline fun <reified T> request(
        url: String,
        crossinline block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        // Общий лимит MangaDex — 5 req/s на IP; идём заведомо мягче, глав всё равно грузим пачками.
        rateLimiter.acquire()
        return client.get(url) { block() }.body()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.contentRatings() {
        CONTENT_RATINGS.forEach { parameter("contentRating[]", it) }
    }

    private fun MangaDto.toItem(): MangaItem {
        val cover = relationships.firstOrNull { it.type == "cover_art" }?.attributes?.fileName
        return MangaItem(
            sourceId = this@MangaDexSource.id,
            key = this.id,
            title = attributes.title.preferred() ?: attributes.altTitles.firstNotNullOfOrNull { it.preferred() }.orEmpty(),
            altTitle = attributes.altTitles.firstNotNullOfOrNull { it["ru"] }
                ?: attributes.altTitles.firstNotNullOfOrNull { it["en"] },
            coverUrl = cover?.let { "$UPLOADS/covers/${this.id}/$it.512.jpg" },
            year = attributes.year,
            status = when (attributes.status?.lowercase()) {
                "ongoing" -> MangaStatus.Ongoing
                "completed" -> MangaStatus.Completed
                "hiatus" -> MangaStatus.Hiatus
                "cancelled" -> MangaStatus.Cancelled
                else -> MangaStatus.Unknown
            },
            languages = attributes.availableTranslatedLanguages.filterNotNull(),
        )
    }

    private fun ChapterDto.toChapter(manga: MangaItem): MangaChapter? {
        if (!attributes.externalUrl.isNullOrBlank()) return null
        if (attributes.pages <= 0) return null
        return MangaChapter(
            sourceId = this@MangaDexSource.id,
            mangaKey = manga.key,
            key = this.id,
            number = attributes.chapter?.toDoubleOrNull(),
            volume = attributes.volume?.toDoubleOrNull(),
            title = attributes.title?.takeIf { it.isNotBlank() },
            language = attributes.translatedLanguage,
            scanlator = relationships.firstOrNull { it.type == "scanlation_group" }?.attributes?.name,
            pageCount = attributes.pages,
            publishedAt = attributes.publishAt.toEpochMillisOrZero(),
        )
    }

    /** RU важнее EN: коллекция и UI Vetro русскоязычные по умолчанию. */
    private fun Map<String, String>.preferred(): String? =
        this["ru"] ?: this["en"] ?: this["ja-ro"] ?: values.firstOrNull()

    private fun String?.toEpochMillisOrZero(): Long =
        this?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L

    private companion object {
        const val API = "https://api.mangadex.org"
        const val UPLOADS = "https://uploads.mangadex.org"
        const val SEARCH_LIMIT = 20
        const val FEED_LIMIT = 500
        const val MAX_CHAPTERS = 2000
        val LANGUAGES = listOf("ru", "en")
        /** Порнографию не запрашиваем вовсе — не «фильтруем после», а не тянем с сервера. */
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
private data class MangaEntityResponse(val data: MangaDto)

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
    val year: Int? = null,
    val status: String? = null,
    val tags: List<TagDto> = emptyList(),
    val availableTranslatedLanguages: List<String?> = emptyList(),
    /** Ссылки на другие каталоги: `al` - номер в AniList, `mal` - в MyAnimeList. */
    val links: Map<String, String> = emptyMap(),
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
private data class RelationshipAttributesDto(
    val fileName: String? = null,
    val name: String? = null,
)

@Serializable
private data class ChapterListResponse(
    val data: List<ChapterDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
private data class ChapterDto(
    val id: String,
    val attributes: ChapterAttributesDto,
    val relationships: List<RelationshipDto> = emptyList(),
)

@Serializable
private data class ChapterAttributesDto(
    val volume: String? = null,
    val chapter: String? = null,
    val title: String? = null,
    val translatedLanguage: String? = null,
    val externalUrl: String? = null,
    val pages: Int = 0,
    val publishAt: String? = null,
)

@Serializable
private data class AtHomeResponse(
    val baseUrl: String,
    @SerialName("chapter") val chapter: AtHomeChapterDto,
)

@Serializable
private data class AtHomeChapterDto(
    val hash: String,
    val data: List<String> = emptyList(),
    val dataSaver: List<String> = emptyList(),
)
