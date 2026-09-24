package com.example.myapplication.localplayer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отмена автоматического пропуска.
 *
 * Главная ловушка здесь такая: отмена — это перемотка НАЗАД в тот самый сегмент, а перемотка
 * снимает дедупликацию автопропуска. Без явного отказа кнопка отмены выглядела бы сломанной —
 * видео прыгало бы обратно вперёд через долю секунды.
 */
class SkipDeclineTest {

    private val opening = SkipSegment(0L, 85_000L, SkipKind.OPENING)
    private val resolution = SkipSegmentResolution(
        segments = listOf(opening),
        origin = "jut.su",
        referenceDurationMs = 1_377_000L,
    )
    private val key = SkipMediaKey(1, "episode-1", 1)

    @Test
    fun `automatic decision remembers where it jumped from`() {
        val coordinator = MediaSkipCoordinator()
        coordinator.install(key, resolution)

        val decision = coordinator.automaticSeek(key, 12_000L, enabled = true)

        assertNotNull(decision)
        assertEquals(12_000L, decision!!.fromMs)
        assertEquals(85_000L, decision.targetMs)
    }

    @Test
    fun `declined segment is never skipped again - even after a seek back into it`() {
        val coordinator = MediaSkipCoordinator()
        coordinator.install(key, resolution)
        val decision = coordinator.automaticSeek(key, 12_000L, enabled = true)
        assertNotNull(decision)

        coordinator.declineAutomatic(key, decision!!.segment)
        // Отмена = перемотка назад. Именно она раньше и перевзводила автопропуск.
        coordinator.onPositionDiscontinuity(key, decision.fromMs)

        assertNull(coordinator.automaticSeek(key, decision.fromMs, enabled = true))
        assertNull(coordinator.automaticSeek(key, 30_000L, enabled = true))
        assertTrue(coordinator.isDeclined(opening))
    }

    @Test
    fun `manual skip still works after declining the automatic one`() {
        // Отказ касается только автоматики: нажать «Пропустить» руками пользователь вправе
        // в любой момент.
        val coordinator = MediaSkipCoordinator()
        coordinator.install(key, resolution)
        coordinator.declineAutomatic(key, opening)

        val manual = coordinator.manualSeek(key, 20_000L)

        assertEquals(85_000L, manual?.targetMs)
        assertEquals(SkipSeekReason.MANUAL, manual?.reason)
    }

    @Test
    fun `next episode forgets the refusal`() {
        // Отказались от опенинга этой серии, а не от опенингов вообще.
        val coordinator = MediaSkipCoordinator()
        coordinator.install(key, resolution)
        coordinator.declineAutomatic(key, opening)

        val nextEpisode = key.copy(mediaId = "episode-2", episodeNumber = 2)
        coordinator.install(nextEpisode, resolution)

        assertNotNull(coordinator.automaticSeek(nextEpisode, 5_000L, enabled = true))
    }
}
