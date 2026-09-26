package com.example.myapplication.network.enrichment

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.TokenBucketRateLimiter
import com.phnem.vetro.network.BuildConfig
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

private fun JsonObject.strings(name: String): List<String> = when (val e = get(name)) {
    is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
    is JsonPrimitive -> listOfNotNull(e.content.trim().takeIf(String::isNotEmpty))
    else -> emptyList()
}

private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull

// ---------- Open Library ----------

/** Произведение (Work) Open Library — не издание. У «三体» 44 издания, среди них «Задача трёх тел». */
data class BookWork(
    val key: String,
    val title: String,
    val authors: List<String>,
    val firstPublishYear: Int?,
    val editionCount: Int,
    val coverId: Int?,
    val subjects: List<String>,
    val languages: List<String>,
    /**
     * Издание, по которому совпал запрос (поиск `q=` отдаёт его вместе с произведением): у «Задача
     * трёх тел» — русское издание с ISBN и обложкой, у самого произведения название «三体».
     */
    val matchedEdition: BookEdition? = null,
)

/** Описание и темы произведения (`/works/{id}.json`). */
data class BookWorkDetails(val description: String?, val subjects: List<String>)

/** Издание: ISBN, язык, издатель, дата. */
data class BookEdition(
    val key: String,
    val title: String,
    val isbn13: List<String>,
    val isbn10: List<String>,
    /** ISO 639-2/B: `rus`, `eng`, `fre`… */
    val languages: List<String>,
    val publishers: List<String>,
    val publishDate: String?,
    val coverIds: List<Int>,
)

class OpenLibraryClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
) {
    /**
     * Поиск произведения по названию любого его издания. Общий `q=` индексирует названия изданий:
     * «Задача трёх тел» находит «三体», а `title=`+`author=` — нет (автор записан «刘慈欣»). Автор в
     * запрос не идёт — транслитерации расходятся; по нему ранжирует вызывающий. В ответе у каждого
     * произведения — совпавшее издание (`editions.*`).
     */
    suspend fun searchWorks(title: String, limit: Int = 5): LookupResult<List<BookWork>> {
        val q = "q=" + enc(title)
        return http.text("Open Library", "$BASE/search.json?$q&fields=$SEARCH_FIELDS&limit=$limit", rate,
            policy = CachePolicy("openlibrary:q:$q:$limit", 30 * CacheTtl.DAY, 7 * CacheTtl.DAY))
            .parse { OpenLibraryParser.works(it).takeIf(List<BookWork>::isNotEmpty) }
    }

    suspend fun work(workKey: String): LookupResult<BookWorkDetails> =
        http.text("Open Library", "$BASE${workKey.ensureWorksPath()}.json", rate, notFoundById = true,
            policy = CachePolicy("openlibrary:work:$workKey", 30 * CacheTtl.DAY, 7 * CacheTtl.DAY))
            .parse(OpenLibraryParser::work)

    suspend fun editions(workKey: String, limit: Int = 50): LookupResult<List<BookEdition>> =
        http.text("Open Library", "$BASE${workKey.ensureWorksPath()}/editions.json?limit=$limit", rate, notFoundById = true,
            policy = CachePolicy("openlibrary:editions:$workKey:$limit", 30 * CacheTtl.DAY, 7 * CacheTtl.DAY))
            .parse(OpenLibraryParser::editions)

    private fun String.ensureWorksPath() = if (startsWith("/works/")) this else "/works/$this"

    companion object {
        private const val BASE = "https://openlibrary.org"
        private const val SEARCH_FIELDS = "key,title,author_name,first_publish_year,edition_count,cover_i,subject,language," +
            "editions,editions.key,editions.title,editions.isbn,editions.language,editions.cover_i," +
            "editions.publisher,editions.publish_date"

        fun coverUrl(coverId: Int, size: Char = 'L') = "https://covers.openlibrary.org/b/id/$coverId-$size.jpg"
    }
}

