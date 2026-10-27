package com.example.myapplication.network

import kotlinx.serialization.Serializable

/**
 * Типы связей AniList, по которым два тайтла считаются одной франшизой: цепочка сезонов, фильмы, спин-оффы,
 * пересказы и другие экранизации той же истории. CHARACTER и OTHER не берём: это кроссоверы и случайные
 * пересечения, они склеивали бы разные франшизы.
 */
val SAME_FRANCHISE_RELATIONS: Set<String> = setOf(
    "PREQUEL", "SEQUEL", "PARENT", "SIDE_STORY", "SPIN_OFF", "SUMMARY", "ALTERNATIVE",
)

/** Тайтл и его прямые связи франшизы (только аниме, только [SAME_FRANCHISE_RELATIONS]). */
@Serializable
data class FranchiseLinks(
    val anilistId: Int,
    val malId: Int?,
    val edges: List<FranchiseLink>,
)

@Serializable
data class FranchiseLink(
    val anilistId: Int,
    val malId: Int?,
    val relationType: String,
)
