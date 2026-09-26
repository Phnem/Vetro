package com.example.myapplication.audiobooks.data

import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.source.AudiobookSearch
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.domain.source.SourceBookRef
import com.example.myapplication.audiobooks.domain.source.SourceId
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.audiobooks.domain.source.WorkMatch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Цепочка точек доступа одной озвучки (D-10, AB-24/25): одна и та же запись чтеца лежит на
 * нескольких сайтах, каждая копия — вариант в БД (`audiobook_variant`). Плеер идёт по цепочке сам:
 * звено не ответило — отмечаем его и переходим к следующему живому, позиция книги сохраняется.
 *
 * Порядок звеньев: закреплённое пользователем → живые по приоритету источника → недавно упавшие
 * (через [RETRY_AFTER_MS] они снова считаются живыми: сайт мог подняться).
 */
class NarrationChain(
    private val repository: AudiobookRepository,
    private val sources: List<AudiobookSource>,
    private val search: AudiobookSearch,
    private val resolver: ManifestResolver,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Link(val source: AudiobookSource, val ref: SourceBookRef, val variant: VariantId)

    data class Next(val link: Link, val manifest: MediaManifest)

    private val discovering = Mutex()

    suspend fun links(narrationId: NarrationId): List<Link> {
        val now = nowMs()
        return repository.variants(narrationId)
            .mapNotNull { v ->
                val source = sources.firstOrNull { it.id.value == v.sourceId } ?: return@mapNotNull null
                val alive = v.available || (v.verifiedAt ?: 0L) < now - RETRY_AFTER_MS
                Triple(Link(source, SourceBookRef(source.id, v.key), v.variantId), v, alive)
            }
            .sortedWith(
                compareByDescending<Triple<Link, VariantLink, Boolean>> { it.second.pinned }
                    .thenByDescending { it.third }
                    .thenBy { search.rank(it.first.source.id) },
            )
            .map { it.first }
    }

    /**
     * Ищет копии этой озвучки (то же произведение, тот же чтец) на всех сайтах и пристёгивает их к
     * цепочке. Возвращает, сколько звеньев добавилось. Параллельные вызовы для одной книги не
     * дублируют работу.
     */
    suspend fun discover(narrationId: NarrationId): Int = discovering.withLock {
        val book = repository.bookOf(narrationId) ?: return@withLock 0
        val known = repository.variants(narrationId).map { it.variantId.value }.toSet()
        val voice = WorkMatch.words(book.narrators)
        val candidates = search.sameWork(book.title, book.authors).filter { hit ->
            val source = sources.firstOrNull { it.id == hit.ref.source } ?: return@filter false
            source.variantOf(hit.ref).value !in known &&
                (voice.isEmpty() || hit.narrators.isEmpty() || WorkMatch.words(hit.narrators).any(voice::contains))
        }
        var added = 0
        for (hit in candidates) {
            val source = sources.first { it.id == hit.ref.source }
            val details = (source.details(hit.ref) as? SourceResult.Ok)?.value ?: continue
            // Та же книга и тот же чтец сводятся в эту же озвучку по отпечаткам; другой чтец — своя.
            if (repository.saveOpened(source, details).narrationId == narrationId) added++
        }
        added
    }

    /**
     * Следующее живое звено, кроме [tried]: живое — значит, сайт отдал плейлист. Когда известные
     * звенья кончились, один раз ищем новые копии на сайтах.
     */
    suspend fun next(narrationId: NarrationId, tried: Set<VariantId>): Next? {
        repeat(2) { pass ->
            for (link in links(narrationId).filter { it.variant !in tried }) {
                val manifest = runCatching { resolver.manifest(link.variant) }.getOrNull()
                repository.markVariant(link.variant, manifest != null)
                if (manifest != null) return Next(link, manifest)
            }
            if (pass == 0 && discover(narrationId) == 0) return null
        }
        return null
    }

    /** Звено не отдало звук: помечаем, сбрасываем закэшированный плейлист. */
    suspend fun fail(variant: VariantId) {
        resolver.invalidate(variant)
        repository.markVariant(variant, false)
    }

    fun sourceName(id: SourceId): String = sources.firstOrNull { it.id == id }?.displayName ?: id.value

    private companion object {
        const val RETRY_AFTER_MS = 30 * 60 * 1000L
    }
}
