package com.example.myapplication.localplayer.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlayerAudioSheetDeviceTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(35)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun longDubListShowsCurrentTrackAndSelectsAnOffscreenProvider() {
        val visible = MutableTransitionState(false).apply { targetState = true }
        val tracks = (0..31).map { AudioTrackOption("dub-$it", "Студия $it", it == 4, -1, -1, "https://example.test/$it") }
        var selected: AudioTrackOption? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (visible.targetState || visible.currentState || !visible.isIdle) {
                        PlayerAudioSheet(visible, tracks, true, { selected = it }, {
                            dismissed = true
                            visible.targetState = false
                        })
                    }
                }
            }
        }
        compose.onNodeWithText("Озвучка").assertIsDisplayed()
        compose.onNodeWithText("Студия 4").assertIsSelected()
        screenshot("player_audio_sheet.png")
        val scrollBefore = compose.onNode(hasScrollAction()).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        val scrollAfter = compose.onNode(hasScrollAction()).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("The dub list must scroll with a finger gesture", scrollAfter > scrollBefore)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Студия 31"))
        compose.onNodeWithText("Студия 31").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(tracks[31], selected)
            assertTrue(dismissed)
        }
        compose.onNodeWithText("Озвучка").assertDoesNotExist()
    }

    @Test fun tappingTheScrimClosesWithoutSendingTheTapToThePlayer() {
        val visible = MutableTransitionState(false).apply { targetState = true }
        var backgroundTaps = 0
        var dismissed = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black).clickable { backgroundTaps++ }) {
                    if (visible.targetState || visible.currentState || !visible.isIdle) {
                        PlayerAudioSheet(visible, listOf(AudioTrackOption("one", "English", true, 0, 0)), false,
                            { fail("Scrim must not choose a track") }, {
                                dismissed = true
                                visible.targetState = false
                            })
                    }
                }
            }
        }
        compose.onNodeWithText("Audio").assertIsDisplayed()
        compose.onRoot().performTouchInput { click(Offset(15f, 15f)) }
        compose.runOnIdle {
            assertTrue(dismissed)
            assertEquals(0, backgroundTaps)
        }
    }

    private fun screenshot(name: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens/$name")
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
