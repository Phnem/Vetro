package com.example.myapplication.network.enrichment

import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.TokenBucketRateLimiter
import com.phnem.vetro.network.BuildConfig
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Картинка с языком и «народной» оценкой: по ним выбирается лучшая под язык интерфейса. */
data class ArtworkImage(
    val url: String,
    /** ISO 639-1; null — картинка без текста (фон, клирарт без надписи). */
    val language: String?,
    val width: Int?,
    val height: Int?,
    /** Голоса TMDb или лайки Fanart.tv — только для сравнения внутри одного источника. */
    val score: Double,
    val source: EnrichmentSource,
)

/** Ролик на YouTube (или другом сайте) с признаками официальности. */
data class VideoClip(
    val site: String,
    val key: String,
    val name: String,
    val type: String,
    val official: Boolean,
    val language: String?,
    val source: EnrichmentSource,
)

/** Ответ TMDb для обогащения одним запросом: картинки, ролики, внешние id, ближайшая серия. */
data class TmdbEnrichment(
    val logos: List<ArtworkImage>,
    val backdrops: List<ArtworkImage>,
    val videos: List<VideoClip>,
    val imdbId: String?,
    val tvdbId: Int?,
    val status: String?,
    val nextEpisode: TmdbEpisodeRef?,
    val lastEpisode: TmdbEpisodeRef?,
    /** ISO 639-1 языка оригинала: `en`, `ru`, `ja`. */
    val originalLanguage: String? = null,
)

data class TmdbEpisodeRef(val season: Int?, val number: Int?, val airDate: LocalDate?)

enum class TmdbKind(val path: String) { MOVIE("movie"), TV("tv") }

/**
 * TMDb для обогащения: `append_to_response=images,videos,external_ids` — один запрос на открытие
 * карточки. Логотипы TMDb — прозрачные PNG, в том числе на русском.
 */
class TmdbEnrichmentClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
    private val apiKey: () -> String = { BuildConfig.TMDB_API_KEY },
) {
    suspend fun bundle(kind: TmdbKind, id: Int, language: AppLanguage, refresh: Boolean = false): LookupResult<TmdbEnrichment> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        val lang = if (language == AppLanguage.RU) "ru" else "en"
        val url = "https://api.themoviedb.org/3/${kind.path}/$id?api_key=$key" +
            "&language=${if (lang == "ru") "ru-RU" else "en-US"}" +
            "&append_to_response=images,videos,external_ids" +
            "&include_image_language=$lang,en,null&include_video_language=$lang,en"
        val policy = CachePolicy("tmdb:bundle:${kind.path}:$id:$lang", 3 * CacheTtl.DAY)
        return http.text("TMDB", url, rate, notFoundById = true, refresh = refresh, policy = policy).parse(TmdbEnrichmentParser::parse)
    }
}

internal object TmdbEnrichmentParser {
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/original"

    fun parse(body: String): TmdbEnrichment {
        val root = EnrichmentJson.parseToJsonElement(body).jsonObject
        val images = root["images"] as? JsonObject
        val external = root["external_ids"] as? JsonObject
        return TmdbEnrichment(
            logos = images.images("logos"),
            backdrops = images.images("backdrops"),
            videos = ((root["videos"] as? JsonObject)?.get("results") as? JsonArray).orEmpty().mapNotNull(::video),
            // У фильма imdb_id в корне, у сериала — только в external_ids.
            imdbId = root.str("imdb_id") ?: external?.str("imdb_id"),
            tvdbId = (external?.get("tvdb_id") as? JsonPrimitive)?.intOrNull,
            status = root.str("status"),
            nextEpisode = (root["next_episode_to_air"] as? JsonObject)?.episode(),
            lastEpisode = (root["last_episode_to_air"] as? JsonObject)?.episode(),
            originalLanguage = root.str("original_language"),
        )
    }

