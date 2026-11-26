package com.example.myapplication.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.ui.shared.theme.OneUiTheme
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EpisodeSelectionDockTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectionDockUsesWhiteInkAgainstDarkGlass() {
        compose.setContent {
            OneUiTheme(darkTheme = true) {
                CompositionLocalProvider(LocalContentColor provides Color.Black) {
                    val backdrop = rememberLayerBackdrop()
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color(0xFF391611)))
                        EpisodeSelectionDock(
                            count = 4,
                            ru = false,
                            backdrop = backdrop,
                            onClear = {},
                            onWatched = {},
                            onRate = {},
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }

        val labels = listOf("Selected episodes: 4", "Mark as watched", "Mark as unwatched", "Clear selection")
        val bounds = labels.map { label ->
            val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
            val position = node.layoutInfo.coordinates.localToScreen(Offset.Zero)
            android.graphics.Rect(
                position.x.toInt(), position.y.toInt(),
                position.x.toInt() + node.size.width, position.y.toInt() + node.size.height,
            )
        }
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            labels.zip(bounds).forEach { (label, area) ->
                val hasWhiteInk = (area.top until area.bottom).any { y ->
                    (area.left until area.right).any { x ->
                        val pixel = screenshot.getPixel(x, y)
                        android.graphics.Color.red(pixel) > 240 &&
                            android.graphics.Color.green(pixel) > 240 &&
                            android.graphics.Color.blue(pixel) > 240
                    }
                }
                assertTrue("Expected visible white ink for $label", hasWhiteInk)
            }
        } finally {
            screenshot.recycle()
        }
    }
}
