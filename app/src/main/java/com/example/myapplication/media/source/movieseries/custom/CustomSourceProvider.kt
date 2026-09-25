package com.example.myapplication.media.source.movieseries.custom

import com.example.myapplication.network.AppJson
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.PlaybackRequest
import com.example.myapplication.media.source.VetroHoster
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.source.movieseries.MatchAccuracy
import com.example.myapplication.media.source.movieseries.MovieSeriesStreamingProvider
import com.example.myapplication.media.source.movieseries.ProviderCapability
import com.example.myapplication.media.source.movieseries.ProviderId
import com.example.myapplication.media.source.movieseries.ProviderResolution
import com.example.myapplication.media.source.movieseries.requireProviderSuccess
import com.example.myapplication.media.source.movieseries.resolveTyped
import com.example.myapplication.network.AppLanguage
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Values a manifest template may substitute, resolved from one playback request. */
private class Substitutions(
    request: PlaybackRequest,
    private val lookupId: String? = null,
) {
    private val values: Map<String, String?> = mapOf(
        "tmdbId" to request.tmdbId?.toString(),
        "imdbId" to request.imdbId,
        "kinopoiskId" to request.kinopoiskId?.toString(),
        "season" to request.seasonNumber.toString(),
        "episode" to request.episodeNumber.toString(),
        "title" to request.anime.title,
        "lookupId" to lookupId,
    )

    /** `null` when the request cannot supply a value the template needs. */
    fun valueFor(placeholder: String): String? = values[placeholder]
}

private val PLACEHOLDER = Regex("""\{([A-Za-z]+)}""")

/**
 * A user-installed source driven entirely by its manifest.
 *
 * Everything provider-specific lives in data, so adding a source needs no code and no rebuild — and
 * equally, nothing the manifest says can make this class do something other than issue the requests
 * it describes and read JSON back.
 */