    private fun JsonObject?.images(field: String): List<ArtworkImage> =
        (this?.get(field) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val path = o.str("file_path") ?: return@mapNotNull null
            ArtworkImage(
                url = IMAGE_BASE + path,
                language = o.str("iso_639_1"),
                width = (o["width"] as? JsonPrimitive)?.intOrNull,
                height = (o["height"] as? JsonPrimitive)?.intOrNull,
                score = (o["vote_average"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                source = EnrichmentSource.TMDB,
            )
        }

    private fun video(e: JsonElement): VideoClip? {
        val o = e as? JsonObject ?: return null
        return VideoClip(
            site = o.str("site") ?: return null,
            key = o.str("key") ?: return null,
            name = o.str("name").orEmpty(),
            type = o.str("type").orEmpty(),
            official = (o["official"] as? JsonPrimitive)?.booleanOrNull == true,
            language = o.str("iso_639_1"),
            source = EnrichmentSource.TMDB,
        )
    }

    private fun JsonObject.episode() = TmdbEpisodeRef(
        season = (get("season_number") as? JsonPrimitive)?.intOrNull,
        number = (get("episode_number") as? JsonPrimitive)?.intOrNull,
        airDate = str("air_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    )
}

internal fun JsonObject.str(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

// ---------- Fanart.tv ----------

/**
 * Fanart.tv: логотипы/клирарты/фоны по лайкам. Фильм — по TMDb или IMDb id, сериал — по TVDB id.
 * Ключ проекта обязателен; без него модуль выключен.
 */
class FanartClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
    private val apiKey: () -> String = { BuildConfig.FANART_API_KEY },
) {
    suspend fun movie(tmdbOrImdbId: String): LookupResult<FanartArtwork> = fetch("movies/$tmdbOrImdbId", movie = true)

    suspend fun tv(tvdbId: Int): LookupResult<FanartArtwork> = fetch("tv/$tvdbId", movie = false)

    private suspend fun fetch(path: String, movie: Boolean): LookupResult<FanartArtwork> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        return http.text("Fanart.tv", "https://webservice.fanart.tv/v3/$path?api_key=$key", rate, notFoundById = true,
            policy = CachePolicy("fanart:$path", 14 * CacheTtl.DAY, 3 * CacheTtl.DAY))
            .parse { FanartParser.parse(it, movie) }
    }
}

data class FanartArtwork(
    val logos: List<ArtworkImage>,
    val clearArt: List<ArtworkImage>,
    val backgrounds: List<ArtworkImage>,
    val posters: List<ArtworkImage>,
)

internal object FanartParser {
    fun parse(body: String, movie: Boolean): FanartArtwork {
        val root = EnrichmentJson.parseToJsonElement(body).jsonObject
        fun list(vararg fields: String): List<ArtworkImage> = fields.flatMap { f ->
            (root[f] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                ArtworkImage(
                    url = o.str("url") ?: return@mapNotNull null,
                    language = o.str("lang")?.takeIf { it != "00" },
                    width = null,
                    height = null,
                    score = o.str("likes")?.toDoubleOrNull() ?: 0.0,
                    source = EnrichmentSource.FANART,
                )
            }
        }
        return if (movie) {
            FanartArtwork(list("hdmovielogo", "movielogo"), list("hdmovieclearart", "movieart"), list("moviebackground"), list("movieposter"))
        } else {
            FanartArtwork(list("hdtvlogo", "clearlogo"), list("hdclearart", "clearart"), list("showbackground"), list("tvposter"))
        }
    }
}

// ---------- OMDb ----------

/** OMDb — рейтинги IMDb / Rotten Tomatoes / Metacritic и награды по IMDb id (ключ, 1000/сут). */
class OmdbClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
    private val apiKey: () -> String = { BuildConfig.OMDB_API_KEY },
) {
    val isConfigured: Boolean get() = apiKey().isNotBlank()

    suspend fun byImdb(imdbId: String): LookupResult<OmdbRatings> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        return http.text("OMDb", "https://www.omdbapi.com/?i=$imdbId&apikey=$key", rate,
            policy = CachePolicy("omdb:$imdbId", 7 * CacheTtl.DAY)).parse(OmdbParser::parse)
    }
}

data class OmdbRatings(
    val imdbRating: Double?,
    val imdbVotes: Int?,
    /** Проценты 0..100. */
    val rottenTomatoes: Int?,
    /** 0..100. */
    val metacritic: Int?,
    val awards: String?,
)

internal object OmdbParser {
    fun parse(body: String): OmdbRatings? {
        val root = EnrichmentJson.parseToJsonElement(body).jsonObject
        // OMDb отвечает 200 и на «не найдено»: признак — Response=False.
        if (root.str("Response")?.equals("False", true) == true) return null
        val ratings = (root["Ratings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun rating(source: String) = ratings.firstOrNull { it.str("Source") == source }?.str("Value")
        return OmdbRatings(
            imdbRating = root.str("imdbRating")?.toDoubleOrNull(),
            imdbVotes = root.str("imdbVotes")?.replace(",", "")?.toIntOrNull(),
            rottenTomatoes = rating("Rotten Tomatoes")?.removeSuffix("%")?.toIntOrNull(),
            metacritic = rating("Metacritic")?.substringBefore('/')?.toIntOrNull()
                ?: root.str("Metascore")?.toIntOrNull(),
            awards = root.str("Awards")?.takeIf { it != "N/A" },
        )
    }
}

// ---------- YouTube ----------

/**
 * YouTube Data API. `search.list` у проекта — всего 100 вызовов в сутки, поэтому основной путь —
 * проверка уже известного ролика (`videos.list`, 1 единица): доступен ли, можно ли встраивать, чей
 * канал. Поиск — только запасной и под бюджетом приложения.
 */
class YouTubeClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
    private val apiKey: () -> String = { BuildConfig.YOUTUBE_API_KEY },
) {
    val isConfigured: Boolean get() = apiKey().isNotBlank()

    suspend fun videos(ids: List<String>): LookupResult<List<YouTubeVideo>> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        if (ids.isEmpty()) return LookupResult.NoMatch
        val url = "https://www.googleapis.com/youtube/v3/videos?part=snippet,status&id=${ids.take(50).joinToString(",")}&key=$key"
        return http.text("YouTube", url, rate, policy = CachePolicy("youtube:videos:${ids.sorted().joinToString(",")}", 7 * CacheTtl.DAY))
            .parse(YouTubeParser::videos)
    }

