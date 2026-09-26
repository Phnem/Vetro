package com.example.myapplication.domain.enrichment.title

import com.example.myapplication.network.AniListTitleEnrichment
import com.example.myapplication.network.enrichment.ArtworkImage
import com.example.myapplication.network.enrichment.EnrichmentSource
import com.example.myapplication.network.enrichment.ShikimoriEnrichment
import com.example.myapplication.network.enrichment.TmdbEnrichment
import com.example.myapplication.network.enrichment.TvMazeShow
import com.example.myapplication.network.enrichment.VideoClip
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Правила выбора полей обогащения — чистые функции, чтобы приоритеты источников проверялись
 * тестами, а не угадывались по коду загрузки.
 */
object TitleEnrichmentRules {

    /**
     * Следующая серия в стране производства. Порядок: точное время AniList → TVmaze → Shikimori →
     * дата TMDb → оценка по недельному ритму (прошлая серия + 7 дней, если выходящий сериал и
     * прошлая серия была не больше двух недель назад).
     */
    fun nextOriginalRelease(
        now: Instant,
        aniList: AniListTitleEnrichment?,
        shikimori: ShikimoriEnrichment?,
        tvMaze: TvMazeShow?,
        tmdb: TmdbEnrichment?,
    ): NextRelease? {
        aniList?.nextAiringAtEpochSec?.let { Instant.ofEpochSecond(it) }?.takeIf { it.isAfter(now) }?.let { at ->
            return NextRelease(ReleaseTrack.ORIGINAL, null, aniList.nextEpisode, at, at.utcDate(), ReleaseConfidence.EXACT, EnrichmentSource.ANILIST)
        }
        tvMaze?.next?.let { next ->
            val at = next.airstamp
            if (at != null && at.isAfter(now)) {
                return NextRelease(ReleaseTrack.ORIGINAL, next.season, next.number, at, next.airdate, ReleaseConfidence.EXACT, EnrichmentSource.TVMAZE)
            }
        }
        shikimori?.nextEpisodeAt?.takeIf { it.isAfter(now) }?.let { at ->
            return NextRelease(ReleaseTrack.ORIGINAL, null, null, at, at.utcDate(), ReleaseConfidence.EXACT, EnrichmentSource.SHIKIMORI)
        }
        tmdb?.nextEpisode?.let { next ->
            val date = next.airDate
            if (date != null && !date.isBefore(now.utcDate())) {
                return NextRelease(ReleaseTrack.ORIGINAL, next.season, next.number, null, date, ReleaseConfidence.DATE_ONLY, EnrichmentSource.TMDB)
            }
        }
        val running = tvMaze?.status.equals("Running", true) || aniList?.status == "RELEASING" || shikimori?.ongoing == true
        val previous = tvMaze?.previous
        val previousAt = previous?.airstamp
        if (running && previousAt != null && Duration.between(previousAt, now) <= Duration.ofDays(14)) {
            var at = previousAt.plus(Duration.ofDays(7))
            while (!at.isAfter(now)) at = at.plus(Duration.ofDays(7))
            val skipped = Duration.between(previousAt, at).toDays() / 7
            return NextRelease(
                ReleaseTrack.ORIGINAL, previous.season, previous.number?.plus(skipped.toInt()), at, at.utcDate(),
                ReleaseConfidence.ESTIMATED, EnrichmentSource.TVMAZE,
            )
        }
        return null
    }

    /**
     * Логотип под язык интерфейса: сначала с надписью на языке UI, затем английский, затем без
     * языка; внутри — по оценке. Слишком вытянутые (баннеры) и крошечные отбрасываются.
     */
    fun pickLogo(candidates: List<ArtworkImage>, uiLanguage: String): ArtworkImage? {
        val usable = candidates.filter { img ->
            val w = img.width
            val h = img.height
            w == null || h == null || (w >= 300 && h > 0 && w.toDouble() / h in 1.0..8.0)
        }
        return languageOrder(uiLanguage).firstNotNullOfOrNull { lang ->
            usable.filter { it.language == lang }.maxByOrNull { it.score }
        }
    }

    /** Бэкдроп: без надписей лучше всего (фон под текстом интерфейса), затем язык UI. */
    fun pickBackdrop(candidates: List<ArtworkImage>, uiLanguage: String): ArtworkImage? =
        listOf(null, uiLanguage, "en").firstNotNullOfOrNull { lang ->
            candidates.filter { it.language == lang && (it.width ?: 1920) >= 1280 }.maxByOrNull { it.score }
        }

    /**
     * Трейлер: только YouTube; язык UI, затем английский; официальный лучше неофициального; Trailer
     * лучше Teaser; клипы и фичуретки — только если ничего другого нет.
     */
    fun pickTrailer(candidates: List<VideoClip>, uiLanguage: String): VideoClip? {
        val youtube = candidates.filter { it.site.equals("YouTube", true) && it.key.isNotBlank() }
        val typeRank = mapOf("Trailer" to 0, "Teaser" to 1, "Opening Credits" to 2, "Clip" to 3, "Featurette" to 4)
        return youtube.sortedWith(
            compareBy<VideoClip>(
                { languageOrder(uiLanguage).indexOf(it.language).let { i -> if (i < 0) 9 else i } },
                { typeRank[it.type] ?: 5 },
                { if (it.official) 0 else 1 },
            ),
        ).firstOrNull()
    }

    /** Порядок языков картинки: язык UI, английский, без языка. */
    private fun languageOrder(uiLanguage: String): List<String?> = listOf(uiLanguage, "en", null).distinct()

    private fun Instant.utcDate() = atZone(ZoneOffset.UTC).toLocalDate()
}
