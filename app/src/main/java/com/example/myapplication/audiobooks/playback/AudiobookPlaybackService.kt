package com.example.myapplication.audiobooks.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline
import com.example.myapplication.MainActivity
import com.phnem.vetro.BuildConfig
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** Same-process Media3 service; the Activity may disappear while audio continues. */
@UnstableApi
class AudiobookPlaybackService : MediaLibraryService() {
    private val manifestResolver: ManifestResolver by inject()
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var resumptionStore: PlaybackResumptionStore
    private lateinit var sleepTimer: SleepTimerController
    private var sleepRemainingMs = -1L
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var retryingMediaId: String? = null
    private var chapterVariant: VariantId? = null
    private var chapterTimeline: BookTimeline? = null
    private var chapterLoadJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val savePosition = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                resumptionStore.save(player)
                handler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
            }
        }
    }
    private val updateChapter = object : Runnable {
        override fun run() {
            refreshChapterMetadata()
            // На паузе глава не меняется: тик нужен только во время воспроизведения.
            if (player.isPlaying) handler.postDelayed(this, CHAPTER_UPDATE_INTERVAL_MS)
        }
    }
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            sleepTimer.onPlaybackChanged()
            handler.removeCallbacks(updateChapter)
            if (isPlaying) handler.post(updateChapter)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (player.currentMediaItem != null) resumptionStore.save(player)
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                refreshChapterMetadata()
            }
            val currentId = player.currentMediaItem?.mediaId
            if (currentId != null && currentId != retryingMediaId) retryingMediaId = null
            handler.removeCallbacks(savePosition)
            if (player.isPlaying) handler.postDelayed(savePosition, POSITION_SAVE_INTERVAL_MS)
        }

        override fun onPlayerError(error: PlaybackException) {
            val responseCode = generateSequence(error as Throwable?) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()?.responseCode
            if (responseCode !in setOf(401, 403, 410)) return
            val item = player.currentMediaItem ?: return
            val ref = item.localConfiguration?.uri?.toString()?.let(TrackUriCodec::decode) ?: return
            if (retryingMediaId == item.mediaId) return
            retryingMediaId = item.mediaId
            val index = player.currentMediaItemIndex
            val position = player.currentPosition.coerceAtLeast(0L)
            val shouldPlay = player.playWhenReady
            recoveryScope.launch {
                manifestResolver.invalidate(ref.variant)
                player.seekTo(index, position)
                player.prepare()
                if (shouldPlay) player.play()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        resumptionStore = PlaybackResumptionStore(this)
        player = AudiobookPlayerFactory.create(this, manifestResolver)
        player.skipSilenceEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(AudiobookSessionCommands.SKIP_SILENCE, false)
        sleepTimer = SleepTimerController(player) { remaining ->
            sleepRemainingMs = remaining ?: -1L
            publishSessionState()
        }
        player.addListener(playerListener)

        val launchApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaLibrarySession.Builder(this, player, SessionCallback(resumptionStore))
            .setSessionActivity(launchApp)
            .setSessionExtras(sessionState())
            .setMediaButtonPreferences(
                listOf(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                        .setDisplayName("−15")
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setSlots(CommandButton.SLOT_BACK)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                        .setDisplayName("+30")
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .build(),
                ),
            )
            .build()
        handler.post(updateChapter)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        if (BuildConfig.AUDIOBOOKS_ENABLED || BuildConfig.DEBUG) session else null

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(savePosition)
        handler.removeCallbacks(updateChapter)
        chapterLoadJob?.cancel()
        sleepTimer.cancel()
        recoveryScope.cancel()
        resumptionStore.save(player)
        player.removeListener(playerListener)
        session.release()
        player.release()
        super.onDestroy()
    }

    private fun sessionState() = Bundle().apply {
        putLong(AudiobookSessionCommands.REMAINING_MS, sleepRemainingMs)
        putBoolean(AudiobookSessionCommands.SKIP_SILENCE, player.skipSilenceEnabled)
    }

    private fun publishSessionState() {
        if (::session.isInitialized) session.setSessionExtras(sessionState())
    }

    /** Media3 updates a progressive source in place when only metadata changes and URI stays fixed. */
    private fun refreshChapterMetadata() {
        val item = player.currentMediaItem ?: return
        val ref = item.localConfiguration?.uri?.toString()?.let(TrackUriCodec::decode) ?: return
        if (chapterVariant != ref.variant) {
            chapterLoadJob?.cancel()
            chapterVariant = ref.variant
            chapterTimeline = null
            chapterLoadJob = recoveryScope.launch {
                val timeline = runCatching {
                    manifestResolver.manifest(ref.variant).let { BookTimeline(it.tracks, it.chapters) }
                }.getOrNull()
                if (chapterVariant == ref.variant) {
                    chapterTimeline = timeline
                    updateCurrentChapter()
                }
            }
        } else {
            updateCurrentChapter()
        }
    }

    private fun updateCurrentChapter() {
        val item = player.currentMediaItem ?: return
        val ref = item.localConfiguration?.uri?.toString()?.let(TrackUriCodec::decode) ?: return
        if (ref.variant != chapterVariant) return
        val global = chapterTimeline?.toGlobal(ref.trackIndex, player.currentPosition.coerceAtLeast(0L)) ?: return
        val chapter = chapterTimeline?.chapterAt(global) ?: return
        val previousIndex = item.mediaMetadata.extras?.getInt("chapterIndex", -1) ?: -1
        if (previousIndex == chapter.index && item.mediaMetadata.title?.toString() == chapter.title) return
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply { putInt("chapterIndex", chapter.index) }
        val updated = item.buildUpon().setMediaMetadata(
            item.mediaMetadata.buildUpon().setTitle(chapter.title).setExtras(extras).build(),
        ).build()
        player.replaceMediaItem(player.currentMediaItemIndex, updated)
    }

    private inner class SessionCallback(
        private val store: PlaybackResumptionStore,
    ) : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val default = super.onConnect(session, controller)
            if (!default.isAccepted || controller.packageName != packageName) return default
            val commands = default.availableSessionCommands.buildUpon()
                .add(AudiobookSessionCommands.setSleepTimer)
                .add(AudiobookSessionCommands.cancelSleepTimer)
                .add(AudiobookSessionCommands.setSkipSilence)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            when (customCommand.customAction) {
                AudiobookSessionCommands.setSleepTimer.customAction -> {
                    val minutes = args.getInt(AudiobookSessionCommands.MINUTES)
                    if (minutes !in 1..240) {
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                    }
                    sleepTimer.start(minutes)
                }
                AudiobookSessionCommands.cancelSleepTimer.customAction -> sleepTimer.cancel()
                AudiobookSessionCommands.setSkipSilence.customAction -> {
                    player.skipSilenceEnabled = args.getBoolean(AudiobookSessionCommands.SKIP_SILENCE)
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(AudiobookSessionCommands.SKIP_SILENCE, player.skipSilenceEnabled).apply()
                    publishSessionState()
                }
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val snapshot = store.load()
                ?: return Futures.immediateFailedFuture(IllegalStateException("No local audiobook to resume"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    snapshot.items,
                    snapshot.mediaIndex,
                    snapshot.positionMs,
                ),
            )
        }
    }

    private companion object {
        const val POSITION_SAVE_INTERVAL_MS = 5_000L
        const val CHAPTER_UPDATE_INTERVAL_MS = 1_000L
        const val PREFS = "audiobook_player_options"
    }
}
