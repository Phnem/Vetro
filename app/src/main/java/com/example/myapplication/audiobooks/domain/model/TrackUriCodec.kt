package com.example.myapplication.audiobooks.domain.model

import java.net.URI
import java.util.Base64

/** Stable, secret-free URI for a track. A variant ID may itself contain ':' and '/'. */
object TrackUriCodec {
    private const val SCHEME = "vetro-audio"
    private const val AUTHORITY = "track"

    fun encode(variant: VariantId, trackIndex: Int): String {
        require(variant.value.isNotBlank())
        require(trackIndex >= 0)
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(variant.value.toByteArray(Charsets.UTF_8))
        return "$SCHEME://$AUTHORITY/$encoded/$trackIndex"
    }

    fun decode(uri: String): TrackRef? = runCatching {
        val parsed = URI(uri)
        if (parsed.scheme != SCHEME || parsed.authority != AUTHORITY || parsed.query != null || parsed.fragment != null) return null
        val segments = parsed.path.trim('/').split('/')
        if (segments.size != 2) return null
        val variant = String(Base64.getUrlDecoder().decode(segments[0]), Charsets.UTF_8)
        val index = segments[1].toInt()
        if (variant.isBlank() || index < 0) return null
        TrackRef(VariantId(variant), index)
    }.getOrNull()

    data class TrackRef(val variant: VariantId, val trackIndex: Int)
}

