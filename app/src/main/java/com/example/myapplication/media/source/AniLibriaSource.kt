package com.example.myapplication.media.source

import com.example.myapplication.network.AppJson
import android.util.Log
import com.example.myapplication.data.models.Anime
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.localplayer.domain.SkipKind
import com.example.myapplication.sync.TitleMatcher
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Native AniLiberty source.
 *
 * The old api.anilibria.tv/v3 endpoint currently fails TLS validation on a number of Android
 * devices. The current public API is anilibria.top/api/v1 and exposes direct hls_480/720/1080
 * links, so no WebView, yt-dlp or Python process is needed.
 */
class AniLibriaSource(
    client: HttpClient,
) : VetroHttpSource(client) {

    override val name: String = "AniLiberty"
    override val baseUrl: String = API_ORIGIN

    private val json = AppJson

    suspend fun resolveEpisode(
        anime: Anime,
        episodeNumber: Int,
        knownReleaseUrl: String? = null,
        seasonInfo: SeasonInfo? = null,
    ): List<VetroHoster> {
        if (episodeNumber <= 0) return emptyList()

        val knownAlias = extractAniLibriaAlias(knownReleaseUrl)
        val releaseRef = knownAlias?.let { ReleaseRef(alias = it, title = it) }
            ?: if (seasonInfo != null) {
                findRelease(anime.seasonSourceQuery(seasonInfo).anime, seasonInfo)
                    ?: findFranchiseSeasonRelease(anime, seasonInfo, episodeNumber)
            } else {
                findRelease(anime)
            }
        if (releaseRef == null) {
            Log.i(
                TAG,
                "No release matched '${anime.title}' " +
                    "S${seasonInfo?.seasonNumber ?: 1}E$episodeNumber " +
                    "(season title='${seasonInfo?.title.orEmpty()}')",
            )
            return emptyList()
        }
        val release = fetchRelease(releaseRef.alias)
        if (release == null) {
            Log.i(TAG, "Release '${releaseRef.alias}' did not load for ep=$episodeNumber")
            return emptyList()
        }
        val videos = parseAniLibriaV1Episode(release, episodeNumber)
        if (videos.isEmpty()) {
            Log.i(TAG, "No playable episode $episodeNumber in '${releaseRef.title}'")
            return emptyList()
        }

        Log.i(TAG, "Resolved '${releaseRef.title}' ep=$episodeNumber qualities=${videos.map { it.label }}")
        return listOf(
            VetroHoster(
                name = "AniLiberty",
                url = "$SITE_ORIGIN/anime/releases/release/${releaseRef.alias}",
                videos = videos,
                lazy = false,
            )
        )
    }

    private suspend fun findRelease(
        anime: Anime,
        requiredSeason: SeasonInfo? = null,
    ): ReleaseRef? {
        val queries = listOfNotNull(anime.title, anime.titleRu, anime.titleEn)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (queries.isEmpty()) return null

        val localTitles = queries
        for (query in queries) {
            val matches = searchTitles(query)
            val best = matches
                .map { obj -> obj to scoreTitle(localTitles, obj) }
                .maxByOrNull { it.second }
                ?: continue
            if (best.second < TitleMatcher.MATCH_THRESHOLD) continue
            val alias = best.first.string("alias")
                ?: best.first["id"]?.jsonPrimitive?.intOrNull?.toString()
                ?: continue
            val names = best.first["name"] as? JsonObject
            val releaseTitles = listOfNotNull(
                names?.string("main"),
                names?.string("english"),
                names?.string("alternative"),
            )
            if (
                requiredSeason != null &&
                !releaseIdentifiesSelectedSeason(releaseTitles, alias, requiredSeason)
            ) {
                continue
            }
            val title = best.first["name"]?.jsonObject?.string("main")
                ?: best.first["name"]?.jsonObject?.string("english")
                ?: query
            return ReleaseRef(alias, title)
        }
        return null
    }

    /**
     * Search results are individual releases and broad franchise titles usually put season one
     * first. Resolve candidate franchises, then select the Nth seasonal release after excluding
     * movies/specials. Ambiguous or weakly validated results fail closed.
     */
    private suspend fun findFranchiseSeasonRelease(
        anime: Anime,
        seasonInfo: SeasonInfo,
        episodeNumber: Int,
    ): ReleaseRef? {
        val localTitles = listOfNotNull(anime.titleRu, anime.title, anime.titleEn)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
        val candidates = buildList {
            for (query in localTitles) {
                addAll(searchTitles(query).filter { isAniLibriaFranchiseCandidate(localTitles, it) })
            }
        }.distinctBy { it["id"]?.jsonPrimitive?.contentOrNull ?: it.string("alias") }
            .take(MAX_FRANCHISE_CANDIDATES)
        if (candidates.isEmpty()) return null

        val franchises = buildList {
            for (candidate in candidates) {
                val id = candidate["id"]?.jsonPrimitive?.contentOrNull ?: continue
                addAll(fetchFranchises(id))
            }
        }
        val selected = selectAniLibriaSeasonRelease(
            franchises = franchises,
            localTitles = localTitles,
            seasonInfo = seasonInfo,
            episodeNumber = episodeNumber,
        ) ?: return null
        Log.i(
            TAG,
            "Franchise season ${seasonInfo.seasonNumber} -> '${selected.title}' (${selected.id})",
        )
        return ReleaseRef(selected.alias.ifBlank { selected.id }, selected.title)
    }

    private suspend fun searchTitles(query: String): List<JsonObject> = runCatching {
        val body = client.get("$API_ORIGIN/app/search/releases") {
            parameter("query", query)
        }.bodyAsText()
        when (val root = json.parseToJsonElement(body)) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> root["data"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
            else -> emptyList()
        }
    }.onFailure { Log.w(TAG, "v1 search failed: ${it.message}") }
        .getOrElse { emptyList() }

    private suspend fun fetchRelease(alias: String): JsonObject? = runCatching {
        val body = client.get("$API_ORIGIN/anime/releases/$alias").bodyAsText()
        json.parseToJsonElement(body) as? JsonObject
    }.onFailure { Log.w(TAG, "v1 release '$alias' failed: ${it.message}") }
        .getOrNull()

    private suspend fun fetchFranchises(releaseId: String): List<JsonObject> = runCatching {
        val body = client.get("$API_ORIGIN/anime/franchises/release/$releaseId").bodyAsText()
        when (val root = json.parseToJsonElement(body)) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> root["data"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
            else -> emptyList()
        }
    }.onFailure { Log.w(TAG, "v1 franchises '$releaseId' failed: ${it.message}") }
        .getOrElse { emptyList() }

    private fun scoreTitle(localTitles: List<String>, obj: JsonObject): Double {
        val names = obj["name"]?.jsonObject
        val remotes = listOfNotNull(
            names?.string("main"),
            names?.string("english"),
            names?.string("alternative"),
            obj.string("alias")?.replace('-', ' '),
        )
        return localTitles.maxOfOrNull { TitleMatcher.bestScore(it, remotes) } ?: 0.0
    }

    private data class ReleaseRef(val alias: String, val title: String)

    companion object {
        private const val TAG = "AniLibriaSource"
        private const val SITE_ORIGIN = "https://anilibria.top"
        private const val API_ORIGIN = "$SITE_ORIGIN/api/v1"
        private const val MAX_FRANCHISE_CANDIDATES = 10
    }
}

