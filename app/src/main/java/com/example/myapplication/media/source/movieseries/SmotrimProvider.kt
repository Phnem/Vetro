package com.example.myapplication.media.source.movieseries

import com.example.myapplication.data.models.MediaType
import com.example.myapplication.domain.normalizeForSearch
import com.example.myapplication.media.source.PlaybackRequest
import com.example.myapplication.media.source.VetroHoster
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.network.AppLanguage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * «Смотрим» (ВГТРК) — фильмы, сериалы и передачи российских каналов. Бесплатно только то, что на
 * сайте помечено `PublicContent`; у `SubscriptionContent` (платная подписка «Смотрим») провайдер
 * отдаёт лишь серии, которые сайт сам открывает всем без оплаты.
 *
 * Три шага, все по открытым адресам сайта:
 * 1. поиск — GraphQL `searchFloors` (адрес несёт хэши запроса, как у самого сайта);
 * 2. серии бренда — из данных, которые страница бренда отдаёт при серверной отрисовке;
 * 3. поток — `player-api.smotrim.ru/api/v1/video/{id}` с подписанной HLS-ссылкой.
 */
class SmotrimProvider(
    private val client: HttpClient,
) : MovieSeriesStreamingProvider {
    override val id = ProviderId("smotrim")
    override val displayName = "Смотрим"
    override val capabilities = setOf(
        ProviderCapability.MOVIE,
        ProviderCapability.SERIES,
        ProviderCapability.RU,
        ProviderCapability.HLS,
    )

    override suspend fun resolve(request: PlaybackRequest): ProviderResolution = resolveTyped(displayName) {
        if (request.mediaType != MediaType.MOVIE && request.mediaType != MediaType.SERIES) {
            return@resolveTyped ProviderResolution.Unsupported
        }
        try {
            val serial = request.mediaType == MediaType.SERIES
            val titles = listOfNotNull(request.anime.titleRu, request.anime.title)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(2)
            var brand: SmotrimParsing.Brand? = null
            for (title in titles) {
                brand = SmotrimParsing.pickBrand(searchBrands(title), title, serial)
                if (brand != null) break
            }
            val picked = brand ?: return@resolveTyped ProviderResolution.NotFound

            val episodes = SmotrimParsing.episodes(brandPage(picked.id))
            val episode = SmotrimParsing.pickEpisode(
                episodes = episodes,
                serial = serial,
                season = request.seasonNumber,
                number = request.episodeNumber,
            ) ?: return@resolveTyped ProviderResolution.NotFound

            val stream = streamOf(episode.publicId) ?: return@resolveTyped ProviderResolution.NotFound
            ProviderResolution.Found(
                hosters = listOf(
                    VetroHoster(
                        name = displayName,
                        videos = listOf(
                            VetroVideo(
                                url = stream,
                                label = "HLS",
                                sourceName = displayName,
                                headers = mapOf("Referer" to "https://smotrim.ru/", "User-Agent" to BROWSER_UA),
                                providerKey = id.value,
                            ),
                        ),
                    ),
                ),
                accuracy = MatchAccuracy.TITLE_ONLY,
                language = AppLanguage.RU,
            )
        } catch (e: IOException) {
            ProviderResolution.TemporaryError("${e.javaClass.simpleName}: ${e.message}".take(160))
        }
    }

    private suspend fun searchBrands(text: String): List<SmotrimParsing.Brand> {
        val variables = buildJsonObject { put("text", text) }
        val response = client.post(
            "$GRAPHQL?page=SearchFloors&body=${md5(SEARCH_QUERY)}&vars=${md5(variables.toString())}",
        ) {
            header("User-Agent", BROWSER_UA)
            header("Origin", SITE)
            header("Referer", "$SITE/")
            header("Content-Type", "application/json")
            setBody(
                buildJsonObject {
                    put("query", SEARCH_QUERY)
                    put("variables", variables)
                }.toString(),
            )
        }
        return SmotrimParsing.parseBrands(response.okBody("search"))
    }

    private suspend fun brandPage(brandId: Int): String {
        val response = client.get("$SITE/brand/$brandId") {
            header("User-Agent", BROWSER_UA)
            header("Accept", "text/html")
        }
        return response.okBody("brand")
    }

    private suspend fun streamOf(publicId: Long): String? {
        val response = client.get("$PLAYER_API/video/$publicId") {
            header("User-Agent", BROWSER_UA)
            header("Accept", "application/json")
            header("Origin", SITE)
            header("Referer", "$SITE/")
        }
        return SmotrimParsing.parseStream(response.okBody("video"))
    }

    private suspend fun HttpResponse.okBody(label: String): String {
        requireProviderSuccess(status.value, "$displayName $label")
        return bodyAsText()
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SITE = "https://smotrim.ru"
        const val GRAPHQL = "https://apis.smotrim.ru/graphql"
        const val PLAYER_API = "https://player-api.smotrim.ru/api/v1"
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0 Safari/537.36"
        const val SEARCH_QUERY =
            "query SearchFloors(\$text: String!) { searchFloors(text: \$text) { title blocks { positions { " +
                "type data { ... on Brand { id title yearReleasedFrom publishedEpisodesCount " +
                "numberOfEpisodesFree tariff { enum } type { enum } } } } } } }"
    }
}

