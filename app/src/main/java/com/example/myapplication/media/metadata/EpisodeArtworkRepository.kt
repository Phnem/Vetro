package com.example.myapplication.media.metadata

import com.example.myapplication.utils.runSuspendCatching
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.example.myapplication.network.EpisodeArtwork
import com.example.myapplication.network.EpisodeArtworkBundle
import com.example.myapplication.network.anilist.EpisodeArtworkQuery

/**
 * Fetches lightweight episode metadata from AniList. Playback URLs are deliberately ignored:
 * thumbnails/titles are metadata, while the media engine remains the playback source of truth.
 */
class EpisodeArtworkRepository(
    private val apolloClient: ApolloClient,
) {
    suspend fun get(
        anilistId: Int?,
        malId: Int?,
    ): Result<EpisodeArtworkBundle?> = runSuspendCatching {
        if (anilistId == null && malId == null) return@runSuspendCatching null
        val response = apolloClient.query(
            EpisodeArtworkQuery(
                id = Optional.presentIfNotNull(anilistId),
                idMal = Optional.presentIfNotNull(malId),
            )
        ).execute()
        response.exception?.let { throw it }
        response.errors?.takeIf { it.isNotEmpty() }?.let { errors ->
            error(errors.joinToString { it.message })
        }
        val media = response.data?.Media ?: return@runSuspendCatching null
        val episodes = media.streamingEpisodes.orEmpty().mapNotNull { nullableItem ->
            val item = nullableItem ?: return@mapNotNull null
            val image = item.thumbnail?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rawTitle = item.title?.takeIf { it.isNotBlank() }
            val number = rawTitle?.let(::parseEpisodeNumber) ?: return@mapNotNull null
            EpisodeArtwork(
                episodeNumber = number,
                title = rawTitle.let(::stripEpisodePrefix).takeIf { it.isNotBlank() },
                imageUrl = image,
                sourceUrl = item.url?.takeIf { it.isNotBlank() },
                site = item.site?.takeIf { it.isNotBlank() },
            )
        }.distinctBy { it.episodeNumber }
        EpisodeArtworkBundle(
            coverImageUrl = media.coverImage?.extraLarge ?: media.coverImage?.large,
            episodes = episodes,
        )
    }

    private fun parseEpisodeNumber(title: String): Int? =
        EPISODE_NUMBER.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun stripEpisodePrefix(title: String): String =
        title.replace(EPISODE_PREFIX, "").trim(' ', '-', '–', '—', ':')

    private companion object {
        val EPISODE_NUMBER =
            Regex("""(?i)\b(?:episode|ep\.?|эпизод|серия)\s*0*(\d{1,4})\b""")
        val EPISODE_PREFIX =
            Regex("""(?i)^\s*(?:episode|ep\.?|эпизод|серия)\s*0*\d{1,4}\s*[-–—:]?\s*""")
    }
}