internal object OpenLibraryParser {
    fun works(body: String): List<BookWork> =
        ((EnrichmentJson.parseToJsonElement(body).jsonObject["docs"]) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            BookWork(
                key = o.str("key") ?: return@mapNotNull null,
                title = o.str("title") ?: return@mapNotNull null,
                authors = o.strings("author_name"),
                firstPublishYear = o.int("first_publish_year"),
                editionCount = o.int("edition_count") ?: 0,
                coverId = o.int("cover_i"),
                subjects = o.strings("subject").take(12),
                languages = o.strings("language"),
                matchedEdition = ((o["editions"] as? JsonObject)?.get("docs") as? JsonArray)
                    ?.firstOrNull()?.let { it as? JsonObject }?.let(::searchEdition),
            )
        }

    /** Издание из ответа поиска: ISBN там одним списком (10 и 13 вперемешку), даты — списком. */
    private fun searchEdition(o: JsonObject): BookEdition? {
        val isbns = o.strings("isbn")
        return BookEdition(
            key = o.str("key") ?: return null,
            title = o.str("title").orEmpty(),
            isbn13 = isbns.filter { it.length == 13 },
            isbn10 = isbns.filter { it.length == 10 },
            languages = o.strings("language"),
            publishers = o.strings("publisher"),
            publishDate = o.strings("publish_date").firstOrNull(),
            coverIds = listOfNotNull(o.int("cover_i")?.takeIf { it > 0 }),
        )
    }

    fun work(body: String): BookWorkDetails {
        val o = EnrichmentJson.parseToJsonElement(body).jsonObject
        // description бывает строкой или объектом {type, value}.
        val description = when (val d = o["description"]) {
            is JsonPrimitive -> d.contentOrNull
            is JsonObject -> d.str("value")
            else -> null
        }?.replace("\r\n", "\n")?.trim()?.takeIf { it.isNotEmpty() }
        return BookWorkDetails(description, o.strings("subjects").take(12))
    }

