package com.example.myapplication.audiobooks.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.MainActivity
import com.phnem.vetro.BuildConfig
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** Same-process Media3 service; the Activity may disappear while audio continues. */
@UnstableApi
class AudiobookPlaybackService : MediaLibraryService() {
    private val manifestResolver: ManifestResolver by inject()
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var resumptionStore: PlaybackResumptionStore
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var retryingMediaId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val savePosition = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                resumptionStore.save(player)
                handler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
            }
        }
    }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.currentMediaItem != null) resumptionStore.save(player)
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        if (BuildConfig.AUDIOBOOKS_ENABLED || BuildConfig.DEBUG) session else null

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(savePosition)
        recoveryScope.cancel()
        resumptionStore.save(player)
        player.removeListener(playerListener)
        session.release()
        player.release()
        super.onDestroy()
    }

    private class SessionCallback(
        private val store: PlaybackResumptionStore,
    ) : MediaLibrarySession.Callback {
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
    }
}