class CustomSourceProvider(
    private val manifest: VetroSourceManifest,
    private val client: HttpClient,
    /** Returns the stored secret, or null when the source needs none. */
    private val secretProvider: suspend () -> String? = { null },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : MovieSeriesStreamingProvider {

    override val id: ProviderId = ProviderId("custom:${manifest.id}")
    override val displayName: String = manifest.name
    override val capabilities: Set<ProviderCapability> = manifest.capabilities

    private val json = AppJson

    override suspend fun resolve(request: PlaybackRequest): ProviderResolution =
        resolveTyped(displayName) { resolveFromManifest(request) }

    private suspend fun resolveFromManifest(request: PlaybackRequest): ProviderResolution {
        if (request.mediaType != MediaType.MOVIE && request.mediaType != MediaType.SERIES) {
            return ProviderResolution.Unsupported
        }
        val secret = if (manifest.auth != null) {
            secretProvider() ?: return ProviderResolution.NotConfigured
        } else {
            null
        }

        val lookupId = manifest.resolveVia?.let { chain ->
            val body = fetch(chain.lookup.path, chain.lookup.method, Substitutions(request), secret)
                ?: return ProviderResolution.Unsupported
            pointer(body, chain.lookup.extract)?.asPlainString()
                ?: return ProviderResolution.NotFound
        }

        val step = requestFor(request.mediaType) ?: return ProviderResolution.Unsupported
        val substitutions = Substitutions(request, lookupId)
        // Checked before any request: a template needing an id this title does not have was never
        // asked, so reporting NotFound would claim the source answered when it never heard the
        // question — and would wrongly count as a healthy reply.
        if (substitute(step.path, substitutions) == null) return ProviderResolution.Unsupported
        val videos = collectPages(step, substitutions, secret)
        if (videos.isEmpty()) return ProviderResolution.NotFound

        return ProviderResolution.Found(
            hosters = listOf(VetroHoster(name = displayName, url = "", videos = videos)),
            accuracy = accuracyFor(request),
            language = declaredLanguage(),
        )
    }

    private suspend fun collectPages(
        step: ManifestRequest,
        substitutions: Substitutions,
        secret: String?,
    ): List<VetroVideo> {
        val pagination = manifest.pagination
        val pages = pagination?.maxPages ?: 1
        val collected = mutableListOf<VetroVideo>()
        for (index in 0 until pages) {
            val pageParam = pagination?.let { it.param to it.valueFor(index) }
            val body = fetch(step.path, step.method, substitutions, secret, pageParam) ?: break
            val batch = readStreams(body)
            if (batch.isEmpty()) break
            collected += batch
        }
        return collected.distinctBy(VetroVideo::url)
    }

    private fun ManifestPagination.valueFor(index: Int): String = when (kind) {
        PaginationKind.PAGE -> (index + 1).toString()
        PaginationKind.OFFSET -> index.toString()
    }

    /** `null` when the template needs a value this request does not have. */
    private suspend fun fetch(
        template: String,
        method: String,
        substitutions: Substitutions,
        secret: String?,
        pageParam: Pair<String, String>? = null,
    ): JsonElement? {
        val path = substitute(template, substitutions) ?: return null
        val url = manifest.baseUrl.trimEnd('/') + path
        // Re-check after substitution: a value carrying a slash or an encoded host must never be
        // able to move the request off the configured origin.
        val parsed = url.toHttpUrlOrNull() ?: return null
        val base = manifest.baseUrl.toHttpUrlOrNull() ?: return null
        if (parsed.host != base.host || parsed.scheme != base.scheme || parsed.port != base.port) {
            return null
        }

        var attempt = 0
        while (true) {
            attempt++
            val response = client.request(url) {
                this.method = HttpMethod.parse(method.uppercase())
                pageParam?.let { (name, value) -> parameter(name, value) }
                applyAuth(secret)
            }
            val status = response.status.value
            val retryable = status == 429 || status in 500..599
            if (retryable && attempt < manifest.retry.maxAttempts) {
                sleep(manifest.retry.backoffMs * attempt)
                continue
            }
            requireProviderSuccess(status, displayName)
            return json.parseToJsonElement(response.bodyAsText())
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth(secret: String?) {
        val auth = manifest.auth ?: return
        val value = secret ?: return
        when (auth.kind) {
            AuthKind.HEADER -> header(auth.name, auth.prefix + value)
            AuthKind.QUERY -> parameter(auth.name, auth.prefix + value)
        }
    }

    private fun readStreams(body: JsonElement): List<VetroVideo> {
        val array = pointer(body, manifest.response.streams) as? JsonArray ?: return emptyList()
        val mapping = manifest.response
        return array.mapNotNull { entry ->
            val url = pointer(entry, mapping.url)?.asPlainString() ?: return@mapNotNull null
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
            val label = mapping.label?.let { pointer(entry, it)?.asPlainString() }
            val resolution = mapping.resolution?.let { pointer(entry, it)?.asPlainString()?.toIntOrNull() }
            VetroVideo(
                url = url,
                label = label ?: resolution?.let { "${it}p" } ?: "Auto",
                sourceName = displayName,
                resolution = resolution,
                // Download stays default-deny: only an explicit positive from the source flips it.
                downloadAllowed = mapping.downloadAllowed
                    ?.let { pointer(entry, it)?.asPlainString() }
                    ?.equals("true", ignoreCase = true) == true &&
                    ProviderCapability.DOWNLOAD in manifest.capabilities,
            )
        }
    }

    private fun requestFor(mediaType: MediaType): ManifestRequest? = when (mediaType) {
        MediaType.MOVIE -> manifest.movie ?: manifest.resolveVia?.movie
        MediaType.SERIES -> manifest.series ?: manifest.resolveVia?.series
        else -> null
    }

    /**
     * How strongly this source identified the title.
     *
     * Derived from which id the template actually addressed, so a manifest cannot claim a confidence
     * it did not earn.
     */
    private fun accuracyFor(request: PlaybackRequest): MatchAccuracy? {
        val template = requestFor(request.mediaType)?.path
            ?: manifest.resolveVia?.lookup?.path
            ?: return null
        return when {
            template.contains("{tmdbId}") && request.tmdbId != null -> MatchAccuracy.TMDB_ID
            template.contains("{imdbId}") && request.imdbId != null -> MatchAccuracy.IMDB_ID
            template.contains("{kinopoiskId}") && request.kinopoiskId != null ->
                MatchAccuracy.KINOPOISK_ID

            template.contains("{title}") -> MatchAccuracy.TITLE_ONLY
            else -> null
        }
    }

    private fun declaredLanguage(): AppLanguage? {
        val ru = ProviderCapability.RU in manifest.capabilities
        val en = ProviderCapability.EN in manifest.capabilities
        return when {
            ru && !en -> AppLanguage.RU
            en && !ru -> AppLanguage.EN
            else -> null
        }
    }

    private fun substitute(template: String, substitutions: Substitutions): String? {
        val builder = StringBuilder()
        var cursor = 0
        for (match in PLACEHOLDER.findAll(template)) {
            val name = match.groupValues[1]
            val value = substitutions.valueFor(name) ?: return null
            builder.append(template, cursor, match.range.first)
            builder.append(encodeSegment(value))
            cursor = match.range.last + 1
        }
        builder.append(template, cursor, template.length)
        return builder.toString()
    }

    /** Percent-encodes everything that could alter the path structure. */
    private fun encodeSegment(value: String): String = buildString {
        value.forEach { char ->
            if (char.isLetterOrDigit() || char in "-._~") {
                append(char)
            } else {
                char.toString().toByteArray(Charsets.UTF_8).forEach { byte ->
                    append('%').append("%02X".format(byte))
                }
            }
        }
    }
}

/** Minimal RFC 6901 pointer navigation over a parsed body. */
internal fun pointer(root: JsonElement, pointer: String): JsonElement? {
    if (pointer == "/" || pointer.isEmpty()) return root
    var current: JsonElement = root
    pointer.removePrefix("/").split('/').forEach { rawToken ->
        val token = rawToken.replace("~1", "/").replace("~0", "~")
        current = when (val node = current) {
            is JsonObject -> node[token] ?: return null
            is JsonArray -> node.getOrNull(token.toIntOrNull() ?: return null) ?: return null
            else -> return null
        }
    }
    return current
}

/** Reads a primitive as text without JSON quoting; `null` for objects and arrays. */
internal fun JsonElement.asPlainString(): String? =
    (this as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
