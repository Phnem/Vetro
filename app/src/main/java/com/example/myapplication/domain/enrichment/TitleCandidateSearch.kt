package com.example.myapplication.domain.enrichment

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.domain.normalizeForSearch

import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Вариант тайтла для ручного выбора: один и тот же тайтл из AniList, Shikimori и MAL склеен в одну
 * строку по MAL id (у Shikimori он равен собственному), поэтому пользователь видит одно название,
 * а не три дубля, а запись получает все id сразу.
 */
data class TitleCandidate(
    val key: String,
    /** Название, которое запишется как английское (в списке — крупной строкой). */
    val titleEn: String,
    val titleRu: String?,
    val posterUrl: String?,
    val year: Int?,
    val format: String?,
    val episodes: Int,
    val anilistId: Int?,
    val malId: Int?,
    val shikimoriId: Int?,
    val sources: List<String>,
)

/** Ответ каталога: имя каталога и сам результат. */
private typealias Hit = Pair<String, ApiSearchResult>

/** Обложка тайтла у одного из каталогов: [caption] — как тайтл назван там, чтобы узнать «не ту». */
data class PosterCandidate(
    val url: String,
    val source: String,
    val caption: String?,
)

/**
 * Поиск вариантов вместо «верь первому ответу»: раньше относительный матч брал первый ответ каталога
 * на русский запрос, и у разных записей появлялся один и тот же чужой тайтл. Здесь каталоги
 * опрашиваются все сразу, а выбирает человек.
 */
class TitleCandidateSearch(private val repository: AnimeRepository) {

    /**
     * Варианты для записи. [query] пуст — ищем по русскому и обычному названию записи; число —
     * это id (AniList, Shikimori/MAL), ищем по нему; иначе — произвольный запрос пользователя.
     */
    suspend fun titles(anime: Anime, query: String?): List<TitleCandidate> {
        val typed = query?.trim().orEmpty()
        val queries = if (typed.isNotEmpty()) listOf(typed) else defaultQueries(anime)
        val hits = if (typed.isNotEmpty() && typed.all(Char::isDigit)) {
            byId(typed.toIntOrNull() ?: return emptyList())
        } else {
            byQueries(queries)
        }
        val needles = queries.map { it.normalizeForSearch() }
        return merge(hits).sortedByDescending { relevance(it, needles) }.take(MAX_CANDIDATES)
    }

