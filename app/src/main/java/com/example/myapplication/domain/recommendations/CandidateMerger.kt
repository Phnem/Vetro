package com.example.myapplication.domain.recommendations

import com.example.myapplication.domain.normalizeForSearch
import com.example.myapplication.network.ApiSearchResult

/** Один тайтл после склейки дублей: все записи пула, которые оказались им же. */
data class MergedGroup(
    /** Канонический ключ `ANIME:anilist:1` / `MOVIE:tmdb:550` / `TV:title:…`. */
    val key: String,
    val type: ContentType,
    val entries: List<PoolEntry>,
)

/**
 * Склейка дублей между источниками и сидами.
 *
 * Порядок доверия: подтверждённые id (anilist/mal/shikimori/tmdb/imdb) → название как осторожный fallback.
 * Склейка идёт строго внутри [ContentType]: `MOVIE:123` и `TV:123` — разные сущности. Для кино/сериалов
 * совпадение названия требует совпадения года (если он известен) — иначе ремейки и омонимы не склеятся.
 */
object CandidateMerger {

    fun group(pool: List<PoolEntry>): List<MergedGroup> {
        val groups = ArrayList<MergedGroup>()
        val byType = LinkedHashMap<ContentType, MutableList<PoolEntry>>()
        pool.forEach { byType.getOrPut(it.contentType) { mutableListOf() } += it }
        for ((type, entries) in byType) groups += groupWithinType(type, entries)
        return groups
    }

    private fun groupWithinType(type: ContentType, entries: List<PoolEntry>): List<MergedGroup> {
        val parent = IntArray(entries.size) { it }
        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) root = parent[root]
            var cur = i
            while (parent[cur] != root) { val next = parent[cur]; parent[cur] = root; cur = next }
            return root
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }

        val owner = HashMap<String, Int>()
        entries.forEachIndexed { index, entry ->
            for (key in identityKeys(entry.result, type)) {
                val existing = owner[key]
                if (existing == null) owner[key] = index else union(index, existing)
            }
        }

        val members = LinkedHashMap<Int, MutableList<PoolEntry>>()
        entries.forEachIndexed { index, entry -> members.getOrPut(find(index)) { mutableListOf() } += entry }
        return members.values.map { group ->
            MergedGroup(key = canonicalKey(type, group), type = type, entries = group)
        }
    }

    /** Типизированные id источника. Shikimori-id совпадает с MAL-id только для аниме, но склейку по этому не строим. */
    internal fun idKeys(r: ApiSearchResult): List<String> = buildList {
        val ext = r.externalId?.takeIf { it.isNotBlank() }
        if (ext != null) {
            when (r.source.lowercase()) {
                "anilist" -> add("anilist:$ext")
                "shikimori" -> add("shikimori:$ext")
                "mal", "jikan" -> add("mal:$ext")
                "tmdb" -> add("tmdb:$ext")
                "trakt" -> add("trakt:$ext")
            }
        }
        r.malId?.let { add("mal:$it") }
        r.externalIds.anilist?.let { add("anilist:$it") }
        r.externalIds.mal?.let { add("mal:$it") }
        r.externalIds.shikimori?.let { add("shikimori:$it") }
        r.externalIds.tmdb?.let { add("tmdb:$it") }
        r.externalIds.imdb?.let { add("imdb:$it") }
    }.distinct()

    private fun identityKeys(r: ApiSearchResult, type: ContentType): List<String> {
        val ids = idKeys(r).map { "id:$it" }
        val titles = listOfNotNull(r.title, r.altTitle, r.originalTitle)
            .map { it.normalizeForSearch() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { title ->
                // Кино/сериалы: год — часть ключа; без года склеиваются только с такими же безгодовыми.
                if (type.isScreen) "t:$title@${r.seasonYear ?: ""}" else "t:$title"
            }
        return ids + titles
    }

    private val ID_PRIORITY = listOf("anilist", "shikimori", "mal", "tmdb", "imdb", "trakt")

    private fun canonicalKey(type: ContentType, group: List<PoolEntry>): String {
        val ids = group.flatMap { idKeys(it.result) }
        val best = ID_PRIORITY.firstNotNullOfOrNull { prefix -> ids.firstOrNull { it.startsWith("$prefix:") } }
        val tail = best ?: "title:" + group.first().result.title.normalizeForSearch()
        return "${type.name}:$tail"
    }
}
