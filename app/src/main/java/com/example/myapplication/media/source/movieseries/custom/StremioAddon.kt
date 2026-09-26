package com.example.myapplication.media.source.movieseries.custom

import com.example.myapplication.network.AppJson
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.PlaybackRequest
import com.example.myapplication.media.source.SanitizeHeaders
import com.example.myapplication.media.source.VetroHoster
import com.example.myapplication.media.source.VetroSubtitleTrack
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.source.movieseries.MatchAccuracy
import com.example.myapplication.media.source.movieseries.MovieSeriesStreamingProvider
import com.example.myapplication.media.source.movieseries.ProviderCapability
import com.example.myapplication.media.source.movieseries.ProviderId
import com.example.myapplication.media.source.movieseries.ProviderResolution
import com.example.myapplication.media.source.movieseries.normalizeImdbId
import com.example.myapplication.media.source.movieseries.requireProviderSuccess
import com.example.myapplication.media.source.movieseries.resolveTyped
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The documented Stremio addon manifest subset Vetro reads.
 *
 * Source: stremio-addon-sdk `docs/api/responses/manifest.md`.
 */
@Serializable
data class StremioManifest(
    val id: String,
    val name: String,
    val version: String = "",
    val description: String = "",
    val resources: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val idPrefixes: List<String>? = null,
    val behaviorHints: StremioAddonHints? = null,
)

@Serializable
data class StremioAddonHints(
    val p2p: Boolean = false,
    val adult: Boolean = false,
    val configurationRequired: Boolean = false,
)

/** One entry of a `/stream/{type}/{id}.json` response. */
@Serializable
data class StremioStream(
    val url: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val infoHash: String? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val nzbUrl: String? = null,
    val subtitles: List<StremioSubtitle> = emptyList(),
    val behaviorHints: StremioStreamHints? = null,
)

@Serializable
data class StremioSubtitle(
    val url: String = "",
    val lang: String = "",
)

@Serializable
data class StremioStreamHints(
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val proxyHeaders: StremioProxyHeaders? = null,
)

@Serializable
data class StremioProxyHeaders(
    val request: Map<String, String> = emptyMap(),
)

@Serializable
private data class StremioStreamResponse(
    @SerialName("streams") val streams: List<StremioStream> = emptyList(),
)

/** A validated addon ready to be used as a provider. */
data class StremioAddon(
    val baseUrl: String,
    val manifest: StremioManifest,
    val capabilities: Set<ProviderCapability>,
)

sealed interface StremioImport {
    data class Valid(val addon: StremioAddon) : StremioImport
    data class Invalid(val reason: String) : StremioImport
}

/**
 * Translates a Stremio addon manifest into something Vetro can run.
 *
 * The addon keeps its own logic on its own server; Vetro only speaks the documented protocol and
 * normalizes what comes back. That is what makes this transport usable without running third-party
 * code on the device.
 */
object StremioImporter {

    fun import(baseUrl: String, manifest: StremioManifest): StremioImport {
        val base = baseUrl.trimEnd('/').toHttpUrlOrNull()
            ?: return StremioImport.Invalid("Addon URL is not valid")
        if (!base.isHttps) return StremioImport.Invalid("Addon URL must use https")
        if (manifest.id.isBlank() || manifest.name.isBlank()) {
            return StremioImport.Invalid("Addon manifest has no id or name")
        }
        if ("stream" !in manifest.resources) {
            return StremioImport.Invalid("Addon does not provide the stream resource")
        }
        // An addon that declares itself P2P is a torrent index; Vetro has no such stack and adding
        // one is out of scope for this transport.
        if (manifest.behaviorHints?.p2p == true) {
            return StremioImport.Invalid("P2P addons are not supported")
        }
        if (manifest.behaviorHints?.configurationRequired == true) {
            return StremioImport.Invalid("Addon requires configuration on its own site first")
        }

        val servesMovie = "movie" in manifest.types
        val servesSeries = "series" in manifest.types
        if (!servesMovie && !servesSeries) {
            return StremioImport.Invalid("Addon serves neither movie nor series")
        }
        // Stremio addresses content by IMDb id; an addon restricted to other prefixes cannot answer
        // anything Vetro is able to ask.
        manifest.idPrefixes?.let { prefixes ->
            if (prefixes.none { it.startsWith("tt") }) {
                return StremioImport.Invalid("Addon does not accept IMDb ids")
            }
        }

        val capabilities = buildSet {
            if (servesMovie) add(ProviderCapability.MOVIE)
            if (servesSeries) add(ProviderCapability.SERIES)
            add(ProviderCapability.IMDB_ID)
            add(ProviderCapability.HLS)
            add(ProviderCapability.DIRECT)
            add(ProviderCapability.SUBTITLES)
        }
        return StremioImport.Valid(
            StremioAddon(
                baseUrl = base.toString().trimEnd('/'),
                manifest = manifest,
                capabilities = capabilities,
            )
        )
    }
}