    suspend fun search(query: String, maxResults: Int = 5): LookupResult<List<YouTubeVideo>> {
        val key = apiKey().takeIf { it.isNotBlank() } ?: return disabled()
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&maxResults=$maxResults&q=$q&key=$key"
        return http.text("YouTube", url, rate).parse(YouTubeParser::search)
    }
}

data class YouTubeVideo(
    val id: String,
    val title: String,
    val channelTitle: String,
    val embeddable: Boolean?,
    val public: Boolean?,
)

internal object YouTubeParser {
    fun videos(body: String): List<YouTubeVideo> = items(body) { o ->
        val status = o["status"] as? JsonObject
        YouTubeVideo(
            id = o.str("id") ?: return@items null,
            title = (o["snippet"] as? JsonObject)?.str("title").orEmpty(),
            channelTitle = (o["snippet"] as? JsonObject)?.str("channelTitle").orEmpty(),
            embeddable = (status?.get("embeddable") as? JsonPrimitive)?.booleanOrNull,
            public = status?.str("privacyStatus")?.let { it == "public" },
        )
    }

    fun search(body: String): List<YouTubeVideo> = items(body) { o ->
        YouTubeVideo(
            id = (o["id"] as? JsonObject)?.str("videoId") ?: return@items null,
            title = (o["snippet"] as? JsonObject)?.str("title").orEmpty(),
            channelTitle = (o["snippet"] as? JsonObject)?.str("channelTitle").orEmpty(),
            embeddable = null,
            public = null,
        )
    }

    private fun items(body: String, map: (JsonObject) -> YouTubeVideo?): List<YouTubeVideo> =
        ((EnrichmentJson.parseToJsonElement(body).jsonObject["items"]) as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(map) }
}
