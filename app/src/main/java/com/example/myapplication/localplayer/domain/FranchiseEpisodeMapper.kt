package com.example.myapplication.localplayer.domain

import android.util.Log
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.network.AniListRemoteDataSource
import com.example.myapplication.network.EpisodeCheckMedia
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Один сезон франшизы для сдвига: MAL id + число серий. */
data class FranchiseSeason(val malId: Int, val episodes: Int)

/**
 * Чистая математика сдвига: по упорядоченной цепочке сезонов и абсолютному номеру серии находит,
 * какому сезону и какому внутрисезонному номеру он соответствует. Вынесено отдельно ради юнит-тестов.
 *
 * Пример: [S1(24), S2(12)], abs=26 → (S2.malId, 2).
 */
internal fun offsetAbsoluteEpisode(chain: List<FranchiseSeason>, absoluteEpisode: Int): Pair<Int, Int>? {
    if (absoluteEpisode <= 0) return null
    var rem = absoluteEpisode
    for (s in chain) {
        if (s.episodes <= 0) continue
        if (rem <= s.episodes) return s.malId to rem
        rem -= s.episodes
    }
    return null
}

/** Цепочка сезонов из сохранённого расклада и сезон, к которому относятся запрошенные id. */
internal data class StoredChain(val self: FranchiseSeason, val chain: List<FranchiseSeason>)

/**
 * Цепочка из уже сохранённого расклада сезонов ([com.example.myapplication.data.local.SeasonEpisodesStore]).
 *
 * Раскладку франшизы резолвер сезонов уже собрал обходом PREQUEL/SEQUEL — тем же, что делает
 * маппер. Годится она, только если у каждого обычного сезона есть MAL id и число серий: иначе
 * сдвиг посчитать нечем, и маппер идёт в сеть как раньше.
 */
internal fun chainFromLayout(seasons: List<SeasonInfo>, anilistId: Int?, malId: Int?): StoredChain? {
    val regular = seasons.filterNot { it.isSpecial }.sortedBy { it.seasonNumber }
    if (regular.isEmpty()) return null
    val chain = regular.map { season ->
        val mid = season.malId?.takeIf { it > 0 } ?: return null
        val eps = (season.totalEpisodes ?: season.episodes).takeIf { it > 0 } ?: return null
        FranchiseSeason(mid, eps)
    }
    val selfIndex = regular.indexOfFirst { season ->
        (anilistId != null && season.anilistId == anilistId) || (malId != null && season.malId == malId)
    }
    if (selfIndex < 0) return null
    return StoredChain(chain[selfIndex], chain)
}

/**
 * Сопоставляет абсолютный номер локальной серии конкретной записи MAL внутри франшизы — чтобы
 * AniSkip (который знает только id сезона + внутрисезонный номер) нашёл тайминги для мультисезонных
 * папок со сквозной нумерацией.
 *
 * Метод «математический сдвиг»: строим упорядоченную цепочку TV-сезонов франшизы (обход PREQUEL/
 * SEQUEL через уже существующий [AniListRemoteDataSource], каждый узел несёт malId + число серий),
 * затем вычитаем длины предыдущих сезонов. Определение «абсолютный vs посерийный»: если номер не
 * превышает число серий самой записи — считаем посерийным и сдвиг не нужен.
 *
 * Всё best-effort: любая неудача (нет данных/сети) → `null`, и вызывающий откатывается к сырым
 * malId + номер.
 */
