package com.example.myapplication.media.source

import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.movieseries.MovieSeriesStreamingProvider
import com.example.myapplication.media.source.movieseries.ProviderCapability
import com.example.myapplication.media.source.movieseries.ProviderId
import com.example.myapplication.media.source.movieseries.ProviderResolution
import com.example.myapplication.media.source.movieseries.providerResolutionForStatus
import com.example.myapplication.media.source.movieseries.resolveTyped
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class WebDavConfig(
    val baseUrl: String,
    val rootPath: String,
    val username: String,
    val password: String,
    val downloadAllowed: Boolean = false,
    val allowInsecureHttp: Boolean = false,
) {
    fun isValid(): Boolean {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return false
        val schemeAllowed = uri.scheme == "https" || (uri.scheme == "http" && allowInsecureHttp)
        return schemeAllowed && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.query == null && uri.fragment == null && username.isNotBlank() &&
            password.isNotBlank() && authScope() != null
    }
}

class WebDavPlaybackSource(
    private val client: HttpClient,
    private val configProvider: () -> WebDavConfig?,
) : MovieSeriesStreamingProvider {
    override val id: ProviderId = ProviderId("webdav")
    override val displayName: String = "WebDAV"
    private val sourceName: String get() = displayName

    /** The user owns the library, so downloads are possible; the config still decides per stream. */
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.MOVIE,
        ProviderCapability.SERIES,
        ProviderCapability.DIRECT,
        ProviderCapability.DOWNLOAD,
    )

    override suspend fun resolve(request: PlaybackRequest): ProviderResolution =
        resolveTyped(displayName) { resolveFromServer(request) }

    private suspend fun resolveFromServer(request: PlaybackRequest): ProviderResolution {
        val config = configProvider()?.takeIf(WebDavConfig::isValid)
            ?: return ProviderResolution.NotConfigured
        if (request.mediaType !in setOf(MediaType.MOVIE, MediaType.SERIES)) {
            return ProviderResolution.Unsupported
        }
        val auth = config.authorizationHeader()
        val response = client.request(listingUrl(config)) {
            method = HttpMethod("PROPFIND")
            header(HttpHeaders.Authorization, auth)
            header("Depth", "infinity")
            header(HttpHeaders.ContentType, "application/xml; charset=utf-8")
            setBody(PROPFIND_BODY)
        }
        if (response.status != HttpStatusCode.MultiStatus) {
            // 207 is the only success here. A 404 means the configured root is wrong, which says
            // nothing about the title — reporting NotFound would hide a broken configuration and
            // leave provider health looking clean.
            val status = response.status.value
            return if (status == 404 || status in 200..299) {
                ProviderResolution.InvalidResponse("WebDAV root did not list: HTTP $status")
            } else {
                providerResolutionForStatus(status)
                    ?: ProviderResolution.InvalidResponse("WebDAV HTTP $status")
            }
        }
        val href = parseDavHrefs(response.bodyAsText())
            .mapNotNull { resolveAllowedHref(config, it) }
            .firstOrNull { matchesRequest(it.toASCIIString(), request) }
            ?: return ProviderResolution.NotFound
        val mediaUrl = href.toASCIIString()
        val resolution = Regex("(?i)(?:^|[^0-9])(\\d{3,4})p(?:[^0-9]|$)")
            .find(mediaUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val video = VetroVideo(
            url = mediaUrl,
            label = resolution?.let { "${it}p" } ?: "Auto",
            sourceName = sourceName,
            resolution = resolution,
            headers = mapOf(HttpHeaders.Authorization to auth),
            isPreferred = true,
            downloadAllowed = config.downloadAllowed,
            credentialRef = config.credentialRef(),
            credentialScope = requireNotNull(config.authScope()).credentialScope(),
        )
        return ProviderResolution.Found(
            listOf(VetroHoster(name = sourceName, url = mediaUrl, videos = listOf(video)))
        )
    }

    private fun listingUrl(config: WebDavConfig): String =
        config.baseUrl.trim().trimEnd('/') + "/" + config.rootPath.trim().trim('/')

    private fun matchesRequest(href: String, request: PlaybackRequest): Boolean {
        val path = runCatching { URI(href).rawPath }.getOrNull() ?: return false
        val decoded = URLDecoder.decode(path, StandardCharsets.UTF_8.name()).lowercase()
        val fileName = decoded.substringAfterLast('/')
        if (VIDEO_EXTENSIONS.none(fileName::endsWith)) return false
        val aliases = listOf(request.anime.title, request.anime.titleEn, request.anime.titleRu)
            .mapNotNull { it?.normalizeTitle()?.takeIf(String::isNotBlank) }
        if (aliases.none { alias -> alias.split(' ').all(decoded.normalizeTitle()::contains) }) {
            return false
        }
        if (request.mediaType == MediaType.MOVIE) return true
        val season = request.seasonNumber
        val episode = request.episodeNumber
        return listOf(
            Regex("(?i)s0*$season[^0-9]*e0*$episode(?:[^0-9]|$)"),
            Regex("(?i)(?:^|[^0-9])0*$season\\s*x\\s*0*$episode(?:[^0-9]|$)"),
            Regex("(?i)season\\s*0*$season.*(?:episode|e)\\s*0*$episode(?:[^0-9]|$)"),
        ).any { it.containsMatchIn(decoded) }
    }

    private fun String.normalizeTitle(): String = lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private companion object {
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontenttype/></d:prop></d:propfind>"""
        // Authenticated adaptive manifests may reference cross-origin children; v1 intentionally
        // accepts only progressive files so Basic credentials never become default segment headers.
        val VIDEO_EXTENSIONS = listOf(".mp4", ".m4v", ".webm", ".mkv")

        fun parseDavHrefs(xml: String): List<String> {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml.trim())))
            val nodes = document.getElementsByTagNameNS("DAV:", "href")
            return (0 until nodes.length).mapNotNull { index ->
                nodes.item(index)?.textContent?.trim()?.takeIf(String::isNotBlank)
            }
        }
    }
}

internal fun WebDavConfig.authorizationHeader(): String {
    val value = Base64.getEncoder().encodeToString(
        "$username:$password".toByteArray(StandardCharsets.UTF_8)
    )
    return "Basic $value"
}

internal fun WebDavConfig.credentialRef(): PlaybackCredentialRef {
    return requireNotNull(authScope()).credentialRef("webdav")
}

internal fun WebDavConfig.isAllowedMediaUrl(url: String): Boolean =
    authScope()?.contains(url) == true

private fun resolveAllowedHref(config: WebDavConfig, href: String): URI? = runCatching {
    val root = URI(config.baseUrl.trim().trimEnd('/') + "/" + config.rootPath.trim().trim('/') + "/")
        .normalize()
    val candidate = root.resolve(href.trim()).normalize()
    candidate.takeIf { config.isAllowedMediaUrl(it.toASCIIString()) }
}.getOrNull()

internal fun VetroVideo.rehydrateWebDavCredentials(config: WebDavConfig?): VetroVideo {
    if (config == null || credentialRef != config.credentialRef() ||
        !config.isAllowedMediaUrl(url)
    ) {
        return this
    }
    return copy(headers = headers + (HttpHeaders.Authorization to config.authorizationHeader()))
}

private fun WebDavConfig.authScope(): PlaybackAuthScope? =
    PlaybackAuthScope.create(baseUrl, rootPath, allowQuery = false)
