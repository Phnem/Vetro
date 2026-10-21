package com.example.myapplication.domain.status

import com.example.myapplication.AppScope
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.local.SeriesSeasonsStore
import com.example.myapplication.data.models.AiringProgress
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.domain.seasons.SeasonEpisodesEntry
import com.example.myapplication.domain.seasons.ongoingSeason
import com.example.myapplication.manga.data.MangaBindingStore
import com.example.myapplication.manga.data.MangaChapterCacheStore
import com.example.myapplication.manga.data.MangaReadingStore
import com.example.myapplication.manga.domain.MangaReadingSummary
import com.example.myapplication.manga.domain.summarizeMangaReading
import com.example.myapplication.manga.translate.ChapterTranslationPlan
import com.example.myapplication.manga.translate.MangaTranslateSettings
import com.example.myapplication.media.progress.EpisodePlaybackStore
import com.example.myapplication.ui.home.autoWatchStatus
import com.example.myapplication.ui.home.computeCardProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Статус просмотра «по умолчанию» для каждого тайтла — тот, что действует, пока пользователь не выбрал
 * свой (`Anime.watchStatus == null`): «Смотрю» у тех, что в процессе по прогрессу, «Просмотрено» у
 * всех остальных (см. [autoWatchStatus]).
 *
 * «В процессе» — это ровно то состояние, которое карточка главной показывает полоской прогресса:
 * источники те же (просмотренные серии, расклад сезонов, снимок выхода, чтение манги) и считает их та
 * же функция ([computeCardProgress]). Поэтому источники здесь собраны заново, а не берутся из
 * `HomeViewModel`: статус нужен и Details, и фильтру главной, и ни тот, ни другой не должен зависеть
 * от жизни экрана. Потоки холодные и живут, только пока кто-то подписан (5 с после последнего).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoWatchStatusResolver(
    private val localDataSource: AnimeLocalDataSource,
    private val episodePlaybackStore: EpisodePlaybackStore,
    private val seasonEpisodesStore: SeasonEpisodesStore,
    private val seriesSeasonsStore: SeriesSeasonsStore,
    private val mangaBindingStore: MangaBindingStore,
    private val mangaChapterCacheStore: MangaChapterCacheStore,
    private val mangaReadingStore: MangaReadingStore,
    private val mangaTranslateSettings: MangaTranslateSettings?,
    appScope: AppScope,
) {
    /** null — ещё не посчитано: до первого ответа статус «по умолчанию» неизвестен, а не «Просмотрено». */
    val statuses: StateFlow<Map<String, WatchStatus>?> = statusesFlow()
        .stateIn(appScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private fun statusesFlow(): Flow<Map<String, WatchStatus>?> {
        val all = localDataSource.observeAllAnime()
        val watched = all
            .map { list -> list.map { it.id } }
            .distinctUntilChanged()
            // Только ДОСМОТРЕННЫЕ серии — как «Просмотрено» в меню серий и на карточке.
            .flatMapLatest { ids -> episodePlaybackStore.furthestEpisodeFlow(ids, completedOnly = true) }
            .map { furthest -> furthest.filterValues { it.episode > 0 } }
        val airing = combine(localDataSource.observeAiringProgress(), seriesSeasonsStore.flow) { anime, series ->
            seriesAiring(series) + anime
        }
        val layouts = combine(seasonEpisodesStore.flow, seriesSeasonsStore.flow) { anime, series -> series + anime }
        return combine(all, watched, airing, layouts, mangaReading()) { list, w, a, l, r ->
            list.associate { anime ->
                anime.id to computeCardProgress(
                    layout = l[anime.id],
                    storedEpisodes = anime.episodes,
                    watched = w[anime.id],
                    airing = a[anime.id],
                    reading = r[anime.id],
                ).autoWatchStatus()
            }
        }
    }

    /**
     * Статус одного тайтла — для Details. Те же источники, что в [statuses], но только по одному id:
     * экрану не нужен пересчёт всей коллекции на каждую запись прогресса. [storedEpisodes] — сохранённый
     * счётчик серий тайтла (знаменатель, когда расклада сезонов нет).
     */
    fun observeOne(animeId: String, storedEpisodes: Flow<Int>): Flow<WatchStatus> {
        val watched = episodePlaybackStore.furthestEpisodeFlow(listOf(animeId), completedOnly = true)
            .map { it[animeId]?.takeIf { key -> key.episode > 0 } }
        val airing = combine(localDataSource.observeAiringProgress(), seriesSeasonsStore.flow) { anime, series ->
            anime[animeId] ?: seriesAiring(series.filterKeys { it == animeId })[animeId]
        }
        val layout = combine(seasonEpisodesStore.flow, seriesSeasonsStore.flow) { anime, series ->
            anime[animeId] ?: series[animeId]
        }
        return combine(storedEpisodes, watched, airing, layout, mangaReading(onlyId = animeId)) { stored, w, a, l, r ->
            computeCardProgress(
                layout = l,
                storedEpisodes = stored,
                watched = w,
                airing = a,
                reading = r[animeId],
            ).autoWatchStatus()
        }.distinctUntilChanged()
    }

    /** Прогресс чтения манги — те же условия, что у карточки (только привязанные к источнику тайтлы). */
    private fun mangaReading(onlyId: String? = null): Flow<Map<String, MangaReadingSummary>> =
        mangaBindingStore.flow.map { all -> if (onlyId == null) all else all.filterKeys { it == onlyId } }.flatMapLatest { bindings ->
            if (bindings.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    mangaReadingStore.progressFlow(bindings.keys.toList()),
                    mangaChapterCacheStore.flow,
                    mangaTranslateSettings?.active ?: flowOf(false),
                ) { progressByTitle, chapterCache, autoTranslate ->
                    bindings.mapValues { (animeId, binding) ->
                        val cached = chapterCache[mangaChapterCacheStore.entryKey(binding.sourceId, binding.mangaKey)]
                        summarizeMangaReading(
                            chapters = ChapterTranslationPlan.chapters(
                                chapters = cached?.chapters.orEmpty(),
                                preferredLanguage = binding.preferredLanguage,
                                autoTranslate = autoTranslate,
                            ),
                            progress = progressByTitle[animeId].orEmpty(),
                        )
                    }.filterValues { it.hasProgress }
                }
            }
        }

    /** У сериалов своего снимка выхода нет: выходящий сезон — последний сезон TMDB-расклада с недовышедшими сериями. */
    private fun seriesAiring(series: Map<String, SeasonEpisodesEntry>): Map<String, AiringProgress> =
        series.mapNotNull { (animeId, entry) ->
            val season = entry.ongoingSeason() ?: return@mapNotNull null
            animeId to AiringProgress(
                animeId = animeId,
                seasonNumber = season.seasonNumber,
                airedEpisodes = season.episodes,
                totalEpisodes = season.totalEpisodes,
                updatedAt = entry.resolvedAt,
            )
        }.toMap()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
