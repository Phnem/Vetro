package com.example.myapplication.media.ui

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.phnem.vetro.R

/**
 * What the player can do right now, as the PiP window has to render it.
 *
 * [hasPrevious]/[hasNext] drive the enabled state instead of dropping the button: a control that
 * disappears at the last episode reads as a broken window, a greyed-out one reads as the end.
 */
data class PipPlaybackCommands(
    val isPlaying: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val onPrevious: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
)

/**
 * A player Activity that mirrors its transport controls into the PiP window.
 *
 * The player composables don't know which Activity hosts them, so they look this up on the context
 * chain and push their state through it.
 */
interface PipHostActivity {
    fun updatePipCommands(commands: PipPlaybackCommands?)
}

/**
 * Закрыли ли PiP-окно крестиком — в отличие от разворота обратно в полный экран.
 *
 * Отдельного колбэка на крестик система не даёт: оба исхода приходят одним и тем же
 * `onPictureInPictureModeChanged(false)`. Различает их состояние жизненного цикла. Развёрнутая
 * активити к этому моменту снова видима (STARTED и выше), закрытую — остановили, и она осталась
 * в CREATED.
 */
fun ComponentActivity.pipWindowWasClosed(isInPictureInPictureMode: Boolean): Boolean =
    !isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

/**
 * Реакция плеер-активити на закрытие PiP-окна.
 *
 * Активити после крестика остаётся жива: её всего лишь остановили, а ExoPlayer внутри продолжает
 * играть — пользователь закрыл окно и слушает серию в фоне. Достаточно довести активити до конца:
 * плеер освобождается там же, где и создавался, на разборке композиции.
 */
fun ComponentActivity.finishIfPipWindowClosed(isInPictureInPictureMode: Boolean) {
    if (pipWindowWasClosed(isInPictureInPictureMode) && !isFinishing) finish()
}

/**
 * Та же развязка, но со стороны `onStop()` — на случай прошивки с другим порядком колбэков.
 *
 * Различение по жизненному циклу выше опирается на то, что к приходу
 * `onPictureInPictureModeChanged(false)` активити уже остановлена. Контрактом это не закреплено,
 * это наблюдаемое поведение AOSP, и прошивка, зовущая колбэк ДО `onStop()`, вернула бы исходную
 * ошибку — серия играет в фоне, и никакого признака этого в коде нет. Проверка в `onStop()`
 * ловит оба порядка и стоит одного сравнения.
 *
 * @param wasInPip была ли активити в PiP хоть раз за свою жизнь; экран, в PiP не уходивший,
 *   закрывать по уходу в фон нельзя — это обычное переключение приложений.
 */
fun ComponentActivity.finishIfStoppedOutsidePip(wasInPip: Boolean) {
    if (!wasInPip || isFinishing || isChangingConfigurations) return
    if (isInPictureInPictureMode) return
    finish()
}

/**
 * Previous / play-pause / next inside the system picture-in-picture window.
 *
 * Every player Activity owns one. Our Compose overlay is hidden in PiP — the window has room for
 * nothing but the video — so these `RemoteAction`s are the only controls the user can reach, and an
 * Activity that sets no actions leaves them with just the system close/expand pair.
 *
 * Actions arrive as broadcasts, so the intent action is namespaced per Activity class: two player
 * screens alive at once must never answer each other's buttons.
 *
 * The icons are the player's own `ic_player_*` vectors, not the `android.R.drawable.ic_media_*`
 * set: the system ones are filled and heavier, so the window looked like a different app's player.
 */
class PipActionsController(private val activity: Activity) {

    private val action = "$ACTION_PREFIX.${activity.javaClass.name}"
    private var commands: PipPlaybackCommands? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val current = commands ?: return
            when (intent?.getIntExtra(EXTRA_CODE, 0)) {
                CODE_PREVIOUS -> current.onPrevious()
                CODE_PLAY_PAUSE -> current.onPlayPause()
                CODE_NEXT -> current.onNext()
                else -> return
            }
        }
    }

    fun register() {
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregister() {
        runCatching { activity.unregisterReceiver(receiver) }
    }

    /**
     * Publishes the current controls. Called on every play-state or episode change: the system
     * keeps the last params, so a stale set would leave the pause icon on a paused video and the
     * next button live past the final episode.
     */
    fun setCommands(commands: PipPlaybackCommands?) {
        this.commands = commands
        runCatching { activity.setPictureInPictureParams(buildParams()) }
    }

    fun enterPip() {
        runCatching { activity.enterPictureInPictureMode(buildParams()) }
    }

    private fun buildParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        commands?.let { current ->
            builder.setActions(
                listOf(
                    remoteAction(
                        R.drawable.ic_player_skip_back,
                        "Previous",
                        CODE_PREVIOUS,
                        current.hasPrevious,
                    ),
                    if (current.isPlaying) {
                        remoteAction(R.drawable.ic_player_pause, "Pause", CODE_PLAY_PAUSE, true)
                    } else {
                        remoteAction(R.drawable.ic_player_play, "Play", CODE_PLAY_PAUSE, true)
                    },
                    remoteAction(
                        R.drawable.ic_player_skip_forward,
                        "Next",
                        CODE_NEXT,
                        current.hasNext,
                    ),
                ),
            )
        }
        return builder.build()
    }

    private fun remoteAction(
        iconRes: Int,
        title: String,
        code: Int,
        enabled: Boolean,
    ): RemoteAction {
        val intent = Intent(action).setPackage(activity.packageName).putExtra(EXTRA_CODE, code)
        val pending = PendingIntent.getBroadcast(
            activity,
            code,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(
            Icon.createWithResource(activity, iconRes),
            title,
            title,
            pending,
        ).apply { isEnabled = enabled }
    }

    private companion object {
        const val ACTION_PREFIX = "com.phnem.vetro.player.PIP_CONTROL"
        const val EXTRA_CODE = "pip_code"
        const val CODE_PREVIOUS = 1
        const val CODE_PLAY_PAUSE = 2
        const val CODE_NEXT = 3
    }
}
