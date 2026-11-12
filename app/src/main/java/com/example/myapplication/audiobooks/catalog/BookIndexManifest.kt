package com.example.myapplication.audiobooks.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Манифест индекса каталога книг (`GET /v1/manifest`, собирается book-dictionary/v2/build/build_index.py). */
@Serializable
data class BookIndexManifest(
    val schema: Int,
    val version: Int,
    val base: BaseRef,
    val deltas: List<DeltaRef> = emptyList(),
    val api: String? = null,
    val covers: String? = null,
) {
    @Serializable
    data class BaseRef(
        val version: Int,
        val url: String,
        val bytes: Long,
        val sha256: String,
        @SerialName("raw_bytes") val rawBytes: Long,
        @SerialName("raw_sha256") val rawSha256: String,
    )

    @Serializable
    data class DeltaRef(
        val from: Int,
        val to: Int,
        val url: String,
        val bytes: Long,
        val sha256: String,
    )
}

/** Что делать с локальным индексом, чтобы он стал версией манифеста. */
sealed interface BookIndexPlan {
    data object UpToDate : BookIndexPlan

    /** Цепочка дельт подряд: каждая применяется в своей транзакции. */
    data class Deltas(val steps: List<BookIndexManifest.DeltaRef>) : BookIndexPlan

    data class FullBase(val base: BookIndexManifest.BaseRef) : BookIndexPlan

    /** Индекс новее, чем понимает это приложение: остаёмся на том, что есть, и ходим в API. */
    data object Unsupported : BookIndexPlan
}

object BookIndexPlanner {
    /** Схема индекса, которую читает этот код (user_version файла). */
    const val SUPPORTED_SCHEMA = 2

    /** Дельты выгоднее базы, пока их суммарный размер не больше этой доли от неё. */
    private const val MAX_DELTA_SHARE = 0.5

    fun plan(local: Int?, manifest: BookIndexManifest): BookIndexPlan {
        if (manifest.schema > SUPPORTED_SCHEMA) return BookIndexPlan.Unsupported
        if (local == null || local > manifest.version) return BookIndexPlan.FullBase(manifest.base)
        if (local == manifest.version) return BookIndexPlan.UpToDate
        val steps = mutableListOf<BookIndexManifest.DeltaRef>()
        var at: Int = local
        while (at < manifest.version) {
            val next = manifest.deltas.firstOrNull { it.from == at && it.to > at } ?: return BookIndexPlan.FullBase(manifest.base)
            steps += next
            at = next.to
        }
        val deltaBytes = steps.sumOf { it.bytes }
        return if (deltaBytes <= manifest.base.bytes * MAX_DELTA_SHARE) BookIndexPlan.Deltas(steps) else BookIndexPlan.FullBase(manifest.base)
    }
}
