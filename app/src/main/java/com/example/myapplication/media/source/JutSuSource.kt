package com.example.myapplication.media.source

import android.util.Log
import com.example.myapplication.data.models.Anime
import com.example.myapplication.domain.seasons.DiscoveredSeason
import com.example.myapplication.domain.seasons.SeasonInfo
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Native Kotlin source for jut.su (RU).
 *
 * Search: GET /lookfor/{q} → 302 to title page.
 *
 * **Источник таймскипов, а не видео.** Сайт перешёл на новый плеер (`jutsu_new_player = "yes"`):
 * `<source>` на странице серии есть, но их `src` — заглушка `pixel.png?<res>` с подставленным
 * `type="video/mp4"`, а настоящие адреса подставляются на клиенте и в получаемую разметку не
 * попадают (снимок: `app/src/test/resources/jutsu/`, разбор: `.scratch/season-source-resolution/`
 * TICKET-02). Конфиг плеера с интро/аутро и длительностью приезжает по-прежнему, поэтому класс
 * остаётся поставщиком [VetroSkipReference]; `SourceEngine` отбрасывает у него видео.
 *
 * Разбор `<source>` намеренно сохранён: он корректен и заработает сам, если сайт вернёт адреса
 * в разметку.
 */
class JutSuSource(
    client: HttpClient,
    /**
     * Зеркало домена. Источник — синглтон, а зеркало меняется в настройках на лету, поэтому это
     * провайдер, а не строка: значение читается на каждом резолве. null/пусто/мусор → дефолтный
     * домен, то есть без настройки поведение ровно прежнее.
     */
    private val mirrorProvider: suspend () -> String? = { null },
) : VetroHttpSource(client) {

    override val name: String = "jut.su"

    /**
     * Дефолт для контракта [VetroHttpSource]. Реальные запросы строятся от [activeBaseUrl] —
     * он учитывает зеркало, поэтому внутри класса на это поле опираться нельзя.
     */
    override val baseUrl: String = DEFAULT_BASE_URL

    /** Домен, на который реально уходят запросы прямо сейчас. */
    private suspend fun activeBaseUrl(): String =
        normalizeMirror(runCatching { mirrorProvider() }.getOrNull()) ?: DEFAULT_BASE_URL

    suspend fun resolveEpisode(
        anime: Anime,
        episodeNumber: Int,
        seasonInfo: SeasonInfo? = null,
        knownTitleUrl: String? = null,
    ): List<VetroHoster> {
        val base = activeBaseUrl()
        val titleUrl = knownTitleUrl?.takeIf { isOwnTitleUrl(it, base) }
            ?: resolveTitleUrl(anime, base)
            ?: return emptyList()

        val slug = titleUrl.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank() || slug in JUTSU_NON_TITLE_PATHS) return emptyList()

        val season = seasonInfo?.seasonNumber?.takeIf { it > 0 } ?: 1
        val candidates = episodeUrlCandidates(base, slug, season, episodeNumber)

        for (pageUrl in candidates) {
            val episode = scrapeEpisode(pageUrl)
            if (episode.videos.isNotEmpty() || episode.reference != null) {
                Log.i(
                    TAG,
                    "OK $pageUrl → ${episode.videos.size} qualities, " +
                        "reference=${episode.reference != null}",
                )
                return listOf(
                    VetroHoster(
                        name = "jut.su",
                        url = pageUrl,
                        videos = episode.videos,
                        skipReference = episode.reference,
                        lazy = false,
                    )
                )
            }
        }
        Log.w(TAG, "No sources for $slug ep=$episodeNumber season=$season")
        return emptyList()
    }

    /**
     * Сезоны и число серий по странице тайтла — для «Найти ещё»
     * (§ [com.example.myapplication.domain.seasons.StreamingSeasonDiscovery]).
     *
     * Страница тайтла на jut.su — это полное оглавление: ссылки вида `/slug/season-2/episode-5.html`
     * (у первого сезона префикс сезона опускается). Номера серий сплошные с единицы, поэтому
     * максимальный номер в сезоне и есть число серий; отдельный запрос на каждую серию не нужен.
     */
    suspend fun findSeasons(anime: Anime): List<DiscoveredSeason> = runCatching {
        val base = activeBaseUrl()
        val titleUrl = resolveTitleUrl(anime, base) ?: return emptyList()
        val slug = titleUrl.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank() || slug in JUTSU_NON_TITLE_PATHS) return emptyList()

        val html = getText(titleUrl, extraHeaders = mapOf("Referer" to "$base/"))
        val episodesBySeason = HashMap<Int, Int>()
        Jsoup.parse(html, titleUrl).select("a[href*=episode-]").forEach { element ->
            val href = element.attr("href")
            val episode = EPISODE_HREF.find(href)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            // Ссылка без «season-N» ведёт в первый сезон — так устроены URL самого сайта.
            val season = SEASON_HREF.find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            if (season > 0 && episode > 0) episodesBySeason.merge(season, episode, ::maxOf)
        }
        episodesBySeason
            .map { (number, episodes) -> DiscoveredSeason(number, episodes, SOURCE_NAME) }
            .sortedBy { it.seasonNumber }
            .also { Log.i(TAG, "jut.su seasons for '$slug': ${it.size}") }
    }.onFailure { Log.i(TAG, "findSeasons: ${it.message}") }.getOrElse { emptyList() }

    private suspend fun resolveTitleUrl(anime: Anime, base: String): String? {
        val aliases = orderedJutSuAliases(anime.titleRu, anime.title, anime.titleEn)
        val localTitles = aliases.ifEmpty { return null }
        for (query in jutSuSearchQueries(aliases)) {
            val selected = runCatching {
                val enc = URLEncoder.encode(query, "UTF-8")
                val resp = client.get("$base/lookfor/$enc") {
                    header(HttpHeaders.UserAgent, DEFAULT_UA)
                    header(HttpHeaders.AcceptLanguage, "ru,en;q=0.9")
                }
                val page = resp.bodyAsText()
                val finalUrl = resp.call.request.url.toString()
                selectJutSuSearchResponse(
                    localTitles = localTitles,
                    baseUrl = base,
                    responses = listOf(JutSuSearchResponse(finalUrl, page)),
                )
            }.onFailure {
                Log.w(TAG, "resolveTitleUrl '$query': ${it.message}")
            }.getOrNull()
            if (selected != null) return selected.url
            Log.i(TAG, "lookfor miss or weak match '$query'")
        }
        return null
    }

    /**
     * URL-ы ТОЛЬКО запрошенного сезона. Никаких перебросов на соседние: раньше список заканчивался
     * бессезонным «/episode-N.html» (это первый сезон) и «/season-2/…», поэтому для сезона, которого
     * на jut.su нет под таким номером, источник молча отдавал серию ПЕРВОГО сезона — пользователь
     * открывал 8-й сезон и попадал на 1-й. Лучше вернуть пусто и уступить Kodik/AnimeGo, чем
     * подсунуть чужую серию.
     *
     * У первого сезона обе формы (с префиксом и без) ведут на него же, поэтому их можно пробовать
     * обе — это не смена сезона.
     */
    private fun episodeUrlCandidates(base: String, slug: String, season: Int, episode: Int): List<String> {
        val ep = episode.coerceAtLeast(1)
        return if (season > 1) {
            listOf("$base/$slug/season-$season/episode-$ep.html")
        } else {
            listOf(
                "$base/$slug/episode-$ep.html",
                "$base/$slug/season-1/episode-$ep.html",
            )
        }
    }

    private suspend fun scrapeEpisode(pageUrl: String): ScrapedJutSuEpisode = runCatching {
        val html = getText(
            pageUrl,
            extraHeaders = mapOf(
                // Referer должен совпадать с доменом самой страницы, иначе на зеркале уедет на jut.su.
                "Referer" to originOf(pageUrl),
                HttpHeaders.AcceptLanguage to "ru,en;q=0.9",
            ),
        )
        val parsed = JutSuEpisodePageParser.parse(html, pageUrl)
        val firstMediaUrl = parsed.sources.firstOrNull()?.url
        val cookieHeader = firstMediaUrl?.let { mediaUrl ->
            client.cookies(Url(mediaUrl)).joinToString("; ") { cookie ->
                "${cookie.name}=${cookie.value}"
            }
        }.orEmpty()

        val videos = parsed.sources.map { source ->
            VetroVideo(
                url = source.url,
                label = source.label,
                resolution = source.resolution,
                headers = buildMap {
                    put("Referer", pageUrl)
                    put("User-Agent", DEFAULT_UA)
                    put("Accept", "video/webm,video/mp4,video/*;q=0.9,*/*;q=0.8")
                    put("Sec-Fetch-Dest", "video")
                    put("Sec-Fetch-Mode", "no-cors")
                    put("Sec-Fetch-Site", "same-site")
                    if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
                },
                timestamps = parsed.timestamps,
                isPreferred = source.resolution == 720 || source.label.contains("720"),
                downloadAllowed = true,
            )
        }.distinctBy { it.url }
            .sortedByDescending { it.resolution ?: 0 }

        val preferred = if (videos.isNotEmpty() && videos.none { it.isPreferred }) {
            listOf(videos.first().copy(isPreferred = true)) + videos.drop(1)
        } else {
            videos
        }
        val reference = parsed.toSkipReference(SOURCE_NAME)
        ScrapedJutSuEpisode(
            videos = preferred.map { it.copy(skipReference = reference) },
            reference = reference,
        )
    }.onFailure {
        Log.w(TAG, "scrape $pageUrl: ${it.message}")
    }.getOrElse {
        ScrapedJutSuEpisode(emptyList(), null)
    }

    override fun headersBuilder(): Map<String, String> = mapOf(
        HttpHeaders.UserAgent to DEFAULT_UA,
        HttpHeaders.AcceptLanguage to "ru,en;q=0.9",
    )

    companion object {
        private const val TAG = "JutSuSource"
        const val DEFAULT_BASE_URL = "https://jut.su"

        /** Совпадает со значением в `StreamingSeasonDiscovery.STREAMING_SOURCES`. */
        private const val SOURCE_NAME = "jut.su"
        private val EPISODE_HREF = Regex("""episode-(\d+)\.html""", RegexOption.IGNORE_CASE)
        private val SEASON_HREF = Regex("""season-(\d+)""", RegexOption.IGNORE_CASE)

        /** Хост без схемы, пути и порта-мусора: «https://Mirror.example/x» → «mirror.example». */
        private fun hostOf(url: String): String =
            url.substringAfter("://").substringBefore('/').substringBefore('?').trim().lowercase()

        /** Origin со слэшем — годится как Referer: «https://host/…» → «https://host/». */
        private fun originOf(url: String): String =
            url.substringBefore("://") + "://" + hostOf(url) + "/"

        /**
         * Ссылка на тайтл считается «нашей», если её хост — активный домен ИЛИ дефолтный jut.su:
         * сохранённые в WebLinks ссылки писались на jut.su ещё до включения зеркала, и терять их
         * при переключении нельзя. Сам URL никуда не запрашивается — из него берётся только слог,
         * а страницы уже строятся от активного домена.
         */
        internal fun isOwnTitleUrl(url: String, base: String): Boolean {
            val host = hostOf(url)
            return host.isNotEmpty() && (host == hostOf(base) || host == hostOf(DEFAULT_BASE_URL))
        }

        /**
         * Нормализует пользовательский ввод зеркала: «mirror.example», «http://mirror.example/»,
         * «https://mirror.example/anime/» → «https://mirror.example» (без хвостового слэша).
         * Мусор (пусто, пробелы, хост без точки) → null, чтобы источник откатился на дефолтный
         * домен, а не начал слать запросы в никуда.
         */
        internal fun normalizeMirror(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val scheme = when {
                trimmed.startsWith("https://", ignoreCase = true) -> "https"
                trimmed.startsWith("http://", ignoreCase = true) -> "http"
                else -> null
            }
            val host = hostOf(if (scheme == null) "https://$trimmed" else trimmed)
            if (!VALID_HOST.matches(host)) return null
            return "${scheme ?: "https"}://$host"
        }

        /** Домен (с необязательным портом); точка обязательна — так отсеивается ввод вида «asdf». */
        private val VALID_HOST =
            Regex("""^[a-z0-9](?:[a-z0-9.\-]*[a-z0-9])?\.[a-z0-9\-]{2,}(?::\d{1,5})?$""")

    }

    private data class ScrapedJutSuEpisode(
        val videos: List<VetroVideo>,
        val reference: VetroSkipReference?,
    )
}
