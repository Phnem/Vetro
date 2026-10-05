package com.example.myapplication.audiobooks.data.local

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.data.LocalBookMeta
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.source.AudiobookSearch
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.audiobooks.domain.source.WorkMatch
import kotlinx.coroutines.CancellationException

/**
 * Своя папка → книга библиотеки. Название и автор — из тегов файлов и имени папки («Автор —
 * Название»), недостающее (обложка, описание, жанры, год) добирается у источников по тому же
 * произведению. Совпадение проверяется по названию и автору, а не по одному названию: чужая
 * обложка хуже, чем никакой.
 */
@UnstableApi
class LocalLibraryImporter(
    private val local: LocalFolderSource,
    private val resolver: ManifestResolver,
    private val search: AudiobookSearch,
    private val sources: List<AudiobookSource>,
    private val repository: AudiobookRepository,
) {
    /** Папка из системного выбора: все книги в ней (или она сама) — в библиотеку. Число добавленных. */
    suspend fun importTree(treeUri: Uri): Int {
        val books = local.addTree(treeUri)
        books.forEach { import(it) }
        return books.size
    }

    /**
     * Папки, добавленные раньше отдельной страницей «Свои книги», в библиотеку не попадали —
     * переносим их один раз, без повторного добора для уже перенесённых.
     */
    suspend fun syncExisting() {
        local.books().forEach { book ->
            if (!repository.inLibrary(book.workId)) import(book)
        }
    }

    private suspend fun import(book: LocalBook) {
        try {
            val manifest = runCatching { local.refresh(book.variant) }.getOrNull()
            manifest?.let { resolver.put(it) }
            val folder = FolderName.parse(book.title)
            val title = folder.title
            // «Исполнитель» в тегах — чаще чтец, чем автор. Если принять его за автора, сверка автора
            // отбросит верное совпадение и обложки не будет, поэтому в поиск идёт только то, что
            // точно автор: явный тег либо автор из имени папки. Остальное разберёт каталог.
            val tags = manifest?.let(local::tags)
            val authors = listOfNotNull(tags?.author ?: folder.author)
            val match = findWork(title, authors)
            val details = match?.let { m ->
                val source = sources.firstOrNull { it.id == m.ref.source } ?: return@let null
                (runCatching { source.details(m.ref) }.getOrNull() as? SourceResult.Ok)?.value
            }
            val matchAuthors = WorkMatch.words(match?.authors.orEmpty())
            val narrators = folder.narrator?.let(::listOf)
                ?: tags?.people.orEmpty().filterNot { person -> WorkMatch.words(listOf(person)).any(matchAuthors::contains) }
            repository.saveLocal(
                workId = book.workId,
                narrationId = book.narrationId,
                variantId = book.variant,
                folderUri = book.folderUri.toString(),
                meta = LocalBookMeta(
                    title = title,
                    authors = authors.ifEmpty { match?.authors.orEmpty() },
                    narrators = narrators,
                    coverUrl = book.artworkUri?.toString() ?: match?.coverUrl,
                    description = details?.description,
                    genres = match?.genres.orEmpty(),
                    year = match?.year,
                    durationMs = manifest?.totalMs(),
                    chapterCount = manifest?.chapters?.size?.takeIf { it > 0 },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "import failed for \"${book.title}\": ${e.message}")
        }
    }

    private suspend fun findWork(title: String, authors: List<String>): SourceBook? =
        runCatching { search.sameWork(title, authors) }.getOrDefault(emptyList())
            .sortedWith(compareBy<SourceBook> { search.rank(it.ref.source) }.thenByDescending { it.coverUrl != null })
            .firstOrNull()

    private fun MediaManifest.totalMs(): Long? {
        val durations = tracks.map { it.durationMs ?: return null }
        return durations.sum().takeIf { it > 0 }
    }

    private companion object {
        const val TAG = "LocalLibraryImporter"
    }
}

/**
 * Имя папки аудиокниги по обычным раскладкам: «Автор - Название», «Автор — Название (Чтец)»,
 * «Название [Чтец]». Всё, что не распознано, остаётся названием целиком.
 */
internal data class FolderName(val title: String, val author: String?, val narrator: String?) {
    companion object {
        fun parse(raw: String): FolderName {
            var rest = raw.trim()
            var narrator: String? = null
            BRACKETS.find(rest)?.let { m ->
                val inside = m.groupValues[1].trim()
                // В скобках чаще всего чтец («(читает Князев)», «[Князев И.]»), реже год — год не чтец.
                if (inside.isNotEmpty() && !inside.all { it.isDigit() }) {
                    narrator = inside.replace(READS, "").trim().takeIf { it.isNotEmpty() }
                }
                rest = rest.removeRange(m.range).trim()
            }
            val parts = rest.split(DASH, limit = 2).map { it.trim() }
            return if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                FolderName(title = parts[1], author = parts[0], narrator = narrator)
            } else {
                FolderName(title = rest.ifEmpty { raw.trim() }, author = null, narrator = narrator)
            }
        }

        private val BRACKETS = Regex("""[(\[]([^)\]]*)[)\]]\s*$""")
        private val DASH = Regex("""\s+[-–—]\s+""")
        private val READS = Regex("""(?i)^(читает|чтец|read by|narrated by)\s*""")
    }
}