    fun editions(body: String): List<BookEdition> =
        ((EnrichmentJson.parseToJsonElement(body).jsonObject["entries"]) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            BookEdition(
                key = o.str("key") ?: return@mapNotNull null,
                title = o.str("title").orEmpty(),
                isbn13 = o.strings("isbn_13"),
                isbn10 = o.strings("isbn_10"),
                languages = (o["languages"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.str("key")?.substringAfterLast('/') },
                publishers = o.strings("publishers"),
                publishDate = o.str("publish_date"),
                coverIds = (o["covers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }.filter { it > 0 },
            )
        }
}

// ---------- Google Books ----------

data class GoogleVolume(
    val id: String,
    val title: String,
    val authors: List<String>,
    val description: String?,
    val categories: List<String>,
    val publishedDate: String?,
    val pageCount: Int?,
    val language: String?,
    val thumbnail: String?,
    val isbn13: String?,
)

/**
 * Google Books: без ключа — общая квота (часто исчерпана, 429), со своим ключом — 1000/сут. Точный
 * поиск — по ISBN издания из Open Library.
 */
class GoogleBooksClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
    private val apiKey: () -> String = { BuildConfig.GOOGLE_BOOKS_API_KEY },
) {
    suspend fun byIsbn(isbn: String): LookupResult<GoogleVolume> {
        val key = apiKey().takeIf { it.isNotBlank() }?.let { "&key=$it" }.orEmpty()
        return http.text("Google Books", "https://www.googleapis.com/books/v1/volumes?q=isbn:${enc(isbn)}$key", rate,
            policy = CachePolicy("googlebooks:isbn:$isbn", 30 * CacheTtl.DAY, 7 * CacheTtl.DAY))
            .parse { GoogleBooksParser.volumes(it).firstOrNull() }
    }
}

internal object GoogleBooksParser {
    fun volumes(body: String): List<GoogleVolume> =
        ((EnrichmentJson.parseToJsonElement(body).jsonObject["items"]) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val info = o["volumeInfo"] as? JsonObject ?: return@mapNotNull null
            val ids = (info["industryIdentifiers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            GoogleVolume(
                id = o.str("id") ?: return@mapNotNull null,
                title = info.str("title") ?: return@mapNotNull null,
                authors = info.strings("authors"),
                description = info.str("description"),
                categories = info.strings("categories"),
                publishedDate = info.str("publishedDate"),
                pageCount = info.int("pageCount"),
                language = info.str("language"),
                // Google отдаёт http-миниатюру; https и крупнее — тот же адрес с zoom=0 не всегда есть.
                thumbnail = (info["imageLinks"] as? JsonObject)?.str("thumbnail")?.replace("http://", "https://"),
                isbn13 = ids.firstOrNull { it.str("type") == "ISBN_13" }?.str("identifier"),
            )
        }
}

// ---------- BookBrainz ----------

/** BookBrainz — открытая идентичность произведений; покрытие небольшое — только тай-брейкер. */
class BookBrainzClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
) {
    suspend fun searchWorks(title: String): LookupResult<List<BookBrainzEntity>> =
        http.text("BookBrainz", "https://api.bookbrainz.org/1/search?q=${enc(title)}&type=work", rate,
            policy = CachePolicy("bookbrainz:work:${title.lowercase()}", 30 * CacheTtl.DAY, 7 * CacheTtl.DAY))
            .parse { BookBrainzParser.search(it).takeIf(List<BookBrainzEntity>::isNotEmpty) }
}

data class BookBrainzEntity(val bbid: String, val name: String)

internal object BookBrainzParser {
    fun search(body: String): List<BookBrainzEntity> =
        ((EnrichmentJson.parseToJsonElement(body).jsonObject["searchResult"]) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            BookBrainzEntity(
                bbid = o.str("bbid") ?: return@mapNotNull null,
                name = (o["defaultAlias"] as? JsonObject)?.str("name") ?: return@mapNotNull null,
            )
        }
}

// ---------- iTunes ----------

/** Аудиокнига или e-книга в витрине Apple: обложка до 600×600, превью, ссылка. Чтеца API не отдаёт. */
data class StoreBook(
    val id: Long,
    val title: String,
    val author: String,
    val artworkUrl: String?,
    val previewUrl: String?,
    val trackCount: Int?,
    val releaseDate: String?,
    val description: String?,
    val storeUrl: String?,
)

class ITunesClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
) {
    suspend fun audiobooks(term: String, country: String): LookupResult<List<StoreBook>> = search(term, "audiobook", country)

    suspend fun ebooks(term: String, country: String): LookupResult<List<StoreBook>> = search(term, "ebook", country)

    private suspend fun search(term: String, media: String, country: String): LookupResult<List<StoreBook>> =
        http.text("iTunes", "https://itunes.apple.com/search?term=${enc(term)}&media=$media&limit=10&country=$country", rate,
            policy = CachePolicy("itunes:$media:$country:${term.lowercase()}", 7 * CacheTtl.DAY, 2 * CacheTtl.DAY))
            .parse { ITunesParser.results(it).takeIf(List<StoreBook>::isNotEmpty) }
}

internal object ITunesParser {
    fun results(body: String): List<StoreBook> =
        ((EnrichmentJson.parseToJsonElement(body).jsonObject["results"]) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            StoreBook(
                id = (o["collectionId"] ?: o["trackId"])?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() } ?: return@mapNotNull null,
                title = o.str("collectionName") ?: o.str("trackName") ?: return@mapNotNull null,
                author = o.str("artistName").orEmpty(),
                // 100×100 в выдаче — тот же адрес отдаёт и 600×600.
                artworkUrl = o.str("artworkUrl100")?.replace("100x100bb", "600x600bb"),
                previewUrl = o.str("previewUrl"),
                trackCount = o.int("trackCount"),
                releaseDate = o.str("releaseDate"),
                description = o.str("description")?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim(),
                storeUrl = o.str("collectionViewUrl") ?: o.str("trackViewUrl"),
            )
        }
}

// ---------- NYT Books ----------

