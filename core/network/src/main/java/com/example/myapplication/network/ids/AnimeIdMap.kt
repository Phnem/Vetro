package com.example.myapplication.network.ids

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.TokenBucketRateLimiter
import com.example.myapplication.network.enrichment.CacheTtl
import com.example.myapplication.network.enrichment.CachePolicy
import com.example.myapplication.network.enrichment.EnrichmentHttp
import com.example.myapplication.network.enrichment.EnrichmentJson
import com.example.myapplication.network.enrichment.parse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Строка карты соответствия аниме: один тайтл во всех каталогах. */
data class AnimeIds(
    val ids: CrossIds,
    /** Все известные IMDb id (у франшиз их несколько). */
    val imdb: List<String> = emptyList(),
    /** `TV`, `MOVIE`, `OVA`, `ONA`, `SPECIAL`… как в источнике. */
    val format: String? = null,
    /** Сезон в нумерации TheTVDB и TMDB, если карта его знает. */
    val tvdbSeason: Int? = null,
    val tmdbSeason: Int? = null,
)

/**
 * Карта соответствия id аниме между AniList, MAL, Kitsu, AniDB, TheTVDB, TMDB, IMDb и Simkl —
 * открытый список Fribb/anime-lists (около 40 тысяч записей, ~1 МБ по сети). Скачивается раз в две
 * недели, лежит в кэше и один раз разбирается в индексы в памяти. Ключ не нужен.
 *
 * Для Vetro это мост «аниме ↔ TMDB/IMDb»: у записи из AniList появляется TMDB-id, по которому можно
 * достать русские названия, обложки и логотипы, а у записи из TMDB — AniList/MAL для расписаний.
 */
class AnimeIdMap(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
) {
    private val mutex = Mutex()
    private var index: Index? = null

    suspend fun find(kind: ExternalIdKind, value: String): LookupResult<AnimeIds> {
        return when (val loaded = load()) {
            is LookupResult.Found -> loaded.value.find(kind, value)?.let { LookupResult.Found(it) }
                ?: LookupResult.NoMatch
            is LookupResult.Failure -> loaded
            else -> LookupResult.NoMatch
        }
    }

    private suspend fun load(): LookupResult<Index> = mutex.withLock {
        index?.let { return@withLock LookupResult.Found(it) }
        val result = http.text(
            provider = "anime-lists",
            url = URL,
            rate = rate,
            policy = CachePolicy("anime-lists:mini:v1", 14 * CacheTtl.DAY, negativeTtlMs = CacheTtl.HOUR),
        ).parse { Index(AnimeIdMapParser.parse(it)) }
        if (result is LookupResult.Found) index = result.value
        result
    }

    internal class Index(entries: List<AnimeIds>) {
        private val byKind = HashMap<ExternalIdKind, HashMap<String, AnimeIds>>()

        init {
            for (e in entries) {
                put(ExternalIdKind.ANILIST, e.ids.anilist?.toString(), e)
                put(ExternalIdKind.MAL, e.ids.mal?.toString(), e)
                put(ExternalIdKind.KITSU, e.ids.kitsu?.toString(), e)
                put(ExternalIdKind.TVDB, e.ids.tvdb?.toString(), e)
                put(ExternalIdKind.TMDB_TV, e.ids.tmdbTv?.toString(), e)
                put(ExternalIdKind.TMDB_MOVIE, e.ids.tmdbMovie?.toString(), e)
                e.imdb.forEach { put(ExternalIdKind.IMDB, it, e) }
            }
        }

        private fun put(kind: ExternalIdKind, key: String?, entry: AnimeIds) {
            if (key == null) return
            byKind.getOrPut(kind) { HashMap() }.putIfAbsent(key, entry)
        }

        fun find(kind: ExternalIdKind, value: String): AnimeIds? = byKind[kind]?.get(value.trim())
    }

    private companion object {
        const val URL = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-mini.json"
    }
}

/**
 * Запасной онлайн-канал той же задачи — ARM (arm.haglund.dev): по одному id отдаёт остальные.
 * Нужен, когда в локальной карте нет свежего тайтла (карта обновляется раз в две недели).
 */
class ArmClient(
    private val http: EnrichmentHttp,
    private val rate: TokenBucketRateLimiter,
) {
    suspend fun ids(source: ArmSource, id: String): LookupResult<CrossIds> {
        val clean = id.trim()
        if (clean.isEmpty()) return LookupResult.NoMatch
        return http.text(
            provider = "ARM",
            url = "$BASE/ids?source=${source.value}&id=$clean",
            rate = rate,
            policy = CachePolicy("arm:${source.value}:$clean", 14 * CacheTtl.DAY, negativeTtlMs = CacheTtl.DAY),
        ).parse { ArmParser.parse(it) }
    }

    enum class ArmSource(val value: String) {
        ANILIST("anilist"), MAL("myanimelist"), KITSU("kitsu"), ANIDB("anidb"), IMDB("imdb"),
        TMDB("themoviedb"), TVDB("thetvdb"),
    }

    private companion object {
        const val BASE = "https://arm.haglund.dev/api/v2"
    }
}

internal object ArmParser {
    fun parse(body: String): CrossIds? {
        val o = EnrichmentJson.parseToJsonElement(body).jsonObject
        val ids = CrossIds(
            imdb = canonicalImdb((o["imdb"] as? JsonPrimitive)?.contentOrNull),
            tmdbTv = o.int("themoviedb"),
            tvdb = o.int("thetvdb"),
            mal = o.int("myanimelist"),
            anilist = o.int("anilist"),
            kitsu = o.int("kitsu"),
            anidb = o.int("anidb"),
            simkl = o.int("simkl"),
        )
        return ids.takeUnless { it.isEmpty }
    }

    private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull
}

internal object AnimeIdMapParser {
    fun parse(body: String): List<AnimeIds> {
        val array = EnrichmentJson.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val tmdb = o["themoviedb_id"] as? JsonObject
            val imdb = imdbList(o["imdb_id"])
            val season = o["season"] as? JsonObject
            val ids = CrossIds(
                imdb = imdb.firstOrNull(),
                tmdbMovie = firstInt(tmdb?.get("movie")),
                tmdbTv = firstInt(tmdb?.get("tv")),
                tvdb = o.int("tvdb_id"),
                mal = o.int("mal_id"),
                anilist = o.int("anilist_id"),
                kitsu = o.int("kitsu_id"),
                anidb = o.int("anidb_id"),
                simkl = o.int("simkl_id"),
            )
            if (ids.isEmpty) return@mapNotNull null
            AnimeIds(
                ids = ids,
                imdb = imdb,
                format = (o["type"] as? JsonPrimitive)?.contentOrNull,
                tvdbSeason = season?.int("tvdb"),
                tmdbSeason = season?.int("tmdb"),
            )
        }
    }

    private fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull

    /** `tv`/`movie` бывают числом или списком чисел. */
    private fun firstInt(e: JsonElement?): Int? = when (e) {
        is JsonPrimitive -> e.intOrNull
        is JsonArray -> e.firstNotNullOfOrNull { (it as? JsonPrimitive)?.intOrNull }
        else -> null
    }

    private fun imdbList(e: JsonElement?): List<String> = when (e) {
        is JsonPrimitive -> listOfNotNull(canonicalImdb(e.contentOrNull))
        is JsonArray -> e.mapNotNull { canonicalImdb((it as? JsonPrimitive)?.contentOrNull) }
        else -> emptyList()
    }
}
