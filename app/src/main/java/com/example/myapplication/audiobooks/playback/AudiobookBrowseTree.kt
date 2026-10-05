package com.example.myapplication.audiobooks.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.data.ContinueItem
import com.example.myapplication.audiobooks.data.LibraryBook
import com.example.myapplication.audiobooks.data.NarrationChain
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline
import kotlinx.coroutines.flow.first

/**
 * Дерево каталога для Android Auto и других клиентов MediaBrowser: машина показывает его на экране,
 * водитель выбирает книгу голосом или касанием, и воспроизведение стартует с сохранённого места.
 *
 * Корень -> «Продолжить» (книги с прогрессом, свежие первыми) и «Библиотека» (свои книги и избранное).
 * Листья - книги: `book:<narrationId>`. Очередь из файлов собирается только при запуске
 * ([resolve]) - в списке тяжёлых манифестов нет.
 */
@UnstableApi
class AudiobookBrowseTree(
    private val repository: AudiobookRepository,
    private val chain: NarrationChain,
    private val ru: () -> Boolean,
) {
    /** Корень каталога; [recent] - клиент просит «что играло последним» (Auto при подключении к машине). */
    fun root(recent: Boolean): MediaItem = folder(if (recent) RECENT_ID else ROOT_ID, "Vetro")

    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        ROOT_ID -> listOf(continueFolder(), libraryFolder())
        RECENT_ID -> repository.continueListening(1).first().map(::continueItem)
        CONTINUE_ID -> repository.continueListening(MAX_ITEMS).first().map(::continueItem)
        LIBRARY_ID -> repository.library().first().mapNotNull(::libraryItem).take(MAX_ITEMS.toInt())
        else -> emptyList()
    }

    suspend fun item(mediaId: String): MediaItem? = when (mediaId) {
        ROOT_ID -> root(recent = false)
        RECENT_ID -> root(recent = true)
        CONTINUE_ID -> continueFolder()
        LIBRARY_ID -> libraryFolder()
        else -> bookId(mediaId)?.let { id ->
            val book = repository.bookOf(id) ?: return@let null
            bookItem(id, book.title, book.authors, book.narrators, book.coverUrl, subtitle = null, completion = null)
        }
    }

    /**
     * Запрос клиента «играть `book:<id>`» -> очередь файлов и стартовая позиция. Любые другие
     * элементы (дорожки уже собранной очереди) уходят как есть.
     */
    suspend fun resolve(
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaSession.MediaItemsWithStartPosition {
        val requested = items.firstNotNullOfOrNull { bookId(it.mediaId) }
            ?: return MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs)
        val book = repository.bookOf(requested) ?: throw IllegalStateException("Unknown book")
        val next = chain.next(requested, emptySet()) ?: throw IllegalStateException("No source for the book")
        val queue = PlaybackQueueBuilder.build(
            next.manifest, book.workId, requested, book.title,
            book.authors.joinToString(", "), book.narrators.joinToString(", "), book.coverUrl,
        )
        val timeline = runCatching { BookTimeline(next.manifest.tracks, next.manifest.chapters) }.getOrNull()
        val saved = repository.progress(requested)?.takeIf { !it.finished }?.globalMs
        val (index, offset) = saved?.let { timeline?.toTrack(it) } ?: (0 to 0L)
        return MediaSession.MediaItemsWithStartPosition(queue, index, offset)
    }

    private fun continueFolder() = folder(CONTINUE_ID, if (ru()) "Продолжить" else "Continue listening")

    private fun libraryFolder() = folder(LIBRARY_ID, if (ru()) "Библиотека" else "Library")

    private fun continueItem(c: ContinueItem): MediaItem {
        val percent = c.totalMs?.takeIf { it > 0 }?.let { (c.globalMs.toDouble() / it).coerceIn(0.0, 1.0) }
        val chapter = if (ru()) "Глава ${c.chapterIndex + 1}" else "Chapter ${c.chapterIndex + 1}"
        val subtitle = percent?.let { "$chapter · ${(it * 100).toInt()}%" } ?: chapter
        return bookItem(c.narrationId, c.title, c.authors, c.narrators, c.coverUrl, subtitle, percent)
    }

    private fun libraryItem(b: LibraryBook): MediaItem? {
        val narration = b.narrationId ?: return null
        return bookItem(narration, b.title, b.authors, b.narrators, b.coverUrl, subtitle = null, completion = null)
    }

    private fun bookItem(
        narrationId: NarrationId,
        title: String,
        authors: List<String>,
        narrators: List<String>,
        coverUrl: String?,
        subtitle: String?,
        completion: Double?,
    ): MediaItem {
        val extras = Bundle().apply {
            if (completion != null) {
                putInt(
                    MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                    if (completion >= 0.99) MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
                    else MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
                )
                putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, completion)
            }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle ?: narrators.joinToString(", ").ifBlank { authors.joinToString(", ") })
            .setArtist(authors.joinToString(", "))
            .setAlbumArtist(narrators.joinToString(", "))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setExtras(extras)
            .apply { coverUrl?.let { setArtworkUri(android.net.Uri.parse(it)) } }
            .build()
        return MediaItem.Builder()
            .setMediaId(BOOK_PREFIX + narrationId.value)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                .build(),
        )
        .build()

    private fun bookId(mediaId: String): NarrationId? =
        mediaId.takeIf { it.startsWith(BOOK_PREFIX) }
            ?.removePrefix(BOOK_PREFIX)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { NarrationId(it) }.getOrNull() }

    companion object {
        const val ROOT_ID = "vetro_root"
        const val RECENT_ID = "vetro_recent"
        const val CONTINUE_ID = "vetro_continue"
        const val LIBRARY_ID = "vetro_library"
        const val BOOK_PREFIX = "book:"
        private const val MAX_ITEMS = 50L
    }
}
