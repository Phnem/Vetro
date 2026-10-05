package com.example.myapplication.domain.stats

import com.example.myapplication.data.local.AnimeDatabase
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.models.Anime
import com.example.myapplication.manga.data.MangaReadingStore
import com.example.myapplication.media.progress.EpisodePlaybackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Всё, что показывает блок «Инсайты» шторки статистики. */
data class InsightsData(
    val events: List<ActivityEvent>,
    val returns: ReturnsSummary,
    val genreQuality: List<GenreQuality>,
    val profile: ProfileShift,
)

/**
 * Собирает сигналы активности из локальных хранилищ: прогресс серий и глав (с отметкой времени),
 * сессии прослушивания и даты добавления. Облако не трогает — это история этого устройства
 * (прогресс из облака подтягивается в те же хранилища и тоже попадает сюда).
 */
class CollectionInsightsLoader(
    private val episodeStore: EpisodePlaybackStore,
    private val mangaStore: MangaReadingStore,
    private val db: AnimeDatabase,
    private val localDataSource: AnimeLocalDataSource,
) {
    suspend fun load(animeList: List<Anime>, nowMs: Long = System.currentTimeMillis()): InsightsData =
        withContext(Dispatchers.Default) {
            val ids = animeList.map { it.id }
            val episodes = episodeStore.snapshotAll(ids)
            val chapters = mangaStore.snapshotAll(ids)
            val airing = withContext(Dispatchers.IO) { localDataSource.getAiringProgressSnapshot() }

            val events = ArrayList<ActivityEvent>()
            val lastActivity = HashMap<String, Long>()
            val returnsByTitle = HashMap<String, Int>()

            for ((animeId, progress) in episodes) {
                for (value in progress.values) {
                    if (value.watched && value.updatedAt > 0L) {
                        events += ActivityEvent(value.updatedAt, ActivityKind.WATCH)
                    }
                    // Проба источника (секунды в серии) активностью не считается — как в furthestEpisodeFlow.
                    if (value.watched || value.positionMs >= MEANINGFUL_WATCH_MS) {
                        lastActivity.merge(animeId, value.updatedAt, ::maxOf)
                    }
                    if (value.rewatchCount > 0) returnsByTitle.merge(animeId, value.rewatchCount, Int::plus)
                }
            }
            for ((animeId, progress) in chapters) {
                for (value in progress.values) {
                    if (value.read && value.updatedAt > 0L) {
                        events += ActivityEvent(value.updatedAt, ActivityKind.READ)
                        lastActivity.merge(animeId, value.updatedAt, ::maxOf)
                    }
                    if (value.rereads > 0) returnsByTitle.merge(animeId, value.rereads, Int::plus)
                }
            }
            for (anime in animeList) {
                events += ActivityEvent(anime.dateAdded, ActivityKind.ADDED)
            }

            val sinceMs = nowMs - HISTORY_DAYS * DAY_MS
            val (sessions, passes) = withContext(Dispatchers.IO) {
                val rows = db.audiobookQueries.listenSessionsSince(sinceMs).executeAsList()
                val totals = db.audiobookQueries.listenTotalsByNarration().executeAsList()
                rows to totals.mapNotNull { row ->
                    val bookMs = row.book_ms ?: 0L
                    if (bookMs > 0L) (row.listened_ms ?: 0L).toDouble() / bookMs else null
                }
            }
            for (row in sessions) {
                val units = CollectionInsights.listenUnits(row.listened_ms)
                if (units > 0) events += ActivityEvent(row.started_at, ActivityKind.LISTEN, units)
            }

            val signals = animeList.associate { anime ->
                anime.id to TitleSignals(
                    lastActivityMs = lastActivity[anime.id] ?: 0L,
                    returns = returnsByTitle[anime.id] ?: 0,
                    airingTotal = airing[anime.id]?.totalEpisodes,
                )
            }
            val returns = CollectionInsights.returns(
                rewatchedEpisodes = episodes.mapValues { (_, p) -> p.values.map { it.rewatchCount } },
                rereadChapters = chapters.mapValues { (_, p) -> p.values.map { it.rereads } },
                bookPasses = passes,
            )
            InsightsData(
                events = events,
                returns = returns,
                genreQuality = CollectionInsights.genreQuality(animeList, signals, nowMs),
                profile = CollectionInsights.profileShift(animeList, nowMs),
            )
        }

    private companion object {
        const val MEANINGFUL_WATCH_MS = 60_000L
        const val HISTORY_DAYS = 400L
        const val DAY_MS = 86_400_000L
    }
}
