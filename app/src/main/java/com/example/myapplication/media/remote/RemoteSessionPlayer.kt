package com.example.myapplication.media.remote

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Телевизор как Media3 `Player`: те же элементы управления Vetro (play/pause, шкала, ±10 с,
 * перемотка свайпом) управляют показом на ТВ, ничего не зная о протоколах.
 *
 * Позиция: приёмник сообщает её редко и приблизительно, а между ответами SimpleBasePlayer сам
 * продвигает её по часам (экстраполяция) — шкала идёт плавно, без скачков; каждый новый ответ ТВ
 * мягко выправляет её.
 */
@UnstableApi
class RemoteSessionPlayer(
    private val manager: RemotePlaybackManager,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch { manager.playback.collect { invalidateState() } }
    }

    override fun getState(): State {
        val s = manager.playback.value
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_STOP,
        ).build()
        val playbackState = when (s.status) {
            RemoteStatus.IDLE, RemoteStatus.ERROR -> Player.STATE_IDLE
            RemoteStatus.LOADING, RemoteStatus.BUFFERING -> Player.STATE_BUFFERING
            RemoteStatus.PLAYING, RemoteStatus.PAUSED -> Player.STATE_READY
            RemoteStatus.ENDED -> Player.STATE_ENDED
        }
        val playing = s.status == RemoteStatus.PLAYING || s.status == RemoteStatus.BUFFERING || s.status == RemoteStatus.LOADING
        val now = SystemClock.elapsedRealtime()
        val position = s.positionAt(now)
        val item = MediaItemData.Builder("remote")
            .setMediaItem(MediaItem.Builder().setMediaId("remote").build())
            .setDurationUs(s.durationMs?.let { it * 1000 } ?: C.TIME_UNSET)
            .setIsSeekable(true)
            .build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(if (playbackState == Player.STATE_IDLE && s.status != RemoteStatus.ERROR) Player.STATE_BUFFERING else playbackState)
            .setPlaylist(listOf(item))
            .setContentPositionMs(
                if (s.status == RemoteStatus.PLAYING) PositionSupplier.getExtrapolating(position, 1f)
                else PositionSupplier.getConstant(position),
            )
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        scope.launch { runCatching { if (playWhenReady) manager.play() else manager.pause() } }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        scope.launch { runCatching { manager.seek(target) } }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        scope.launch { runCatching { manager.pause() } }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }
}
