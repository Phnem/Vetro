package com.example.myapplication.media.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.myapplication.audiobooks.playback.AudiobookArtworkLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.phnem.vetro.R

/**
 * Системная карточка медиа для видео (аниме, сериалы, фильмы) — то же, что у аудиокниг: название
 * тайтла, строка «S2 E5», постер, кнопки перемотки и соседних серий.
 *
 * Плееры видео держат сессию в активити, без MediaSessionService, поэтому Media3 своего уведомления
 * не рисует и системной карточки не было вовсе. Здесь сессия получает:
 *  • обёртку плеера [VideoSessionPlayer], которая подставляет метаданные тайтла — у элементов потока
 *    их нет (это просто адрес видео), а сам плеер трогать нельзя;
 *  • MediaStyle-уведомление, привязанное к токену сессии: по нему система и строит карточку.
 */
@UnstableApi
internal class VideoMediaSession(
    private val context: Context,
    player: Player,
    id: String,
    placeholderIcon: Int,
    private val contentIntent: PendingIntent,
) {
    private val wrapper = VideoSessionPlayer(player)
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var onPrevious: () -> Unit = {}
    private var onNext: () -> Unit = {}

    val session: MediaSession = MediaSession.Builder(context, wrapper)
        .setId(id)
        .setSessionActivity(contentIntent)
        .setBitmapLoader(AudiobookArtworkLoader(context, placeholderIcon))
        .setCallback(Callback())
        .build()

    /**
     * Обновить то, что видит система. [line] — вторая строка («S2 E5»), [artwork] — постер тайтла;
     * соседние серии — кнопками по краям, только если они есть.
     */
    fun update(
        title: String,
        line: String?,
        artwork: Uri?,
        hasPrevious: Boolean,
        hasNext: Boolean,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
    ) {
        this.onPrevious = onPrevious
        this.onNext = onNext
        wrapper.displayMetadata = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist(line)
            .setArtworkUri(artwork)
            .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
            .build()
        session.setMediaButtonPreferences(buttons(hasPrevious, hasNext))
        notify(title, line)
    }

    fun release() {
        manager.cancel(NOTIFICATION_ID)
        // Сам плеер принадлежит экрану и освобождается им; обёртку не освобождаем — это освободило
        // бы и его.
        session.release()
    }

    private fun buttons(hasPrevious: Boolean, hasNext: Boolean): List<CommandButton> {
        val ru = java.util.Locale.getDefault().language == "ru"
        return buildList {
            if (hasPrevious) {
                add(
                    CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                        .setDisplayName(if (ru) "Предыдущая серия" else "Previous episode")
                        .setSessionCommand(PREVIOUS)
                        .setSlots(CommandButton.SLOT_OVERFLOW)
                        .build(),
                )
            }
            add(
                CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                    .setDisplayName(if (ru) "Назад" else "Rewind")
                    .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                    .setSlots(CommandButton.SLOT_BACK)
                    .build(),
            )
            add(
                CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                    .setDisplayName(if (ru) "Вперёд" else "Forward")
                    .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                    .setSlots(CommandButton.SLOT_FORWARD)
                    .build(),
            )
            if (hasNext) {
                add(
                    CommandButton.Builder(CommandButton.ICON_NEXT)
                        .setDisplayName(if (ru) "Следующая серия" else "Next episode")
                        .setSessionCommand(NEXT)
                        .setSlots(CommandButton.SLOT_OVERFLOW)
                        .build(),
                )
            }
        }
    }

    /**
     * Уведомление только связывает сессию с системой: кнопки, прогресс и обложку система берёт из
     * самой сессии. Тихий канал — карточка не должна звенеть при каждой смене серии.
     */
    private fun notify(title: String, line: String?) {
        ensureChannel()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(line)
            .setContentIntent(contentIntent)
            .setStyle(Notification.MediaStyle().setMediaSession(session.platformToken))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        // Без разрешения на уведомления система просто не покажет карточку — это не ошибка плеера.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val ru = java.util.Locale.getDefault().language == "ru"
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, if (ru) "Воспроизведение видео" else "Video playback", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val default = super.onConnect(session, controller)
            if (!default.isAccepted) return default
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    default.availableSessionCommands.buildUpon().add(PREVIOUS).add(NEXT).build(),
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PREVIOUS.customAction -> onPrevious()
                NEXT.customAction -> onNext()
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        private const val CHANNEL_ID = "video_playback"
        private const val NOTIFICATION_ID = 0x71DE0
        private val PREVIOUS = SessionCommand("vetro.video.PREVIOUS_EPISODE", Bundle.EMPTY)
        private val NEXT = SessionCommand("vetro.video.NEXT_EPISODE", Bundle.EMPTY)

        /** Вторая строка карточки: «S2 E5» у сериала/аниме, «Фильм» у фильма. */
        fun line(season: Int?, episode: Int?, isMovie: Boolean): String? {
            val ru = java.util.Locale.getDefault().language == "ru"
            if (isMovie) return if (ru) "Фильм" else "Movie"
            val e = episode ?: return null
            return if (season != null) "S$season E$e" else if (ru) "Серия $e" else "Episode $e"
        }
    }
}

/**
 * Обёртка плеера для сессии: всё пересылает настоящему плееру, но метаданные текущего элемента
 * отдаёт свои ([displayMetadata]) — в элементе потока их нет.
 */
@UnstableApi
internal class VideoSessionPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    var displayMetadata: MediaMetadata? = null
        set(value) {
            field = value
            invalidateState()
        }

    override fun getState(): State {
        val state = super.getState()
        val metadata = displayMetadata ?: return state
        if (state.timeline.isEmpty) return state
        return state.buildUpon()
            .setPlaylist(state.timeline, state.currentTracks, metadata)
            .build()
    }
}

/** Тап по карточке возвращает к уже открытому плееру, а не запускает новый. */
internal fun videoPlayerContentIntent(activity: android.app.Activity): PendingIntent =
    PendingIntent.getActivity(
        activity,
        0,
        android.content.Intent(activity, activity.javaClass)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** Постер тайтла из коллекции как file:// — обложка системной карточки. */
internal suspend fun collectionPosterUri(animeId: String): Uri? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val koin = org.koin.java.KoinJavaComponent.getKoin()
            val anime = koin.get<com.example.myapplication.data.local.AnimeLocalDataSource>().getAnimeById(animeId)
            val name = anime?.imageFileName ?: return@runCatching null
            koin.get<com.example.myapplication.data.repository.ImageStorageRepository>()
                .getImageFilePath(name)
                ?.let { Uri.fromFile(java.io.File(it)) }
        }.getOrNull()
    }