internal object SmotrimParsing {
    data class Brand(
        val id: Int,
        val title: String,
        val isFilm: Boolean,
        val isSerial: Boolean,
        /** `PublicContent` — всё бесплатно; иначе доступны лишь [freeEpisodes] серий. */
        val isFree: Boolean,
        val freeEpisodes: Int,
    )

    data class Episode(
        val publicId: Long,
        val season: Int?,
        val number: Int?,
        val durationSec: Double,
        val isSubscription: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Бренды (фильмы, сериалы, передачи) из ответа `searchFloors`; статьи, сюжеты и выпуски пропускаются. */
    fun parseBrands(body: String): List<Brand> {
        val floors = runCatching {
            ((json.parseToJsonElement(body) as JsonObject)["data"] as JsonObject)["searchFloors"] as JsonArray
        }.getOrElse { throw IllegalArgumentException("search shape", it) }
        val out = mutableListOf<Brand>()
        for (floor in floors) {
            val blocks = (floor as? JsonObject)?.get("blocks") as? JsonArray ?: continue
            for (block in blocks) {
                val positions = (block as? JsonObject)?.get("positions") as? JsonArray ?: continue
                for (position in positions) {
                    val data = (position as? JsonObject)?.get("data") as? JsonObject ?: continue
                    val id = data.int("id") ?: continue
                    val kind = (data["type"] as? JsonObject)?.text("enum") ?: continue
                    val title = data.text("title") ?: continue
                    val tariff = (data["tariff"] as? JsonObject)?.text("enum")
                    out += Brand(
                        id = id,
                        title = title,
                        isFilm = kind.equals("Film", ignoreCase = true),
                        isSerial = kind.equals("Serial", ignoreCase = true),
                        isFree = tariff.equals("PublicContent", ignoreCase = true),
                        freeEpisodes = data.int("numberOfEpisodesFree") ?: 0,
                    )
                }
            }
        }
        return out.distinctBy { it.id }
    }

    /** Подходит бренд нужного вида с совпадающим названием; недоступные без подписки отбрасываются. */
    fun pickBrand(brands: List<Brand>, title: String, serial: Boolean): Brand? {
        val wanted = title.normalizeForSearch()
        if (wanted.isEmpty()) return null
        return brands
            .filter { if (serial) it.isSerial else it.isFilm }
            .filter { it.isFree || it.freeEpisodes > 0 }
            .filter { it.title.normalizeForSearch().let { n -> n == wanted || n.startsWith(wanted) } }
            .minByOrNull { it.title.length }
    }

    /**
     * Серии бренда из данных серверной отрисовки страницы (`__NUXT_DATA__`, формат devalue: плоский
     * массив, где объекты и списки ссылаются на элементы по индексу).
     */
    fun episodes(html: String): List<Episode> {
        val raw = Regex("""<script[^>]*id="__NUXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        val data = runCatching { json.parseToJsonElement(raw) as JsonArray }.getOrNull() ?: return emptyList()
        val devalue = Devalue(data)
        val root = devalue.node(1) as? JsonObject ?: return emptyList()
        val store = devalue.child(root, "data") as? JsonObject ?: return emptyList()
        val section = store.keys.firstOrNull { it.startsWith("brand-episodes-") }
            ?.let { devalue.child(store, it) } as? JsonObject ?: return emptyList()
        val out = mutableListOf<Episode>()
        for (season in devalue.children(section, "list")) {
            val episodesBox = devalue.child(season, "episodes") as? JsonObject ?: continue
            for (item in devalue.children(episodesBox, "list")) {
                val publicId = devalue.number(item, "publicId")?.toLong() ?: continue
                out += Episode(
                    publicId = publicId,
                    season = devalue.number(season, "value")?.toInt(),
                    number = devalue.number(item, "number")?.toInt(),
                    durationSec = devalue.number(item, "durationSeconds") ?: 0.0,
                    isSubscription = devalue.flag(item, "isSVOD"),
                )
            }
        }
        return out
    }

    /**
     * Фильм — самая длинная бесплатная серия бренда. Сериал — серия с нужным номером в нужном сезоне;
     * сезон сайта и сезон приложения нумеруются независимо, поэтому при отсутствии такого сезона
     * берётся серия с этим номером в любом. Платные серии (`isSVOD`) не выбираются.
     */
    fun pickEpisode(episodes: List<Episode>, serial: Boolean, season: Int, number: Int): Episode? {
        val open = episodes.filter { !it.isSubscription }
        if (!serial) return open.maxByOrNull { it.durationSec }
        return open.firstOrNull { it.season == season && it.number == number }
            ?: open.firstOrNull { it.number == number }
    }

    /** Подписанная ссылка HLS из ответа `player-api`; только бесплатный тариф. */
    fun parseStream(body: String): String? {
        val data = runCatching { (json.parseToJsonElement(body) as JsonObject)["data"] as JsonObject }
            .getOrElse { throw IllegalArgumentException("video shape", it) }
        val tariff = (data["episode"] as? JsonObject)?.text("tariff")
        if (tariff != null && !tariff.equals("public_content", ignoreCase = true)) return null
        return ((data["streams"] as? JsonObject)?.text("m3u8"))?.takeIf { it.startsWith("http") }
    }

    private class Devalue(private val data: JsonArray) {
        /** Узел по индексу; обёртки `Reactive`/`ShallowReactive`/`Ref` разворачиваются. */
        fun node(index: Int): JsonElement? {
            var value = data.getOrNull(index) ?: return null
            var guard = 0
            while (value is JsonArray && value.size == 2 && guard++ < 6) {
                val tag = (value[0] as? JsonPrimitive)?.contentOrNull
                if (tag !in WRAPPERS) break
                val next = (value[1] as? JsonPrimitive)?.intOrNull ?: return null
                value = data.getOrNull(next) ?: return null
            }
            return value
        }

        fun child(container: JsonElement, key: String): JsonElement? {
            val index = ((container as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull ?: return null
            return node(index)
        }

        fun children(container: JsonElement, key: String): List<JsonElement> {
            val list = child(container, key) as? JsonArray ?: return emptyList()
            return list.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.let(::node) }
        }

        fun number(container: JsonElement, key: String): Double? =
            (child(container, key) as? JsonPrimitive)?.doubleOrNull

        fun flag(container: JsonElement, key: String): Boolean =
            (child(container, key) as? JsonPrimitive)?.contentOrNull == "true"

        private companion object {
            val WRAPPERS = setOf("Reactive", "ShallowReactive", "Ref", "ShallowRef")
        }
    }

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull
}
