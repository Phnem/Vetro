package com.example.myapplication.media.source

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.net.URLDecoder
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** Serialized, non-secret boundary used to scope rehydrated playback credentials. */
@Serializable
class PlaybackCredentialScope internal constructor(
    val rootUrl: String,
    val allowQuery: Boolean,
) {
    internal fun contains(url: String): Boolean =
        PlaybackAuthScope.create(rootUrl, allowQuery = allowQuery)?.contains(url) == true

    override fun equals(other: Any?): Boolean = other is PlaybackCredentialScope &&
        rootUrl == other.rootUrl && allowQuery == other.allowQuery

    override fun hashCode(): Int = 31 * rootUrl.hashCode() + allowQuery.hashCode()
}

/** Canonical origin/root boundary for credentials attached to playback requests. */
internal class PlaybackAuthScope private constructor(
    private val root: HttpUrl,
    private val allowQuery: Boolean,
) {
    fun contains(url: String): Boolean {
        val candidate = url.toHttpUrlOrNull() ?: return false
        return sameOrigin(candidate) && candidate.username.isEmpty() && candidate.password.isEmpty() &&
            candidate.fragment == null && (allowQuery || candidate.query == null) &&
            (!allowQuery || !containsSensitiveQuery(candidate.toString())) &&
            containsPath(candidate.encodedPath)
    }

    fun credentialRef(prefix: String, discriminator: String = ""): PlaybackCredentialRef {
        val identity = "${root.scheme}://${root.host}:${root.port}${normalizedRootPath()}|$discriminator"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return PlaybackCredentialRef("$prefix:${digest.take(24)}")
    }

    fun credentialScope(): PlaybackCredentialScope = PlaybackCredentialScope(
        rootUrl = root.newBuilder().query(null).fragment(null).build().toString(),
        allowQuery = allowQuery,
    )

    private fun sameOrigin(candidate: HttpUrl): Boolean =
        root.scheme == candidate.scheme && root.host == candidate.host && root.port == candidate.port

    private fun containsPath(candidatePath: String): Boolean {
        val rootPath = normalizedRootPath()
        return rootPath == "/" || candidatePath == rootPath || candidatePath.startsWith("$rootPath/")
    }

    private fun normalizedRootPath(): String = root.encodedPath.trimEnd('/').ifEmpty { "/" }

    companion object {
        fun create(baseUrl: String, relativeRoot: String = "", allowQuery: Boolean): PlaybackAuthScope? =
            runCatching {
                val base = requireNotNull(baseUrl.trim().toHttpUrlOrNull())
                require(base.username.isEmpty() && base.password.isEmpty())
                require(base.query == null && base.fragment == null)
                val relative = relativeRoot.trim().trim('/')
                val decodedSegments = relative.split('/').filter(String::isNotEmpty).map {
                    URLDecoder.decode(it, StandardCharsets.UTF_8.name())
                }
                require(decodedSegments.none { it == "." || it == ".." || '/' in it || '\\' in it })
                val root = base.newBuilder().apply {
                    if (relative.isNotEmpty()) addPathSegments(relative)
                }.build()
                PlaybackAuthScope(root, allowQuery)
            }.getOrNull()
    }
}

/**
 * Credentialed candidates never follow redirects. Adaptive child requests may still be absolute,
 * so a network interceptor removes every candidate-provided header outside the configured root.
 */
internal fun OkHttpClient.forPlaybackCandidate(video: VetroVideo): OkHttpClient {
    if (video.credentialRef == null || video.headers.isEmpty()) return this
    val scopedHeaderNames = SanitizeHeaders.sanitize(video.headers).keys
    return newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val allowed = video.credentialHeadersFor(request.url.toString()).keys
            val scoped = request.newBuilder().apply {
                scopedHeaderNames.filterNot(allowed::contains).forEach(::removeHeader)
            }.build()
            chain.proceed(scoped)
        }
        .build()
}

internal fun VetroVideo.credentialHeadersFor(requestUrl: String): Map<String, String> {
    if (credentialRef == null) return headers
    return if (credentialScope?.contains(requestUrl) == true) headers else emptyMap()
}
