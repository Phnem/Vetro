package com.example.myapplication.media.source

import com.example.myapplication.localplayer.domain.SkipKind
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class VetroSubtitleTrack(
    val url: String,
    val lang: String,
    val mimeType: String = "text/vtt",
    /** Id дорожки в плеере; у подгруженных пользователем — `opensubtitles:<file_id>`. */
    val id: String? = null,
    val label: String? = null,
) {
    /** Подгружена пользователем из внешней базы, а не пришла с источником видео. */
    val isUserAdded: Boolean get() = id?.startsWith(USER_ADDED_PREFIX) == true

    companion object {
        const val USER_ADDED_PREFIX = "opensubtitles:"
    }
}

@Serializable
data class VetroTimestamp(
    val startMs: Long,
    val endMs: Long,
    val kind: SkipKind,
)

@Serializable
data class VetroSkipReference(
    val segments: List<VetroTimestamp>,
    val referenceDurationMs: Long,
    val origin: String,
)

@Serializable
@JvmInline
value class PlaybackCredentialRef(val value: String) {
    init {
        require(value.matches(Regex("(?:webdav|jellyfin|emby):[0-9a-f]{24}"))) {
            "Invalid playback credential reference"
        }
    }
}

/**
 * Resolved playable stream. Independent of Animetail's Video model
 * (no mutable status, no okhttp Headers, no MPV-only fields).
 */
@Serializable
data class VetroVideo(
    val url: String,
    val label: String,
    val sourceName: String? = null,
    val resolution: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<VetroSubtitleTrack> = emptyList(),
    val audioTracks: List<VetroSubtitleTrack> = emptyList(),
    val timestamps: List<VetroTimestamp> = emptyList(),
    val skipReference: VetroSkipReference? = null,
    val isPreferred: Boolean = false,
    /** The configured owner/provider explicitly permits offline download. */
    val downloadAllowed: Boolean = false,
    /** Encrypted-store lookup key used to rehydrate sensitive headers in background work. */
    val credentialRef: PlaybackCredentialRef? = null,
    /** Non-secret canonical root to which rehydrated credentials may be sent. */
    val credentialScope: PlaybackCredentialScope? = null,
    val resolvedAt: Long = System.currentTimeMillis(),
) {
    init {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "VetroVideo.url must be http(s), got: $url"
        }
    }

    val isHlsOrDash: Boolean
        get() {
            val lower = url.lowercase()
            return lower.contains(".m3u8") || lower.contains(".mpd") ||
                lower.contains("m3u8") || lower.contains("mpd")
        }
}

internal fun VetroVideo.withoutPersistedSecrets(): VetroVideo = copy(
    headers = headers.filter { (key, value) ->
        isPersistableHeader(key, value)
    }
)

internal fun areHeadersSafeForPersistence(headers: Map<String, String>): Boolean =
    headers.all { (key, value) -> isPersistableHeader(key, value) }

internal fun VetroVideo.isSafeForBackgroundPersistence(): Boolean {
    val unsupportedHeaders = headers.any { (key, value) ->
        !isPersistableHeader(key, value)
    }
    if (unsupportedHeaders && credentialRef == null) return false
    return !containsSensitiveQuery(url)
}

private fun isPersistableHeader(key: String, value: String): Boolean {
    if (PERSISTED_HEADER_ALLOWLIST.none { allowed -> key.equals(allowed, ignoreCase = true) }) {
        return false
    }
    if (key.equals("Referer", ignoreCase = true) || key.equals("Origin", ignoreCase = true)) {
        return !containsSensitiveQuery(value)
    }
    return true
}

internal fun containsSensitiveQuery(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return true
    val keys = parsed.queryParameterNames.map(String::lowercase)
    return keys.any { key -> SENSITIVE_QUERY_KEYS.any(key::contains) }
}

internal fun urlContainsSecret(url: String, secret: String): Boolean {
    if (secret.isEmpty()) return false
    val parsed = url.toHttpUrlOrNull() ?: return true
    val decodedPath = parsed.pathSegments.joinToString("/", prefix = "/")
    return secret in decodedPath || parsed.queryParameterNames.any { name ->
        secret in name || parsed.queryParameterValues(name).any { value ->
            value != null && secret in value
        }
    }
}

private val PERSISTED_HEADER_ALLOWLIST = setOf("User-Agent", "Accept", "Referer", "Origin")
private val SENSITIVE_QUERY_KEYS = setOf(
    "token", "signature", "sig", "key", "auth", "jwt", "policy", "expires", "x-amz-",
)

@Serializable
data class VetroHoster(
    val name: String,
    val url: String = "",
    val videos: List<VetroVideo>? = null,
    val skipReference: VetroSkipReference? = null,
    val lazy: Boolean = false,
)

object SanitizeHeaders {
    /** Drop or scrub CR/LF so headers cannot be used for injection. */
    fun sanitize(headers: Map<String, String>): Map<String, String> =
        headers.mapNotNull { (k, v) ->
            val key = k.trim()
            if (key.isEmpty() || key.contains('\r') || key.contains('\n')) return@mapNotNull null
            val value = v.replace("\r", "").replace("\n", " ").trim()
            if (value.isEmpty()) null else key to value
        }.toMap()
}
