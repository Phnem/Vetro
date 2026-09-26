package com.example.myapplication.domain.enrichment.title

import com.example.myapplication.network.enrichment.ArtworkImage
import com.example.myapplication.network.enrichment.EnrichmentSource
import com.example.myapplication.network.enrichment.OmdbRatings
import com.example.myapplication.network.enrichment.VideoClip
import java.time.Instant
import java.time.LocalDate

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
    /** Студии русской озвучки (Shikimori `fandubbers`) — из них складывается RU-трек выхода. */
    val russianDubs: List<String> = emptyList(),
    val provenance: Map<String, EnrichmentSource> = emptyMap(),
) {
    val isEmpty: Boolean get() = logo == null && backdrop == null && trailer == null && ratings == null && nextRelease == null
}

/**
 * Для кого выходит серия. Одна и та же серия выходит в разное время: в стране производства, в
 * подписанной версии на платформе, в русской озвучке.
 */
enum class ReleaseTrack { ORIGINAL, SUBTITLED, RU_DUB }

enum class ReleaseConfidence {
    /** Точное время из расписания вещателя. */
    EXACT,

    /** Выведено из недельного ритма по времени прошлой серии. */
    ESTIMATED,

    /** Известна только дата. */
    DATE_ONLY,
}

data class NextRelease(
    val track: ReleaseTrack,
    val season: Int?,
    val episode: Int?,
    /** Момент выхода; null — только [date]. */
    val at: Instant?,
    val date: LocalDate?,
    val confidence: ReleaseConfidence,
    val source: EnrichmentSource,
)
