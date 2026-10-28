package com.example.myapplication.domain.titleidentityintegration

import android.content.Context
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.domain.enrichment.TitleCandidate
import com.example.myapplication.domain.recommendations.ContentType
import com.example.myapplication.domain.recommendations.PoolEntry
import com.example.myapplication.domain.titleidentity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Local-only runtime. Asset IO never runs on the UI thread; no collection titles are logged. */
class TitleIdentityService(context: Context) {
    private val assets = context.applicationContext.assets
    private val runtime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TitleMatchPack.load(assets) }
    private val observations = ConcurrentHashMap<String, AtomicLong>()
    private suspend fun load() = withContext(Dispatchers.IO) { runtime }
    fun shadowCounts(): Map<String, Long> = observations.mapValues { it.value.get() }

    suspend fun allowResolvedEnrichment(anime: Anime, resolved: com.example.myapplication.network.ids.ResolvedTitle): Boolean {
        val model = load() ?: return true // Existing resolver remains the explicit fallback without a pack.
        val original = anime.toTitleIdentity()
        val candidate = TitleIdentity(listOfNotNull(resolved.titleEn, resolved.titleRu) + resolved.altTitles, original.kind, buildSet {
            val tmdb = if (original.kind == TitleKind.MOVIE) resolved.ids.tmdbMovie else resolved.ids.tmdbTv
            tmdb?.takeIf { it > 0 }?.let { add(TitleExternalId(if (original.kind == TitleKind.SERIES) TitleNamespace.TMDB_SERIES else TitleNamespace.TMDB_MOVIE, it.toString())) }
            resolved.ids.imdb?.let { add(TitleExternalId(TitleNamespace.IMDB, it)) }
            resolved.ids.kinopoisk?.takeIf { it > 0 }?.let { add(TitleExternalId(TitleNamespace.KINOPOISK, it.toString())) }
        })
        // Модель здесь только вето: пишем, если нет доказанного различия или конфликта ID.
        return withContext(Dispatchers.Default) { !TitleIdentityPolicies.vetoEnrichment(model.matcher.compare(original, candidate)) }
    }

    suspend fun <T> rerank(query: TitleIdentity, candidates: List<T>, identity: (T) -> TitleIdentity): List<T> {
        val model = load() ?: return candidates
        return withContext(Dispatchers.Default) {
            candidates.mapIndexed { index, candidate ->
                Triple(candidate, TitleIdentityPolicies.searchScore(model.matcher.compare(query, identity(candidate))), index)
            }.sortedWith(compareByDescending<Triple<T, Double, Int>> { it.second }.thenBy { it.third }).map { it.first }
        }
    }

    /** Observes only; the existing recommendation and franchise filters retain authority. */
    suspend fun observeRecommendations(pool: List<PoolEntry>, library: List<Anime>) {
        val model = load() ?: return
        withContext(Dispatchers.Default) {
            val known = HashMap<String, TitleIdentity>()
            val prefixes = HashMap<String, MutableList<TitleIdentity>>()
            for (anime in library) {
                val record = anime.toTitleIdentity()
                model.aliases.resolve(record).singleOrNull()?.let { known.putIfAbsent(it, record) }
                record.titles.forEach { title ->
                    val prefix = TitleMatchFeatures.key(title).take(4)
                    if (prefix.length >= 3) prefixes.getOrPut("${record.kind}:$prefix") { ArrayList() }.add(record)
                }
            }
            for (entry in pool.take(50)) {
                val candidate = entry.toTitleIdentity()
                val verified = model.aliases.resolve(candidate).singleOrNull()?.let(known::get)
                val comparables = if (verified != null) listOf(verified) else candidate.titles.flatMap {
                    prefixes["${candidate.kind}:${TitleMatchFeatures.key(it).take(4)}"].orEmpty()
                }.distinct().take(3)
                for (owned in comparables) {
                    val result = model.matcher.compare(owned, candidate)
                    val key = "${result.version}:${result.reason}:${result.verified}"
                    observations.getOrPut(key) { AtomicLong() }.incrementAndGet()
                }
            }
        }
    }
}

