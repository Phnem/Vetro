package com.example.myapplication.audiobooks.domain.source

import com.example.myapplication.audiobooks.domain.model.AudioTrack
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.VariantId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One source implements this for each family of variant IDs. */
interface ManifestSource {
    fun supports(variant: VariantId): Boolean
    suspend fun refresh(variant: VariantId): MediaManifest
}

/** Holds fresh manifests and serializes refreshes so prefetching adjacent tracks does not stampede. */
class ManifestResolver(
    private val sources: List<ManifestSource>,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val manifests = mutableMapOf<VariantId, MediaManifest>()

    suspend fun put(manifest: MediaManifest) = lock.withLock {
        validate(manifest)
        manifests[manifest.variant] = manifest
    }

    suspend fun invalidate(variant: VariantId) = lock.withLock {
        manifests.remove(variant)
    }

    suspend fun manifest(variant: VariantId): MediaManifest = lock.withLock {
        manifests[variant]?.takeIf(::isFresh) ?: run {
            val source = sources.firstOrNull { it.supports(variant) }
                ?: error("No manifest source for variant")
            source.refresh(variant).also {
                validate(it)
                require(it.variant == variant) { "Manifest belongs to another variant" }
                manifests[variant] = it
            }
        }
    }

    suspend fun resolve(variant: VariantId, trackIndex: Int): AudioTrack = lock.withLock {
        require(trackIndex >= 0)
        val cached = manifests[variant]?.takeIf(::isFresh)
        val manifest = cached ?: run {
            val source = sources.firstOrNull { it.supports(variant) }
                ?: error("No manifest source for variant")
            source.refresh(variant).also {
                validate(it)
                require(it.variant == variant) { "Manifest belongs to another variant" }
                manifests[variant] = it
            }
        }
        manifest.tracks.getOrNull(trackIndex)
            ?: error("Track index out of range")
    }

    private fun isFresh(manifest: MediaManifest): Boolean =
        manifest.expiresAt == null || manifest.expiresAt > nowMs() + EXPIRY_MARGIN_MS

    private fun validate(manifest: MediaManifest) {
        require(manifest.tracks.isNotEmpty())
        require(manifest.tracks.indices.all { manifest.tracks[it].index == it })
        require(manifest.tracks.all { it.url.isNotBlank() })
    }

    private companion object {
        const val EXPIRY_MARGIN_MS = 30_000L
    }
}
