package com.example.myapplication.media.source

import com.example.myapplication.network.AppJson
import android.util.Log
import com.example.myapplication.sync.TitleMatcher
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import java.net.URLDecoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

/**
 * Native AnimeGo resolver. Search always includes the requested title and never falls
 * back to the first unrelated card.
 *
 * AnimeGO раздаёт серии через два независимых плеера — AniBoom и CVH (CDNVideoHub). Оба пути
 * выполняются и складываются: это разные CDN с разным набором озвучек, и падение одного не должно
 * лишать пользователя другого.
 */
class AnimeGoSource(
    client: HttpClient,
) : VetroHttpSource(client) {

    override val name: String = "AnimeGo"
    override val baseUrl: String = "https://animego.me"
    private val json = AppJson
    private val cvh = CvhResolver(client)

    internal suspend fun resolveEpisode(
        seasonQuery: SeasonSourceQuery,
        episodeNumber: Int,
    ): List<VetroHoster> {
        val anime = seasonQuery.anime
        val queries = animeGoSearchQueries(seasonQuery)
        if (queries.isEmpty()) {
            Log.i(TAG, "No usable title alias for ep=$episodeNumber")
            return emptyList()
        }
        val seasonTitle = anime.title.takeIf { seasonQuery.seasonIdentifiable && it.isNotBlank() }
        val franchiseTitles = listOfNotNull(anime.titleRu, anime.title, anime.titleEn)

        // Ступени идут по убыванию точности и стоят по одному запросу каждая: следующая
        // выполняется, только если предыдущая не дала подтверждённой страницы.
        var best: SearchHit? = null
        var usedQuery: String? = null
        for (candidate in queries) {
            best = search(candidate)
                .filter { hit ->
                    animeGoPageMatchesSeason(
                        pageTitle = hit.title,
                        seasonTitle = seasonTitle,
                        franchiseTitles = franchiseTitles,
                        seasonNumber = seasonQuery.seasonNumber,
                    )
                }
                .maxByOrNull { hit ->
                    franchiseTitles.maxOfOrNull {
                        TitleMatcher.bestScore(it, listOf(hit.title))
                    } ?: 0.0
                }
            if (best != null) {
                usedQuery = candidate
                break
            }
        }
        if (best == null) {
            Log.i(
                TAG,
                "No season-confirmed match for S${seasonQuery.seasonNumber}E$episodeNumber " +
                    "(tried $queries)",
            )
            return emptyList()
        }
        Log.i(TAG, "Matched '${best.title}' via '$usedQuery' S${seasonQuery.seasonNumber}")

        val playerHtml = fetchPlayerHtml(best.id, episodeNumber)
        if (playerHtml == null) {
            Log.i(TAG, "No player page for '${best.title}' ep=$episodeNumber")
            return emptyList()
        }

        // Агрегируем оба бэкенда, а не останавливаемся на первом успехе: supervisorScope не даёт
        // падению одной ветки отменить вторую.
        val hosters = supervisorScope {
            val aniboom = async { aniboomHosters(playerHtml) }
            val cvhPath = async { cvhHosters(playerHtml, episodeNumber) }
            aniboom.await() + cvhPath.await()
        }
        Log.i(TAG, "Resolved '${best.title}' ep=$episodeNumber hosters=${hosters.size}")
        return hosters
    }

    private suspend fun aniboomHosters(playerHtml: String): List<VetroHoster> = runCatching {
        val embedUrl = parseAniboomEmbed(playerHtml) ?: return@runCatching emptyList<VetroHoster>()
        val stream = scrapeAniboom(embedUrl) ?: return@runCatching emptyList<VetroHoster>()
        val video = VetroVideo(
            url = stream,
            label = "720p",
            resolution = 720,
            headers = mapOf(
                "Referer" to "https://aniboom.one/",
                "Origin" to "https://aniboom.one",
                "User-Agent" to DEFAULT_UA,
            ),
            isPreferred = true,
            downloadAllowed = true,
        )
        listOf(
            VetroHoster(
                name = "AnimeGo / AniBoom",
                url = embedUrl,
                videos = listOf(video),
            )
        )
    }.onFailure { Log.w(TAG, "AniBoom path failed: ${it.message}") }
        .getOrElse { emptyList() }

    /**
     * CVH-ветка. Каждая озвучка становится отдельным [VetroHoster] — как у Kodik-дубляжей: чужую
     * аудиодорожку к видео не подмешиваем и разные студии в один хостер не сваливаем.
     */
    private suspend fun cvhHosters(playerHtml: String, episode: Int): List<VetroHoster> =
        runCatching {
            val translations = parseCvhTranslations(playerHtml)
            if (translations.isEmpty()) return@runCatching emptyList<VetroHoster>()

            coroutineScope {
                translations.groupBy { it.cvhId }.map { (cvhId, group) ->
                    async {
                        val playlist = cvh.fetchPlaylist(cvhId)
                        // Сезон из запроса недоступен на этом уровне API, поэтому просим первый;
                        // резолвер сам игнорирует номер, если сезон в плейлисте единственный.
                        val forEpisode = cvh.itemsForEpisode(playlist, season = 1, episode = episode)
                        if (forEpisode.isEmpty()) return@async emptyList<VetroHoster>()

                        val studios = forEpisode.map { it.voiceStudio }
                            .filter { it.isNotBlank() }
                            .distinct()
                        group.mapNotNull { translation ->
                            val studio = CvhResolver.matchStudio(translation.label, studios)
                            if (studio == null) {
                                Log.i(TAG, "CVH: no studio match for '${translation.label}' in $studios")
                                return@mapNotNull null
                            }
                            val item = forEpisode.firstOrNull { it.voiceStudio == studio }
                                ?: return@mapNotNull null
                            translation to item
                        }.distinctBy { it.second.vkId }.map { (translation, item) ->
                            async {
                                val videos = cvh.fetchStreams(item.vkId)
                                if (videos.isEmpty()) {
                                    null
                                } else {
                                    VetroHoster(
                                        name = "AnimeGo / CVH · ${translation.label}",
                                        url = translation.embed,
                                        videos = videos,
                                    )
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }
                }.awaitAll().flatten()
            }
        }.onFailure { Log.w(TAG, "CVH path failed: ${it.message}") }
            .getOrElse { emptyList() }

    private suspend fun search(query: String): List<SearchHit> = runCatching {
        val body = client.get("$baseUrl/search/all") {
            parameter("type", "all")
            parameter("q", query)
            header("X-Requested-With", "XMLHttpRequest")
            header("Referer", "$baseUrl/")
        }.bodyAsText()

        parseSearchJson(body).ifEmpty {
            Jsoup.parse(body, baseUrl)
                .select("a[href*=/anime/]")
                .mapNotNull { anchor ->
                    val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                    val id = Regex("""(\d+)/?$""").find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: return@mapNotNull null
                    val title = anchor.text().trim().ifBlank { anchor.attr("title").trim() }
                    title.takeIf { it.isNotBlank() }?.let { SearchHit(id, it) }
                }
                .distinctBy { it.id }
                .take(15)
        }
    }.onFailure { Log.w(TAG, "search '$query' failed: ${it.message}") }
        .getOrElse { emptyList() }

    private fun parseSearchJson(body: String): List<SearchHit> = runCatching {
        val root = json.parseToJsonElement(body)
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> root["items"]?.jsonArray
                ?: root["results"]?.jsonArray
                ?: return emptyList()
            else -> return emptyList()
        }
        items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val title = item["title"]?.jsonPrimitive?.contentOrNull
                ?: item["name"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val rawId = item["id"]?.jsonPrimitive?.contentOrNull
                ?: item["url"]?.jsonPrimitive?.contentOrNull
                ?: item["link"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val id = Regex("""(\d+)""").findAll(rawId).lastOrNull()?.value
                ?: return@mapNotNull null
            SearchHit(id, title)
        }
    }.getOrElse { emptyList() }

    /** Страница плеера общая для обеих веток, поэтому тянем её один раз. */
    private suspend fun fetchPlayerHtml(animeId: String, episode: Int): String? = runCatching {
        client.get("$baseUrl/anime/$animeId/player") {
            parameter("episode", episode)
            header("Referer", "$baseUrl/anime/$animeId")
            header("X-Requested-With", "XMLHttpRequest")
        }.bodyAsText()
    }.onFailure { Log.w(TAG, "player page ep=$episode failed: ${it.message}") }
        .getOrNull()

    private fun parseAniboomEmbed(body: String): String? = runCatching {
        val iframe = Jsoup.parse(body, baseUrl)
            .selectFirst("iframe[src*=aniboom], iframe[data-src*=aniboom]")
        val source = iframe?.absUrl("src")?.ifBlank { null }
            ?: iframe?.attr("src")?.ifBlank { null }
            ?: iframe?.attr("data-src")?.ifBlank { null }
            ?: Regex("""https?://[^"'\s]*aniboom[^"'\s]*""").find(body)?.value
            ?: Regex("""//[^"'\s]*aniboom[^"'\s]*""").find(body)?.value
                ?.let { "https:$it" }
        source?.let { if (it.startsWith("//")) "https:$it" else it }
    }.onFailure { Log.w(TAG, "aniboom embed parse failed: ${it.message}") }
        .getOrNull()

    /**
     * Кнопки выбора озвучки несут провайдера в `data-provider-title` и embed в `data-player`.
     * Разбираем той же идиомой, что и aniboom-iframe, только под CVH.
     */
    private fun parseCvhTranslations(body: String): List<CvhTranslation> = runCatching {
        val fromButtons = Jsoup.parse(body, baseUrl)
            .select("[data-player]")
            .mapNotNull { element ->
                val provider = element.attr("data-provider-title")
                val player = element.attr("data-player")
                if (!provider.equals("CVH", ignoreCase = true) &&
                    !player.contains("cdn-iframe")
                ) {
                    return@mapNotNull null
                }
                val embed = normalizeUrl(player) ?: return@mapNotNull null
                val cvhId = extractCvhId(embed) ?: return@mapNotNull null
                // "(ошибка)" — так AnimeGO помечает нерабочие озвучки, в имени студии он лишний.
                val label = element.attr("data-translation-title")
                    .ifBlank { element.text() }
                    .replace("(ошибка)", "")
                    .trim()
                    .ifBlank { return@mapNotNull null }
                CvhTranslation(label = label, cvhId = cvhId, embed = embed)
            }
        if (fromButtons.isNotEmpty()) {
            return@runCatching fromButtons.distinctBy { it.cvhId + "|" + it.label.lowercase() }
                .take(MAX_CVH_TRANSLATIONS)
        }

        // Фолбэк: AnimeGO иногда отдаёт плеер JSON-обёрткой с экранированными слэшами, и Jsoup
        // такой ответ деревом не видит. Путь embed сам несёт и id, и имя студии.
        val unescaped = body.replace("\\/", "/").replace("\\\"", "\"")
        CVH_EMBED_REGEX.findAll(unescaped)
            .mapNotNull { match ->
                val cvhId = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val rawStudio = match.groupValues.getOrNull(2).orEmpty()
                val label = runCatching { URLDecoder.decode(rawStudio, "UTF-8") }
                    .getOrDefault(rawStudio)
                    .trim()
                    .ifBlank { return@mapNotNull null }
                CvhTranslation(
                    label = label,
                    cvhId = cvhId,
                    embed = "$baseUrl/cdn-iframe/$cvhId/$rawStudio",
                )
            }
            .distinctBy { it.cvhId + "|" + it.label.lowercase() }
            .take(MAX_CVH_TRANSLATIONS)
            .toList()
    }.onFailure { Log.w(TAG, "CVH translations parse failed: ${it.message}") }
        .getOrElse { emptyList() }

    /**
     * cvh_id — сегмент пути сразу после `cdn-iframe/`:
     * `https://animego.me/cdn-iframe/60254/Dream%20Cast/1/1` → `60254`.
     */
    private fun extractCvhId(embed: String): String? {
        val marker = "cdn-iframe/"
        val start = embed.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = embed.indexOf('/', from)
        val id = if (end < 0) embed.substring(from) else embed.substring(from, end)
        return id.takeIf { it.isNotBlank() }
    }

    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim().ifBlank { return null }
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/") -> "$baseUrl$trimmed"
            else -> null
        }
    }

    private suspend fun scrapeAniboom(embedUrl: String): String? = runCatching {
        val html = getText(
            embedUrl,
            extraHeaders = mapOf(
                "Referer" to "$baseUrl/",
                "Accept-Language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            ),
        )
        val video = Jsoup.parse(html, embedUrl).selectFirst("video#video")
            ?: return@runCatching null
        val raw = video.attr("data-parameters").ifBlank { return@runCatching null }
        val data = json.parseToJsonElement(
            org.jsoup.parser.Parser.unescapeEntities(raw, false)
        ).jsonObject

        fun source(key: String): String? {
            val value = data[key]?.jsonPrimitive?.contentOrNull ?: return null
            return runCatching {
                json.parseToJsonElement(value).jsonObject["src"]
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()
        }

        source("hls")
            ?: source("dash")?.replace(".mpd", ".m3u8")
    }.onFailure { Log.w(TAG, "AniBoom scrape failed: ${it.message}") }
        .getOrNull()

    private data class SearchHit(val id: String, val title: String)

    /** Озвучка AnimeGO, играющая через CVH. */
    private data class CvhTranslation(
        val label: String,
        val cvhId: String,
        val embed: String,
    )

    companion object {
        private const val TAG = "AnimeGoSource"
        private const val MAX_CVH_TRANSLATIONS = 8
        private val CVH_EMBED_REGEX = Regex("""cdn-iframe/([^/"'\\\s<>]+)/([^/"'\\\s<>]+)""")
    }
}
