package com.example.myapplication.localplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Время воспроизведения для контролов обоих плееров (скачанные серии и стрим).
 *
 * Раньше позиция лежала обычной переменной в хосте плеера и читалась им же в композиции: опрос
 * раз в 250 мс пересобирал ВЕСЬ экран плеера — AndroidView, доки, меню — четыре раза в секунду.
 * Теперь позицию читают только те, кому она нужна, и только через лямбды [position]/[buffered]:
 * полоса — в фазе отрисовки, метка времени — раз в секунду, автопропуск — в своём snapshotFlow.
 */
@Stable
class PlaybackClock {
    var positionMs by mutableLongStateOf(0L)
        private set
    var bufferedMs by mutableLongStateOf(0L)
        private set
    /** Длительность серии; 0 — пока неизвестна. Меняется редко, её можно читать в композиции. */
    var durationMs by mutableLongStateOf(0L)
        private set

    /** Постоянные ссылки: одна и та же лямбда на всё время жизни часов не ломает пропуск рекомпозиции. */
    val position: () -> Long = { positionMs }
    val buffered: () -> Long = { bufferedMs }

    fun sync(player: Player) {
        positionMs = player.currentPosition
        bufferedMs = player.bufferedPosition
        player.duration.takeIf { it > 0L }?.let { durationMs = it }
    }

    /** Новая серия: старая длительность не должна дожить до первого опроса. */
    internal fun onNewMedia(player: Player) {
        durationMs = 0L
        sync(player)
    }
}

/**
 * Часы плеера. Пока идёт воспроизведение — опрос каждые [PLAYING_TICK_MS]; на паузе — раз в
 * [PAUSED_TICK_MS], только чтобы полоса буфера продолжала расти, пока сеть докачивает. Значения на
 * паузе не меняются, поэтому такой опрос никого не будит. Перемотка и смена серии применяются сразу
 * через слушатель — бегунок не ждёт следующего тика.
 */
@Composable
fun rememberPlaybackClock(player: Player, isPlaying: Boolean): PlaybackClock {
    val clock = remember(player) { PlaybackClock().also { it.sync(player) } }
    DisposableEffect(player, clock) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_IDLE) clock.sync(player)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                clock.onNewMedia(player)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                clock.sync(player)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, clock, isPlaying) {
        val tick = if (isPlaying) PLAYING_TICK_MS else PAUSED_TICK_MS
        while (isActive) {
            clock.sync(player)
            delay(tick)
        }
    }
    return clock
}

private const val PLAYING_TICK_MS = 250L
private const val PAUSED_TICK_MS = 1_000L