/**
 * Plays content offered by a user-installed Stremio addon.
 *
 * Only a direct http(s) `url` is accepted. The protocol also allows `infoHash`, `nzbUrl`, archive
 * lists, `ytId` and `externalUrl`; Vetro has no torrent, usenet or archive stack, and `externalUrl`
 * is a browser link rather than a stream, so each is discarded rather than half-supported.
 */
class StremioAddonProvider(
    private val addon: StremioAddon,
    private val client: HttpClient,
) : MovieSeriesStreamingProvider {

    override val id: ProviderId = ProviderId("stremio:${addon.manifest.id}")
    override val displayName: String = addon.manifest.name
    override val capabilities: Set<ProviderCapability> = addon.capabilities

    private val json = AppJson

    override suspend fun resolve(request: PlaybackRequest): ProviderResolution =
        resolveTyped(displayName) { resolveFromAddon(request) }

    private suspend fun resolveFromAddon(request: PlaybackRequest): ProviderResolution {
        val type = when (request.mediaType) {
            MediaType.MOVIE -> "movie"
            MediaType.SERIES -> "series"
            else -> return ProviderResolution.Unsupported
        }
        // No IMDb id means there is nothing to ask for; the protocol has no other addressing mode.
        val imdbId = request.imdbId.normalizeImdbId() ?: return ProviderResolution.Unsupported

        val videoId = when (request.mediaType) {
            MediaType.SERIES -> "$imdbId:${request.seasonNumber}:${request.episodeNumber}"
            else -> imdbId
        }
        val response = client.get("${addon.baseUrl}/stream/$type/$videoId.json")
        requireProviderSuccess(response.status.value, displayName)

        val streams = json.decodeFromString<StremioStreamResponse>(response.bodyAsText()).streams
        val videos = streams.mapNotNull(::toVetroVideo)
        if (videos.isEmpty()) return ProviderResolution.NotFound

        return ProviderResolution.Found(
            hosters = listOf(VetroHoster(name = displayName, url = "", videos = videos)),
            // The id came straight from the request, so the match is as strong as IMDb itself.
            accuracy = MatchAccuracy.IMDB_ID,
        )
    }

    private fun toVetroVideo(stream: StremioStream): VetroVideo? {
        val url = stream.url ?: return null
        // The spec also permits ftp/rtmp here; VetroVideo requires http(s) and the player has no
        // transport for the others.
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null

        val label = stream.name?.takeIf(String::isNotBlank)
            ?: stream.description?.takeIf(String::isNotBlank)
            ?: stream.title?.takeIf(String::isNotBlank)
            ?: "Auto"

        return VetroVideo(
            url = url,
            label = label,
            sourceName = displayName,
            resolution = RESOLUTION.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull(),
            headers = safeProxyHeaders(stream.behaviorHints?.proxyHeaders?.request.orEmpty()),
            subtitles = stream.subtitles.mapNotNull { subtitle ->
                subtitle.url
                    .takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    ?.let { VetroSubtitleTrack(url = it, lang = subtitle.lang) }
            },
            // Streaming never implies an offline copy, and a third-party addon is in no position to
            // grant one.
            downloadAllowed = false,
        )
    }

    /**
     * Keeps only headers a CDN legitimately needs.
     *
     * An addon must not be able to make Vetro attach arbitrary headers — `Authorization` or `Cookie`
     * from a manifest would turn any installed addon into a credential-forwarding primitive.
     */
    private fun safeProxyHeaders(headers: Map<String, String>): Map<String, String> =
        SanitizeHeaders.sanitize(
            headers.filterKeys { key -> PROXY_HEADER_ALLOWLIST.any { it.equals(key, true) } }
        )

    private companion object {
        val RESOLUTION = Regex("""(?i)\b(\d{3,4})p\b""")
        val PROXY_HEADER_ALLOWLIST = setOf("User-Agent", "Referer", "Origin", "Accept")
    }
}
