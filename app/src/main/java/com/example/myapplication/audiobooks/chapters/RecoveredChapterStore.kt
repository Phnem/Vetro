package com.example.myapplication.audiobooks.chapters

import com.example.myapplication.audiobooks.domain.model.Chapter
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.source.ChapterOverride
import com.example.myapplication.data.local.JsonMapFileStore
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Найденные главы озвучки. Пока [applied] false — это предложение («Найдено 18 предполагаемых глав —
 * применить?»); принятые накладываются на манифест. [fingerprint] — какие именно файлы разбирались:
 * если у варианта сменились треки, старая разметка к ним не прикладывается.
 */
@Serializable
data class RecoveredChapters(
    val fingerprint: String,
    val chapters: List<RecoveredChapter>,
    val applied: Boolean = false,
    val createdAt: Long = 0L,
    /** «Не надо» / «Вернуть исходные»: предложение не показывается и сам поиск не повторяется. */
    val declined: Boolean = false,
) {
    val confirmedCount: Int get() = chapters.count { it.confirmed }
}

class RecoveredChapterStore(file: File) : ChapterOverride {
    private val store = JsonMapFileStore(file, RecoveredChapters.serializer(), "RecoveredChapters")

    val flow: StateFlow<Map<String, RecoveredChapters>> get() = store.flow

    suspend fun get(variant: VariantId): RecoveredChapters? {
        store.ensureLoaded()
        return store[variant.value]
    }

    suspend fun save(variant: VariantId, value: RecoveredChapters) {
        store.ensureLoaded()
        store.update { it + (variant.value to value) }
    }

    suspend fun setApplied(variant: VariantId, applied: Boolean) {
        store.ensureLoaded()
        store.update { map ->
            map[variant.value]?.let { map + (variant.value to it.copy(applied = applied, declined = !applied)) } ?: map
        }
    }

    suspend fun remove(variant: VariantId) {
        store.ensureLoaded()
        store.update { it - variant.value }
    }

    override suspend fun chapters(manifest: MediaManifest): List<Chapter>? {
        val saved = get(manifest.variant)?.takeIf { it.applied && it.fingerprint == fingerprint(manifest) } ?: return null
        return toChapters(saved.chapters, manifest.tracks.sumOf { it.durationMs ?: 0L }.takeIf { t -> manifest.tracks.all { it.durationMs != null } })
    }

    companion object {
        /** Треки варианта: число и длительности (или размеры), в которых искались паузы. */
        fun fingerprint(manifest: MediaManifest): String =
            manifest.tracks.joinToString(",", prefix = "${manifest.tracks.size}:") { "${it.durationMs ?: -1}/${it.sizeBytes ?: -1}" }

        fun toChapters(found: List<RecoveredChapter>, totalMs: Long?): List<Chapter> {
            val sorted = found.sortedBy { it.startMs }
            return sorted.mapIndexed { i, c ->
                val end = sorted.getOrNull(i + 1)?.startMs ?: totalMs
                Chapter(i, c.title, c.startMs, end?.minus(c.startMs)?.takeIf { it > 0 })
            }
        }
    }
}