    /** Обложки из разных каталогов: по id записи и по поиску названия — с подписью, как тайтл назван там. */
    suspend fun posters(anime: Anime): List<PosterCandidate> {
        val hits = supervisorScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<Hit>>>()
            anime.anilistId?.let { id ->
                jobs += async { single("AniList") { repository.mediaByAnilistId(id).getOrNull() } }
            }
            (anime.shikimoriId ?: anime.malId)?.let { id ->
                jobs += async { single("Shikimori") { repository.shikimoriById(id, AppLanguage.EN).getOrNull() } }
            }
            anime.malId?.let { id ->
                jobs += async { single("MAL") { repository.malById(id, AppLanguage.EN).getOrNull() } }
            }
            for (q in defaultQueries(anime).take(2)) {
                jobs += async { many("AniList") { repository.searchAnimeAniListOnly(q, AppLanguage.EN, 4).getOrNull() } }
                jobs += async {
                    many("Shikimori") { repository.searchAnimeShikimoriOnly(q, AppLanguage.EN, true).getOrNull()?.take(4) }
                }
                jobs += async { many("MAL") { repository.searchAnimeMalOnly(q, AppLanguage.EN, 4).getOrNull() } }
                jobs += async { many("Kitsu") { repository.searchAnimeKitsuOnly(q, 4).getOrNull() } }
                jobs += async { many("AniLibria") { repository.searchAnimeAnilibriaOnly(q, 3).getOrNull() } }
            }
            jobs.awaitAll().flatten()
        }
        // Порядок: сначала найденное по id записи (обложка «своего» тайтла), затем поиск.
        val out = LinkedHashMap<String, PosterCandidate>()
        for ((source, result) in hits) {
            val url = result.posterUrl?.takeIf { it.startsWith("http") } ?: continue
            out.putIfAbsent(url, PosterCandidate(url, source, result.titleEn ?: result.title))
        }
        return out.values.take(MAX_POSTERS)
    }

    private suspend fun byId(id: Int): List<ApiSearchResult> = supervisorScope {
        listOf(
            async { single("AniList") { repository.mediaByAnilistId(id).getOrNull() } },
            async { single("Shikimori") { repository.shikimoriById(id, AppLanguage.EN).getOrNull() } },
        ).awaitAll().flatten().map { it.second }
    }

    private suspend fun byQueries(queries: List<String>): List<ApiSearchResult> = supervisorScope {
        queries.flatMap { q ->
            listOf(
                async { many("Shikimori") { repository.searchAnimeShikimoriOnly(q, AppLanguage.EN, true).getOrNull() } },
                async { many("AniList") { repository.searchAnimeAniListOnly(q, AppLanguage.EN, 8).getOrNull() } },
                async { many("MAL") { repository.searchAnimeMalOnly(q, AppLanguage.EN, 8).getOrNull() } },
            )
        }.awaitAll().flatten().map { it.second }
    }

    private suspend fun single(source: String, block: suspend () -> ApiSearchResult?): List<Hit> =
        listOfNotNull(withTimeoutOrNull(TIMEOUT_MS) { runCatching { block() }.getOrNull() }?.let { source to it })

    private suspend fun many(source: String, block: suspend () -> List<ApiSearchResult>?): List<Hit> =
        withTimeoutOrNull(TIMEOUT_MS) { runCatching { block() }.getOrNull() }.orEmpty().map { source to it }

    private fun defaultQueries(anime: Anime): List<String> =
        listOfNotNull(anime.titleRu, anime.title, anime.titleEn)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(3)

    private fun merge(hits: List<ApiSearchResult>): List<TitleCandidate> {
        val byKey = LinkedHashMap<String, Accumulator>()
        for (hit in hits) {
            val ids = idsOf(hit)
            val key = when {
                ids.mal != null -> "mal:${ids.mal}"
                ids.anilist != null -> "al:${ids.anilist}"
                ids.shikimori != null -> "sh:${ids.shikimori}"
                else -> continue
            }
            byKey.getOrPut(key) { Accumulator(key) }.add(hit, ids)
        }
        return byKey.values.mapNotNull { it.build() }
    }

    private data class Ids(val anilist: Int?, val mal: Int?, val shikimori: Int?)

    private fun idsOf(hit: ApiSearchResult): Ids {
        val external = hit.externalId?.toIntOrNull()
        return when (hit.source.lowercase()) {
            "anilist" -> Ids(anilist = external, mal = hit.malId, shikimori = null)
            "shikimori" -> Ids(anilist = null, mal = hit.malId ?: external, shikimori = external)
            "mal", "jikan" -> Ids(anilist = null, mal = external, shikimori = null)
            else -> Ids(null, null, null)
        }
    }

    private class Accumulator(val key: String) {
        var anilist: Int? = null
        var mal: Int? = null
        var shikimori: Int? = null
        var titleEn: String? = null
        var titleRu: String? = null
        var romaji: String? = null
        var poster: String? = null
        var year: Int? = null
        var format: String? = null
        var episodes: Int = 0
        val sources = LinkedHashSet<String>()

        fun add(hit: ApiSearchResult, ids: Ids) {
            anilist = anilist ?: ids.anilist
            mal = mal ?: ids.mal
            shikimori = shikimori ?: ids.shikimori
            sources += if (hit.source.equals("Jikan", ignoreCase = true)) "MAL" else hit.source
            poster = poster ?: hit.posterUrl?.takeIf { it.startsWith("http") }
            year = year ?: hit.seasonYear
            format = format ?: hit.format?.takeIf { it.isNotBlank() }
            if (hit.episodes > episodes) episodes = hit.episodes
            when (hit.source.lowercase()) {
                // У Shikimori `title` — ромадзи, `altTitle` — русское.
                "shikimori" -> {
                    romaji = romaji ?: hit.title
                    titleRu = titleRu ?: hit.altTitle ?: hit.titleRu
                }
                else -> {
                    titleEn = titleEn ?: hit.titleEn ?: hit.title
                    titleRu = titleRu ?: hit.titleRu
                }
            }
        }

        fun build(): TitleCandidate? {
            val en = (titleEn ?: romaji)?.takeIf { it.isNotBlank() } ?: return null
            return TitleCandidate(
                key = key,
                titleEn = en,
                titleRu = titleRu?.takeIf { it.isNotBlank() && !it.equals(en, ignoreCase = true) },
                posterUrl = poster,
                year = year,
                format = format,
                episodes = episodes,
                anilistId = anilist,
                malId = mal,
                shikimoriId = shikimori,
                sources = sources.toList(),
            )
        }
    }

    private fun relevance(candidate: TitleCandidate, needles: List<String>): Int {
        val names = listOfNotNull(candidate.titleEn, candidate.titleRu).map { it.normalizeForSearch() }
        var best = 0
        for (needle in needles.filter { it.isNotEmpty() }) {
            for (name in names) {
                best = maxOf(
                    best,
                    when {
                        name == needle -> 100
                        name.startsWith(needle) || needle.startsWith(name) -> 70
                        name.contains(needle) || needle.contains(name) -> 50
                        else -> 0
                    },
                )
            }
        }
        // Больше каталогов подтвердили тайтл — надёжнее.
        return best + candidate.sources.size * 5
    }

    private companion object {
        const val TIMEOUT_MS = 12_000L
        const val MAX_CANDIDATES = 14
        const val MAX_POSTERS = 18
    }
}
