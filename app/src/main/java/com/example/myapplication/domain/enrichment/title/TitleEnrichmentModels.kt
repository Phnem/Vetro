package com.example.myapplication.domain.enrichment.title

import com.example.myapplication.network.enrichment.ArtworkImage
import com.example.myapplication.network.enrichment.EnrichmentSource
import com.example.myapplication.network.enrichment.OmdbRatings
import com.example.myapplication.network.enrichment.VideoClip

/**
 * Обогащение тайтла для Details (.scratch/sources-expansion/ARCHITECTURE.md): одна сущность для UI,
 * какие API её собрали — знает только [provenance] (журнал разработчика).
 */
data class TitleEnrichment(
    /** Прозрачный логотип под язык интерфейса — вместо текстового названия в шапке. */
    val logo: ArtworkImage? = null,
    val backdrop: ArtworkImage? = null,
    val trailer: VideoClip? = null,
    val ratings: OmdbRatings? = null,
    val nextRelease: NextRelease? = null,
    /** Студии русской озвучки (Shikimori `fandubbers`). */
    val russianDubs: List<String> = emptyList(),
    val provenance: Map<String, EnrichmentSource> = emptyMap(),
    /**
     * Какой-то источник не ответил (чаще всего AniList с 429 сразу после старта) — карточка
     * собрана не полностью, и экран перечитает её чуть позже.
     */
    val incomplete: Boolean = false,
) {
    val isEmpty: Boolean get() = logo == null && backdrop == null && trailer == null && ratings == null && nextRelease == null
}
