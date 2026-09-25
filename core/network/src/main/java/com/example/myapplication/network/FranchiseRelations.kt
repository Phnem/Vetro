package com.example.myapplication.network

/** Соседи по цепочке сезонов: предыстория и продолжение. */
fun EpisodeCheckMedia.seasonNeighbors(): Set<Int> =
    relations.filter { it.relationType == "PREQUEL" || it.relationType == "SEQUEL" }
        .map { it.anilistId }
        .toSet()

/**
 * Порядок узлов франшизы по рёбрам PREQUEL/SEQUEL: от корня (узла без предыстории) по цепочке
 * продолжений; ветвления, не попавшие в цепочку, — в конец по anilistId.
 */
fun orderByRelations(nodes: Map<Int, EpisodeCheckMedia>): List<EpisodeCheckMedia> {
    if (nodes.isEmpty()) return emptyList()
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
    nodes.values.filter { it.anilistId !in seen }.sortedBy { it.anilistId }.forEach { ordered += it }
    return ordered
}
