package com.example.myapplication.audiobooks.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * App-owned commands. Таймер сна и пропуск тишины — только своим контроллерам; скорость и
 * избранное — ещё и системной карточке медиа (кнопки по краям).
 */
object AudiobookSessionCommands {
    const val MINUTES = "minutes"
    const val REMAINING_MS = "sleep_remaining_ms"
    const val SKIP_SILENCE = "skip_silence"
    /** Имя сайта, на который плеер сам перешёл по цепочке; пусто — запасных не осталось. */
    const val SOURCE_NOTICE = "source_notice"
    /** Растёт с каждым переходом: приложение показывает каждое сообщение один раз. */
    const val SOURCE_NOTICE_SEQ = "source_notice_seq"
    val setSleepTimer = SessionCommand("vetro.audiobook.SET_SLEEP_TIMER", Bundle.EMPTY)
    val cancelSleepTimer = SessionCommand("vetro.audiobook.CANCEL_SLEEP_TIMER", Bundle.EMPTY)
    val setSkipSilence = SessionCommand("vetro.audiobook.SET_SKIP_SILENCE", Bundle.EMPTY)
    val cycleSpeed = SessionCommand("vetro.audiobook.CYCLE_SPEED", Bundle.EMPTY)
    val toggleFavorite = SessionCommand("vetro.audiobook.TOGGLE_FAVORITE", Bundle.EMPTY)
}
