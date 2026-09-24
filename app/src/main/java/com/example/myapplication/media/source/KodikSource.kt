package com.example.myapplication.media.source

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.myapplication.data.models.Anime
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.sync.TitleMatcher
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native YummyAnime → Kodik resolver. YummyAnime provides episode/translation metadata and iframe
 * URLs; Kodik is then resolved into complete synchronized renditions. We deliberately expose each
 * dubbing as a separate [VetroHoster], never as an external audio track over unrelated video.
 *
 * К YummyAnime добавлен второй, независимый путь к iframe — [KodikDirectSearch] по kodik-api.
 * Пути идут параллельно и не зависят друг от друга: YummyAnime индексирует не всё, а падение
 * любого из двух не должно лишать пользователя второго.
 */
class KodikSource(
    private val client: OkHttpClient,
    private val directSearch: KodikDirectSearch? = null,
) {
    suspend fun resolveEpisode(
        anime: Anime,
        episodeNumber: Int,
        seasonInfo: SeasonInfo? = null,
    ): List<VetroHoster> {
        if (episodeNumber <= 0) return emptyList()
        val seasonQuery = anime.seasonSourceQuery(seasonInfo)
        return withContext(Dispatchers.IO) {
            val (yummy, direct) = coroutineScope {
                val yummyTask = async(Dispatchers.IO) {
                    // Путь больше не отключается на сезонах без собственного названия:
                    // запрос всегда несёт пригодный набор алиасов.
                    runCatchingCancellable {
                        yummyCandidates(seasonQuery.anime, episodeNumber, seasonInfo)
                    }
                        .onFailure { Log.i(TAG, "Yummy path failed: ${it.message}") }
                        .getOrDefault(emptyList<KodikIframeCandidate>())
                }
                val directTask = async(Dispatchers.IO) {
                    val search = directSearch ?: return@async emptyList<KodikIframeCandidate>()
                    runCatchingCancellable {
                        search.findEpisodeCandidates(
                            anime = seasonQuery.anime,
                            episodeNumber = episodeNumber,
                            seasonNumber = seasonQuery.seasonNumber,
                            seasonIdentifiable = seasonQuery.seasonIdentifiable,
                            limit = MAX_DUBBINGS,
                        )
                    }
                        .onFailure { Log.i(TAG, "Kodik direct path failed: ${it.message}") }
                        .getOrDefault(emptyList<KodikIframeCandidate>())
                }
                awaitAll(yummyTask, directTask)
            }

            // Дедуп до экстрактора, а не после: один и тот же iframe приходит обоими путями,
            // а resolve() — это ещё 2-3 сетевых запроса на каждый адрес.
            val candidates = (yummy + direct).distinctBy { it.iframeUrl }.take(MAX_DUBBINGS)
            if (candidates.isEmpty()) {
                Log.i(TAG, "No Kodik candidates for '${anime.title}' ep=$episodeNumber")
                return@withContext emptyList()
            }

            coroutineScope {
                candidates.map { candidate ->
                    async(Dispatchers.IO) {
                        val videos = KodikExtractor(client).resolve(candidate.iframeUrl)
                        if (videos.isEmpty()) null else VetroHoster(
                            name = "Kodik · ${candidate.dubbing}",
                            url = candidate.iframeUrl,
                            videos = videos,
                            lazy = false,
                        )
                    }
                }.awaitAll().filterNotNull()
            }.also { hosters ->
                Log.i(
                    TAG,
                    "Resolved Kodik S${seasonInfo?.seasonNumber ?: 1}E$episodeNumber " +
                        "dubbings=${hosters.size} " +
                        "(yummy=${yummy.size}, direct=${direct.size})",
                )
            }
        }
    }

    /** Прежний путь: YummyAnime отдаёт метаданные серий и готовые iframe_url плеера Kodik. */
    private suspend fun yummyCandidates(
        anime: Anime,
        episodeNumber: Int,
        seasonInfo: SeasonInfo?,
    ): List<KodikIframeCandidate> {
        val release = findRelease(anime, seasonInfo) ?: return emptyList()
        val slug = release.optString("anime_url").trim().ifBlank { return emptyList() }
        val details = requestJson("$API_ORIGIN/anime/$slug?need_videos=true")
            ?.optJSONObject("response")
            ?: return emptyList()
        return details.optJSONArray("videos").objects()
            .filter { entry ->
                val number = entry.optString("number").trim()
                val player = entry.optJSONObject("data")?.optString("player").orEmpty()
                episodeMatches(number, episodeNumber) && player.contains("Kodik", ignoreCase = true)
            }
            .distinctBy { entry ->
                val data = entry.optJSONObject("data")
                data?.optString("dubbing").orEmpty() + "|" + entry.optString("iframe_url")
            }
            .take(MAX_DUBBINGS)
            .mapNotNull { entry ->
                val iframe = normalizeUrl(entry.optString("iframe_url")) ?: return@mapNotNull null
                val dubbing = entry.optJSONObject("data")
                    ?.optString("dubbing")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: "Kodik"
                KodikIframeCandidate(iframeUrl = iframe, dubbing = dubbing)
            }
    }

    /**
     * Ищет релиз, доказуемо относящийся к запрошенному сезону.
     *
     * Отсев по сезону стоит ДО выбора лучшего, а не после: русское название франшизы — это
     * название первого сезона (см. [seasonSourceQuery]), поэтому при сезоне ≥2 релиз первого
     * сезона выигрывал бы счёт у правильного и «лучший из всех» всегда оказывался бы чужим.
     * Отсеяв неподтверждённые, лестница запросов идёт дальше и может найти нужный релиз
     * английским названием сезона.
     */
    private suspend fun findRelease(anime: Anime, seasonInfo: SeasonInfo?): JSONObject? {
        val queries = listOfNotNull(anime.titleRu, anime.title, anime.titleEn)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
        var rejectedBySeason = 0
        for (query in queries) {
            val url = "$API_ORIGIN/anime".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "20")
                .addQueryParameter("offset", "0")
                .addQueryParameter("q", query)
                .build()
            val items = requestJson(url.toString())?.optJSONArray("response").objects()
            val localTitles = queries
            val eligible = items.filter { item ->
                yummyReleaseServesSeason(
                    releaseTitles = releaseTitles(item),
                    slug = item.optString("anime_url").trim(),
                    seasonInfo = seasonInfo,
                ).also { if (!it) rejectedBySeason++ }
            }
            val best = eligible
                .map { item -> item to score(localTitles, item) }
                .take(MAX_SEARCH_CANDIDATES)
                .maxByOrNull { it.second }
            if (best != null && best.second >= TitleMatcher.MATCH_THRESHOLD) return best.first
        }
        if (rejectedBySeason > 0) {
            Log.i(
                TAG,
                "Yummy: no season-confirmed release for S${seasonInfo?.seasonNumber} " +
                    "($rejectedBySeason rejected)",
            )
        }
        return null
    }

    private fun releaseTitles(item: JSONObject): List<String> = buildList {
        item.optString("title").takeIf(String::isNotBlank)?.let(::add)
        item.optString("original").takeIf(String::isNotBlank)?.let(::add)
        item.optJSONArray("other_titles").strings().forEach(::add)
    }

    private fun score(localTitles: List<String>, item: JSONObject): Double {
        val remote = releaseTitles(item)
        return localTitles.maxOfOrNull { TitleMatcher.bestScore(it, remote) } ?: 0.0
    }

    private suspend fun requestJson(url: String): JSONObject? = runCatchingCancellable {
        val request = Request.Builder().url(url).applyApiHeaders().build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                Log.i(TAG, "Yummy API HTTP ${response.code}: $url")
                return@use null
            }
            response.body?.string().orEmpty().takeIf { it.trimStart().startsWith("{") }?.let(::JSONObject)
        }
    }.onFailure { Log.i(TAG, "Yummy API failed: ${it.message}") }.getOrNull()

    private fun Request.Builder.applyApiHeaders(): Request.Builder = apply {
        header("Accept", "application/json,*/*")
        header("Lang", "ru")
        header("X-Application", PUBLIC_TOKEN)
        header("User-Agent", USER_AGENT)
    }

    private fun episodeMatches(raw: String, episode: Int): Boolean =
        raw.toIntOrNull() == episode ||
            Regex("""^\s*(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() == episode

    companion object {
        private const val TAG = "KodikSource"
        private const val API_ORIGIN = "https://api.yani.tv"
        private const val PUBLIC_TOKEN = "yp_ls7tmtv9n74hp"
        private const val MAX_SEARCH_CANDIDATES = 10
        private const val MAX_DUBBINGS = 8
        internal const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/132.0.0.0 Mobile Safari/537.36"
    }
}

private class KodikExtractor(
    private val client: OkHttpClient,
) {
    suspend fun resolve(iframeUrl: String): List<VetroVideo> {
        val page = fetch(iframeUrl, SITE_REFERER) ?: return emptyList()
        val context = parseContext(page, iframeUrl) ?: return emptyList()
        val endpoint = detectEndpoint(page, iframeUrl) ?: DEFAULT_ENDPOINT
        val response = requestLinks(endpoint, iframeUrl, context) ?: return emptyList()
        val links = response.optJSONObject("links") ?: return emptyList()
        val headers = mapOf(
            "Referer" to iframeUrl,
            "Origin" to originOf(iframeUrl),
            "User-Agent" to KodikSource.USER_AGENT,
        )
        val qualities = buildList {
            val iterator = links.keys()
            while (iterator.hasNext()) add(iterator.next())
        }.sortedByDescending { it.substringBefore('p').toIntOrNull() ?: 0 }

        return buildList {
            qualities.forEach { quality ->
                val sources = links.optJSONArray(quality).objects()
                sources.forEachIndexed { index, source ->
                    val url = source.optString("src").decodeKodikSource()?.let(::normalizeUrl)
                        ?: return@forEachIndexed
                    val resolution = quality.substringBefore('p').toIntOrNull()
                    add(
                        VetroVideo(
                            url = url,
                            label = if (index == 0) quality else "$quality #${index + 1}",
                            resolution = resolution,
                            headers = headers,
                            isPreferred = resolution == 720,
                        )
                    )
                }
            }
        }.distinctBy { it.url }
    }

    private suspend fun fetch(url: String, referer: String): String? = runCatchingCancellable {
        val request = Request.Builder().url(url)
            .header("User-Agent", KodikSource.USER_AGENT)
            .header("Referer", referer)
            .build()
        client.newCall(request).await().use { response ->
            response.body?.string().orEmpty().takeIf { response.isSuccessful }
        }
    }.onFailure { Log.i(TAG, "iframe failed: ${it.message}") }.getOrNull()

    private suspend fun requestLinks(
        endpoint: String,
        referer: String,
        context: PlayerContext,
    ): JSONObject? {
        if (REQUIRED_PARAMS.any { context.params.optString(it).isBlank() }) return null
        val body = FormBody.Builder()
            .add("hash", context.hash)
            .add("id", context.id)
            .add("type", context.type)
            .add("d", context.params.optString("d"))
            .add("d_sign", context.params.optString("d_sign"))
            .add("pd", context.params.optString("pd"))
            .add("pd_sign", context.params.optString("pd_sign"))
            .add("ref", Uri.decode(context.params.optString("ref")))
            .add("ref_sign", context.params.optString("ref_sign"))
            .add("bad_user", "true")
            .add("cdn_is_working", "true")
            .build()

        val hosts = linkedSetOf<String>()
        normalizeHost(context.params.optString("d"))?.let(hosts::add)
        hosts += context.host
        normalizeHost(context.params.optString("pd"))?.let(hosts::add)
        hosts += "https://kodik.cc"
        hosts += "https://kodik.info"

        hosts.forEach { host ->
            val url = when {
                endpoint.startsWith("http") -> endpoint
                endpoint.startsWith('/') -> "$host$endpoint"
                else -> "$host/$endpoint"
            }
            val parsed = runCatchingCancellable {
                val request = Request.Builder().url(url).post(body)
                    .header("User-Agent", KodikSource.USER_AGENT)
                    .header("Referer", referer)
                    .header("Origin", originOf(referer))
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .build()
                client.newCall(request).await().use { response ->
                    val text = response.body?.string().orEmpty()
                    text.takeIf { it.trimStart().startsWith("{") }?.let(::JSONObject)
                }
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parseContext(page: String, iframeUrl: String): PlayerContext? {
        val type = quotedAfter(page, "vInfo.type =") ?: quotedAfter(page, "var type =")
        val hash = quotedAfter(page, "vInfo.hash =")
        val id = quotedAfter(page, "vInfo.id =") ?: quotedAfter(page, "var videoId =")
        val raw = quotedAfter(page, "var urlParams =") ?: quotedAfter(page, "urlParams =")
        val params = raw?.let(::parseParams)
        if (type.isNullOrBlank() || hash.isNullOrBlank() || id.isNullOrBlank() || params == null) {
            Log.i(TAG, "context missing for $iframeUrl")
            return null
        }
        return PlayerContext(originOf(iframeUrl), type, id, hash, params)
    }

    private fun parseParams(raw: String): JSONObject? {
        val normalized = raw.replace("\\\"", "\"").replace("\\/", "/")
        val parsed = sequenceOf(raw, normalized, normalized.replace("\\\\", "\\"))
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull() ?: return null
        REQUIRED_PARAMS.forEach { key ->
            if (parsed.optString(key).isBlank()) {
                Regex("""["']${Regex.escape(key)}["']\s*:\s*["']([^"']*)["']""")
                    .find(normalized)?.groupValues?.getOrNull(1)?.let { parsed.put(key, it) }
            }
        }
        return parsed
    }

    private fun quotedAfter(source: String, anchor: String): String? {
        val anchorIndex = source.indexOf(anchor, ignoreCase = true)
        if (anchorIndex < 0) return null
        var pos = anchorIndex + anchor.length
        while (pos < source.length && source[pos].isWhitespace()) pos++
        if (pos >= source.length || source[pos] !in charArrayOf('\'', '"')) return null
        val quote = source[pos++]
        val out = StringBuilder()
        var escaped = false
        while (pos < source.length) {
            val char = source[pos++]
            if (escaped) {
                out.append(char)
                escaped = false
            } else if (char == '\\') {
                out.append(char)
                escaped = true
            } else if (char == quote) {
                return out.toString()
            } else {
                out.append(char)
            }
        }
        return null
    }

    private suspend fun detectEndpoint(page: String, iframeUrl: String): String? {
        val marker = "/assets/js/app.player_single."
        val start = page.indexOf(marker, ignoreCase = true)
        if (start < 0) return null
        val end = page.indexOf(".js", start, ignoreCase = true)
        if (end < 0) return null
        val scriptUrl = originOf(iframeUrl) + page.substring(start, end + 3)
        val script = fetch(scriptUrl, iframeUrl) ?: return null
        val encoded = Regex("""url\s*:\s*atob\(["']([^"']+)["']\)""")
            .find(script)?.groupValues?.getOrNull(1) ?: return null
        return encoded.decodeBase64()
    }

    private data class PlayerContext(
        val host: String,
        val type: String,
        val id: String,
        val hash: String,
        val params: JSONObject,
    )

    companion object {
        private const val TAG = "KodikExtractor"
        private const val SITE_REFERER = "https://yummyani.me/"
        private const val DEFAULT_ENDPOINT = "/ftor"
        private val REQUIRED_PARAMS = listOf("d", "d_sign", "pd", "pd_sign", "ref", "ref_sign")
    }
}

