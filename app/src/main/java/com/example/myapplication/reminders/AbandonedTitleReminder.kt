package com.example.myapplication.reminders

import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.local.SeriesSeasonsStore
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.domain.seasons.regularSeasons
import com.example.myapplication.domain.status.AutoWatchStatusResolver
import com.example.myapplication.manga.data.MangaReadingStore
import com.example.myapplication.media.progress.EpisodePlaybackStore
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * Фоновая проверка «не бросили ли что-то на полпути». Вызывается из `AnimeUpdateWorker` ПОСЛЕ проверки
 * новых серий, глав и обновления раскладов сезонов, поэтому счётчики вышедшего к этому моменту свежие:
 * это и есть обязательная проверка «не закончился ли тайтл» (см. [AbandonedTitleRules.hasUnfinishedContent]).
 *
 * За один вызов показывается не больше одного напоминания, и только в дневное окно по местному времени.
 */
class AbandonedTitleReminder(
    private val localDataSource: AnimeLocalDataSource,
    private val playback: EpisodePlaybackStore,
    private val reading: MangaReadingStore,
    private val seasonEpisodes: SeasonEpisodesStore,
    private val seriesSeasons: SeriesSeasonsStore,
    private val autoStatus: AutoWatchStatusResolver,
    private val store: AbandonedReminderStore,
    private val notifier: AbandonedTitleNotifier,
    private val now: () -> Long = System::currentTimeMillis,
    private val hourOfDay: () -> Int = { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) },
) {
    /**
     * @param hidden тайтлы секретной папки и с выключенным колокольчиком: о них не напоминаем,
     *   как и не шлём о них пуши о новых сериях.
     * @return показано ли напоминание.
     */
    suspend fun remindIfDue(language: AppLanguage, hidden: Set<String>): Boolean {
        if (!store.isEnabled()) return false
        if (!AbandonedTitleRules.isInWindow(hourOfDay())) return false
        val t = now()
        val history = store.history()
        if (!AbandonedTitleRules.passesGlobalCooldown(history.lastGlobalAt, t)) return false
        val candidate = findCandidate(hidden, history, t) ?: return false
        if (!notifier.show(candidate, language)) return false
        store.record(candidate.animeId, t)
        return true
    }

    private suspend fun findCandidate(
        hidden: Set<String>,
        history: AbandonedReminderStore.History,
        t: Long,
    ): AbandonedCandidate? {
        val titles = localDataSource.getAllAnimeList()
            .filter { it.id !in hidden && AbandonedTitleRules.isStatusEligible(it.watchStatus) }
        val found = ArrayList<AbandonedCandidate>()
        found += episodeCandidates(
            titles.filter { it.mediaType == MediaType.ANIME || it.mediaType == MediaType.SERIES },
            history,
            t,
        )
        found += mangaCandidates(titles.filter { it.mediaType == MediaType.MANGA }, history, t)
        return AbandonedTitleRules.pick(found)
    }

    private suspend fun episodeCandidates(
        titles: List<com.example.myapplication.data.models.Anime>,
        history: AbandonedReminderStore.History,
        t: Long,
    ): List<AbandonedCandidate> {
        if (titles.isEmpty()) return emptyList()
        val progress = playback.snapshotAll(titles.map { it.id })
        val layouts = seriesSeasons.flow.first() + seasonEpisodes.flow.first()
        val airing = localDataSource.observeAiringProgress().first()
        return titles.mapNotNull { anime ->
            val entries = progress[anime.id] ?: return@mapNotNull null
            val watched = entries.filterValues { it.watched }
            if (watched.size < AbandonedTitleRules.MIN_UNITS) return@mapNotNull null
            // Случайные открытия серии на пару секунд (проба источника) активностью не считаем.
            val last = entries.values
                .filter { it.watched || it.positionMs >= MEANINGFUL_WATCH_MS }
                .maxOfOrNull { it.updatedAt } ?: return@mapNotNull null
            if (!AbandonedTitleRules.isIdleLongEnough(last, t)) return@mapNotNull null
            val layout = layouts[anime.id]
            val released = AbandonedTitleRules.releasedEpisodes(layout, airing[anime.id], anime.episodes)
            if (!AbandonedTitleRules.hasUnfinishedContent(released, watched.size)) return@mapNotNull null
            if (!AbandonedTitleRules.passesHistory(history.perTitle[anime.id], last, t)) return@mapNotNull null
            val furthest = watched.keys.maxWithOrNull(compareBy({ it.season }, { it.episode }))
                ?: return@mapNotNull null
            val multiSeason = layout.regularSeasons().size > 1 || furthest.season > 1
            AbandonedCandidate(
                animeId = anime.id,
                title = anime.title,
                unit = ReminderUnit.EPISODES,
                doneCount = watched.size,
                position = if (multiSeason) "S${furthest.season} E${furthest.episode}" else "E${furthest.episode}",
                lastActivityAt = last,
                idleDays = AbandonedTitleRules.idleDays(last, t),
            )
        }
    }

    private suspend fun mangaCandidates(
        titles: List<com.example.myapplication.data.models.Anime>,
        history: AbandonedReminderStore.History,
        t: Long,
    ): List<AbandonedCandidate> {
        if (titles.isEmpty()) return emptyList()
        val summaries = autoStatus.mangaReadingSnapshot()
        val progress = reading.snapshotAll(titles.map { it.id })
        return titles.mapNotNull { anime ->
            val summary = summaries[anime.id] ?: return@mapNotNull null
            if (summary.readChapters < AbandonedTitleRules.MIN_UNITS) return@mapNotNull null
            // Оглавление неизвестно, значит, и «закончился ли тайтл» проверить нечем.
            val total = summary.totalChapters ?: return@mapNotNull null
            if (!AbandonedTitleRules.hasUnfinishedContent(total, summary.readChapters)) return@mapNotNull null
            val last = progress[anime.id]?.values?.filter { it.read }?.maxOfOrNull { it.updatedAt }
                ?: return@mapNotNull null
            if (!AbandonedTitleRules.isIdleLongEnough(last, t)) return@mapNotNull null
            if (!AbandonedTitleRules.passesHistory(history.perTitle[anime.id], last, t)) return@mapNotNull null
            AbandonedCandidate(
                animeId = anime.id,
                title = anime.title,
                unit = ReminderUnit.CHAPTERS,
                doneCount = summary.readChapters,
                position = null,
                lastActivityAt = last,
                idleDays = AbandonedTitleRules.idleDays(last, t),
            )
        }
    }

    private companion object {
        /** Меньше минуты в серии — проба источника, а не просмотр (как в EpisodePlaybackStore). */
        const val MEANINGFUL_WATCH_MS = 60_000L
    }
}
