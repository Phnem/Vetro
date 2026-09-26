package com.example.myapplication.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Стабильные ключи одобренных сайтов. Должны совпадать с ключами в UI-каталоге (ApprovedSite). */
object WebLinkSites {
    const val ANILIBRIA_TOP = "anilibria_top"
    const val ANILIBRIA_TV = "anilibria_tv"
    const val ANIMEVOST = "animevost"
    const val JUTSU = "jutsu"
    const val YUMMYANIME_TV = "yummyanime_tv"
    const val HIANIME = "hianime"
    const val JUSTWATCH = "justwatch"
    const val NETFLIX = "netflix"
    const val CRUNCHYROLL = "crunchyroll"
}

/** Одна разрешённая ссылка: ключ сайта + прямой URL. */
data class WebLinkResolution(val siteKey: String, val url: String)

/**
 * Резолвер прямых ссылок на страницу тайтла по одобренному списку сайтов.
 *
 * Точечно резолвим каждый сайт whitelist — официальным API (AniLibria, Animevost, JustWatch),
 * скрапом страницы поиска (jut.su, yummyanime, hianime), либо deep-link на поиск для сайтов, которые
 * нельзя проверить из клиента (Netflix, Crunchyroll). Сайты одного тайтла резолвятся параллельно.
 *
 * Никакого обхода антибота: сайт блокирует/отвечает не тем → метод возвращает null, иконка не
 * появляется. Логируем КАЖДУЮ попытку и её исход (tag "WebLinkResolver").
 */
interface WebLinkResolver {
    suspend fun resolveRu(query: String): List<WebLinkResolution>
    suspend fun resolveEn(query: String): List<WebLinkResolution>
}

