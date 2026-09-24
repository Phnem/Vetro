package com.example.myapplication.ui.home.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeNotificationTrayTest {

    @Test
    fun starts_as_a_stack() {
        assertFalse(EpisodeNotificationTray().collapsed)
    }

    @Test
    fun collapse_is_one_way_and_idempotent() {
        val tray = EpisodeNotificationTray()
        tray.collapse()
        tray.collapse()
        assertTrue(tray.collapsed)
    }
}