fun Anime.toTitleIdentity(): TitleIdentity {
    val kind = when (mediaType) { MediaType.ANIME -> TitleKind.ANIME; MediaType.MANGA -> TitleKind.MANGA; MediaType.MOVIE -> TitleKind.MOVIE; MediaType.SERIES -> TitleKind.SERIES }
    return TitleIdentity(listOfNotNull(title, titleEn, titleRu).distinct(), kind, buildSet {
        if (kind == TitleKind.ANIME || kind == TitleKind.MANGA) {
            malId?.takeIf { it > 0 }?.let { add(TitleExternalId(if (kind == TitleKind.MANGA) TitleNamespace.MAL_MANGA else TitleNamespace.MAL_ANIME, it.toString())) }
            anilistId?.takeIf { it > 0 }?.let { add(TitleExternalId(if (kind == TitleKind.MANGA) TitleNamespace.ANILIST_MANGA else TitleNamespace.ANILIST_ANIME, it.toString())) }
            shikimoriId?.takeIf { it > 0 }?.let { add(TitleExternalId(if (kind == TitleKind.MANGA) TitleNamespace.SHIKIMORI_MANGA else TitleNamespace.SHIKIMORI_ANIME, it.toString())) }
        }
        tmdbId?.takeIf { it > 0 && (kind == TitleKind.MOVIE || kind == TitleKind.SERIES) }?.let {
            add(TitleExternalId(if (kind == TitleKind.SERIES) TitleNamespace.TMDB_SERIES else TitleNamespace.TMDB_MOVIE, it.toString()))
        }
        imdbId?.let { add(TitleExternalId(TitleNamespace.IMDB, it)) }
        kinopoiskId?.takeIf { it > 0 }?.let { add(TitleExternalId(TitleNamespace.KINOPOISK, it.toString())) }
    }, releaseYear?.takeIf { it > 0 })
}

fun PoolEntry.toTitleIdentity(): TitleIdentity {
    val kind = when (contentType) { ContentType.ANIME -> TitleKind.ANIME; ContentType.MOVIE -> TitleKind.MOVIE; ContentType.TV -> TitleKind.SERIES }
    val r = result
    return TitleIdentity(listOfNotNull(r.title, r.altTitle, r.titleEn, r.titleRu).distinct(), kind, buildSet {
        if (kind == TitleKind.ANIME) {
            r.malId?.takeIf { it > 0 }?.let { add(TitleExternalId(TitleNamespace.MAL_ANIME, it.toString())) }
            r.externalId?.toIntOrNull()?.takeIf { it > 0 }?.let { id ->
                val namespace = when (r.source.lowercase()) { "mal", "jikan" -> TitleNamespace.MAL_ANIME; "anilist" -> TitleNamespace.ANILIST_ANIME; "shikimori" -> TitleNamespace.SHIKIMORI_ANIME; else -> null }
                namespace?.let { add(TitleExternalId(it, id.toString())) }
            }
        } else {
            (r.externalIds.tmdb ?: r.externalId?.toIntOrNull()?.takeIf { r.source.equals("tmdb", true) })?.takeIf { it > 0 }?.let {
                add(TitleExternalId(if (kind == TitleKind.SERIES) TitleNamespace.TMDB_SERIES else TitleNamespace.TMDB_MOVIE, it.toString()))
            }
        }
    }, r.seasonYear?.takeIf { it > 0 })
}

fun TitleCandidate.toTitleIdentity(kind: TitleKind): TitleIdentity = TitleIdentity(listOfNotNull(titleEn, titleRu), kind, buildSet {
    if (kind == TitleKind.ANIME || kind == TitleKind.MANGA) {
        malId?.takeIf { it > 0 }?.let { add(TitleExternalId(if (kind == TitleKind.MANGA) TitleNamespace.MAL_MANGA else TitleNamespace.MAL_ANIME, it.toString())) }
        anilistId?.takeIf { it > 0 }?.let { add(TitleExternalId(if (kind == TitleKind.MANGA) TitleNamespace.ANILIST_MANGA else TitleNamespace.ANILIST_ANIME, it.toString())) }
    }
}, year?.takeIf { it > 0 })