data class BestsellerList(val name: String, val displayName: String, val books: List<Bestseller>)

data class Bestseller(
    val rank: Int,
    val title: String,
    val author: String,
    val isbn13: String?,
    val description: String?,
    val imageUrl: String?,
    val weeksOnList: Int?,
)

/** NYT Books — бестселлеры (ключ, 5 запросов/мин, 500/сут): только для витрины Книг. */
class NytBooksClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
    private val apiKey: () -> String = { BuildConfig.NYT_API_KEY },
) {
    val isConfigured: Boolean get() = apiKey().isNotBlank()

    /** Все текущие списки одним запросом (`overview`) — экономит квоту. */
    suspend fun overview(): LookupResult<List<BestsellerList>> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        return http.text("NYT Books", "https://api.nytimes.com/svc/books/v3/lists/overview.json?api-key=$key", rate,
            policy = CachePolicy("nyt:overview", 12 * CacheTtl.HOUR))
            .parse(NytParser::overview)
    }
}

internal object NytParser {
    fun overview(body: String): List<BestsellerList> {
        val results = EnrichmentJson.parseToJsonElement(body).jsonObject["results"] as? JsonObject ?: return emptyList()
        return (results["lists"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            BestsellerList(
                name = o.str("list_name_encoded") ?: return@mapNotNull null,
                displayName = o.str("display_name") ?: o.str("list_name").orEmpty(),
                books = (o["books"] as? JsonArray).orEmpty().mapNotNull { b ->
                    val bo = b as? JsonObject ?: return@mapNotNull null
                    Bestseller(
                        rank = bo.int("rank") ?: return@mapNotNull null,
                        title = bo.str("title") ?: return@mapNotNull null,
                        author = bo.str("author").orEmpty(),
                        isbn13 = bo.str("primary_isbn13"),
                        description = bo.str("description"),
                        imageUrl = bo.str("book_image"),
                        weeksOnList = bo.int("weeks_on_list"),
                    )
                },
            )
        }
    }
}

// ---------- TasteDive ----------

enum class TasteType(val param: String) { MOVIE("movie"), SHOW("show"), BOOK("book"), MUSIC("music"), PODCAST("podcast"), GAME("game") }

data class TasteItem(val name: String, val type: String, val description: String?, val wikiUrl: String?, val youtubeId: String?)

/** TasteDive — «похожее» между типами (книга → фильмы и наоборот). Ключ; ~300 запросов в час. */
class TasteDiveClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
    private val apiKey: () -> String = { BuildConfig.TASTEDIVE_API_KEY },
) {
    val isConfigured: Boolean get() = apiKey().isNotBlank()

    suspend fun similar(name: String, of: TasteType, want: TasteType?, limit: Int = 12): LookupResult<List<TasteItem>> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        val q = enc("${of.param}:$name")
        val type = want?.let { "&type=${it.param}" }.orEmpty()
        return http.text("TasteDive", "https://tastedive.com/api/similar?q=$q$type&info=1&limit=$limit&k=$key", rate,
            policy = CachePolicy("tastedive:$q:$type:$limit", 30 * CacheTtl.DAY, 7 * CacheTtl.DAY))
            .parse { TasteDiveParser.results(it).takeIf(List<TasteItem>::isNotEmpty) }
    }
}

internal object TasteDiveParser {
    fun results(body: String): List<TasteItem> {
        val root = EnrichmentJson.parseToJsonElement(body).jsonObject
        // Ответ бывает в двух регистрах: `similar.results` и `Similar.Results`.
        val similar = (root["similar"] ?: root["Similar"]) as? JsonObject ?: return emptyList()
        return ((similar["results"] ?: similar["Results"]) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            TasteItem(
                name = (o.str("name") ?: o.str("Name")) ?: return@mapNotNull null,
                type = (o.str("type") ?: o.str("Type")).orEmpty(),
                description = o.str("description") ?: o.str("wTeaser"),
                wikiUrl = o.str("wUrl"),
                youtubeId = o.str("yID"),
            )
        }
    }
}
