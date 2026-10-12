package com.example.myapplication.updates

import android.util.Log
import com.example.myapplication.data.models.AnimeUpdate

/**
 * Single read/merge/write policy for the shared anime_update feed.
 *
 * [applyEpisodes] is for providers whose record counter means "released" (SERIES). Anime keep it
 * `null`: their counter is what the user has watched, and a new release must not move it.
 * Without the counter bump, [getAcknowledged] (anime_id -> episode count the user has already
 * seen a notification for) is what keeps a swiped notification from coming back on the next pass.
 */
internal suspend fun publishEpisodeUpdates(
    detected: List<AnimeUpdate>,
    getExisting: () -> List<AnimeUpdate>,
    applyEpisodes: (suspend (AnimeUpdate) -> Unit)? = null,
    getAcknowledged: () -> Map<String, Int> = { emptyMap() },
    setUpdates: suspend (List<AnimeUpdate>) -> Unit,
): List<AnimeUpdate> {
    val existing = getExisting()
    val acknowledged = getAcknowledged()
    val fresh = detected
        .distinctBy { it.animeId }
        .filter { candidate ->
            candidate.newEpisodes > (acknowledged[candidate.animeId] ?: 0) &&
                existing.none { it.animeId == candidate.animeId && it.newEpisodes == candidate.newEpisodes }
        }
    if (applyEpisodes != null) {
        fresh.forEach { update ->
            runCatching { applyEpisodes(update) }
                .onFailure { Log.w(TAG, "Auto-apply failed for ${update.animeId}: ${it.message}") }
        }
    }
    val freshIds = fresh.mapTo(HashSet()) { it.animeId }
    val kept = existing.filter { it.animeId !in freshIds }
    setUpdates((fresh + kept).take(MAX_UPDATE_ROWS))
    return fresh
}

private const val TAG = "EpisodeUpdateFeed"
private const val MAX_UPDATE_ROWS = 30
