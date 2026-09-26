package com.example.myapplication.media.source.movieseries.custom

import com.example.myapplication.media.source.movieseries.ProviderCapability
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Manifest schema version this build understands. */
const val SUPPORTED_MANIFEST_VERSION = 1

/**
 * A user-installed source, described declaratively.
 *
 * The format describes a *structural API* — endpoints that answer with JSON — and deliberately has
 * no way to express HTML selectors, regex extraction from a page body, JavaScript, iframe unwrapping
 * or anti-bot handling. That absence is the point: it is what separates a described source from an
 * arbitrary code runtime, and it is why a manifest can be installed without executing anything.
 */
@Serializable
data class VetroSourceManifest(
    val manifestVersion: Int,
    val id: String,
    val name: String,
    val baseUrl: String,
    val capabilities: Set<ProviderCapability> = emptySet(),
    val auth: ManifestAuth? = null,
    /** Direct movie lookup. Omit when the source needs [resolveVia] first. */
    val movie: ManifestRequest? = null,
    /** Direct episode lookup. Omit when the source needs [resolveVia] first. */
    val series: ManifestRequest? = null,
    val resolveVia: ManifestChain? = null,
    val pagination: ManifestPagination? = null,
    val retry: ManifestRetry = ManifestRetry(),
    val response: ManifestResponseMapping,
    /** Opt-in for a LAN address without TLS. Never permitted for a public host. */
    val allowInsecureHttp: Boolean = false,
)

@Serializable
data class ManifestRequest(
    val path: String,
    val method: String = "GET",
)

/**
 * A two-step source: find the internal id, then ask for the stream with it.
 *
 * Each step is one request plus one JSON-pointer extraction. There is no branching and no
 * expression language — a chain, not a script.
 */
@Serializable
data class ManifestChain(
    val lookup: ManifestLookupStep,
    val movie: ManifestRequest? = null,
    val series: ManifestRequest? = null,
)

@Serializable
data class ManifestLookupStep(
    val path: String,
    val method: String = "GET",
    /** JSON pointer to the value substituted as `{lookupId}` in the next step. */
    val extract: String,
)

@Serializable
data class ManifestPagination(
    val kind: PaginationKind = PaginationKind.PAGE,
    val param: String = "page",
    val maxPages: Int = 1,
)

enum class PaginationKind { PAGE, OFFSET }

@Serializable
data class ManifestRetry(
    val maxAttempts: Int = 1,
    val backoffMs: Long = 500,
)

@Serializable
data class ManifestAuth(
    val kind: AuthKind,
    /** Header name or query parameter name. The secret itself never appears in the manifest. */
    val name: String,
    val prefix: String = "",
)

enum class AuthKind {
    /** Secret sent as a request header. */
    HEADER,

    /** Secret sent as a query parameter. Rejected for public hosts: URLs leak into logs. */
    QUERY,
}

/** JSON pointers describing where the stream fields live in the provider's response. */
@Serializable
data class ManifestResponseMapping(
    val streams: String,
    val url: String,
    val label: String? = null,
    val resolution: String? = null,
    val language: String? = null,
    @SerialName("downloadAllowed") val downloadAllowed: String? = null,
    val translation: String? = null,
)

sealed interface ManifestValidation {
    data class Valid(val manifest: VetroSourceManifest) : ManifestValidation
    data class Invalid(val reason: String) : ManifestValidation
}

/**
 * Substitutions a path template may contain. Anything else is a validation error.
 *
 * `year` is deliberately absent: the library entry does not carry a release year, so accepting the
 * placeholder would advertise something that could never resolve and would turn every request from
 * such a manifest into an unsupported one.
 */
val SUPPORTED_PLACEHOLDERS = setOf(
    "tmdbId", "imdbId", "kinopoiskId", "season", "episode", "title", "lookupId",
)

private val PLACEHOLDER = Regex("""\{([A-Za-z]+)\}""")
private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,64}")

/**
 * Rejects a manifest that could not be executed safely.
 *
 * Validation is deliberately strict and explicit: a user pastes these from the internet, so an
 * unclear rule becomes someone else's security bug.
 */
object ManifestValidator {

    fun validate(manifest: VetroSourceManifest): ManifestValidation {
        fail(manifest)?.let { return ManifestValidation.Invalid(it) }
        return ManifestValidation.Valid(manifest)
    }