class FranchiseEpisodeMapper(
    private val aniList: AniListRemoteDataSource,
    private val seasonStore: SeasonEpisodesStore,
) {
    data class Mapped(val malId: Int, val episode: Int)

    private val mutex = Mutex()
    private val chainCache = HashMap<Int, List<FranchiseSeason>>()

    suspend fun resolve(anilistId: Int?, malId: Int?, absoluteEpisode: Int): Mapped? {
        if (absoluteEpisode <= 0) return null
        // Сначала — сохранённый расклад сезонов: франшизу уже обошёл резолвер сезонов, и второй
        // обход AniList (плюс запрос самого тайтла) ради той же цепочки не нужен.
        storedChain(anilistId, malId)?.let { stored ->
            if (absoluteEpisode <= stored.self.episodes) {
                return Mapped(stored.self.malId, absoluteEpisode)
            }
            offsetAbsoluteEpisode(stored.chain, absoluteEpisode)?.let { (mid, ep) -> return Mapped(mid, ep) }
        }
        val self = fetchSelf(anilistId, malId) ?: return null
        val selfMal = self.malId
        val e0 = self.totalEpisodes ?: self.airedEpisodes.takeIf { it > 0 }

        // В пределах собственного сезона (посерийная нумерация или один сезон) — сдвиг не нужен.
        if (e0 != null && absoluteEpisode <= e0) {
            return selfMal?.let { Mapped(it, absoluteEpisode) }
        }

        val chain = orderedChain(self) ?: return null
        return offsetAbsoluteEpisode(chain, absoluteEpisode)?.let { (mid, ep) -> Mapped(mid, ep) }
    }

    private suspend fun storedChain(anilistId: Int?, malId: Int?): StoredChain? {
        if (anilistId == null && malId == null) return null
        runCatching { seasonStore.ensureLoaded() }.getOrElse { return null }
        return seasonStore.flow.value.values.firstNotNullOfOrNull { entry ->
            chainFromLayout(entry.seasons, anilistId, malId)
        }
    }

    private suspend fun fetchSelf(anilistId: Int?, malId: Int?): EpisodeCheckMedia? {
        if (anilistId != null) {
            aniList.episodeCheckByAnilistIds(listOf(anilistId)).getOrNull()?.firstOrNull()?.let { return it }
        }
        if (malId != null) {
            aniList.episodeCheckByMalIds(listOf(malId)).getOrNull()?.firstOrNull()?.let { return it }
        }
        return null
    }

    private suspend fun orderedChain(self: EpisodeCheckMedia): List<FranchiseSeason>? = mutex.withLock {
        chainCache[self.anilistId]?.let { return it }

        val nodes = gatherFranchise(self)
        val ordered = orderByRelations(nodes)
        val seasons = ordered.mapNotNull { m ->
            val mid = m.malId ?: return@mapNotNull null
            val eps = (m.totalEpisodes ?: m.airedEpisodes).takeIf { it > 0 } ?: return@mapNotNull null
            FranchiseSeason(mid, eps)
        }
        if (seasons.isEmpty()) return null

        ordered.forEach { chainCache[it.anilistId] = seasons }
        Log.i(TAG, "Franchise chain for ${self.anilistId}: ${seasons.map { "${it.malId}(${it.episodes})" }}")
        seasons
    }

    /** BFS по PREQUEL/SEQUEL, только TV-подобные форматы; собираем узлы франшизы (с их malId/сериями). */
    private suspend fun gatherFranchise(self: EpisodeCheckMedia): Map<Int, EpisodeCheckMedia> {
        val result = LinkedHashMap<Int, EpisodeCheckMedia>()
        result[self.anilistId] = self
        var frontier = self.seasonNeighbors() - result.keys
        var level = 0
        while (frontier.isNotEmpty() && result.size < MAX_NODES && level++ < MAX_LEVELS) {
            val fetched = aniList.episodeCheckByAnilistIds(frontier.toList()).getOrNull().orEmpty()
            val next = HashSet<Int>()
            for (m in fetched) {
                if (!m.isSeasonFormat()) continue
                result[m.anilistId] = m
                next += m.seasonNeighbors()
            }
            frontier = next - result.keys
        }
        return result
    }

    /** Восстанавливаем порядок сезонов по рёбрам PREQUEL/SEQUEL внутри собранного множества. */
    private fun orderByRelations(nodes: Map<Int, EpisodeCheckMedia>): List<EpisodeCheckMedia> {
        val next = HashMap<Int, Int>()
        val prev = HashMap<Int, Int>()
        for (m in nodes.values) {
            for (r in m.relations) {
                if (r.anilistId !in nodes) continue
                when (r.relationType) {
                    "SEQUEL" -> { next[m.anilistId] = r.anilistId; prev[r.anilistId] = m.anilistId }
                    "PREQUEL" -> { prev[m.anilistId] = r.anilistId; next[r.anilistId] = m.anilistId }
                }
            }
        }
        val root = nodes.keys.firstOrNull { it !in prev } ?: nodes.keys.first()
        val ordered = ArrayList<EpisodeCheckMedia>()
        val seen = HashSet<Int>()
        var cur: Int? = root
        while (cur != null && cur !in seen) {
            seen += cur
            nodes[cur]?.let { ordered += it }
            cur = next[cur]
        }
        // На всякий случай добавим не попавшие в цепочку (ветвления) — в конец, по anilistId.
        nodes.values.filter { it.anilistId !in seen }.sortedBy { it.anilistId }.forEach { ordered += it }
        return ordered
    }

    private fun EpisodeCheckMedia.seasonNeighbors(): Set<Int> =
        relations.filter { it.relationType == "PREQUEL" || it.relationType == "SEQUEL" }
            .map { it.anilistId }
            .toSet()

    private fun EpisodeCheckMedia.isSeasonFormat(): Boolean =
        format == null || format in SEASON_FORMATS

    private companion object {
        const val TAG = "FranchiseMapper"
        const val MAX_NODES = 16
        const val MAX_LEVELS = 8
        val SEASON_FORMATS = setOf("TV", "TV_SHORT", "ONA")
    }
}
