package com.example.myapplication.localplayer.domain

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.enrichment.AnimeSkipClient
import com.example.myapplication.network.enrichment.AnimeSkipEpisode
import com.example.myapplication.network.enrichment.ExternalSkipKind
import com.example.myapplication.network.enrichment.ExternalSkipSegment
import com.example.myapplication.network.enrichment.IntroDbClient
import kotlin.math.abs

/** Выбор из внешних баз разметки (Anime-Skip, IntroDB) для текущего видео. */
data class ExternalSkipSelection(
    val segments: List<SkipSegment>,
    val referenceDurationMs: Long?,
    val origin: String,
)

/**
 * Внешние базы после AniSkip (.scratch/sources-expansion/ARCHITECTURE.md, «Пропуск»):
 * - [supplement] — чего нет у AniSkip: рекап и превью из Anime-Skip;
 * - [fallback] — если AniSkip пуст: Anime-Skip целиком для аниме, IntroDB для кино и сериалов.
 */
internal interface ExternalSkipLookup {
    suspend fun supplement(request: SkipSegmentRequest): ExternalSkipSelection?
    suspend fun fallback(request: SkipSegmentRequest): ExternalSkipSelection?
}

internal class ExternalSkipSegmentProvider(
    private val animeSkip: AnimeSkipClient,
    private val introDb: IntroDbClient,
) : ExternalSkipLookup {

    override suspend fun supplement(request: SkipSegmentRequest): ExternalSkipSelection? {
        val selection = animeSkip(request) ?: return null
        val extra = selection.segments.filter { it.kind == SkipKind.RECAP || it.kind == SkipKind.PREVIEW }
        return selection.copy(segments = extra).takeIf { extra.isNotEmpty() }
    }

    override suspend fun fallback(request: SkipSegmentRequest): ExternalSkipSelection? =
        animeSkip(request) ?: introDb(request)

    private suspend fun animeSkip(request: SkipSegmentRequest): ExternalSkipSelection? {
        val anilistId = request.anilistId ?: return null
        val episode = request.episodeNumber ?: return null
        val episodes = (animeSkip.episodesByAnilist(anilistId) as? LookupResult.Found)?.value ?: return null
        return ExternalSkipMatching.animeSkip(episodes, episode, request.durationMs)
    }

    private suspend fun introDb(request: SkipSegmentRequest): ExternalSkipSelection? {
        val imdb = request.imdbId ?: return null
        val result = if (request.isMovie) {
            introDb.movie(imdb)
        } else {
            val season = request.seasonNumber ?: return null
            val episode = request.episodeNumber ?: return null
            introDb.episode(imdb, season, episode)
        }
        val segments = (result as? LookupResult.Found)?.value ?: return null
        return ExternalSkipMatching.introDb(segments, request.durationMs)
    }
}

/** Чистые правила сопоставления внешней разметки с текущим видео. */
internal object ExternalSkipMatching {
    const val ANIME_SKIP_ORIGIN = "Anime-Skip"
    const val INTRODB_ORIGIN = "IntroDB"

    /** Разметка с согласием ниже порога не применяется: одна случайная метка не должна прыгать по видео. */
    private const val MIN_INTRODB_CONFIDENCE = 0.6

    /** Сколько IntroDB-отрезок может заходить за конец видео (другая сборка титров), прежде чем его отбросить. */
    private const val INTRODB_END_OVERHANG_MS = 60_000L

    /**
     * Anime-Skip: у серии бывает несколько версий (TV, BD, стриминг). Берётся версия, чья длительность
     * совместима с текущим видео по тем же допускам, что и у остальных эталонов; из нескольких — ближайшая.
     */
    fun animeSkip(episodes: List<AnimeSkipEpisode>, episode: Int, durationMs: Long): ExternalSkipSelection? {
        if (durationMs <= 0L) return null
        val version = episodes
            .filter { it.number == episode || (it.number == null && it.absoluteNumber == episode) }
            .filter { v -> v.baseDurationMs?.let { areSkipDurationsCompatible(durationMs, it) } == true }
            .minByOrNull { abs((it.baseDurationMs ?: 0L) - durationMs) }
            ?: return null
        val segments = version.segments.mapNotNull { it.toSkip(durationMs) }
        if (segments.isEmpty()) return null
        return ExternalSkipSelection(segments, version.baseDurationMs, ANIME_SKIP_ORIGIN)
    }

    /**
     * IntroDB не знает длительность размеченного файла: отрезок принимается, если начинается внутри
     * видео и не уходит за конец больше чем на минуту; уверенность — не ниже порога.
     */
    fun introDb(segments: List<ExternalSkipSegment>, durationMs: Long): ExternalSkipSelection? {
        if (durationMs <= 0L) return null
        val accepted = segments
            .filter { (it.confidence ?: 0.0) >= MIN_INTRODB_CONFIDENCE }
            .filter { it.startMs < durationMs && it.endMs <= durationMs + INTRODB_END_OVERHANG_MS }
            .mapNotNull { it.toSkip(durationMs) }
        if (accepted.isEmpty()) return null
        return ExternalSkipSelection(accepted, null, INTRODB_ORIGIN)
    }

    private fun ExternalSkipSegment.toSkip(durationMs: Long): SkipSegment? {
        val kind = when (kind) {
            ExternalSkipKind.OPENING -> SkipKind.OPENING
            ExternalSkipKind.RECAP -> SkipKind.RECAP
            ExternalSkipKind.PREVIEW -> SkipKind.PREVIEW
            // Сцена после титров — это содержание, её не пропускаем; титры перед ней — эндинг.
            ExternalSkipKind.ENDING -> SkipKind.ENDING
            ExternalSkipKind.POST_CREDITS -> return null
        }
        val end = endMs.coerceAtMost(durationMs)
        if (startMs < 0L || startMs >= end) return null
        return SkipSegment(startMs, end, kind)
    }
}