class KtorWebLinkResolver(
    private val client: HttpClient,
) : WebLinkResolver {

    private val json = AppJson

    override suspend fun resolveRu(query: String): List<WebLinkResolution> = coroutineScope {
        val q = query.trim()
        if (q.isEmpty()) {
            Log.i(TAG, "resolveRu skipped: empty query")
            return@coroutineScope emptyList()
        }
        Log.i(TAG, "resolveRu START query='$q'")
        // Верифицируемые прямые ссылки (параллельно): AniLibria (2 URL), Animevost, jut.su, YummyAnime.
        val aniLibria = async { resolveAniLibria(q) }
        val animevost = async { resolveAnimevost(q) }
        val jutsu = async { resolveJutsu(q) }
        val yummy = async { resolveYummyAnime(q) }

        val out = mutableListOf<WebLinkResolution>()
        aniLibria.await()?.let { alias ->
            out += WebLinkResolution(WebLinkSites.ANILIBRIA_TOP, "https://anilibria.top/anime/releases/release/$alias")
            out += WebLinkResolution(WebLinkSites.ANILIBRIA_TV, "https://www.anilibria.tv/release/$alias.html")
        }
        animevost.await()?.let { out += WebLinkResolution(WebLinkSites.ANIMEVOST, it) }
        jutsu.await()?.let { out += WebLinkResolution(WebLinkSites.JUTSU, it) }
        yummy.await()?.let { out += WebLinkResolution(WebLinkSites.YUMMYANIME_TV, it) }
        Log.i(TAG, "resolveRu DONE query='$q' found=${out.size} sites=${out.map { it.siteKey }}")
        out
    }

    override suspend fun resolveEn(query: String): List<WebLinkResolution> = coroutineScope {
        val q = query.trim()
        if (q.isEmpty()) {
            Log.i(TAG, "resolveEn skipped: empty query")
            return@coroutineScope emptyList()
        }
        Log.i(TAG, "resolveEn START query='$q'")
        val justwatch = async { resolveJustWatch(q) }

        val out = mutableListOf<WebLinkResolution>()
        val jw = justwatch.await()
        jw?.justwatchUrl?.let { out += WebLinkResolution(WebLinkSites.JUSTWATCH, it) }
        // Netflix: поиск на сайте закрыт логин-стеной, поэтому ТОЛЬКО прямая страница тайтла
        // из JustWatch-офферов (netflix.com/title/{id} открывается без логина). Нет оффера — нет иконки.
        jw?.netflixUrl?.let { out += WebLinkResolution(WebLinkSites.NETFLIX, it) }
        val enc = q.urlEncoded()
        // Crunchyroll: прямая страница из оффера, иначе поиск сайта (работает без логина).
        out += WebLinkResolution(
            WebLinkSites.CRUNCHYROLL,
            jw?.crunchyrollUrl ?: "https://www.crunchyroll.com/search?q=$enc",
        )
        // hianime: актуальное зеркало hianimez.cz; формат поиска — из адресной строки сайта.
        out += WebLinkResolution(
            WebLinkSites.HIANIME,
            "https://hianimez.cz/search/?s_keyword=$enc&orderby=popular&order=DESC&action=advanced_search&page=1",
        )
        Log.i(TAG, "resolveEn DONE query='$q' found=${out.size} sites=${out.map { it.siteKey }}")
        out
    }

    /** Ссылки из одного JustWatch-запроса: своя страница + прямые URL провайдеров из офферов. */
    private data class JustWatchLinks(
        val justwatchUrl: String?,
        val netflixUrl: String?,
        val crunchyrollUrl: String?,
    )

    // ============================================================
    // API-based (verified)
    // ============================================================

    /** AniLibria v1: alias релиза или null. Док: https://anilibria.top/api/docs/v1 */
    private suspend fun resolveAniLibria(query: String): String? = safe("anilibria") {
        val body = client.get("https://anilibria.top/api/v1/app/search/releases") {
            parameter("query", query)
            browserHeaders()
        }.bodyAsText()
        val root = json.parseToJsonElement(body)
        val arr = (root as? kotlinx.serialization.json.JsonArray)
            ?: root.jsonObject["data"]?.jsonArray
            ?: run { Log.i(TAG, "anilibria: unexpected shape"); return@safe null }
        val first = arr.asSequence()
            .map { it.jsonObject }
            .take(MAX_SEARCH_CANDIDATES)
            .firstOrNull { item ->
                val names = item["name"]?.jsonObject
                titleMatches(
                    query,
                    names?.get("main")?.jsonPrimitive?.content,
                    names?.get("english")?.jsonPrimitive?.content,
                    names?.get("alternative")?.jsonPrimitive?.content,
                )
            } ?: run { Log.i(TAG, "anilibria: no matching result for '$query'"); return@safe null }
        val alias = first["alias"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: run { Log.i(TAG, "anilibria: no alias"); return@safe null }
        Log.i(TAG, "anilibria: OK '$query' -> $alias")
        alias
    }

    /** Animevost API (POST form name=). DLE index.php?newsid= надёжно ведёт на страницу тайтла. */
    private suspend fun resolveAnimevost(query: String): String? = safe("animevost") {
        val body = client.post("https://api.animevost.org/v1/search") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(Parameters.build { append("name", query) }))
            browserHeaders()
        }.bodyAsText()
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            ?: run { Log.i(TAG, "animevost: no data for '$query'"); return@safe null }
        val first = data.asSequence()
            .map { it.jsonObject }
            .take(MAX_SEARCH_CANDIDATES)
            .firstOrNull { item ->
                titleMatches(query, item["title"]?.jsonPrimitive?.content, null)
            } ?: run { Log.i(TAG, "animevost: no matching result for '$query'"); return@safe null }
        val id = first["id"]?.jsonPrimitive?.intOrNull ?: run { Log.i(TAG, "animevost: no id"); return@safe null }
        Log.i(TAG, "animevost: OK '$query' -> id=$id")
        "https://v13.vost.pw/index.php?newsid=$id"
    }

    /**
     * JustWatch GraphQL: страница самого JustWatch + прямые URL провайдеров (Netflix/Crunchyroll)
     * из офферов. Партнёрские обёртки (crunchyroll.pxf.io?u=<encoded>) распаковываются до прямого URL.
     */
    private suspend fun resolveJustWatch(query: String): JustWatchLinks? = safe("justwatch") {
        val payload = buildJsonObject {
            put("operationName", "GetSearchTitles")
            put("variables", buildJsonObject {
                put("country", "US"); put("language", "en"); put("first", 1); put("searchQuery", query)
            })
            put(
                "query",
                "query GetSearchTitles(\$searchQuery:String!,\$country:Country!,\$language:Language!,\$first:Int!)" +
                    "{popularTitles(country:\$country,first:\$first,filter:{searchQuery:\$searchQuery})" +
                    "{edges{node{content(country:\$country,language:\$language){fullPath title}" +
                    "... on MovieOrShow{offers(country:\$country,platform:WEB){standardWebURL package{clearName}}}}}}}",
            )
        }
        val body = client.post("https://apis.justwatch.com/graphql") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload))
            browserHeaders()
        }.bodyAsText()
        val node = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?.get("popularTitles")?.jsonObject?.get("edges")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("node")?.jsonObject
            ?: run { Log.i(TAG, "justwatch: no results for '$query'"); return@safe null }
        val content = node["content"]?.jsonObject ?: run { Log.i(TAG, "justwatch: no content"); return@safe null }
        val title = content["title"]?.jsonPrimitive?.content
        if (!titleMatches(query, title, null)) {
            Log.i(TAG, "justwatch: title mismatch '$query' vs '$title'")
            return@safe null
        }
        val fullPath = content["fullPath"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        var netflix: String? = null
        var crunchyroll: String? = null
        node["offers"]?.jsonArray?.forEach { el ->
            val offer = el.jsonObject
            val url = offer["standardWebURL"]?.jsonPrimitive?.content ?: return@forEach
            val provider = offer["package"]?.jsonObject?.get("clearName")?.jsonPrimitive?.content ?: return@forEach
            when {
                netflix == null && provider.equals("Netflix", ignoreCase = true) ->
                    netflix = unwrapAffiliate(url)
                crunchyroll == null && provider.equals("Crunchyroll", ignoreCase = true) ->
                    crunchyroll = unwrapAffiliate(url)
            }
        }
        Log.i(TAG, "justwatch: OK '$query' path=$fullPath netflix=${netflix != null} crunchyroll=${crunchyroll != null}")
        JustWatchLinks(
            justwatchUrl = fullPath?.let { "https://www.justwatch.com$it" },
            netflixUrl = netflix,
            crunchyrollUrl = crunchyroll,
        )
    }

    /** Партнёрский редирект вида https://xxx.pxf.io/..?u=<encoded-url> → прямой URL из параметра u. */
    private fun unwrapAffiliate(url: String): String {
        val encoded = Regex("""[?&]u=([^&]+)""").find(url)?.groupValues?.get(1) ?: return url
        return runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(url)
    }

    /**
     * YummyAnime API (old.yummyani.me/api/search). Сайтовый id ≠ API anime_id, поэтому URL страницы
     * НЕЛЬЗЯ строить из anime_id — берём поле `anime_url` (собственная ссылка сайта на тайтл) и
     * подставляем домен yummyanime.tv. Сырое значение anime_url логируем для проверки формата.
     */
    /**
     * yummyanime.tv — DLE fast-search: POST engine/ajax/controller.php?mod=search, body query=.
     * Возвращает HTML-фрагмент со ссылками на реальные страницы `/{news_id}-{slug}.html` (это и есть
     * прямая ссылка сайта — сторонний old.yummyani.me давал другой id-space и открывал чужие тайтлы).
     */
    private suspend fun resolveYummyAnime(query: String): String? = safe("yummyanime") {
        val html = client.post("https://yummyanime.tv/engine/ajax/controller.php?mod=search") {
            contentType(ContentType.Application.FormUrlEncoded)
            header("X-Requested-With", "XMLHttpRequest")
            browserHeaders()
            setBody(FormDataContent(Parameters.build { append("query", query) }))
        }.bodyAsText()
        // Ссылки на страницы тайтлов: /{цифры}-{slug}.html (абсолютные или относительные).
        val href = Regex("""href="(https://yummyanime\.tv/\d+-[^"]+\.html)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""href="(/\d+-[^"]+\.html)"""").find(html)?.groupValues?.get(1)?.let { "https://yummyanime.tv$it" }
            ?: run { Log.i(TAG, "yummyanime: no result for '$query' (html ${html.length} b)"); return@safe null }
        Log.i(TAG, "yummyanime: OK '$query' -> $href")
        href
    }

    // ============================================================
    // Redirect-based (verified direct page)
    // ============================================================

    /**
     * jut.su: собственный search-endpoint `/lookfor/{q}` (из schema.org SearchAction) делает 302
     * прямо на страницу тайтла. Берём финальный URL после редиректа — это точная прямая ссылка.
     * Если увёл не на тайтл (нет совпадения) — null.
     */
    private suspend fun resolveJutsu(query: String): String? = safe("jutsu") {
        val resp = client.get("https://jut.su/lookfor/${query.urlEncoded()}") { browserHeaders() }
        resp.bodyAsText() // вычитываем тело, чтобы освободить соединение
        val finalUrl = resp.call.request.url.toString()
        val slug = Regex("""^https?://jut\.su/([a-z0-9\-]+)/?$""").find(finalUrl)?.groupValues?.get(1)
        if (slug == null || slug in JUTSU_NON_TITLE) {
            Log.i(TAG, "jutsu: no anime match '$query' (landed $finalUrl)")
            return@safe null
        }
        Log.i(TAG, "jutsu: OK '$query' -> https://jut.su/$slug/")
        "https://jut.su/$slug/"
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** Ловит обычные ошибки (логирует), но НЕ глотает CancellationException (structured concurrency). */
    private suspend inline fun <T> safe(tag: String, block: () -> T?): T? =
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.i(TAG, "$tag: failed — ${e.javaClass.simpleName}: ${e.message}")
            null
        }

    private fun io.ktor.client.request.HttpRequestBuilder.browserHeaders() {
        header(HttpHeaders.UserAgent, BROWSER_UA)
        header(HttpHeaders.AcceptLanguage, "ru,en;q=0.9")
    }

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun titleMatches(query: String, vararg candidates: String?): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        return candidates.filterNotNull().any { c ->
            val n = normalize(c)
            n.isNotEmpty() && (n.contains(q) || q.contains(n))
        }
    }

    private fun normalize(s: String): String = titleKey(s)

    companion object {
        private const val TAG = "WebLinkResolver"
        private const val MAX_SEARCH_CANDIDATES = 10
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Mobile Safari/537.36"
        private val JUTSU_NON_TITLE = setOf(
            "anime", "films", "film", "search", "reg", "user", "sign", "rules", "faq",
            "manga", "top", "new", "ongoing", "favorite", "history",
        )
    }
}