    private fun fail(manifest: VetroSourceManifest): String? {
        if (manifest.manifestVersion != SUPPORTED_MANIFEST_VERSION) {
            return "Unsupported manifestVersion ${manifest.manifestVersion}"
        }
        if (!manifest.id.matches(SAFE_ID)) return "Invalid source id"
        if (manifest.name.isBlank()) return "Source name must not be blank"

        val base = manifest.baseUrl.toHttpUrlOrNull() ?: return "baseUrl is not a valid URL"
        if (base.encodedQuery != null || base.encodedFragment != null) {
            return "baseUrl must not carry a query or fragment"
        }
        if (base.encodedUsername.isNotEmpty() || base.encodedPassword.isNotEmpty()) {
            return "baseUrl must not embed credentials"
        }
        if (!base.isHttps) {
            // Plain HTTP is tolerable for a box on the user's own network and nowhere else.
            if (!manifest.allowInsecureHttp) return "baseUrl must use https"
            if (!isPrivateHost(base.host)) return "Plain http is only allowed for a local address"
        }
        if (manifest.auth?.kind == AuthKind.QUERY && base.isHttps && !isPrivateHost(base.host)) {
            return "Query-parameter auth is not allowed for a public host"
        }
        manifest.auth?.let { auth ->
            if (auth.name.isBlank() || auth.name.any { it == '\r' || it == '\n' }) {
                return "Invalid auth parameter name"
            }
        }

        if (manifest.capabilities.isEmpty()) return "Manifest declares no capabilities"
        val servesMovie = ProviderCapability.MOVIE in manifest.capabilities
        val servesSeries = ProviderCapability.SERIES in manifest.capabilities
        if (!servesMovie && !servesSeries) return "Manifest must serve MOVIE or SERIES"

        val movieRequest = manifest.movie ?: manifest.resolveVia?.movie
        val seriesRequest = manifest.series ?: manifest.resolveVia?.series
        if (servesMovie && movieRequest == null) return "MOVIE is declared but no movie request"
        if (servesSeries && seriesRequest == null) return "SERIES is declared but no series request"

        val templates = listOfNotNull(
            manifest.movie?.path,
            manifest.series?.path,
            manifest.resolveVia?.lookup?.path,
            manifest.resolveVia?.movie?.path,
            manifest.resolveVia?.series?.path,
        )
        templates.forEach { template ->
            validateTemplate(template, manifest)?.let { return it }
        }

        if (seriesRequest != null && servesSeries) {
            val path = seriesRequest.path
            if (!path.contains("{season}") || !path.contains("{episode}")) {
                return "A series request must address both {season} and {episode}"
            }
        }

        manifest.resolveVia?.let { chain ->
            if (!chain.lookup.extract.startsWith("/")) return "extract must be a JSON pointer"
            val usesLookup = listOfNotNull(chain.movie?.path, chain.series?.path)
                .any { it.contains("{lookupId}") }
            if (!usesLookup) return "resolveVia is declared but {lookupId} is never used"
        }
        if (manifest.resolveVia == null && templates.any { it.contains("{lookupId}") }) {
            return "{lookupId} requires a resolveVia lookup step"
        }

        if (!manifest.response.streams.startsWith("/")) return "response.streams must be a pointer"
        if (!manifest.response.url.startsWith("/")) return "response.url must be a pointer"
        if (manifest.retry.maxAttempts !in 1..5) return "retry.maxAttempts must be between 1 and 5"
        if (manifest.retry.backoffMs !in 0..30_000) return "retry.backoffMs is out of range"
        manifest.pagination?.let { pagination ->
            if (pagination.maxPages !in 1..10) return "pagination.maxPages must be between 1 and 10"
            if (pagination.param.isBlank()) return "pagination.param must not be blank"
        }
        return null
    }

    private fun validateTemplate(template: String, manifest: VetroSourceManifest): String? {
        if (!template.startsWith("/")) return "Path template must start with '/': $template"
        // A template is a path, never a whole URL: allowing one would let a manifest redirect the
        // request to any host it liked while still looking like a configured source.
        if (template.contains("://")) return "Path template must not contain a scheme"
        if (template.startsWith("//")) return "Path template must not be protocol-relative"
        if (template.contains("..")) return "Path template must not contain '..'"

        PLACEHOLDER.findAll(template).forEach { match ->
            val name = match.groupValues[1]
            if (name !in SUPPORTED_PLACEHOLDERS) return "Unknown placeholder {$name}"
            declaredCapabilityFor(name)?.let { required ->
                if (required !in manifest.capabilities) {
                    // Substituting an id the source never claimed to support would silently produce
                    // a blank segment and a nonsense request.
                    return "{$name} requires capability $required"
                }
            }
        }
        return null
    }

    private fun declaredCapabilityFor(placeholder: String): ProviderCapability? = when (placeholder) {
        "tmdbId" -> ProviderCapability.TMDB_ID
        "imdbId" -> ProviderCapability.IMDB_ID
        "kinopoiskId" -> ProviderCapability.KINOPOISK_ID
        else -> null
    }

    private fun isPrivateHost(host: String): Boolean =
        host == "localhost" ||
            host.endsWith(".local") ||
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host == "127.0.0.1" ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)
}
