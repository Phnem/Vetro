package com.example.myapplication.audiobooks.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.data.OpenedBook
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceResult
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline
import com.example.myapplication.audiobooks.ui.AudiobookPlayerState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Запуск книги из любого места раздела (полка, витрина, «Продолжить»): запись в БД → манифест →
 * очередь → воспроизведение с сохранённого места. Контроллер берётся на время команды: живой
 * держит [com.example.myapplication.audiobooks.ui.AudiobookPlayerHost].
 */
@UnstableApi
class AudiobookLauncher(
    private val context: Context,
    private val sources: List<AudiobookSource>,
    private val resolver: ManifestResolver,
    private val repository: AudiobookRepository,
    private val playerState: AudiobookPlayerState,
) {
    sealed interface Result {
        data object Started : Result
        data object Restricted : Result
        data object Unavailable : Result
    }

    /**
     * @param startFraction доля книги, с которой начать, если у этой озвучки ещё нет своей позиции
     *   (смена чтеца: разметка глав у разных озвучек разная, переносим по доле — spec/03 случай 4).
     */
    suspend fun play(book: SourceBook, startFraction: Float? = null, expand: Boolean = true): Result {
        val source = sources.firstOrNull { it.id == book.ref.source } ?: return Result.Unavailable
        val details = when (val r = source.details(book.ref)) {
            is SourceResult.Ok -> r.value
            is SourceResult.Restricted -> return Result.Restricted
            is SourceResult.Failed -> return Result.Unavailable
        }
        val opened = repository.saveOpened(source, details)
        val manifest = runCatching { resolver.manifest(opened.variantId) }.getOrElse { return Result.Unavailable }
        val d = details.book
        start(opened, manifest, d.title, d.authors.joinToString(", "), d.narrators.joinToString(", "), d.coverUrl, startFraction)
        // «Слушать» открывает полный плеер, а не мини: пользователь пришёл слушать эту книгу.
        // У карточки «Продолжить» свои контролы — там плеер остаётся свёрнутым.
        if (expand) playerState.requestExpand()
        return Result.Started
    }

    private suspend fun start(
        book: OpenedBook,
        manifest: MediaManifest,
        title: String,
        author: String,
        narrator: String,
        cover: String?,
        startFraction: Float?,
    ) {
        val items = PlaybackQueueBuilder.build(manifest, book.workId, book.narrationId, title, author, narrator, cover)
        // Продолжаем с места, где остановились в этой озвучке; шкала книги → (трек, смещение).
        val saved = repository.progress(book.narrationId)
        val timeline = runCatching { BookTimeline(manifest.tracks, manifest.chapters) }.getOrNull()
        val startGlobal = saved?.takeIf { !it.finished }?.globalMs
            ?: startFraction?.let { f -> timeline?.totalMs?.let { (it * f).toLong() } }
        val (index, offset) = startGlobal?.let { timeline?.toTrack(it) } ?: (0 to 0L)
        val token = SessionToken(context, ComponentName(context, AudiobookPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        try {
            val controller = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
            controller.setMediaItems(items, index, offset)
            controller.prepare()
            controller.play()
        } finally {
            MediaController.releaseFuture(future)
        }
    }
}
