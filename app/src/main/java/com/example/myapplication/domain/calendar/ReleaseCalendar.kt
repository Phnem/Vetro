package com.example.myapplication.domain.calendar

import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.ReleaseObservationStore
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.local.SeriesSeasonsStore
import com.example.myapplication.data.models.AiringProgress
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.domain.enrichment.title.AiringSeasonRef
import com.example.myapplication.domain.enrichment.title.NextRelease
import com.example.myapplication.domain.enrichment.title.TitleEnrichmentRepository
import com.example.myapplication.domain.seasons.ongoingSeason
import com.example.myapplication.network.AppLanguage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Выход серии на конкретный день календаря. */
data class CalendarRelease(
    val animeId: String,
    val title: String,
    val imageFileName: String?,
    val episode: Int?,
    val date: LocalDate,
    /** true — расчёт по недельному ритму от ближайшей известной серии, а не слово источника. */
    val projected: Boolean,
    /** true — серия уже вышла (по журналу наблюдений). */
    val released: Boolean = false,
)

/**
 * Календарь выходящих серий: все тайтлы коллекции, у которых сезон идёт сейчас, и даты их серий.
 *
 * Дата ближайшей серии берётся тем же правилом, что и отсчёт на Details ([TitleEnrichmentRepository.nextRelease]:
 * озвучка / ритм по дням недели → эфир AniList → TMDb), а дальше раскладывается по неделям до конца
 * горизонта — сериалы выходят еженедельно, и именно по этому видно «что в какие дни смотреть».
 */
class ReleaseCalendarRepository(
    private val localDataSource: AnimeLocalDataSource,
    private val enrichment: TitleEnrichmentRepository,
    private val animeSeasons: SeasonEpisodesStore,
    private val seriesSeasons: SeriesSeasonsStore,
    private val observations: ReleaseObservationStore,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Memo(val language: AppLanguage, val at: Long, val releases: List<CalendarRelease>)

    private val mutex = Mutex()
    private var memo: Memo? = null

    suspend fun load(language: AppLanguage, force: Boolean = false): List<CalendarRelease> = mutex.withLock {
        memo?.takeIf { !force && it.language == language && nowMs() - it.at < MEMO_TTL_MS }?.let { return it.releases }
        val releases = build(language)
        memo = Memo(language, nowMs(), releases)
        releases
    }

    private suspend fun build(language: AppLanguage): List<CalendarRelease> = withContext(Dispatchers.Default) {
        animeSeasons.ensureLoaded()
        seriesSeasons.ensureLoaded()
        val airing = withContext(Dispatchers.IO) { localDataSource.getAiringProgressSnapshot() }
        val all = withContext(Dispatchers.IO) { localDataSource.getAllAnimeList() }
        val candidates = all.filter { isAiring(it, airing) }
        val zone = zone()
        val today = Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalDate()
        val observed = observations.all()
        // Сеть — по три тайтла за раз: AniList и TMDb и без того делят лимиты с проверкой серий.
        val gate = Semaphore(PARALLEL_LOOKUPS)
        coroutineScope {
            candidates.map { anime ->
                async {
                    val ref = seasonRef(anime)
                    val next = gate.withPermit {
                        runCatching { enrichment.nextRelease(anime, language, ref) }.getOrNull()
                    }
                    val progress = airing[anime.id]
                    ReleaseProjection.forTitle(
                        animeId = anime.id,
                        title = anime.title,
                        imageFileName = anime.imageFileName,
                        next = next,
                        airedEpisodes = progress?.airedEpisodes,
                        totalEpisodes = progress?.totalEpisodes,
                        observed = observed[anime.id].orEmpty().map { it.episode to Instant.ofEpochMilli(it.atMs) },
                        today = today,
                        zone = zone,
                    )
                }
            }.awaitAll().flatten()
        }
    }

    private fun isAiring(anime: Anime, airing: Map<String, AiringProgress>): Boolean = when (anime.mediaType) {
        MediaType.ANIME -> airing[anime.id]?.let { it.totalEpisodes == null || it.airedEpisodes < it.totalEpisodes } == true
        MediaType.SERIES -> seriesSeasons.entryFor(anime.id).ongoingSeason() != null
        MediaType.MOVIE, MediaType.MANGA -> false
    }

    private fun seasonRef(anime: Anime): AiringSeasonRef? {
        val season = when (anime.mediaType) {
            MediaType.SERIES -> seriesSeasons.entryFor(anime.id)
            else -> animeSeasons.entryFor(anime.id)
        }.ongoingSeason() ?: return null
        return AiringSeasonRef(season.anilistId, season.malId)
    }

    private companion object {
        const val MEMO_TTL_MS = 10 * 60 * 1000L
        const val PARALLEL_LOOKUPS = 3
    }
}

/** Чистая раскладка релизов по дням — отдельно от сети, чтобы проверяться тестами. */
object ReleaseProjection {

    /** Сколько дней вперёд раскладываем ритм. */
    const val HORIZON_DAYS = 120L

    /** Сколько дней назад показываем уже вышедшие серии из журнала. */
    const val LOOKBACK_DAYS = 45L

    fun forTitle(
        animeId: String,
        title: String,
        imageFileName: String?,
        next: NextRelease?,
        airedEpisodes: Int?,
        totalEpisodes: Int?,
        observed: List<Pair<Int, Instant>>,
        today: LocalDate,
        zone: ZoneId,
    ): List<CalendarRelease> {
        val result = mutableListOf<CalendarRelease>()
        val horizon = today.plusDays(HORIZON_DAYS)
        val since = today.minusDays(LOOKBACK_DAYS)

        // Вышедшее: по одной точке на серию, без выдумок о серии, которой нет в журнале.
        observed.forEach { (episode, at) ->
            val date = at.atZone(zone).toLocalDate()
            if (date in since..today) {
                result += CalendarRelease(animeId, title, imageFileName, episode, date, projected = false, released = true)
            }
        }

        if (next != null) {
            var date = next.at.atZone(zone).toLocalDate()
            var episode = next.episode ?: airedEpisodes?.plus(1)
            var first = true
            while (!date.isAfter(horizon)) {
                if (episode != null && totalEpisodes != null && episode > totalEpisodes) break
                if (!date.isBefore(today)) {
                    result += CalendarRelease(animeId, title, imageFileName, episode, date, projected = !first)
                }
                first = false
                date = date.plusWeeks(1)
                episode = episode?.plus(1)
            }
        }
        // Серия «вышла сегодня» может быть и в журнале, и среди ближайших — одна точка на серию в день.
        return result.distinctBy { it.date to it.episode }
    }
}
