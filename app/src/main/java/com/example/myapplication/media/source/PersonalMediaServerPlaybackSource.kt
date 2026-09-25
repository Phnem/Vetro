package com.example.myapplication.media.source

import com.example.myapplication.network.AppJson
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.movieseries.MediaIdentity
import com.example.myapplication.media.source.movieseries.MediaIdentityMatcher
import com.example.myapplication.media.source.movieseries.MovieSeriesStreamingProvider
import com.example.myapplication.media.source.movieseries.episodeMatches
import com.example.myapplication.media.source.movieseries.ProviderCapability
import com.example.myapplication.media.source.movieseries.ProviderId
import com.example.myapplication.media.source.movieseries.ProviderResolution
import com.example.myapplication.media.source.movieseries.requireProviderSuccess
import com.example.myapplication.media.source.movieseries.resolveTyped
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import java.net.URI
import java.nio.charset.StandardCharsets
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val SAFE_SERVER_ID = Regex("[A-Za-z0-9_-]+")

enum class PersonalMediaServerProvider(val displayName: String, val credentialPrefix: String) {
    JELLYFIN("Jellyfin", "jellyfin"),
    EMBY("Emby", "emby"),
}

data class PersonalMediaServerConfig(
    val baseUrl: String,
    val userId: String,
    val accessToken: String,
    val downloadAllowed: Boolean = false,
    val allowInsecureHttp: Boolean = false,
) {
    fun isValid(): Boolean {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return false
        val schemeAllowed = uri.scheme == "https" || (uri.scheme == "http" && allowInsecureHttp)
        return schemeAllowed && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.query == null && uri.fragment == null && userId.matches(SAFE_SERVER_ID) &&
            accessToken.isNotBlank() && '\r' !in accessToken && '\n' !in accessToken
    }

    fun credentialRef(provider: PersonalMediaServerProvider): PlaybackCredentialRef {
        return requireNotNull(authScope()).credentialRef(provider.credentialPrefix, userId)
    }

    fun isAllowedUrl(url: String): Boolean = authScope()?.contains(url) == true

    internal fun authScope(): PlaybackAuthScope? =
        PlaybackAuthScope.create(baseUrl, allowQuery = true)
}

