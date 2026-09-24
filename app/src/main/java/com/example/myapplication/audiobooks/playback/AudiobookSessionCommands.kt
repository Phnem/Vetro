package com.example.myapplication.audiobooks.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** App-owned commands; the service advertises them only to its own controllers. */
object AudiobookSessionCommands {
    const val MINUTES = "minutes"
    const val REMAINING_MS = "sleep_remaining_ms"
    const val SKIP_SILENCE = "skip_silence"
    val setSleepTimer = SessionCommand("vetro.audiobook.SET_SLEEP_TIMER", Bundle.EMPTY)
    val cancelSleepTimer = SessionCommand("vetro.audiobook.CANCEL_SLEEP_TIMER", Bundle.EMPTY)
    val setSkipSilence = SessionCommand("vetro.audiobook.SET_SKIP_SILENCE", Bundle.EMPTY)
}