/**
 * Может ли найденный релиз обслуживать запрошенный сезон.
 *
 * Yummy-путь ищет по набору алиасов, где русское название франшизы — это название ПЕРВОГО
 * сезона: оно намеренно не сужается, потому что иначе русские каталоги перестают находить тайтл
 * вовсе (см. [seasonSourceQuery]). Значит, чинить надо не запрос, а результат.
 *
 * Сезон 1 и «сезон не выбран» однозначны — доказывать нечего. Для сезона ≥2 работает инвариант
 * «лучше пусто, чем чужой сезон»: без доказательства путь отдаёт пусто, а не первый сезон.
 * Доказательством считается точное совпадение с сезонным названием либо порядковый маркер —
 * ровно так же, как у AniLibria ([releaseIdentifiesSelectedSeason]); слаг участвует как алиас,
 * потому что русские релизы часто несут номер только в нём.
 */
internal fun yummyReleaseServesSeason(
    releaseTitles: List<String>,
    slug: String,
    seasonInfo: SeasonInfo?,
): Boolean {
    if (seasonInfo == null) return true
    return releaseIdentifiesSelectedSeason(releaseTitles, slug, seasonInfo)
}

// internal, а не private: тем же хелпером разбирает ответ соседний KodikDirectSearch.
internal fun JSONArray?.objects(): List<JSONObject> = buildList {
    val array = this@objects ?: return@buildList
    for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add)
}