class PersonalMediaServerPlaybackSource(
    private val client: HttpClient,
    private val provider: PersonalMediaServerProvider,
    private val configProvider: () -> PersonalMediaServerConfig?,
) : MovieSeriesStreamingProvider {
    override val id: ProviderId = ProviderId(provider.credentialPrefix)
    override val displayName: String = provider.displayName
    private val sourceName: String get() = displayName
    private val json = AppJson

    /** Matches by provider ids first, so it declares every id capability it searches on. */
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.MOVIE,
        ProviderCapability.SERIES,
        ProviderCapability.DIRECT,
        ProviderCapability.HLS,
        ProviderCapability.SUBTITLES,
        ProviderCapability.DOWNLOAD,
        ProviderCapability.TMDB_ID,
        ProviderCapability.IMDB_ID,
        ProviderCapability.KINOPOISK_ID,
    )

    override suspend fun resolve(request: PlaybackRequest): ProviderResolution =
        resolveTyped(provider.displayName) { resolveFromServer(request) }

    private suspend fun resolveFromServer(request: PlaybackRequest): ProviderResolution {
        val config = configProvider()?.takeIf(PersonalMediaServerConfig::isValid)
            ?: return ProviderResolution.NotConfigured
        if (request.mediaType !in setOf(MediaType.MOVIE, MediaType.SERIES)) {
            return ProviderResolution.Unsupported
        }
        val item = search(config, request) ?: return ProviderResolution.NotFound
        val playableItem = if (request.mediaType == MediaType.SERIES) {
            episode(config, item.id, request.seasonNumber, request.episodeNumber)
                ?: return ProviderResolution.NotFound
        } else {
            item
        }
        val playback = playbackInfo(config, playableItem.id)
            ?: return ProviderResolution.NotFound
        val candidate = playback.candidate(config, playableItem.id)
            ?: return ProviderResolution.NotFound
        val tokenHeader = candidate.requiredHeaders
            .filterKeys { !it.equals(TOKEN_HEADER, ignoreCase = true) } +
            (TOKEN_HEADER to config.accessToken)
        val video = VetroVideo(
            url = candidate.url,
            label = candidate.label,
            sourceName = provider.displayName,
            headers = tokenHeader,
            isPreferred = true,
            downloadAllowed = candidate.progressive && config.downloadAllowed &&
                playableItem.canDownload == true &&
                areHeadersSafeForPersistence(candidate.requiredHeaders),
            credentialRef = config.credentialRef(provider),
            credentialScope = requireNotNull(config.authScope()).credentialScope(),
        )
        return ProviderResolution.Found(
            listOf(VetroHoster(provider.displayName, candidate.url, listOf(video)))
        )
    }

    private suspend fun search(
        config: PersonalMediaServerConfig,
        request: PlaybackRequest,
    ): ServerItem? {
        val response = client.get(
            config.baseUrl.trim().trimEnd('/') + "/Users/${config.userId}/Items"
        ) {
            header(TOKEN_HEADER, config.accessToken)
            parameter("SearchTerm", request.anime.title)
            parameter("IncludeItemTypes", if (request.mediaType == MediaType.MOVIE) "Movie" else "Series")
            parameter("Recursive", true)
            parameter("Fields", "ProviderIds")
        }
        requireProviderSuccess(response.status.value, provider.displayName)
        val items = json.decodeFromString<ServerItemsResponse>(response.bodyAsText()).items
            .filter { it.id.matches(SAFE_SERVER_ID) }
        return MediaIdentityMatcher.selectUnique(
            wanted = MediaIdentity.of(request),
            candidates = items,
            identityOf = ServerItem::identity,
        )
    }

    private suspend fun episode(
        config: PersonalMediaServerConfig,
        seriesId: String,
        season: Int,
        episode: Int,
    ): ServerItem? {
        val response = client.get(
            config.baseUrl.trim().trimEnd('/') + "/Shows/$seriesId/Episodes"
        ) {
            header(TOKEN_HEADER, config.accessToken)
            parameter("UserId", config.userId)
            parameter("Season", season)
        }
        requireProviderSuccess(response.status.value, provider.displayName)
        return json.decodeFromString<ServerItemsResponse>(response.bodyAsText()).items
            .filter { it.id.matches(SAFE_SERVER_ID) }
            .firstOrNull { episodeMatches(it.parentIndexNumber, it.indexNumber, season, episode) }
    }

    private suspend fun playbackInfo(
        config: PersonalMediaServerConfig,
        itemId: String,
    ): ServerPlaybackInfo? {
        val response = client.get(
            config.baseUrl.trim().trimEnd('/') + "/Items/$itemId/PlaybackInfo"
        ) {
            header(TOKEN_HEADER, config.accessToken)
            parameter("UserId", config.userId)
        }
        requireProviderSuccess(response.status.value, provider.displayName)
        val body = response.bodyAsText()
        return json.decodeFromString<PersonalPlaybackInfoDto>(body).toDomain()
    }

    private fun ServerPlaybackInfo.candidate(
        config: PersonalMediaServerConfig,
        itemId: String,
    ): PlaybackCandidate? {
        for (source in mediaSources) {
            val direct = source.takeIf { it.supportsDirectStream || it.supportsDirectPlay }
                ?.let {
                    it.directStreamUrl?.takeIf(String::isNotBlank)
                        ?: it.constructedDirectUrl(config, itemId, playSessionId)
                }
            if (direct != null) {
                val absolute = config.resolveAllowedUrl(direct) ?: continue
                return PlaybackCandidate(
                    url = absolute,
                    label = "Direct",
                    requiredHeaders = SanitizeHeaders.sanitize(source.requiredHttpHeaders),
                    progressive = !VetroVideo(absolute, "Direct").isHlsOrDash,
                )
            }
        }
        for (source in mediaSources) {
            if (!source.supportsTranscoding) continue
            val transcode = source.transcodingUrl?.takeIf(String::isNotBlank) ?: continue
            val absolute = config.resolveAllowedUrl(transcode) ?: continue
            return PlaybackCandidate(
                url = absolute,
                label = if (absolute.contains(".m3u8", true)) "HLS" else "Transcode",
                requiredHeaders = SanitizeHeaders.sanitize(source.requiredHttpHeaders),
                progressive = false,
            )
        }
        return null
    }

    private fun ServerMediaSource.constructedDirectUrl(
        config: PersonalMediaServerConfig,
        itemId: String,
        playSessionId: String?,
    ): String? {
        val mediaSourceId = id?.takeIf { it.matches(SAFE_SERVER_ID) } ?: return null
        val session = playSessionId?.takeIf { it.matches(SAFE_SERVER_ID) } ?: return null
        val extension = container?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]+")) }
        val path = config.baseUrl.trim().trimEnd('/') + "/Videos/$itemId/stream" +
            extension?.let { ".$it" }.orEmpty()
        return "$path?Static=true&MediaSourceId=${mediaSourceId.urlEncode()}&PlaySessionId=${session.urlEncode()}"
    }

    private companion object {
        const val TOKEN_HEADER = "X-Emby-Token"
    }
}

