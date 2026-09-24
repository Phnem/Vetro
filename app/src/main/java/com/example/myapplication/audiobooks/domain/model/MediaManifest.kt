package com.example.myapplication.audiobooks.domain.model

@JvmInline
value class VariantId(val value: String)

data class MediaManifest(
    val variant: VariantId,
    val tracks: List<AudioTrack>,
    val chapters: List<Chapter>,
    val resolvedAt: Long,
    val expiresAt: Long?,
)

data class AudioTrack(
    val index: Int,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
)

data class Chapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val durationMs: Long?,
)