private fun JSONArray?.strings(): List<String> = buildList {
    val array = this@strings ?: return@buildList
    for (index in 0 until array.length()) {
        array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
    }
}

// internal: KodikDirectSearch обязан приводить ссылки Kodik ровно так же, что и этот файл.
internal fun normalizeUrl(raw: String): String? {
    val value = raw.trim().replace("\\/", "/")
    return when {
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("//") -> "https:$value"
        else -> null
    }
}

private fun normalizeHost(raw: String): String? {
    val candidate = raw.trim().removeSuffix("/").takeIf(String::isNotBlank) ?: return null
    val withScheme = if (candidate.startsWith("http")) candidate else "https://$candidate"
    return runCatching { originOf(withScheme) }.getOrNull()
}

private fun originOf(url: String): String = runCatching {
    val uri = URI(url)
    "${uri.scheme}://${uri.host}" + if (uri.port > 0) ":${uri.port}" else ""
}.getOrDefault("https://kodik.info")

/**
 * Сдвиг у Kodik меняется партиями, а не на каждой ссылке: попадание с прошлого раза
 * экономит до 25 лишних base64-декодирований на каждый элемент sources.
 * Кэш process-wide, потому что KodikExtractor пересоздаётся на каждый iframe,
 * а @Volatile нужен из-за параллельных корутин на Dispatchers.IO.
 */
private object KodikCipherCache {
    @Volatile
    var lastShift: Int? = null
}

private fun String.decodeKodikSource(): String? {
    if (startsWith("http://") || startsWith("https://") || startsWith("//")) return this
    val cached = KodikCipherCache.lastShift
    if (cached != null) {
        tryShift(cached)?.let { return it }
    }
    for (shift in 0..25) {
        if (shift == cached) continue // уже пробовали выше
        tryShift(shift)?.let {
            KodikCipherCache.lastShift = shift
            return it
        }
    }
    return null
}

private fun String.tryShift(shift: Int): String? {
    val shifted = map { char ->
        when (char) {
            in 'a'..'z' -> 'a' + ((char - 'a' + shift) % 26)
            in 'A'..'Z' -> 'A' + ((char - 'A' + shift) % 26)
            else -> char
        }
    }.joinToString("")
    val decoded = shifted.decodeBase64() ?: return null
    return decoded.takeIf { it.startsWith("http") || it.startsWith("//") }
}

private fun String.decodeBase64(): String? = runCatching {
    val padded = this + "=".repeat((4 - length % 4) % 4)
    String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
}.getOrNull()