/** Server-reported provider ids, translated into the shared identity vocabulary. */
private fun ServerItem.identity(): MediaIdentity = MediaIdentity(
    tmdbId = providerId("tmdb")?.toIntOrNull(),
    imdbId = providerId("imdb"),
    kinopoiskId = (providerId("kinopoisk") ?: providerId("kp"))?.toIntOrNull(),
    title = name,
    year = productionYear,
)

private fun ServerItem.providerId(key: String): String? =
    providerIds.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        ?.value
        ?.takeIf(String::isNotBlank)

private data class PlaybackCandidate(
    val url: String,
    val label: String,
    val requiredHeaders: Map<String, String>,
    val progressive: Boolean,
)

private data class ServerPlaybackInfo(
    val mediaSources: List<ServerMediaSource>,
    val playSessionId: String?,
)

private data class ServerMediaSource(
    val id: String?,
    val container: String?,
    val supportsDirectPlay: Boolean,
    val supportsDirectStream: Boolean,
    val directStreamUrl: String?,
    val supportsTranscoding: Boolean,
    val transcodingUrl: String?,
    val requiredHttpHeaders: Map<String, String>,
)

@Serializable
private data class ServerItemsResponse(
    @SerialName("Items") val items: List<ServerItem> = emptyList(),
)

@Serializable
private data class ServerItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("CanDownload") val canDownload: Boolean? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
)

/** The documented Jellyfin/Emby PlaybackInfo subset used by this adapter is wire-compatible. */
@Serializable
private data class PersonalPlaybackInfoDto(
    @SerialName("MediaSources") val mediaSources: List<PersonalMediaSourceDto> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
) {
    fun toDomain() = ServerPlaybackInfo(mediaSources.map(PersonalMediaSourceDto::toDomain), playSessionId)
}

@Serializable
private data class PersonalMediaSourceDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("DirectStreamUrl") val directStreamUrl: String? = null,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("RequiredHttpHeaders") val requiredHttpHeaders: Map<String, String> = emptyMap(),
) {
    fun toDomain() = ServerMediaSource(
        id, container, supportsDirectPlay, supportsDirectStream, directStreamUrl,
        supportsTranscoding, transcodingUrl, requiredHttpHeaders,
    )
}

private fun PersonalMediaServerConfig.resolveAllowedUrl(pathOrUrl: String): String? = runCatching {
    val root = requireNotNull((baseUrl.trim().trimEnd('/') + "/").toHttpUrlOrNull())
    val absolute = requireNotNull(root.resolve(pathOrUrl.trim())).toString()
    require(!urlContainsSecret(absolute, accessToken))
    absolute.takeIf(::isAllowedUrl)
}.getOrNull()

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)

internal fun VetroVideo.rehydratePersonalServerCredentials(
    provider: PersonalMediaServerProvider,
    config: PersonalMediaServerConfig?,
): VetroVideo {
    if (config == null || credentialRef != config.credentialRef(provider) ||
        !config.isAllowedUrl(url)
    ) {
        return this
    }
    return copy(headers = headers + ("X-Emby-Token" to config.accessToken))
}
