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
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Rutube — бесплатные фильмы и сериалы с каналов правообладателей («Мосфильм», «Союзмультфильм»
 * и подобных). Подписка и вход не нужны.
 *
 * В поиске Rutube рядом с официальными копиями лежат пользовательские перезаливы, часто
 * пиратские, поэтому берём только результаты с признаком `is_official` (канал подтверждён
 * площадкой); остальное провайдер не видит вовсе. Платные, взрослые, скрытые, прямые эфиры и
 * аудио тоже отбрасываются.
 *
 * Тайтл ищется по названию (русскому, если есть): у Rutube нет внешних id кино.
 */
class RutubeProvider(
    private val client: HttpClient,
) : MovieSeriesStreamingProvider {
    override val id = ProviderId("rutube")
    override val displayName = "Rutube"
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
            val titles = listOfNotNull(request.anime.titleRu, request.anime.title, request.anime.titleEn)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(2)
            if (titles.isEmpty()) return@resolveTyped ProviderResolution.NotFound
            val episode = request.episodeNumber.takeIf { request.mediaType == MediaType.SERIES }

            var pick: RutubeSearch.Item? = null
            for (title in titles) {
                val items = search(if (episode != null) "$title $episode серия" else title)
                pick = RutubeSearch.pick(items, title, episode)
                if (pick != null) break
            }
            val item = pick ?: return@resolveTyped ProviderResolution.NotFound

            val stream = playlist(item.id) ?: return@resolveTyped ProviderResolution.NotFound
            val video = VetroVideo(
                url = stream,
                label = "HLS",
                sourceName = displayName,
                headers = mapOf("Referer" to "https://rutube.ru/", "User-Agent" to BROWSER_UA),
                providerKey = id.value,
            )
            ProviderResolution.Found(
                hosters = listOf(VetroHoster(name = displayName, videos = listOf(video))),
                accuracy = MatchAccuracy.TITLE_ONLY,
                language = AppLanguage.RU,
            )
        } catch (e: IOException) {
            ProviderResolution.TemporaryError(e.javaClass.simpleName)
        }
    }

    private suspend fun search(query: String): List<RutubeSearch.Item> {
        val response = client.get("$SITE/api/search/video/") {
            parameter("query", query)
            parameter("format", "json")
            header("User-Agent", BROWSER_UA)
            header("Accept", "application/json")
        }
        return RutubeSearch.parse(response.okBody("search"))
    }

    private suspend fun playlist(videoId: String): String? {
        val response = client.get("$SITE/api/play/options/$videoId/") {
            parameter("no_404", "true")
            parameter("referer", SITE)
            parameter("pver", "v2")
            header("User-Agent", BROWSER_UA)
            header("Accept", "application/json")
            header("Referer", "$SITE/")
        }
        return RutubeSearch.parsePlaylist(response.okBody("play options"))
    }

    private suspend fun HttpResponse.okBody(label: String): String {
        requireProviderSuccess(status.value, "$displayName $label")
        return bodyAsText()
    }

    private companion object {
        const val SITE = "https://rutube.ru"
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0 Safari/537.36"
    }
}

internal object RutubeSearch {
    data class Item(val id: String, val title: String, val durationSec: Double)

    private val json = Json { ignoreUnknownKeys = true }

    /** Фильмом считаем ролик от 40 минут: короче — трейлер, нарезка или серия мультсборника. */
    private const val MIN_MOVIE_SECONDS = 40 * 60.0

    /** Только пригодное для просмотра: официальное, бесплатное, не эфир и не аудио. */
    fun parse(body: String): List<Item> {
        val results = runCatching { (json.parseToJsonElement(body) as JsonObject)["results"] as JsonArray }
            .getOrElse { throw IllegalArgumentException("search shape", it) }
        return results.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            if (!o.flag("is_official")) return@mapNotNull null
            if (o.flag("is_paid") || o.flag("is_adult") || o.flag("is_hidden") || o.flag("is_deleted") ||
                o.flag("is_livestream") || o.flag("is_audio") || o.flag("is_locked")
            ) return@mapNotNull null
            Item(
                id = o.text("id") ?: return@mapNotNull null,
                title = o.text("title") ?: return@mapNotNull null,
                durationSec = (o["duration"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            )
        }
    }

    /**
     * Фильм: название результата начинается с названия тайтла, ролик не короче [MIN_MOVIE_SECONDS];
     * из подходящих — с самым коротким названием (без «ремастер», «реж.» и прочего хвоста).
     * Серия: то же начало названия и номер серии/выпуска в заголовке.
     */
    fun pick(items: List<Item>, title: String, episode: Int?): Item? {
        val wanted = title.normalizeForSearch()
        if (wanted.isEmpty()) return null
        val named = items.filter { it.title.normalizeForSearch().startsWith(wanted) }
        return if (episode == null) {
            val long = named.filter { it.durationSec >= MIN_MOVIE_SECONDS }
            // Цельный фильм лучше «1 серии» из двух: серийные названия — только запасной вариант.
            long.filter { episodeOf(it.title) == null }.ifEmpty { long }.minByOrNull { it.title.length }
        } else {
            named.firstOrNull { episodeOf(it.title) == episode }
        }
    }

    private val EPISODE = Regex(
        """(?:(\d{1,4})\s*[-–]?\s*(?:я\s+)?(?:серия|выпуск|эпизод|series|episode)|(?:серия|выпуск|эпизод|№|episode)\s*[№#.:]?\s*(\d{1,4}))""",
        RegexOption.IGNORE_CASE,
    )

    /** «1 серия», «Серия 3», «Выпуск № 9» → номер. */
    fun episodeOf(title: String): Int? {
        val m = EPISODE.find(title) ?: return null
        return (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
    }

    /** Ссылка на мастер-плейлист HLS из ответа `play/options`. */
    fun parsePlaylist(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IllegalArgumentException("play options shape", it) }
        val balancer = root["video_balancer"] as? JsonObject ?: return null
        return (balancer.text("m3u8") ?: balancer.text("default"))?.takeIf { it.startsWith("http") }
    }

    private fun JsonObject.flag(name: String): Boolean = (get(name) as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
