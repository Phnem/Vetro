package com.example.myapplication.updates.availability

import com.example.myapplication.utils.runSuspendCatching
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.ReleaseObservationStore
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.models.AnimeUpdate
import com.example.myapplication.domain.seasons.latestSeasonEpisode
import com.example.myapplication.media.MediaGateway
import com.example.myapplication.media.player.DubbingPendingRule
import com.example.myapplication.media.source.PlaybackIdentity
import com.example.myapplication.media.source.flattenVideosWithSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Мини-аудит свежей серии для «порога доступности»: сколько студий озвучки её уже отдают и когда
 * она вышла. Серия берётся ровно так, как её открыл бы плеер (сезон и номер в нём из расклада), а
 * ссылки — теми же источниками, что при просмотре.
 */
class EpisodeSourceAuditor(
    private val localDataSource: AnimeLocalDataSource,
    private val seasonEpisodesStore: SeasonEpisodesStore,
    private val mediaGateway: MediaGateway,
    private val observations: ReleaseObservationStore,
) {
    /** null — тайтл не найден; такую серию фильтр не придерживает. */
    suspend fun audit(update: AnimeUpdate): EpisodeAudit? {
        val anime = withContext(Dispatchers.IO) { localDataSource.getAnimeById(update.animeId) } ?: return null
        seasonEpisodesStore.ensureLoaded()
        val layout = seasonEpisodesStore.entryFor(anime.id)
        val airing = runSuspendCatching { withContext(Dispatchers.IO) { localDataSource.getAiringProgressSnapshot() } }
            .getOrElse { emptyMap() }
        val latest = latestSeasonEpisode(layout, airing[anime.id])
        val seasonInfo = latest?.let { position -> layout?.seasons?.firstOrNull { it.seasonNumber == position.season } }
        val episode = latest?.episode ?: update.newEpisodes

        val studios = try {
            val identity = PlaybackIdentity.from(anime).toAnime(seasonInfo, episode)
            val hosters = mediaGateway.resolveHosters(identity, episode, seasonInfo)
            DubbingPendingRule.studioCount(flattenVideosWithSource(hosters))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            0
        }
        val releasedAt = DubbingPendingRule.releasedAtMs(observations.get(anime.id), episode)
        return EpisodeAudit(studioCount = studios, releasedAtMs = releasedAt)
    }
}
