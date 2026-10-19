package com.example.myapplication.manga.source

import com.example.myapplication.manga.domain.MangaChapter
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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * Inkstory (inkstory.net) — русскоязычный источник: манга, манхва, веб-комиксы.
 *
 * Публичный REST `api.inkstory.net/v2` отдаёт каталог, главы и страницы без авторизации — тем же
 * способом, которым их читает сам сайт. Особенности:
 * 1. Главы приходят одним списком по `bookId`, но у тайтла может быть несколько веток перевода
 *    (`branchId`); берём самую полную, как у Remanga, иначе список раздувается дублями;
 * 2. Платные главы помечены `donut` — открыть их без подписки нельзя, поэтому они остаются в
 *    списке закрытыми ([MangaChapter.paid]), страницы по ним не запрашиваются;
 * 3. Страницы — длинные вертикальные ленты (ширина 720 px), ридер показывает их как есть.
 */
class InkstorySource(
    private val client: HttpClient,
    private val rateLimiter: TokenBucketRateLimiter,
) : VetroMangaSource {

    override val id = MangaSourceId("inkstory")
    override val name = "Inkstory"
    override val language = "ru"

    override suspend fun search(query: String, page: Int): MangaSearchPage {
        if (page > 1) return MangaSearchPage(emptyList(), hasNextPage = false)
        val books: List<InkstoryBook> = request("$API/books") {
            parameter("search", query)
            parameter("ignoreUserScopedContentStatus", true)
            parameter("serviceName", SERVICE)
        }
        return MangaSearchPage(books.mapNotNull { it.toItem() }, hasNextPage = false)
    }

    override suspend fun details(manga: MangaItem): MangaDetails {
        val book = book(manga.key) ?: return MangaDetails(item = manga)
        return MangaDetails(
            item = book.toItem() ?: manga,
            description = book.description?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun chapters(manga: MangaItem): List<MangaChapter> {
        val book = book(manga.key) ?: return emptyList()
        val all: List<InkstoryChapter> = request("$API/chapters") { parameter("bookId", book.id) }
        val usable = all.filter { !it.corrupted }
        val branch = usable.groupBy { it.branchId }.maxByOrNull { it.value.size }?.value ?: return emptyList()
        return branch.map { it.toChapter(manga) }
    }

    override suspend fun pages(chapter: MangaChapter): List<MangaPage> {
        if (chapter.paid) return emptyList()
        val details: InkstoryChapterDetails = request("$API/chapters/${chapter.key}")
        return details.pages
            .sortedBy { it.index }
            .mapNotNull { page -> page.image?.takeIf { it.startsWith("http") } }
            .mapIndexed { position, url -> MangaPage(index = position, url = url, headers = IMAGE_HEADERS) }
    }

    private suspend fun book(slug: String): InkstoryBook? =
        runCatching { request<InkstoryBook>("$API/books/$slug") }.getOrNull()?.takeIf { it.id.isNotBlank() }

    private suspend inline fun <reified T> request(
        url: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): T {
        rateLimiter.acquire()
        return client.get(url) {
            headers {
                append("User-Agent", BROWSER_UA)
                append("Accept", "application/json, text/plain, */*")
                append("Origin", SITE)
                append("Referer", "$SITE/")
            }
            block()
        }.body()
    }

    private fun InkstoryBook.toItem(): MangaItem? {
        val title = name?.ru?.takeIf { it.isNotBlank() }
            ?: name?.en?.takeIf { it.isNotBlank() }
            ?: name?.original?.takeIf { it.isNotBlank() }
            ?: return null
        val alt = listOfNotNull(name?.en, name?.original)
            .firstOrNull { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
        return MangaItem(
            sourceId = this@InkstorySource.id,
            key = slug.takeIf { it.isNotBlank() } ?: return null,
            title = title,
            altTitle = alt,
            coverUrl = poster?.takeIf { it.startsWith("http") },
            year = year?.takeIf { it > 0 },
            status = when (status?.uppercase()) {
                "ONGOING", "RELEASING" -> MangaStatus.Ongoing
                "DONE", "FINISHED", "COMPLETED" -> MangaStatus.Completed
                "HIATUS", "FROZEN", "PAUSED" -> MangaStatus.Hiatus
                "ABANDONED", "CANCELLED", "DROPPED" -> MangaStatus.Cancelled
                else -> MangaStatus.Unknown
            },
            languages = listOf("ru"),
        )
    }

    private fun InkstoryChapter.toChapter(manga: MangaItem): MangaChapter = MangaChapter(
        sourceId = this@InkstorySource.id,
        mangaKey = manga.key,
        key = id,
        number = number,
        volume = volume,
        title = (title ?: name)?.takeIf { it.isNotBlank() },
        language = "ru",
        paid = donut,
        publishedAt = createdAt?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        } ?: 0L,
    )

    private companion object {
        const val API = "https://api.inkstory.net/v2"
        const val SITE = "https://inkstory.net"
        const val SERVICE = "inkstory"
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val IMAGE_HEADERS = mapOf(
            "Referer" to "$SITE/",
            "User-Agent" to BROWSER_UA,
        )
    }
}

@Serializable
private data class InkstoryBook(
    val id: String = "",
    val slug: String = "",
    val poster: String? = null,
    val status: String? = null,
    val year: Int? = null,
    val description: String? = null,
    val name: InkstoryName? = null,
)

@Serializable
private data class InkstoryName(
    val en: String? = null,
    val ru: String? = null,
    val original: String? = null,
)

@Serializable
private data class InkstoryChapter(
    val id: String = "",
    val number: Double? = null,
    val volume: Double? = null,
    val name: String? = null,
    val title: String? = null,
    val donut: Boolean = false,
    val corrupted: Boolean = false,
    val branchId: String? = null,
    val createdAt: String? = null,
)

@Serializable
private data class InkstoryChapterDetails(
    val pages: List<InkstoryPage> = emptyList(),
)

@Serializable
private data class InkstoryPage(
    val image: String? = null,
    val index: Int = 0,
)
