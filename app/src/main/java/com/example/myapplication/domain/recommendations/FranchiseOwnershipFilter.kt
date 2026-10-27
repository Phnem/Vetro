package com.example.myapplication.domain.recommendations

import android.util.Log
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.network.FranchiseLinks

private const val TAG = "FranchiseOwnership"

/** Кэш связей франшизы на устройстве: связи меняются редко, а запрос к AniList на каждый пересчёт ни к чему. */
interface FranchiseLinksCache {
    suspend fun byAnilistId(id: Int, nowMillis: Long): FranchiseLinks?
    suspend fun byMalId(id: Int, nowMillis: Long): FranchiseLinks?
    suspend fun putAll(links: List<FranchiseLinks>, nowMillis: Long)
}

/**
 * Отсев рекомендаций из франшиз, которые уже есть в коллекции: продолжения и сезоны (Jujutsu Kaisen S2),
 * фильмы и спин-оффы (Demon Slayer: Mugen Train), другие экранизации той же истории (Hunter x Hunter 2011).
 * Сравнение по id и названию их не ловит: у такого тайтла свой id и своё название.
 *
 * Запись коллекции часто хранится на уровне франшизы («Frieren Season 2», «Mugen Train Arc»), поэтому
 * кандидат считается имеющимся, если по связям AniList он достигается от любого тайтла коллекции.
 * Граф обходится от кандидатов не глубже [MAX_DEPTH] шагов: дальше идут уже другие истории.
 *
 * Сбой сети никогда не прячет рекомендации: если связи получить не удалось, кандидат остаётся в выдаче.
 */
class FranchiseOwnershipFilter(
    private val fetchByAnilistIds: suspend (List<Int>) -> Result<List<FranchiseLinks>>,
    private val fetchByMalIds: suspend (List<Int>) -> Result<List<FranchiseLinks>>,
    private val cache: FranchiseLinksCache,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private data class Ref(val anilist: Int?, val mal: Int?)

    /** Связи, известные на данный момент, и соответствие MAL id → AniList id. */
    private class Index {
        val byAnilist = HashMap<Int, FranchiseLinks>()
        val malToAnilist = HashMap<Int, Int>()

        fun add(links: FranchiseLinks) {
            byAnilist[links.anilistId] = links
            links.malId?.let { malToAnilist[it] = links.anilistId }
            links.edges.forEach { edge -> edge.malId?.let { malToAnilist.putIfAbsent(it, edge.anilistId) } }
        }

        fun anilistOf(ref: Ref): Int? = ref.anilist ?: ref.mal?.let { malToAnilist[it] }
    }

    suspend fun dropOwned(pool: List<PoolEntry>, library: List<Anime>): List<PoolEntry> {
        val owned = library.filter { it.mediaType == MediaType.ANIME }.mapNotNull { it.ref() }
        if (owned.isEmpty() || pool.isEmpty()) return pool
        val candidates = pool.map { it to it.ref() }
        val index = Index()

        resolve(owned, index)
        val ownedIds = HashSet<Int>()
        for (ref in owned) {
            val id = index.anilistOf(ref) ?: continue
            ownedIds += id
            index.byAnilist[id]?.edges?.forEach { ownedIds += it.anilistId }
        }
        if (ownedIds.isEmpty()) return pool

        resolve(candidates.mapNotNull { it.second }, index)
        // frontier хранит ещё не раскрытые узлы каждого кандидата, visited — уже пройденные.
        val frontier = HashMap<Int, Set<Int>>()
        val visited = HashMap<Int, MutableSet<Int>>()
        val ownedCandidates = HashSet<Int>()
        for ((_, ref) in candidates) {
            val id = ref?.let(index::anilistOf) ?: continue
            if (id in ownedIds) ownedCandidates += id else {
                frontier[id] = setOf(id)
                visited[id] = mutableSetOf(id)
            }
        }

        repeat(MAX_DEPTH) {
            if (frontier.isEmpty()) return@repeat
            val toLoad = frontier.values.flatten().filter { it !in index.byAnilist }.toSet()
            if (toLoad.isNotEmpty()) resolve(toLoad.map { Ref(it, null) }, index)
            val next = HashMap<Int, Set<Int>>()
            for ((candidate, nodes) in frontier) {
                val neighbors = nodes.flatMap { node -> index.byAnilist[node]?.edges.orEmpty().map { it.anilistId } }
                    .filter { visited.getValue(candidate).add(it) }
                    .toSet()
                if (neighbors.any { it in ownedIds }) ownedCandidates += candidate
                else if (neighbors.isNotEmpty()) next[candidate] = neighbors
            }
            frontier.clear()
            frontier.putAll(next)
        }

        return candidates.filter { (_, ref) ->
            val id = ref?.let(index::anilistOf)
            id == null || id !in ownedCandidates
        }.map { it.first }
    }

    /** Добирает связи для [refs]: из кэша, затем одним батч-запросом по AniList id и одним по MAL id. */
    private suspend fun resolve(refs: Collection<Ref>, index: Index) {
        val now = nowMillis()
        val missingAnilist = LinkedHashSet<Int>()
        val missingMal = LinkedHashSet<Int>()
        for (ref in refs) {
            val known = index.anilistOf(ref)
            if (known != null && known in index.byAnilist) continue
            val cached = ref.anilist?.let { cache.byAnilistId(it, now) } ?: ref.mal?.let { cache.byMalId(it, now) }
            if (cached != null) {
                index.add(cached)
                continue
            }
            when {
                known != null -> missingAnilist += known
                ref.mal != null -> missingMal += ref.mal
            }
        }
        val fetched = ArrayList<FranchiseLinks>()
        if (missingAnilist.isNotEmpty()) {
            fetchByAnilistIds(missingAnilist.toList())
                .onSuccess { fetched += it }
                .onFailure { Log.w(TAG, "Franchise links by AniList id failed: ${it.message}") }
        }
        if (missingMal.isNotEmpty()) {
            fetchByMalIds(missingMal.toList())
                .onSuccess { fetched += it }
                .onFailure { Log.w(TAG, "Franchise links by MAL id failed: ${it.message}") }
        }
        if (fetched.isNotEmpty()) {
            fetched.forEach(index::add)
            cache.putAll(fetched, now)
        }
    }

    private fun Anime.ref(): Ref? =
        if (anilistId == null && malId == null && shikimoriId == null) null else Ref(anilistId, malId ?: shikimoriId)

    /** id Shikimori совпадает с MAL id, поэтому кандидаты Shikimori, MAL и Jikan ищутся по MAL. */
    private fun PoolEntry.ref(): Ref? {
        val sourceId = result.externalId?.toIntOrNull()
        val ref = when (result.source.lowercase()) {
            "anilist" -> Ref(sourceId, result.malId)
            "shikimori", "mal", "jikan" -> Ref(null, sourceId ?: result.malId)
            else -> return null
        }
        return ref.takeIf { it.anilist != null || it.mal != null }
    }

    companion object {
        /** Сколько шагов по связям проходим от кандидата: хватает на цепочку из пяти–шести сезонов. */
        const val MAX_DEPTH = 3
    }
}