internal data class AniLibriaSeasonReleaseRef(
    val id: String,
    val alias: String,
    val title: String,
)

internal fun selectAniLibriaSeasonRelease(
    franchises: List<JsonObject>,
    localTitles: List<String>,
    seasonInfo: SeasonInfo,
    episodeNumber: Int,
): AniLibriaSeasonReleaseRef? {
    if (seasonInfo.seasonNumber <= 0 || episodeNumber <= 0 || localTitles.isEmpty()) return null
    val expectedEpisodes = seasonInfo.totalEpisodes
        ?: seasonInfo.episodes.takeIf { !seasonInfo.ongoing && it > 0 }

    val matches = franchises.mapNotNull { franchise ->
        val franchiseTitles = listOfNotNull(
            franchise.string("name"),
            franchise.string("name_english"),
        )
        val score = localTitles.maxOfOrNull { TitleMatcher.bestScore(it, franchiseTitles) } ?: 0.0
        if (score < TitleMatcher.MATCH_THRESHOLD) return@mapNotNull null

        val selected = franchise["franchise_releases"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.sortedBy { it["sort_order"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
            ?.mapNotNull { it["release"] as? JsonObject }
            ?.filter { release ->
                release["type"]?.jsonObject?.string("value")?.uppercase() in ANILIBRIA_SEASON_TYPES
            }
            ?.getOrNull(seasonInfo.seasonNumber - 1)
            ?: return@mapNotNull null

        if (selected["is_blocked_by_geo"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
        if (selected["is_blocked_by_copyrights"]?.jsonPrimitive?.booleanOrNull == true) {
            return@mapNotNull null
        }
        val episodes = selected["episodes_total"]?.jsonPrimitive?.intOrNull
        if (episodes != null && episodeNumber > episodes) return@mapNotNull null
        if (expectedEpisodes != null && episodes == null) return@mapNotNull null
        if (expectedEpisodes != null && episodes != expectedEpisodes) {
            return@mapNotNull null
        }
        val id = selected["id"]?.jsonPrimitive?.contentOrNull
            ?: return@mapNotNull null
        val alias = selected.string("alias").orEmpty()
        val names = selected["name"] as? JsonObject
        val releaseTitles = listOfNotNull(
            names?.string("main"),
            names?.string("english"),
            names?.string("alternative"),
        )
        if (releaseTitles.isEmpty()) return@mapNotNull null
        val belongsToFranchise = releaseTitles.map(::normalizeAniLibriaTitle).any { releaseTitle ->
            franchiseTitles.map(::normalizeAniLibriaTitle).any { franchiseTitle ->
                releaseTitle.contains(franchiseTitle) || franchiseTitle.contains(releaseTitle)
            }
        }
        if (!belongsToFranchise) return@mapNotNull null
        if (!releaseIdentifiesSelectedSeason(releaseTitles, alias, seasonInfo)) {
            return@mapNotNull null
        }
        val title = releaseTitles.first()
        AniLibriaSeasonReleaseRef(id, alias, title)
    }.distinctBy(AniLibriaSeasonReleaseRef::id)

    return matches.singleOrNull()
}

private fun isAniLibriaFranchiseCandidate(
    localTitles: List<String>,
    candidate: JsonObject,
): Boolean {
    val names = candidate["name"] as? JsonObject
    val remote = listOfNotNull(
        names?.string("main"),
        names?.string("english"),
        names?.string("alternative"),
    ).map(::normalizeAniLibriaTitle)
    return localTitles.map(::normalizeAniLibriaTitle).any { local ->
        local.isNotEmpty() && remote.any { it.contains(local) || local.contains(it) }
    }
}

private fun normalizeAniLibriaTitle(value: String): String =
    value.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")

/**
 * Опознание сезона по релизу — лестница, а не одиночная проверка.
 *
 * 1. Точное совпадение с сезонным названием.
 * 2. Порядковый маркер: номер сезона отдельным токеном в названиях релиза или в алиасе.
 *
 * Раньше непустое [SeasonInfo.title] обрывало проверку на первой ступени: сезонное название
 * приходит из AniList по-английски («Shokugeki no Souma: Ni no Sara»), а релизы AniLibria
 * называются по-русски («Повар-Боец Сома 2»), поэтому равенство было ложным ВСЕГДА при
 * сезоне ≥2 — и до работающей второй ступени дело не доходило.
 *
 * Нечёткого сравнения с сезонным названием здесь намеренно нет: русское имя релиза с
 * английским сезонным всё равно не сходится, зато порог пропускал бы усечённое название
 * соседнего сезона («Shokugeki no Souma» вместо «…San no Sara»).
 */
internal fun releaseIdentifiesSelectedSeason(
    releaseTitles: List<String>,
    alias: String,
    seasonInfo: SeasonInfo,
): Boolean {
    if (seasonInfo.seasonNumber <= 1) return true
    val exactSeasonTitle = seasonInfo.title?.trim().orEmpty()
    if (exactSeasonTitle.isNotEmpty()) {
        val normalizedSeasonTitle = normalizeAniLibriaTitle(exactSeasonTitle)
        if (releaseTitles.any { normalizeAniLibriaTitle(it) == normalizedSeasonTitle }) return true
    }
    val number = seasonInfo.seasonNumber.toString()
    val markerText = (releaseTitles + alias.replace('-', ' ')).joinToString(" ").lowercase()
    return Regex("""(?:^|[^\p{L}\p{N}])${Regex.escape(number)}(?:$|[^\p{L}\p{N}])""")
        .containsMatchIn(markerText)
}

private val ANILIBRIA_SEASON_TYPES = setOf("TV")

internal fun extractAniLibriaAlias(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return Regex(
        """(?i)https?://(?:www\.)?anilibria\.(?:top|tv)/(?:anime/releases/release|release)/([^/?#]+)"""
    ).find(url)?.groupValues?.getOrNull(1)
        ?.removeSuffix(".html")
        ?.takeIf { it.isNotBlank() }
}

internal fun parseAniLibriaV1Episode(
    release: JsonObject,
    episodeNumber: Int,
): List<VetroVideo> {
    val episode = release["episodes"]?.jsonArray
        ?.mapNotNull { it as? JsonObject }
        ?.firstOrNull {
            it["ordinal"]?.jsonPrimitive?.intOrNull == episodeNumber ||
                it["ordinal"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() == episodeNumber
        }
        ?: return emptyList()

    val timestamps = buildList {
        episode.skipTimestamp("opening", SkipKind.OPENING)?.let(::add)
        episode.skipTimestamp("ending", SkipKind.ENDING)?.let(::add)
    }
    val headers = mapOf(
        "Referer" to "https://anilibria.top/",
        "Origin" to "https://anilibria.top",
        "User-Agent" to VetroHttpSource.DEFAULT_UA,
    )

    return buildList {
        fun addVariant(key: String, resolution: Int) {
            val url = episode.string(key)?.takeIf { it.startsWith("http") } ?: return
            add(
                VetroVideo(
                    url = url,
                    label = "${resolution}p",
                    resolution = resolution,
                    headers = headers,
                    timestamps = timestamps,
                    isPreferred = resolution == 1080,
                )
            )
        }
        addVariant("hls_1080", 1080)
        addVariant("hls_720", 720)
        addVariant("hls_480", 480)
    }.distinctBy { it.url }
}

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

private fun JsonObject.skipTimestamp(
    key: String,
    kind: SkipKind,
): VetroTimestamp? {
    val range = this[key] as? JsonObject ?: return null
    val start = range["start"]?.jsonPrimitive?.intOrNull ?: return null
    val stop = range["stop"]?.jsonPrimitive?.intOrNull ?: return null
    if (start < 0 || stop <= start) return null
    return VetroTimestamp(start * 1_000L, stop * 1_000L, kind)
}
